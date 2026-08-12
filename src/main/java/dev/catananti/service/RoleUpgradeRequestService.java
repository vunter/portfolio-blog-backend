package dev.catananti.service;

import dev.catananti.dto.RoleUpgradeRequestDto;
import dev.catananti.dto.RoleUpgradeRequestResponse;
import dev.catananti.dto.RoleUpdateRequest;
import dev.catananti.config.PaginationConfig;
import dev.catananti.entity.RoleUpgradeRequest;
import dev.catananti.entity.RoleUpgradeRequestStatus;
import dev.catananti.entity.UserRole;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.RoleUpgradeRequestRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoleUpgradeRequestService {

    private final RoleUpgradeRequestRepository roleUpgradeRequestRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final IdService idService;
    private final HtmlSanitizerService htmlSanitizerService;
    private final EmailService emailService;
    private final PaginationConfig paginationConfig;
    private final org.springframework.transaction.reactive.TransactionalOperator transactionalOperator;

    /**
     * Submit a role upgrade request.
     * Only one pending request per user is allowed.
     * TX-08: not transactional — the only DB write is one INSERT (guarded by
     * uq_role_upgrade_requests_pending), and the admin-notification emails that
     * follow must not hold a pool connection.
     */
    public Mono<RoleUpgradeRequestResponse> submitRequest(String userEmail, RoleUpgradeRequestDto dto) {
        return userRepository.findByEmail(userEmail)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(user -> {
                    // Check if user already has the requested role or higher
                    UserRole currentRole = UserRole.valueOf(user.getRole());
                    UserRole requestedRole = UserRole.valueOf(dto.requestedRole());

                    if (currentRole.ordinal() <= requestedRole.ordinal()) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "You already have this role or a higher one"));
                    }

                    // Check for existing pending request
                    return roleUpgradeRequestRepository.findPendingByUserId(user.getId())
                            .flatMap(existing -> Mono.<RoleUpgradeRequestResponse>error(new ResponseStatusException(
                                    HttpStatus.CONFLICT, "You already have a pending role upgrade request")))
                            .switchIfEmpty(Mono.defer(() -> {
                                RoleUpgradeRequest request = RoleUpgradeRequest.builder()
                                        .id(idService.nextId())
                                        .userId(user.getId())
                                        .requestedRole(dto.requestedRole())
                                        .reason(dto.reason() != null ? htmlSanitizerService.stripHtml(dto.reason()) : null)
                                        .status(RoleUpgradeRequestStatus.PENDING)
                                        .createdAt(LocalDateTime.now())
                                        .build();

                                return roleUpgradeRequestRepository.save(request)
                                        // CC-08: uq_role_upgrade_requests_pending backs the
                                        // check-then-act above; the losing concurrent submit
                                        // gets the same 409 as the pre-check.
                                        .onErrorResume(org.springframework.dao.DuplicateKeyException.class, e ->
                                                Mono.error(new ResponseStatusException(
                                                        HttpStatus.CONFLICT, "You already have a pending role upgrade request")))
                                        .map(saved -> RoleUpgradeRequestResponse.fromEntityWithUser(
                                                saved, user.getName(), user.getEmail(), user.getRole()))
                                        .doOnSuccess(resp ->
                                            log.info("Role upgrade request submitted by {} for role {}",
                                                    userEmail, dto.requestedRole()))
                                        .flatMap(resp ->
                                            // Notify admin(s) via email (non-critical)
                                            notifyAdminsOfRequest(user.getName(), user.getEmail(), dto.requestedRole(), dto.reason())
                                                    .thenReturn(resp));
                            }));
                });
    }

    /**
     * Get the latest role upgrade request for the current user.
     */
    public Mono<RoleUpgradeRequestResponse> getMyLatestRequest(String userEmail) {
        return userRepository.findByEmail(userEmail)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(user -> roleUpgradeRequestRepository.findLatestByUserId(user.getId())
                        .map(req -> RoleUpgradeRequestResponse.fromEntityWithUser(
                                req, user.getName(), user.getEmail(), user.getRole())));
    }

    /**
     * List all pending role upgrade requests (admin only).
     */
    public Flux<RoleUpgradeRequestResponse> getAllPending() {
        return roleUpgradeRequestRepository.findAllPending()
                .flatMap(req -> userRepository.findById(req.getUserId())
                        .map(user -> RoleUpgradeRequestResponse.fromEntityWithUser(
                                req, user.getName(), user.getEmail(), user.getRole()))
                        .switchIfEmpty(Mono.just(RoleUpgradeRequestResponse.fromEntity(req))));
    }

    /**
     * Get count of pending requests (admin only).
     */
    public Mono<Long> countPending() {
        return roleUpgradeRequestRepository.countPending();
    }

    /**
     * Approve a role upgrade request (admin only).
     * This updates the user's role and marks the request as APPROVED.
     * TX-08: only the status transition + promotion run in a transaction (via the
     * operator below); the user-notification email happens after commit.
     */
    public Mono<RoleUpgradeRequestResponse> approveRequest(Long requestId, String adminEmail) {
        return userRepository.findByEmail(adminEmail)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(admin -> roleUpgradeRequestRepository.findById(requestId)
                        .switchIfEmpty(Mono.error(new ResourceNotFoundException("Role upgrade request not found")))
                        .flatMap(request -> {
                            if (request.getStatus() != RoleUpgradeRequestStatus.PENDING) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "Request is already " + request.getStatus().name().toLowerCase()));
                            }

                            LocalDateTime reviewedAt = LocalDateTime.now();

                            // Update user role via the safe method
                            RoleUpdateRequest roleUpdate = new RoleUpdateRequest();
                            roleUpdate.setRole(request.getRequestedRole());

                            // CC-08: claim the request atomically — a concurrent review that
                            // already processed it makes the conditional UPDATE affect 0 rows.
                            return transactionalOperator.transactional(
                                            roleUpgradeRequestRepository.transitionFromPending(
                                                            requestId, RoleUpgradeRequestStatus.APPROVED.name(),
                                                            admin.getId(), reviewedAt)
                                                    .flatMap(rows -> {
                                                        if (rows == 0) {
                                                            return Mono.error(new ResponseStatusException(
                                                                    HttpStatus.BAD_REQUEST, "Request was already processed"));
                                                        }
                                                        request.setStatus(RoleUpgradeRequestStatus.APPROVED);
                                                        request.setReviewedBy(admin.getId());
                                                        request.setReviewedAt(reviewedAt);
                                                        request.setNewRecord(false);
                                                        return Mono.just(rows);
                                                    })
                                                    .then(Mono.defer(() -> userService.updateUserRoleSafe(
                                                            request.getUserId(), roleUpdate, adminEmail))))
                                    .then(Mono.defer(() -> userRepository.findById(request.getUserId())))
                                    .map(user -> RoleUpgradeRequestResponse.fromEntityWithUser(
                                            request, user.getName(), user.getEmail(), user.getRole()))
                                    .doOnSuccess(resp ->
                                            log.info(
                                                    "Role upgrade request {} approved by {} — user {} promoted to {}",
                                                    requestId, PiiMasker.maskEmail(adminEmail), PiiMasker.maskEmail(resp.getUserEmail()), resp.getRequestedRole()))
                                    .flatMap(resp ->
                                            // Notify the user about the approval (non-critical)
                                            emailService.sendRoleRequestApproved(
                                                    resp.getUserEmail(), resp.getUserName(),
                                                    resp.getCurrentRole(), resp.getRequestedRole()
                                            ).onErrorResume(err -> {
                                                log.warn("Failed to send role approval email to {}: {}", PiiMasker.maskEmail(resp.getUserEmail()), err.getMessage());
                                                return Mono.empty();
                                            }).thenReturn(resp));
                        }));
    }

    /**
     * Reject a role upgrade request (admin only).
     * TX-08: not transactional — the only write is the atomic conditional UPDATE,
     * and the rejection email must not hold a pool connection.
     */
    public Mono<RoleUpgradeRequestResponse> rejectRequest(Long requestId, String adminEmail) {
        return userRepository.findByEmail(adminEmail)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(admin -> roleUpgradeRequestRepository.findById(requestId)
                        .switchIfEmpty(Mono.error(new ResourceNotFoundException("Role upgrade request not found")))
                        .flatMap(request -> {
                            if (request.getStatus() != RoleUpgradeRequestStatus.PENDING) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "Request is already " + request.getStatus().name().toLowerCase()));
                            }

                            LocalDateTime reviewedAt = LocalDateTime.now();

                            // CC-08: claim the request atomically (see approveRequest)
                            return roleUpgradeRequestRepository.transitionFromPending(
                                            requestId, RoleUpgradeRequestStatus.REJECTED.name(),
                                            admin.getId(), reviewedAt)
                                    .flatMap(rows -> {
                                        if (rows == 0) {
                                            return Mono.error(new ResponseStatusException(
                                                    HttpStatus.BAD_REQUEST, "Request was already processed"));
                                        }
                                        request.setStatus(RoleUpgradeRequestStatus.REJECTED);
                                        request.setReviewedBy(admin.getId());
                                        request.setReviewedAt(reviewedAt);
                                        request.setNewRecord(false);
                                        return Mono.just(request);
                                    })
                                    .flatMap(saved -> userRepository.findById(saved.getUserId())
                                            .map(user -> RoleUpgradeRequestResponse.fromEntityWithUser(
                                                    saved, user.getName(), user.getEmail(), user.getRole()))
                                            .switchIfEmpty(Mono.just(RoleUpgradeRequestResponse.fromEntity(saved))))
                                    .doOnSuccess(resp ->
                                            log.info(
                                                    "Role upgrade request {} rejected by {}",
                                                    requestId, PiiMasker.maskEmail(adminEmail)))
                                    .flatMap(resp -> {
                                            // Notify the user about the rejection (non-critical)
                                            if (resp.getUserEmail() != null) {
                                                return emailService.sendRoleRequestRejected(
                                                        resp.getUserEmail(), resp.getUserName(),
                                                        resp.getRequestedRole(), resp.getCurrentRole()
                                                ).onErrorResume(err -> {
                                                    log.warn("Failed to send role rejection email to {}: {}", PiiMasker.maskEmail(resp.getUserEmail()), err.getMessage());
                                                    return Mono.empty();
                                                }).thenReturn(resp);
                                            }
                                            return Mono.just(resp);
                                    });
                        }));
    }

    private Mono<Void> notifyAdminsOfRequest(String userName, String userEmail, String requestedRole, String reason) {
        return userRepository.findByRole(UserRole.ADMIN.name(), paginationConfig.getBulkQueryMax())
                .flatMap(admin -> emailService.sendRoleUpgradeNotification(
                        admin.getEmail(), userName, userEmail, requestedRole, reason))
                .onErrorResume(err -> {
                    log.warn("Failed to send role upgrade notification: {}", err.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}
