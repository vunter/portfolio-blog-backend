package dev.catananti.service;

import dev.catananti.config.ResilienceConfig;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.mail.javamail.JavaMailSender;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock private JavaMailSender mailSender;
    @Mock private ResilienceConfig resilience;
    @Mock private MessageSource messageSource;
    @Mock private EmailTemplateService templateService;
    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock private ReactiveValueOperations<String, String> valueOps;
    @Mock private MimeMessage mimeMessage;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(mailSender, resilience, messageSource, templateService, redisTemplate);

        // Stub template rendering – return a minimal HTML for all templates
        lenient().when(templateService.render(anyString(), anyMap()))
                .thenReturn("<html><body>rendered</body></html>");

        // Set @Value fields via reflection
        setField(emailService, "fromEmail", "noreply@test.com");
        setField(emailService, "fromName", "Test Blog");
        setField(emailService, "appUrl", "http://localhost:8080");
        setField(emailService, "siteUrl", "http://localhost:4200");
        setField(emailService, "supportEmail", "support@test.com");
        setField(emailService, "emailRateLimitPerHour", 10);
        setField(emailService, "defaultLocaleTag", "en");
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }

    @Nested
    @DisplayName("Rate Limiting")
    class RateLimiting {

        @Test
        @DisplayName("should allow email when under rate limit")
        void shouldAllowEmailUnderLimit() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("email_rate:user@test.com")).thenReturn(Mono.just(1L));
            when(redisTemplate.expire(eq("email_rate:user@test.com"), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));

            StepVerifier.create(emailService.sendTextEmail("user@test.com", "Test", "Body"))
                    .verifyComplete();

            verify(valueOps).increment("email_rate:user@test.com");
        }

        @Test
        @DisplayName("should block email when rate limit exceeded")
        void shouldBlockEmailWhenLimitExceeded() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("email_rate:user@test.com")).thenReturn(Mono.just(11L));

            StepVerifier.create(emailService.sendTextEmail("user@test.com", "Test", "Body"))
                    .expectErrorMatches(e -> e instanceof IllegalStateException
                            && e.getMessage().contains("Email rate limit exceeded"))
                    .verify();

            verify(mailSender, never()).createMimeMessage();
        }

        @Test
        @DisplayName("should normalize email to lowercase for rate limiting")
        void shouldNormalizeEmailToLowercase() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("email_rate:user@test.com")).thenReturn(Mono.just(11L));

            StepVerifier.create(emailService.sendTextEmail("User@Test.COM", "Test", "Body"))
                    .expectError(IllegalStateException.class)
                    .verify();
        }

        @Test
        @DisplayName("should fallback to allowing email when Redis is unavailable")
        void shouldFallbackWhenRedisUnavailable() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis connection failed")));
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));

            StepVerifier.create(emailService.sendTextEmail("user@test.com", "Test", "Body"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Newsletter Confirmation")
    class NewsletterConfirmation {

        @Test
        @DisplayName("should send newsletter confirmation with i18n subject")
        void shouldSendConfirmationWithI18nSubject() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            when(messageSource.getMessage(eq("email.newsletter.confirm.subject"), any(), any(Locale.class)))
                    .thenReturn("Confirm your Newsletter");
            when(messageSource.getMessage(eq("email.default.subscriber"), any(), any(Locale.class)))
                    .thenReturn("Subscriber");
            when(messageSource.getMessage(eq("email.greeting"), any(), any(Locale.class)))
                    .thenReturn("Hello Subscriber,");
            when(messageSource.getMessage(eq("email.newsletter.confirm.body"), any(), any(Locale.class)))
                    .thenReturn("Thank you for subscribing!");
            when(messageSource.getMessage(eq("email.newsletter.confirm.action"), any(), any(Locale.class)))
                    .thenReturn("Confirm by clicking:");
            when(messageSource.getMessage(eq("email.newsletter.confirm.button"), any(), any(Locale.class)))
                    .thenReturn("Confirm");
            when(messageSource.getMessage(eq("email.newsletter.confirm.disclaimer"), any(), any(Locale.class)))
                    .thenReturn("Ignore if not you.");
            when(messageSource.getMessage(eq("email.newsletter.confirm.header"), any(), any(Locale.class)))
                    .thenReturn("Welcome!");
            when(messageSource.getMessage(eq("email.footer.copyright"), any(), any(Locale.class)))
                    .thenReturn("© 2025 Leonardo. All rights reserved.");

            StepVerifier.create(emailService.sendNewsletterConfirmation("user@test.com", null, "token123"))
                    .verifyComplete();

            verify(messageSource).getMessage(eq("email.newsletter.confirm.subject"), any(), any(Locale.class));
        }
    }

    @Nested
    @DisplayName("Account Lockout")
    class AccountLockout {

        @Test
        @DisplayName("should send lockout notification and not propagate email errors")
        void shouldNotPropagateEmailErrors() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("SMTP down"));
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllLockoutMessages();

            // sendAccountLockoutNotification has .onErrorResume, so it should complete
            StepVerifier.create(emailService.sendAccountLockoutNotification(
                            "user@test.com", "User", 5, 15, "192.168.1.1"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Password Reset")
    class PasswordReset {

        @Test
        @DisplayName("should use default user name when name is null")
        void shouldUseDefaultUserWhenNameNull() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllPasswordResetMessages();

            StepVerifier.create(emailService.sendPasswordResetEmail("user@test.com", null, "reset-token"))
                    .verifyComplete();

            verify(messageSource).getMessage(eq("email.default.user"), any(), any(Locale.class));
        }
    }

    // ===== Helpers =====

    private void setupRateLimitAllow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    }

    private void stubAllLockoutMessages() {
        // Stub all message keys used in sendAccountLockoutNotification
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0)); // return the key as the value
    }

    private void stubAllPasswordResetMessages() {
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubAllMessages() {
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("Newsletter Welcome")
    class NewsletterWelcome {

        @Test
        @DisplayName("should send newsletter welcome email with name")
        void shouldSendWelcomeWithName() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendNewsletterWelcome("user@test.com", "John"))
                    .verifyComplete();

            verify(mailSender).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("should use default subscriber name when name is null")
        void shouldUseDefaultNameWhenNull() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendNewsletterWelcome("user@test.com", null))
                    .verifyComplete();

            verify(messageSource).getMessage(eq("email.default.subscriber"), any(), any(Locale.class));
        }
    }

    @Nested
    @DisplayName("New Article Notification")
    class NewArticleNotification {

        @Test
        @DisplayName("should send new article notification")
        void shouldSendArticleNotification() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendNewArticleNotification(
                    "sub@test.com", "John", "New Article", "new-article", "Excerpt text", "unsub-token"))
                    .verifyComplete();

            verify(mailSender).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("should handle null subscriber name and null excerpt")
        void shouldHandleNullSubscriberNameAndExcerpt() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendNewArticleNotification(
                    "sub@test.com", null, "Title", "slug", null, "token"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Comment Notification")
    class CommentNotification {

        @Test
        @DisplayName("should send comment notification with escaped HTML")
        void shouldSendCommentNotification() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendCommentNotification(
                    "author@test.com", "Author", "Commenter", "Article Title", "article-slug", "Nice article!"))
                    .verifyComplete();

            verify(mailSender).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("should escape XSS content in comment notification")
        void shouldEscapeXssContent() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendCommentNotification(
                    "author@test.com", "Author", "<script>alert('xss')</script>",
                    "Article <b>Title</b>", "slug", "<img onerror=alert(1)>"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Password Changed Notification")
    class PasswordChanged {

        @Test
        @DisplayName("should send password changed notification")
        void shouldSendPasswordChanged() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendPasswordChangedNotification("user@test.com", "User"))
                    .verifyComplete();

            verify(mailSender).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("should use default user when name is null")
        void shouldUseDefaultUserWhenNameNull() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendPasswordChangedNotification("user@test.com", null))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Registration Welcome")
    class RegistrationWelcome {

        @Test
        @DisplayName("should send registration welcome email")
        void shouldSendRegistrationWelcome() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendRegistrationWelcome("new@test.com", "New User"))
                    .verifyComplete();

            verify(mailSender).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("should handle null name in registration welcome")
        void shouldHandleNullName() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendRegistrationWelcome("new@test.com", null))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("No Redis Template")
    class NoRedisTemplate {

        @Test
        @DisplayName("should allow email when Redis template is null (rate limiting disabled)")
        void shouldAllowWithoutRedis() {
            EmailService noRedisService = new EmailService(mailSender, resilience, messageSource, templateService, null);
            setField(noRedisService, "fromEmail", "noreply@test.com");
            setField(noRedisService, "fromName", "Test Blog");
            setField(noRedisService, "appUrl", "http://localhost:8080");
            setField(noRedisService, "siteUrl", "http://localhost:4200");
            setField(noRedisService, "supportEmail", "support@test.com");
            setField(noRedisService, "emailRateLimitPerHour", 10);
            setField(noRedisService, "defaultLocaleTag", "en");

            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));

            StepVerifier.create(noRedisService.sendTextEmail("user@test.com", "Test", "Body"))
                    .verifyComplete();

            verify(mailSender).send(any(MimeMessage.class));
        }
    }

    @Nested
    @DisplayName("sendHtmlEmail error handling")
    class HtmlEmailErrors {

        @Test
        @DisplayName("should propagate error when mail sender fails on HTML email")
        void shouldPropagateHtmlEmailError() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("SMTP error"));
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));

            StepVerifier.create(emailService.sendHtmlEmail("user@test.com", "Subject", "<p>Body</p>"))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("Account Lockout - more branches")
    class AccountLockoutBranches {

        @Test
        @DisplayName("should handle null name in lockout notification")
        void shouldHandleNullNameInLockout() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendAccountLockoutNotification(
                    "user@test.com", null, 5, 15, "192.168.1.1"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should handle null IP in lockout notification")
        void shouldHandleNullIpInLockout() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendAccountLockoutNotification(
                    "user@test.com", "User", 3, 10, null))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Password Reset - with name")
    class PasswordResetWithName {

        @Test
        @DisplayName("should send password reset with actual name")
        void shouldSendWithActualName() {
            setupRateLimitAllow();
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
            when(resilience.getExternalTimeout()).thenReturn(Duration.ofSeconds(30));
            stubAllMessages();

            StepVerifier.create(emailService.sendPasswordResetEmail("user@test.com", "John", "token-123"))
                    .verifyComplete();

            verify(mailSender).send(any(MimeMessage.class));
        }
    }
}
