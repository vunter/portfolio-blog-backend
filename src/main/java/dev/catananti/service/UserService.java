package dev.catananti.service;

import dev.catananti.dto.ProfileUpdateRequest;
import dev.catananti.dto.RoleUpdateRequest;
import dev.catananti.dto.UserRequest;
import dev.catananti.dto.UserResponse;
import dev.catananti.entity.User;
import dev.catananti.entity.UserRole;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.UserRepository;
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

@Service
@RequiredArgsConstructor
@Slf4j
// TODO F-230: Add email verification on registration before granting full access
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdService idService;
    private final HtmlSanitizerService htmlSanitizerService;
    private final CloudflareEmailRoutingService cfEmailRoutingService;
    private final EmailService emailService;

    public Flux<UserResponse> getAllUsers(int page, int size) {
        int offset = page * size;
        return userRepository.findAllPaged(size, offset)
                .map(UserResponse::fromEntity);
    }

    public Flux<UserResponse> searchUsers(String search, int page, int size) {
        int offset = page * size;
        return userRepository.searchUsers(search, size, offset)
                .map(UserResponse::fromEntity);
    }

    public Mono<Long> getTotalUsers() {
        return userRepository.countAll();
    }

    public Mono<Long> countSearchUsers(String search) {
        return userRepository.countSearch(search);
    }

    public Mono<UserResponse> getUserById(Long id) {
        return userRepository.findById(id)
                .map(UserResponse::fromEntity)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")));
    }

    public Mono<UserResponse> getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(UserResponse::fromEntity)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")));
    }

    @Transactional
    public Mono<UserResponse> createUser(UserRequest request) {
        // Validate role against enum
        String role = UserRole.EDITOR.name();
        if (request.getRole() != null) {
            try {
                role = UserRole.valueOf(request.getRole().toUpperCase()).name();
            } catch (IllegalArgumentException e) {
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
                .doOnSuccess(u -> log.debug("Created new user: {}", u.getEmail()));
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
                .doOnSuccess(u -> log.debug("Updated user: {}", u.getEmail()));
    }

    private Mono<UserResponse> updateUserEntity(User user, UserRequest request) {
        user.setName(htmlSanitizerService.stripHtml(request.getName()));
        user.setEmail(htmlSanitizerService.stripHtml(request.getEmail().toLowerCase().trim()));
        
        // F-233: Wrap blocking BCrypt on boundedElastic scheduler
        Mono<Void> passwordMono = Mono.empty();
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            passwordMono = Mono.fromCallable(() -> passwordEncoder.encode(request.getPassword()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(user::setPasswordHash)
                    .then();
        }
        
        if (request.getRole() != null) {
            try {
                user.setRole(UserRole.valueOf(request.getRole().toUpperCase()).name());
            } catch (IllegalArgumentException e) {
                return Mono.error(new IllegalArgumentException("error.invalid_role"));
            }
        }
        
        user.setUpdatedAt(LocalDateTime.now());

        return passwordMono.then(userRepository.save(user)
                .map(UserResponse::fromEntity));
    }

    @Transactional
    public Mono<UserResponse> updateProfile(String email, ProfileUpdateRequest request) {
        return userRepository.findByEmail(email)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "error.user_not_found")))
            .flatMap(user -> {
                boolean changed = false;

                if (request.name() != null && !request.name().isBlank()) {
                    user.setName(htmlSanitizerService.stripHtml(request.name()));
                    changed = true;
                }

                if (request.username() != null) {
                    user.setUsername(htmlSanitizerService.stripHtml(request.username()));
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

                // Email change
                if (request.email() != null && !request.email().isBlank()
                    && !request.email().equalsIgnoreCase(user.getEmail())) {
                    String newEmail = request.email().toLowerCase().trim();
                    return userRepository.existsByEmail(newEmail)
                        .flatMap(exists -> {
                            if (exists) {
                                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "error.email_in_use"));
                            }
                            user.setEmail(newEmail);
                            user.setUpdatedAt(LocalDateTime.now());
                            return userRepository.save(user).map(UserResponse::fromEntity);
                        });
                }

                // Password change — offload BCrypt to boundedElastic (F-233)
                if (request.newPassword() != null && !request.newPassword().isBlank()) {
                    if (request.currentPassword() == null || request.currentPassword().isBlank()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "error.current_password_required"));
                    }
                    final boolean finalChanged = changed;
                    return Mono.fromCallable(() -> passwordEncoder.matches(request.currentPassword(), user.getPasswordHash()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(matches -> {
                                if (!matches) {
                                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "error.current_password_incorrect"));
                                }
                                return Mono.fromCallable(() -> passwordEncoder.encode(request.newPassword()))
                                        .subscribeOn(Schedulers.boundedElastic());
                            })
                            .flatMap(encoded -> {
                                user.setPasswordHash(encoded);
                                user.setUpdatedAt(LocalDateTime.now());
                                return userRepository.save(user).map(UserResponse::fromEntity);
                            });
                }

                if (changed) {
                    user.setUpdatedAt(LocalDateTime.now());
                    return userRepository.save(user).map(UserResponse::fromEntity);
                }

                return Mono.just(UserResponse.fromEntity(user));
            });
    }

    /**
     * Safe role update that prevents demoting the last admin or self-demotion
     */
    @Transactional
    public Mono<UserResponse> updateUserRoleSafe(Long id, RoleUpdateRequest request, String currentUserEmail) {
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
                                                return performRoleUpdate(targetUser, request.getRole());
                                            });
                                }
                                return performRoleUpdate(targetUser, request.getRole());
                            });
                });
    }

    private Mono<UserResponse> performRoleUpdate(User user, String newRole) {
        String oldRole = user.getRole();
        boolean wasDevOrAbove = UserRole.ADMIN.matches(oldRole) || UserRole.DEV.matches(oldRole);
        boolean isDevOrAbove = UserRole.ADMIN.matches(newRole) || UserRole.DEV.matches(newRole);

        user.setRole(newRole);
        user.setUpdatedAt(LocalDateTime.now());

        // Manage Cloudflare Email Routing on promotion/demotion
        Mono<Void> cfAction = Mono.empty();
        if (!wasDevOrAbove && isDevOrAbove && user.getUsername() != null && !user.getUsername().isBlank()) {
            // Promotion → create forwarding rule
            cfAction = cfEmailRoutingService.createForwardingRule(user.getUsername(), user.getEmail())
                    .doOnNext(ruleId -> user.setCfEmailRuleId(ruleId))
                    .then();
        } else if (wasDevOrAbove && !isDevOrAbove && user.getCfEmailRuleId() != null) {
            // Demotion → delete forwarding rule
            String ruleId = user.getCfEmailRuleId();
            user.setCfEmailRuleId(null);
            cfAction = cfEmailRoutingService.deleteForwardingRule(ruleId);
        }

        return cfAction
                .then(userRepository.save(user))
                .map(UserResponse::fromEntity)
                .doOnSuccess(u -> {
                    log.debug("Updated role for user {}: {} → {}", u.getEmail(), oldRole, u.getRole());
                    // Send email notification on promotion to DEV/ADMIN
                    if (!wasDevOrAbove && isDevOrAbove && user.getUsername() != null) {
                        String newDevEmail = user.getUsername() + "@catananti.dev";
                        emailService.sendDevPromotionNotification(user.getEmail(), user.getName(), newDevEmail)
                                .subscribe(
                                        unused -> {},
                                        err -> log.warn("Failed to send DEV promotion email to {}: {}", user.getEmail(), err.getMessage())
                                );
                    }
                });
    }

    public Mono<Void> deleteUser(Long id) {
        return userRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.user_not_found")))
                .flatMap(user -> {
                    log.debug("Deleting user: {}", user.getEmail());
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
                                                log.debug("Deleting user: {}", targetUser.getEmail());
                                                return userRepository.delete(targetUser);
                                            });
                                }
                                log.debug("Deleting user: {}", targetUser.getEmail());
                                return userRepository.delete(targetUser);
                            });
                });
    }

    public Flux<UserResponse> getUsersByRole(String role) {
        return userRepository.findByRole(role)
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
                .doOnSuccess(resp -> emailService.sendAccountReactivated(resp.getEmail(), resp.getName())
                        .subscribe(
                                unused -> {},
                                err -> log.warn("Failed to send account reactivation email to {}: {}", resp.getEmail(), err.getMessage())
                        ));
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
                .doOnSuccess(resp -> emailService.sendAccountDeactivated(resp.getEmail(), resp.getName())
                        .subscribe(
                                unused -> {},
                                err -> log.warn("Failed to send account deactivation email to {}: {}", resp.getEmail(), err.getMessage())
                        ));
    }
}
