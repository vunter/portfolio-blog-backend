package dev.catananti.service;

import dev.catananti.dto.RoleUpgradeRequestDto;
import dev.catananti.dto.RoleUpgradeRequestResponse;
import dev.catananti.dto.RoleUpdateRequest;
import dev.catananti.entity.RoleUpgradeRequest;
import dev.catananti.entity.UserRole;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.RoleUpgradeRequestRepository;
import dev.catananti.repository.UserRepository;
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

    /**
     * Submit a role upgrade request.
     * Only one pending request per user is allowed.
     */
    @Transactional
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
                                        .status("PENDING")
                                        .createdAt(LocalDateTime.now())
                                        .build();

                                return roleUpgradeRequestRepository.save(request)
                                        .map(saved -> RoleUpgradeRequestResponse.fromEntityWithUser(
                                                saved, user.getName(), user.getEmail(), user.getRole()))
                                        .doOnSuccess(resp -> {
                                            log.info("Role upgrade request submitted by {} for role {}",
                                                    userEmail, dto.requestedRole());
                                            // Notify admin(s) via email
                                            notifyAdminsOfRequest(user.getName(), user.getEmail(), dto.requestedRole(), dto.reason());
                                        });
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
     */
    @Transactional
    public Mono<RoleUpgradeRequestResponse> approveRequest(Long requestId, String adminEmail) {
        return userRepository.findByEmail(adminEmail)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(admin -> roleUpgradeRequestRepository.findById(requestId)
                        .switchIfEmpty(Mono.error(new ResourceNotFoundException("Role upgrade request not found")))
                        .flatMap(request -> {
                            if (!"PENDING".equals(request.getStatus())) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "Request is already " + request.getStatus().toLowerCase()));
                            }

                            // Update request status
                            request.setStatus("APPROVED");
                            request.setReviewedBy(admin.getId());
                            request.setReviewedAt(LocalDateTime.now());
                            request.setNewRecord(false);

                            // Update user role via the safe method
                            RoleUpdateRequest roleUpdate = new RoleUpdateRequest();
                            roleUpdate.setRole(request.getRequestedRole());

                            return roleUpgradeRequestRepository.save(request)
                                    .then(userService.updateUserRoleSafe(
                                            request.getUserId(), roleUpdate, adminEmail))
                                    .then(userRepository.findById(request.getUserId()))
                                    .map(user -> RoleUpgradeRequestResponse.fromEntityWithUser(
                                            request, user.getName(), user.getEmail(), user.getRole()))
                                    .doOnSuccess(resp -> {
                                            log.info(
                                                    "Role upgrade request {} approved by {} — user {} promoted to {}",
                                                    requestId, adminEmail, resp.getUserEmail(), resp.getRequestedRole());
                                            // Notify the user about the approval
                                            emailService.sendRoleRequestApproved(
                                                    resp.getUserEmail(), resp.getUserName(),
                                                    resp.getCurrentRole(), resp.getRequestedRole()
                                            ).subscribe(
                                                    unused -> {},
                                                    err -> log.warn("Failed to send role approval email to {}: {}", resp.getUserEmail(), err.getMessage())
                                            );
                                    });
                        }));
    }

    /**
     * Reject a role upgrade request (admin only).
     */
    @Transactional
    public Mono<RoleUpgradeRequestResponse> rejectRequest(Long requestId, String adminEmail) {
        return userRepository.findByEmail(adminEmail)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(admin -> roleUpgradeRequestRepository.findById(requestId)
                        .switchIfEmpty(Mono.error(new ResourceNotFoundException("Role upgrade request not found")))
                        .flatMap(request -> {
                            if (!"PENDING".equals(request.getStatus())) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "Request is already " + request.getStatus().toLowerCase()));
                            }

                            request.setStatus("REJECTED");
                            request.setReviewedBy(admin.getId());
                            request.setReviewedAt(LocalDateTime.now());
                            request.setNewRecord(false);

                            return roleUpgradeRequestRepository.save(request)
                                    .flatMap(saved -> userRepository.findById(saved.getUserId())
                                            .map(user -> RoleUpgradeRequestResponse.fromEntityWithUser(
                                                    saved, user.getName(), user.getEmail(), user.getRole()))
                                            .switchIfEmpty(Mono.just(RoleUpgradeRequestResponse.fromEntity(saved))))
                                    .doOnSuccess(resp -> {
                                            log.info(
                                                    "Role upgrade request {} rejected by {}",
                                                    requestId, adminEmail);
                                            // Notify the user about the rejection
                                            if (resp.getUserEmail() != null) {
                                                emailService.sendRoleRequestRejected(
                                                        resp.getUserEmail(), resp.getUserName(),
                                                        resp.getRequestedRole(), resp.getCurrentRole()
                                                ).subscribe(
                                                        unused -> {},
                                                        err -> log.warn("Failed to send role rejection email to {}: {}", resp.getUserEmail(), err.getMessage())
                                                );
                                            }
                                    });
                        }));
    }

    private void notifyAdminsOfRequest(String userName, String userEmail, String requestedRole, String reason) {
        userRepository.findByRole(UserRole.ADMIN.name())
                .flatMap(admin -> emailService.sendRoleUpgradeNotification(
                        admin.getEmail(), userName, userEmail, requestedRole, reason))
                .subscribe(
                        unused -> {},
                        err -> log.warn("Failed to send role upgrade notification: {}", err.getMessage())
                );
    }
}
