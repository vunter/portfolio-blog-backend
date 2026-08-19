package dev.catananti.service;

import dev.catananti.dto.ProfileUpdateRequest;
import dev.catananti.dto.RoleUpdateRequest;
import dev.catananti.dto.UserActivityResponse;
import dev.catananti.dto.UserRequest;
import dev.catananti.dto.UserResponse;
import dev.catananti.entity.User;
import dev.catananti.entity.UserRole;
import dev.catananti.config.PaginationConfig;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.AuditLogRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.RefreshTokenRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.DigestUtils;
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
// F-230: Email verification is controlled by the emailVerified field on User entity (added in F-260).
// Registration sets emailVerified=false; verification flow updates it to true.
// Full verification email sending is handled by EmailService when the flow is enabled.
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdService idService;
    private final HtmlSanitizerService htmlSanitizerService;
    private final CloudflareEmailRoutingService cfEmailRoutingService;
    private final EmailService emailService;
    private final EmailChangeService emailChangeService;
    private final UserCacheService userCacheService;
    private final RefreshTokenService refreshTokenService;
    private final PaginationConfig paginationConfig;
    // AUD18-M8: explicit operator so the role update can commit BEFORE the Cloudflare
    // HTTP call (an @Transactional annotation would wrap side effects into the tx).
    private final org.springframework.transaction.reactive.TransactionalOperator transactionalOperator;
    // AUD19-F140: read-only sources for the admin user-activity summary
    private final ArticleRepository articleRepository;
    private final CommentRepository commentRepository;
    private final AuditLogRepository auditLogRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public Flux<UserResponse> getAllUsers(int page, int size) {
        int offset = page * size;
        return userRepository.findAllPaged(size, offset)
                .map(UserResponse::fromEntity);
    }

    public Flux<UserResponse> searchUsers(String search, int page, int size) {
        int offset = page * size;
        String sanitized = DigestUtils.escapeLikePattern(search);
        return userRepository.searchUsers(sanitized, size, offset)
                .map(UserResponse::fromEntity);
    }

    public Mono<Long> getTotalUsers() {
        return userRepository.countAll();
    }

    public Mono<Long> countSearchUsers(String search) {
        String sanitized = DigestUtils.escapeLikePattern(search);
        return userRepository.countSearch(sanitized);
    }

    public Mono<UserResponse> getUserById(Long id) {
        return userRepository.findById(id)
                .map(UserResponse::fromEntity)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")));
    }

    /**
     * AUD19-F140: activity summary for the admin user list.
     *
     * <p>All four values are derived from existing data — no schema migration:
     * accountCreated = users.created_at; articlesCreated = COUNT(articles.author_id);
     * commentsPosted = COUNT(comments.user_id) (structural link added in V21);
     * lastLogin = the newest of (last LOGIN audit entry, last refresh-token issuance).
     * AUD19-LOGIN: the audit source is now live — logLoginSuccess is wired into every
     * successful login (password and MFA via AuthService.issueFullTokens, OAuth2 via
     * OAuth2Service.issueTokens), writing action='LOGIN' rows that this query reads.
     * The refresh-token fallback is kept for logins predating the wiring and because
     * the audit write is best-effort. lastLogin is null when neither source has data.</p>
     *
     * @throws ResourceNotFoundException (→ 404) when the user does not exist
     */
    public Mono<UserActivityResponse> getUserActivity(Long id) {
        return userRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(user -> Mono.zip(
                                articleRepository.countByAuthorId(id).defaultIfEmpty(0L),
                                commentRepository.countByUserId(id).defaultIfEmpty(0L),
                                auditLogRepository.findLastActionAt(id, AuditEventType.LOGIN.action())
                                        .map(Optional::of).defaultIfEmpty(Optional.empty()),
                                refreshTokenRepository.findLatestCreatedAtByUserId(id)
                                        .map(Optional::of).defaultIfEmpty(Optional.empty()))
                        .map(counts -> new UserActivityResponse(
                                latestOf(counts.getT3().orElse(null), counts.getT4().orElse(null)),
                                user.getCreatedAt(),
                                counts.getT1(),
                                counts.getT2())));
    }

    private static LocalDateTime latestOf(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    public Mono<UserResponse> getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(UserResponse::fromEntity)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")));
    }

    @Transactional
    public Mono<UserResponse> createUser(UserRequest request) {
        // Validate role against enum
        String role = UserRole.DEV.name();
        if (request.getRole() != null) {
            try {
                role = UserRole.valueOf(request.getRole().toUpperCase()).name();
            } catch (IllegalArgumentException e) {
                log.warn("Invalid role provided: {}", request.getRole());
                return Mono.error(new IllegalArgumentException("error.invalid_role"));
            }
        }

        final String validatedRole = role;
        return userRepository.existsByEmail(request.getEmail())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new IllegalArgumentException("error.email_already_exists"));
                    }

                    // F-233: Wrap blocking BCrypt on boundedElastic scheduler
                    return Mono.fromCallable(() -> passwordEncoder.encode(request.getPassword()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(encodedPassword -> {
                                User user = User.builder()
                                        .id(idService.nextId())
                                        .email(htmlSanitizerService.stripHtml(request.getEmail().toLowerCase().trim()))
                                        .name(htmlSanitizerService.stripHtml(request.getName()))
                                        .passwordHash(encodedPassword)
                                        .role(validatedRole)
                                        .createdAt(LocalDateTime.now())
                                        .updatedAt(LocalDateTime.now())
                                        .build();

                                return userRepository.save(user)
                                        .map(UserResponse::fromEntity);
                            });
                })
                .doOnSuccess(u -> log.debug("Created new user: {}", PiiMasker.maskEmail(u.getEmail())));
    }

    @Transactional
    public Mono<UserResponse> updateUser(Long id, UserRequest request) {
        return userRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(existingUser -> {
                    // Check if email is being changed and if new email already exists
                    if (!existingUser.getEmail().equals(request.getEmail())) {
                        return userRepository.existsByEmail(request.getEmail())
                                .flatMap(exists -> {
                                    if (exists) {
                                        return Mono.error(new IllegalArgumentException("error.email_already_exists"));
                                    }
                                    return updateUserEntity(existingUser, request);
                                });
                    }
                    return updateUserEntity(existingUser, request);
                })
                .doOnSuccess(u -> log.debug("Updated user: {}", PiiMasker.maskEmail(u.getEmail())));
    }

    private Mono<UserResponse> updateUserEntity(User user, UserRequest request) {
        // AUD18-M5: track which credential-relevant fields change BEFORE mutating the
        // entity, so sessions can be revoked and the auth cache evicted after save
        // (mirrors updateProfile's password handling and the F-046 pattern).
        boolean emailChanging = !user.getEmail().equals(
                htmlSanitizerService.stripHtml(request.getEmail().toLowerCase().trim()));
        boolean passwordChanging = request.getPassword() != null && !request.getPassword().isBlank();
        boolean roleChanging = request.getRole() != null
                && !request.getRole().equalsIgnoreCase(user.getRole());

        user.setName(htmlSanitizerService.stripHtml(request.getName()));
        user.setEmail(htmlSanitizerService.stripHtml(request.getEmail().toLowerCase().trim()));

        // F-233: Wrap blocking BCrypt on boundedElastic scheduler
        Mono<Void> passwordMono = Mono.empty();
        if (passwordChanging) {
            passwordMono = Mono.fromCallable(() -> passwordEncoder.encode(request.getPassword()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(encoded -> {
                        user.setPasswordHash(encoded);
                        return encoded;
                    })
                    .then();
        }

        if (request.getRole() != null) {
            try {
                user.setRole(UserRole.valueOf(request.getRole().toUpperCase()).name());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid role provided: {}", request.getRole());
                return Mono.error(new IllegalArgumentException("error.invalid_role"));
            }
        }

        user.setUpdatedAt(LocalDateTime.now());

        return passwordMono.then(userRepository.save(user))
                .flatMap(saved -> {
                    // AUD18-M5: an admin password reset must kill the target's live
                    // sessions, like a self-service password change does (updateProfile).
                    Mono<Void> revoke = passwordChanging
                            ? refreshTokenService.revokeAllUserTokens(saved.getId())
                            : Mono.empty();
                    return revoke.then(Mono.fromRunnable(() -> {
                        // AUD18-M5 / F-046: any credential or authorization change makes the
                        // cached auth snapshot stale — evict for immediate effect.
                        if (emailChanging || passwordChanging || roleChanging) {
                            userCacheService.evict(saved.getId());
                        }
                    })).thenReturn(UserResponse.fromEntity(saved));
                });
    }

    @Transactional
    public Mono<UserResponse> updateProfile(String email, ProfileUpdateRequest request) {
        return userRepository.findByEmail(email)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "error.user_not_found")))
            .flatMap(user -> {
                boolean changed = false;

                // Simple field updates (no password required)
                if (request.name() != null && !request.name().isBlank()) {
                    user.setName(htmlSanitizerService.stripHtml(request.name()));
                    changed = true;
                }

                if (request.avatarUrl() != null) {
                    String url = request.avatarUrl().isBlank() ? null : request.avatarUrl().trim();
                    if (url != null) {
                        String lower = url.toLowerCase();
                        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
                            return Mono.error(new org.springframework.web.server.ResponseStatusException(
                                    org.springframework.http.HttpStatus.BAD_REQUEST, "Avatar URL must use http or https scheme"));
                        }
                    }
                    user.setAvatarUrl(url);
                    changed = true;
                }

                if (request.bio() != null) {
                    user.setBio(htmlSanitizerService.stripHtml(request.bio()));
                    changed = true;
                }

                if (request.preferredLocale() != null && !request.preferredLocale().isBlank()) {
                    user.setPreferredLocale(request.preferredLocale().trim());
                    changed = true;
                }

                if (Boolean.TRUE.equals(request.termsAccepted()) && !Boolean.TRUE.equals(user.getTermsAccepted())) {
                    user.setTermsAccepted(true);
                    user.setTermsAcceptedAt(LocalDateTime.now());
                    user.setTermsVersion("1.0");
                    changed = true;
                }

                // Detect sensitive changes requiring password confirmation
                boolean emailChanging = request.email() != null && !request.email().isBlank()
                        && !request.email().equalsIgnoreCase(user.getEmail());
                String sanitizedUsername = request.username() != null
                        ? htmlSanitizerService.stripHtml(request.username()) : null;
                boolean usernameChanging = sanitizedUsername != null
                        && !sanitizedUsername.equals(user.getUsername() != null ? user.getUsername() : "");
                boolean passwordChanging = request.newPassword() != null && !request.newPassword().isBlank();
                boolean needsPasswordValidation = emailChanging || usernameChanging || passwordChanging;

                if (needsPasswordValidation) {
                    boolean hasExistingPassword = user.getPasswordHash() != null && !user.getPasswordHash().isBlank();

                    // OAuth users without a password can set username/password without currentPassword
                    Mono<Boolean> passwordCheck;
                    if (hasExistingPassword) {
                        if (request.currentPassword() == null || request.currentPassword().isBlank()) {
                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "error.current_password_required"));
                        }
                        passwordCheck = Mono.fromCallable(() -> passwordEncoder.matches(request.currentPassword(), user.getPasswordHash()))
                                .subscribeOn(Schedulers.boundedElastic());
                    } else {
                        passwordCheck = Mono.just(true);
                    }

                    return passwordCheck
                            .flatMap(matches -> {
                                if (!matches) {
                                    return Mono.<UserResponse>error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "error.current_password_incorrect"));
                                }

                                // Username uniqueness check
                                Mono<Void> usernameCheck = Mono.empty();
                                if (usernameChanging) {
                                    usernameCheck = userRepository.existsByUsername(sanitizedUsername)
                                        .flatMap(exists -> {
                                            if (exists && !sanitizedUsername.equalsIgnoreCase(user.getUsername())) {
                                                return Mono.<Void>error(new ResponseStatusException(HttpStatus.CONFLICT, "error.username_in_use"));
                                            }
                                            user.setUsername(sanitizedUsername);
                                            return Mono.<Void>empty();
                                        });
                                }

                                // Email change — initiate verification (don't change directly)
                                Mono<Void> emailCheck = Mono.empty();
                                if (emailChanging) {
                                    String newEmail = request.email().toLowerCase().trim();
                                    emailCheck = userRepository.existsByEmail(newEmail)
                                        .flatMap(exists -> {
                                            if (exists) {
                                                return Mono.<Void>error(new ResponseStatusException(HttpStatus.CONFLICT, "error.email_in_use"));
                                            }
                                            return emailChangeService.initiateEmailChange(user.getId(), newEmail, user.getName());
                                        });
                                }

                                // Password encoding
                                Mono<Void> passwordUpdate = Mono.empty();
                                if (passwordChanging) {
                                    passwordUpdate = Mono.fromCallable(() -> passwordEncoder.encode(request.newPassword()))
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .map(encoded -> {
                                                user.setPasswordHash(encoded);
                                                return encoded;
                                            })
                                            .then();
                                }

                                return usernameCheck.then(emailCheck).then(passwordUpdate)
                                        .then(Mono.defer(() -> {
                                            user.setUpdatedAt(LocalDateTime.now());
                                            return userRepository.save(user)
                                                    .flatMap(saved -> {
                                                        if (passwordChanging) {
                                                            return refreshTokenService.revokeAllUserTokens(saved.getId())
                                                                    .then(Mono.just(UserResponse.fromEntity(saved)));
                                                        }
                                                        return Mono.just(UserResponse.fromEntity(saved));
                                                    });
                                        }));
                            });
                }

                // Username provided but unchanged — apply without password
                if (sanitizedUsername != null && !usernameChanging) {
                    user.setUsername(sanitizedUsername);
                }

                if (changed) {
                    user.setUpdatedAt(LocalDateTime.now());
                    return userRepository.save(user).map(UserResponse::fromEntity);
                }

                return Mono.just(UserResponse.fromEntity(user));
            });
    }

    /**
     * AUD18-M8: outcome of the DB-only role change, carrying what the post-commit
     * side effects need ({@code removedCfRuleId} is the Cloudflare rule cleared from
     * the user row on demotion, still to be deleted at Cloudflare).
     */
    public record RoleChange(User user, String oldRole, String removedCfRuleId) {}

    /**
     * Safe role update that prevents demoting the last admin or self-demotion.
     *
     * <p>AUD18-M8: restructured so the Cloudflare Email Routing HTTP call happens
     * AFTER the DB transaction commits (TX-08 — external I/O inside a transaction
     * holds a pool connection through the whole HTTP round-trip). The role change is
     * the source of truth; the forwarding rule is best-effort infrastructure that is
     * reconciled after commit and logged on failure, like the promotion email.
     */
    public Mono<UserResponse> updateUserRoleSafe(Long id, RoleUpdateRequest request, String currentUserEmail) {
        return transactionalOperator.transactional(updateUserRoleDbOnly(id, request, currentUserEmail))
                .flatMap(this::applyRoleChangeSideEffects);
    }

    /**
     * DB-only half of the role update: validations + role save. Public so
     * {@code RoleUpgradeRequestService} can run it inside its OWN transaction
     * (status transition + promotion stay atomic) and defer the Cloudflare/email
     * side effects to {@link #applyRoleChangeSideEffects} after commit (AUD18-M8).
     */
    public Mono<RoleChange> updateUserRoleDbOnly(Long id, RoleUpdateRequest request, String currentUserEmail) {
        return userRepository.findByEmail(currentUserEmail)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(currentUser -> {
                    // Prevent self-demotion
                    if (currentUser.getId().equals(id) && !UserRole.ADMIN.matches(request.getRole())) {
                        return Mono.error(new IllegalArgumentException("error.cannot_demote_self"));
                    }

                    return userRepository.findById(id)
                            .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                            .flatMap(targetUser -> {
                                // If demoting an admin, ensure at least one admin remains
                                if (UserRole.ADMIN.matches(targetUser.getRole()) && !UserRole.ADMIN.matches(request.getRole())) {
                                    return countUsersByRole(UserRole.ADMIN.name())
                                            .flatMap(adminCount -> {
                                                if (adminCount <= 1) {
                                                    return Mono.error(new IllegalArgumentException("error.cannot_demote_last_admin"));
                                                }
                                                return performRoleUpdateDb(targetUser, request.getRole());
                                            });
                                }
                                return performRoleUpdateDb(targetUser, request.getRole());
                            });
                });
    }

    private Mono<RoleChange> performRoleUpdateDb(User user, String newRole) {
        String oldRole = user.getRole();
        boolean wasDevOrAbove = UserRole.ADMIN.matches(oldRole) || UserRole.DEV.matches(oldRole);
        boolean isDevOrAbove = UserRole.ADMIN.matches(newRole) || UserRole.DEV.matches(newRole);

        user.setRole(newRole);
        user.setUpdatedAt(LocalDateTime.now());

        // AUD18-M8: on demotion the rule id is cleared in the SAME transaction as the
        // role change (the row never claims a rule a demoted user shouldn't have); the
        // actual Cloudflare delete happens post-commit in applyRoleChangeSideEffects.
        String removedCfRuleId = null;
        if (wasDevOrAbove && !isDevOrAbove && user.getCfEmailRuleId() != null) {
            removedCfRuleId = user.getCfEmailRuleId();
            user.setCfEmailRuleId(null);
        }
        final String removed = removedCfRuleId;

        return userRepository.save(user)
                .map(saved -> new RoleChange(saved, oldRole, removed));
    }

    /**
     * AUD18-M8: post-commit half of the role update — Cloudflare Email Routing and
     * the promotion email. Both are best-effort: a Cloudflare outage must neither
     * roll back nor block an already-committed role change (failures are logged for
     * manual reconciliation, consistent with how the codebase treats notification
     * emails). Must be invoked OUTSIDE any transaction.
     */
    public Mono<UserResponse> applyRoleChangeSideEffects(RoleChange change) {
        User user = change.user();
        String oldRole = change.oldRole();
        boolean wasDevOrAbove = UserRole.ADMIN.matches(oldRole) || UserRole.DEV.matches(oldRole);
        boolean isDevOrAbove = UserRole.ADMIN.matches(user.getRole()) || UserRole.DEV.matches(user.getRole());

        // AUD18-M5 / F-046: the cached auth snapshot carries the old role — evict so
        // the new authorization takes effect immediately.
        userCacheService.evict(user.getId());

        Mono<Void> cfAction = Mono.empty();
        if (!wasDevOrAbove && isDevOrAbove && user.getUsername() != null && !user.getUsername().isBlank()) {
            // Promotion → create forwarding rule, then persist the rule id via a
            // partial UPDATE (CC-07: cannot clobber concurrent writes to other columns).
            cfAction = cfEmailRoutingService.createForwardingRule(user.getUsername(), user.getEmail())
                    .flatMap(ruleId -> {
                        user.setCfEmailRuleId(ruleId);
                        return userRepository.updateCfEmailRuleId(user.getId(), ruleId, LocalDateTime.now()).then();
                    })
                    .onErrorResume(err -> {
                        log.warn("Cloudflare forwarding rule creation failed for {} (role change kept): {}",
                                PiiMasker.maskEmail(user.getEmail()), err.getMessage());
                        return Mono.empty();
                    });
        } else if (wasDevOrAbove && !isDevOrAbove && change.removedCfRuleId() != null) {
            // Demotion → the rule id was already cleared in the transaction; delete the
            // rule at Cloudflare best-effort (an orphan rule is only a stale forward).
            cfAction = cfEmailRoutingService.deleteForwardingRule(change.removedCfRuleId())
                    .onErrorResume(err -> {
                        log.warn("Cloudflare forwarding rule deletion failed for {} (ruleId={}): {}",
                                PiiMasker.maskEmail(user.getEmail()), change.removedCfRuleId(), err.getMessage());
                        return Mono.empty();
                    });
        }

        UserResponse response = UserResponse.fromEntity(user);
        return cfAction
                .then(Mono.defer(() -> {
                    log.debug("Updated role for user {}: {} → {}", PiiMasker.maskEmail(user.getEmail()), oldRole, user.getRole());
                    // Send email notification on promotion to DEV/ADMIN (non-critical)
                    if (!wasDevOrAbove && isDevOrAbove && user.getUsername() != null) {
                        String newDevEmail = user.getUsername() + "@catananti.dev";
                        return emailService.sendDevPromotionNotification(user.getEmail(), response.getName(), newDevEmail)
                                .onErrorResume(err -> {
                                    log.warn("Failed to send DEV promotion email to {}: {}", PiiMasker.maskEmail(user.getEmail()), err.getMessage());
                                    return Mono.empty();
                                })
                                .thenReturn(response);
                    }
                    return Mono.just(response);
                }));
    }

    public Mono<Void> deleteUser(Long id) {
        return userRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(user -> {
                    log.debug("Deleting user: {}", PiiMasker.maskEmail(user.getEmail()));
                    return userRepository.delete(user);
                });
    }

    /**
     * Safe delete that prevents self-deletion and deleting the last admin
     */
    @Transactional
    public Mono<Void> deleteUserSafe(Long id, String currentUserEmail) {
        return userRepository.findByEmail(currentUserEmail)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(currentUser -> {
                    // Prevent self-deletion
                    if (currentUser.getId().equals(id)) {
                        return Mono.error(new IllegalArgumentException("error.cannot_delete_self"));
                    }
                    
                    return userRepository.findById(id)
                            .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                            .flatMap(targetUser -> {
                                // If deleting an admin, ensure at least one admin remains
                                if (UserRole.ADMIN.matches(targetUser.getRole())) {
                                    return countUsersByRole(UserRole.ADMIN.name())
                                            .flatMap(adminCount -> {
                                                if (adminCount <= 1) {
                                                    return Mono.error(new IllegalArgumentException("error.cannot_delete_last_admin"));
                                                }
                                                log.debug("Deleting user: {}", PiiMasker.maskEmail(targetUser.getEmail()));
                                                return userRepository.delete(targetUser);
                                            });
                                }
                                log.debug("Deleting user: {}", targetUser.getEmail());
                                return userRepository.delete(targetUser);
                            });
                });
    }

    public Flux<UserResponse> getUsersByRole(String role) {
        return userRepository.findByRole(role, paginationConfig.getBulkQueryMax())
                .map(UserResponse::fromEntity);
    }

    public Mono<Long> countUsersByRole(String role) {
        return userRepository.countByRole(role);
    }

    public Mono<UserResponse> activateUser(Long id) {
        return userRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(user -> {
                    user.setActive(true);
                    user.setUpdatedAt(java.time.LocalDateTime.now());
                    return userRepository.save(user);
                })
                .map(UserResponse::fromEntity)
                .flatMap(resp -> emailService.sendAccountReactivated(resp.getEmail(), resp.getName())
                        .onErrorResume(err -> {
                            log.warn("Failed to send account reactivation email to {}: {}", PiiMasker.maskEmail(resp.getEmail()), err.getMessage());
                            return Mono.empty();
                        })
                        .thenReturn(resp));
    }

    public Mono<UserResponse> deactivateUser(Long id, String currentUserEmail) {
        return userRepository.findByEmail(currentUserEmail)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(currentUser -> {
                    if (currentUser.getId().equals(id)) {
                        return Mono.error(new IllegalArgumentException("error.cannot_deactivate_self"));
                    }
                    return userRepository.findById(id)
                            .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                            .flatMap(user -> {
                                user.setActive(false);
                                user.setUpdatedAt(java.time.LocalDateTime.now());
                                return userRepository.save(user);
                            });
                })
                .map(UserResponse::fromEntity)
                // F-046: Evict deactivated user from JWT auth cache for immediate lockout
                .flatMap(resp -> {
                    userCacheService.evict(Long.valueOf(resp.getId()));
                    return emailService.sendAccountDeactivated(resp.getEmail(), resp.getName())
                        .onErrorResume(err -> {
                            log.warn("Failed to send account deactivation email to {}: {}", PiiMasker.maskEmail(resp.getEmail()), err.getMessage());
                            return Mono.empty();
                        })
                        .thenReturn(resp);
                });
    }
}
