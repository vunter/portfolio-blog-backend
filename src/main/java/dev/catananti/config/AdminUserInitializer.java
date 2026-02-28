package dev.catananti.config;

import dev.catananti.entity.User;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.IdService;
import dev.catananti.util.PasswordValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Initializes the admin user on application startup if configured via environment variables.
 * This is the secure way to create the initial admin user.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserInitializer {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdService idService;

    @Value("${admin.email:}")
    private String adminEmail;

    @Value("${admin.password:}")
    private String adminPassword;

    @Value("${admin.name:Administrator}")
    private String adminName;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAdminUser() {
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.debug("Admin initialization skipped - ADMIN_EMAIL and ADMIN_PASSWORD not configured");
            return;
        }

        if (!PasswordValidator.isComplexEnough(adminPassword)) {
            log.warn("Admin password does not meet complexity requirements (min 12 chars, uppercase, lowercase, digit, special char). Skipping admin creation.");
            return;
        }

        userRepository.existsByEmail(adminEmail)
                .flatMap(exists -> {
                    if (exists) {
                        log.debug("Admin user already exists: {}", maskEmail(adminEmail));
                        return reactor.core.publisher.Mono.empty();
                    }

                    User admin = User.builder()
                            .id(idService.nextId())
                            .email(adminEmail)
                            .passwordHash(passwordEncoder.encode(adminPassword))
                            .name(adminName)
                            .role("ADMIN")
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    return userRepository.save(admin)
                            .doOnSuccess(u -> {
                                String email = u.getEmail();
                                log.debug("Admin user created successfully: {}", maskEmail(email));
                            });
                })
                .subscribe(
                        result -> {},
                        error -> log.error("Failed to initialize admin user: {}", error.getMessage())
                );
    }

    /** F-034: Safe email masking that won't crash on malformed emails (missing @) */
    private static String maskEmail(String email) {
        if (email == null) return "***";
        int atIdx = email.indexOf('@');
        if (atIdx < 0) return "***";
        return email.substring(0, Math.min(3, atIdx)) + "***@" + email.substring(atIdx + 1);
    }
}
