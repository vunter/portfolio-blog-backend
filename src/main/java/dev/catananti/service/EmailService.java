package dev.catananti.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import dev.catananti.config.ResilienceConfig;
import dev.catananti.util.HtmlUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Service for sending emails.
 * Uses Java 21+ Virtual Threads for efficient blocking I/O operations.
 * All email content is internationalised via {@link MessageSource} with keys in {@code messages_*.properties}.
 * The default locale is configurable via {@code app.email.default-locale} (default: pt-BR).
 * F-172: Emails are now rendered via Thymeleaf templates under {@code classpath:/templates/email/}.
 */
@Service
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final ResilienceConfig resilience;
    private final MessageSource messageSource;
    private final EmailTemplateService templateService;
    @Nullable
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public EmailService(
            JavaMailSender mailSender,
            ResilienceConfig resilience,
            MessageSource messageSource,
            EmailTemplateService templateService,
            @Autowired(required = false)
            @Qualifier("reactiveRedisTemplate") @Nullable ReactiveRedisTemplate<String, String> redisTemplate) {
        this.mailSender = mailSender;
        this.resilience = resilience;
        this.messageSource = messageSource;
        this.templateService = templateService;
        this.redisTemplate = redisTemplate;
        if (redisTemplate == null) {
            log.info("ReactiveRedisTemplate not available — email rate limiting disabled");
        }
    }
    
    /**
     * Virtual thread executor for efficient blocking I/O (Java 21+ Project Loom).
     * Virtual threads are lightweight and ideal for blocking operations like email sending.
     */
    private static final reactor.core.scheduler.Scheduler VIRTUAL_THREAD_SCHEDULER = 
            Schedulers.fromExecutor(Executors.newVirtualThreadPerTaskExecutor());

    @Value("${app.email.from:noreply@localhost}")
    private String fromEmail;

    @Value("${app.email.from-name:Portfolio Blog}")
    private String fromName;

    @Value("${app.url:http://localhost:8080}")
    private String appUrl;

    @Value("${app.site-url:http://localhost:4200}")
    private String siteUrl;

    @Value("${app.email.support:support@catananti.dev}")
    private String supportEmail;

    @Value("${app.email.rate-limit-per-hour:10}")
    private int emailRateLimitPerHour;

    private static final String EMAIL_RATE_KEY_PREFIX = "email_rate:";
    private static final Duration EMAIL_RATE_WINDOW = Duration.ofHours(1);

    @Value("${app.email.default-locale:pt-BR}")
    private String defaultLocaleTag;

    /** Resolve a message key using the configured default locale. */
    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, Locale.forLanguageTag(defaultLocaleTag));
    }

    /**
     * Check per-recipient email rate limit using a Redis sliding window (1-hour window).
     * Returns {@code Mono.empty()} if allowed, or errors with {@link IllegalStateException} if exceeded.
     * Falls back to allowing the email if Redis is unavailable.
     */
    private Mono<Void> checkRateLimit(String recipientEmail) {
        if (redisTemplate == null) {
            return Mono.empty(); // rate limiting disabled without Redis
        }
        String key = EMAIL_RATE_KEY_PREFIX + recipientEmail.toLowerCase();
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    if (count == 1) {
                        return redisTemplate.expire(key, EMAIL_RATE_WINDOW).thenReturn(count);
                    }
                    return Mono.just(count);
                })
                .flatMap(count -> {
                    if (count > emailRateLimitPerHour) {
                        log.warn("Email rate limit exceeded for recipient: {} (count: {}/{})", recipientEmail, count, emailRateLimitPerHour);
                        return Mono.error(new IllegalStateException(
                                "Email rate limit exceeded for " + recipientEmail + ". Max " + emailRateLimitPerHour + " per hour."));
                    }
                    return Mono.<Void>empty();
                })
                .onErrorResume(e -> {
                    if (e instanceof IllegalStateException ise) {
                        return Mono.error(ise); // re-throw rate limit errors
                    }
                    log.warn("Redis unavailable for email rate limiting, allowing email to: {}", recipientEmail);
                    return Mono.empty(); // fallback: allow if Redis is down
                });
    }

    /**
     * Send a plain text email (with per-recipient rate limiting).
     */
    public Mono<Void> sendTextEmail(String to, String subject, String text) {
        return checkRateLimit(to).then(Mono.<Void>fromRunnable(() -> {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
                
                helper.setFrom(fromEmail, fromName);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(text, false);
                
                mailSender.send(message);
                log.debug("Text email sent to: {}", to);
            } catch (Exception e) {
                log.warn("Failed to send text email to {}: {}", to, e.getMessage());
                throw new RuntimeException("Failed to send email", e);
            }
        }).subscribeOn(VIRTUAL_THREAD_SCHEDULER)
                .timeout(resilience.getExternalTimeout()));
    }

    /**
     * Send an HTML email (with per-recipient rate limiting).
     */
    public Mono<Void> sendHtmlEmail(String to, String subject, String htmlContent) {
        return checkRateLimit(to).then(Mono.<Void>fromRunnable(() -> {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                
                helper.setFrom(fromEmail, fromName);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(htmlContent, true);
                
                mailSender.send(message);
                log.debug("HTML email sent to: {}", to);
            } catch (Exception e) {
                log.warn("Failed to send HTML email to {}: {}", to, e.getMessage());
                throw new RuntimeException("Failed to send email", e);
            }
        }).subscribeOn(VIRTUAL_THREAD_SCHEDULER)
                .timeout(resilience.getExternalTimeout()));
    }

    /**
     * Send newsletter subscription confirmation email.
     */
    public Mono<Void> sendNewsletterConfirmation(String to, String name, String confirmationToken) {
        String subject = msg("email.newsletter.confirm.subject");
        String confirmUrl = siteUrl + "/newsletter/confirm?token=" + confirmationToken;
        String displayName = name != null ? name : msg("email.default.subscriber");

        String html = templateService.render("newsletter-confirmation", baseVars(
            "#667eea 0%, #764ba2 100%",
            msg("email.newsletter.confirm.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "bodyText", msg("email.newsletter.confirm.body"),
                "actionText", msg("email.newsletter.confirm.action"),
                "confirmUrl", confirmUrl,
                "buttonText", msg("email.newsletter.confirm.button"),
                "disclaimer", msg("email.newsletter.confirm.disclaimer")
            )
        ));

        return sendHtmlEmail(to, subject, html);
    }

    /**
     * Send welcome email after newsletter confirmation.
     */
    public Mono<Void> sendNewsletterWelcome(String to, String name) {
        String subject = msg("email.newsletter.welcome.subject");
        String displayName = name != null ? name : msg("email.default.subscriber");

        String html = templateService.render("newsletter-welcome", baseVars(
            "#10b981 0%, #059669 100%",
            msg("email.newsletter.welcome.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "bodyText", msg("email.newsletter.welcome.body"),
                "receiveText", msg("email.newsletter.welcome.receive"),
                "item1", msg("email.newsletter.welcome.item1"),
                "item2", msg("email.newsletter.welcome.item2"),
                "item3", msg("email.newsletter.welcome.item3"),
                "visitText", msg("email.newsletter.welcome.visit"),
                "siteUrl", siteUrl
            )
        ));

        return sendHtmlEmail(to, subject, html);
    }

    /**
     * Send new article notification to subscribers.
     */
    public Mono<Void> sendNewArticleNotification(String to, String subscriberName,
            String articleTitle, String articleSlug, String articleExcerpt, String unsubscribeToken) {
        String subject = msg("email.article.notification.subject", articleTitle);
        String articleUrl = siteUrl + "/blog/" + articleSlug;
        String unsubscribeUrl = siteUrl + "/newsletter/unsubscribe?token=" + unsubscribeToken;
        String displayName = subscriberName != null ? subscriberName : msg("email.default.subscriber");

        String html = templateService.render("new-article-notification", baseVars(
            "#3b82f6 0%, #1d4ed8 100%",
            msg("email.article.notification.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "introText", msg("email.article.notification.intro"),
                "articleTitle", articleTitle,
                "articleExcerpt", articleExcerpt != null ? articleExcerpt : "",
                "articleUrl", articleUrl,
                "buttonText", msg("email.article.notification.button")
            ),
            "<p><a href=\"" + unsubscribeUrl + "\" style=\"color: #6b7280;\">"
                + msg("email.article.notification.unsubscribe") + "</a></p>"
        ));

        return sendHtmlEmail(to, subject, html);
    }

    /**
     * Send comment notification to article author.
     */
    public Mono<Void> sendCommentNotification(String authorEmail, String authorName,
            String commenterName, String articleTitle, String articleSlug, String commentContent) {
        String subject = msg("email.comment.notification.subject", articleTitle);
        String articleUrl = siteUrl + "/blog/" + articleSlug;

        String safeCommentContent = escapeHtml(commentContent);
        String safeCommenterName = escapeHtml(commenterName);
        String safeArticleTitle = escapeHtml(articleTitle);

        String html = templateService.render("comment-notification", baseVars(
            "#f59e0b 0%, #d97706 100%",
            msg("email.comment.notification.header"),
            Map.of(
                "greeting", msg("email.greeting", authorName),
                "bodyText", msg("email.comment.notification.body", safeCommenterName, safeArticleTitle),
                "commentContent", safeCommentContent,
                "articleUrl", articleUrl,
                "viewText", msg("email.comment.notification.view")
            )
        ));

        return sendHtmlEmail(authorEmail, subject, html);
    }

    /**
     * Send account lockout notification email.
     */
    public Mono<Void> sendAccountLockoutNotification(String email, String name, int failedAttempts, long lockoutMinutes, String ipAddress) {
        String subject = msg("email.lockout.subject");
        String displayName = name != null ? name : email;
        String displayIp = ipAddress != null ? ipAddress : msg("email.lockout.ip.unknown");

        String html = templateService.render("account-lockout", baseVars(
            "#dc2626 0%, #b91c1c 100%",
            msg("email.lockout.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "alertTitle", msg("email.lockout.alert.title"),
                "alertBody", msg("email.lockout.alert.body", failedAttempts),
                "detailsTitle", msg("email.lockout.details"),
                "failedAttemptsText", msg("email.lockout.failed.attempts", failedAttempts),
                "lockDurationText", msg("email.lockout.temporary.lock", lockoutMinutes),
                "sourceIpText", msg("email.lockout.source.ip", displayIp),
                "ifYouText", msg("email.lockout.if.you"),
                "ifNotYouText", msg("email.lockout.if.not.you"),
                "supportText", msg("email.lockout.support", supportEmail, supportEmail)
            ),
            "<p>" + msg("email.lockout.footer") + "</p>"
        ));

        return sendHtmlEmail(email, subject, html)
                .doOnSuccess(v -> log.debug("Account lockout notification sent to: {}", email))
                .doOnError(e -> log.warn("Failed to send lockout notification to {}: {}", email, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Send password reset email with secure token.
     */
    public Mono<Void> sendPasswordResetEmail(String email, String name, String token) {
        String subject = msg("email.password.reset.subject");
        String resetUrl = siteUrl + "/auth/reset-password?token=" + token;
        String displayName = name != null ? name : msg("email.default.user");

        String html = templateService.render("password-reset", baseVars(
            "#3b82f6 0%, #1d4ed8 100%",
            msg("email.password.reset.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "bodyText", msg("email.password.reset.body"),
                "resetUrl", resetUrl,
                "buttonText", msg("email.password.reset.button"),
                "importantTitle", msg("email.password.reset.important"),
                "expiresText", msg("email.password.reset.expires"),
                "onceText", msg("email.password.reset.once"),
                "ignoreText", msg("email.password.reset.ignore"),
                "fallbackText", msg("email.password.reset.fallback")
            )
        ));

        return sendHtmlEmail(email, subject, html);
    }

    /**
     * Send password changed confirmation email.
     */
    public Mono<Void> sendPasswordChangedNotification(String email, String name) {
        String subject = msg("email.password.changed.subject");
        String displayName = name != null ? name : msg("email.default.user");

        String html = templateService.render("password-changed", baseVars(
            "#10b981 0%, #059669 100%",
            msg("email.password.changed.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "successTitle", msg("email.password.changed.success.title"),
                "successBody", msg("email.password.changed.success.body"),
                "warningTitle", msg("email.password.changed.warning.title"),
                "warningBody", msg("email.password.changed.warning.body"),
                "supportEmail", supportEmail
            )
        ));

        return sendHtmlEmail(email, subject, html);
    }

    /**
     * Send notification when a user is promoted to DEV (or ADMIN) with instructions
     * on how to configure Gmail "Send As" for their new @catananti.dev email.
     */
    public Mono<Void> sendDevPromotionNotification(String to, String name, String newEmail) {
        String displayName = name != null ? name : "Developer";

        String html = templateService.render("dev-promotion", baseVars(
            "#6366f1 0%, #4f46e5 100%",
            "Welcome to the Team!",
            Map.of(
                "displayName", displayName,
                "newEmail", newEmail,
                "currentEmail", to
            )
        ));

        return sendHtmlEmail(to, "Your new @catananti.dev email is ready!", html);
    }

    /**
     * Send notification to an admin when a user requests a role upgrade.
     */
    public Mono<Void> sendRoleUpgradeNotification(String adminEmail, String userName, String userEmail,
                                                   String requestedRole, String reason) {
        String html = templateService.render("role-upgrade-request", baseVars(
            "#f59e0b 0%, #d97706 100%",
            "Role Upgrade Request",
            Map.of(
                "userName", userName,
                "userEmail", userEmail,
                "requestedRole", requestedRole,
                "reason", reason != null && !reason.isBlank() ? reason : "<em>No reason provided</em>"
            )
        ));

        return sendHtmlEmail(adminEmail, "Role Upgrade Request from " + userName, html);
    }

    /**
     * Send welcome email after user registration.
     */
    public Mono<Void> sendRegistrationWelcome(String to, String name) {
        String subject = msg("email.registration.welcome.subject");
        String displayName = name != null ? name : msg("email.default.user");

        String html = templateService.render("registration-welcome", baseVars(
            "#6366f1 0%, #4f46e5 100%",
            msg("email.registration.welcome.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "bodyText", msg("email.registration.welcome.body"),
                "exploreText", msg("email.registration.welcome.explore"),
                "item1", msg("email.registration.welcome.item1"),
                "item2", msg("email.registration.welcome.item2"),
                "item3", msg("email.registration.welcome.item3"),
                "visitText", msg("email.registration.welcome.visit"),
                "siteUrl", siteUrl
            )
        ));

        return sendHtmlEmail(to, subject, html);
    }

    /**
     * Send OTP verification email with a one-time code.
     */
    public Mono<Void> sendOtpVerification(String to, String name, String otpCode, int expiresMinutes) {
        String displayName = name != null ? name : msg("email.default.user");

        String html = templateService.render("otp-verification", baseVars(
            "#8b5cf6 0%, #7c3aed 100%",
            msg("email.otp.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "bodyText", msg("email.otp.body"),
                "otpCode", otpCode,
                "importantTitle", msg("email.otp.important"),
                "expiresText", msg("email.otp.expires", expiresMinutes),
                "neverShareText", msg("email.otp.never.share"),
                "ignoreText", msg("email.otp.ignore")
            )
        ));

        return sendHtmlEmail(to, msg("email.otp.subject"), html);
    }

    /**
     * Send magic link login email.
     */
    public Mono<Void> sendMagicLink(String to, String name, String token, int expiresMinutes) {
        String displayName = name != null ? name : msg("email.default.user");
        String magicLinkUrl = siteUrl + "/auth/magic?token=" + token;

        String html = templateService.render("magic-link", baseVars(
            "#8b5cf6 0%, #6d28d9 100%",
            msg("email.magic.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "bodyText", msg("email.magic.body"),
                "magicLinkUrl", magicLinkUrl,
                "buttonText", msg("email.magic.button"),
                "securityTitle", msg("email.magic.security"),
                "expiresText", msg("email.magic.expires", expiresMinutes),
                "onceText", msg("email.magic.once"),
                "ignoreText", msg("email.magic.ignore"),
                "fallbackText", msg("email.magic.fallback")
            )
        ));

        return sendHtmlEmail(to, msg("email.magic.subject"), html);
    }

    /**
     * Send HTML contact notification to the admin (replaces plain text).
     */
    public Mono<Void> sendContactNotification(String adminEmail, String senderName,
                                               String senderEmail, String subject, String message) {
        String emailSubject = msg("email.contact.notification.subject");
        String safeName = escapeHtml(senderName);
        String safeSubject = escapeHtml(subject);
        String safeMessage = escapeHtml(message);

        String html = templateService.render("contact-notification", baseVars(
            "#0ea5e9 0%, #0284c7 100%",
            msg("email.contact.notification.header"),
            Map.of(
                "greeting", msg("email.greeting", "Admin"),
                "introText", msg("email.contact.notification.intro"),
                "fromLabel", msg("email.contact.notification.from"),
                "senderName", safeName,
                "senderEmail", senderEmail,
                "subjectLabel", msg("email.contact.notification.subjectLabel"),
                "messageSubject", safeSubject,
                "messageContent", safeMessage,
                "footerNote", msg("email.contact.notification.footer")
            )
        ));

        return sendHtmlEmail(adminEmail, emailSubject, html);
    }

    /**
     * Send auto-reply to contact form submitter.
     */
    public Mono<Void> sendContactAutoReply(String to, String name, String subject) {
        String emailSubject = msg("email.contact.autoreply.subject");
        String displayName = name != null ? name : msg("email.default.user");
        String safeSubject = escapeHtml(subject);
        String preview = safeSubject.length() > 100 ? safeSubject.substring(0, 100) + "..." : safeSubject;

        String html = templateService.render("contact-auto-reply", baseVars(
            "#0ea5e9 0%, #0284c7 100%",
            msg("email.contact.autoreply.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "bodyText", msg("email.contact.autoreply.body"),
                "summaryTitle", msg("email.contact.autoreply.summary"),
                "subjectLabel", msg("email.contact.notification.subjectLabel"),
                "messageSubject", safeSubject,
                "messagePreview", preview,
                "responseText", msg("email.contact.autoreply.response"),
                "visitText", msg("email.contact.autoreply.visit"),
                "siteUrl", siteUrl,
                "buttonText", msg("email.contact.autoreply.button")
            )
        ));

        return sendHtmlEmail(to, emailSubject, html);
    }

    /**
     * Send notification to user when their role upgrade request is approved.
     */
    public Mono<Void> sendRoleRequestApproved(String to, String name, String previousRole, String newRole) {
        String subject = msg("email.role.approved.subject");
        String displayName = name != null ? name : msg("email.default.user");

        String html = templateService.render("role-request-approved", baseVars(
            "#10b981 0%, #059669 100%",
            msg("email.role.approved.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "approvedTitle", msg("email.role.approved.title"),
                "approvedBody", msg("email.role.approved.body"),
                "previousRoleLabel", msg("email.role.approved.previousRole"),
                "previousRole", previousRole,
                "newRoleLabel", msg("email.role.approved.newRole"),
                "newRole", newRole,
                "effectiveText", msg("email.role.approved.effective"),
                "siteUrl", siteUrl,
                "buttonText", msg("email.role.approved.button")
            )
        ));

        return sendHtmlEmail(to, subject, html);
    }

    /**
     * Send notification to user when their role upgrade request is rejected.
     */
    public Mono<Void> sendRoleRequestRejected(String to, String name, String requestedRole, String currentRole) {
        String subject = msg("email.role.rejected.subject");
        String displayName = name != null ? name : msg("email.default.user");

        String html = templateService.render("role-request-rejected", baseVars(
            "#f59e0b 0%, #d97706 100%",
            msg("email.role.rejected.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "rejectedTitle", msg("email.role.rejected.title"),
                "rejectedBody", msg("email.role.rejected.body"),
                "requestedRoleLabel", msg("email.role.rejected.requestedRole"),
                "requestedRole", requestedRole,
                "currentRoleLabel", msg("email.role.rejected.currentRole"),
                "currentRole", currentRole,
                "contactText", msg("email.role.rejected.contact"),
                "supportEmail", supportEmail
            )
        ));

        return sendHtmlEmail(to, subject, html);
    }

    /**
     * Send notification when a user account is deactivated.
     */
    public Mono<Void> sendAccountDeactivated(String to, String name) {
        String subject = msg("email.account.deactivated.subject");
        String displayName = name != null ? name : msg("email.default.user");

        String html = templateService.render("account-deactivated", baseVars(
            "#dc2626 0%, #b91c1c 100%",
            msg("email.account.deactivated.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "deactivatedTitle", msg("email.account.deactivated.title"),
                "deactivatedBody", msg("email.account.deactivated.body"),
                "effectText", msg("email.account.deactivated.effect"),
                "effect1", msg("email.account.deactivated.effect1"),
                "effect2", msg("email.account.deactivated.effect2"),
                "effect3", msg("email.account.deactivated.effect3"),
                "contactText", msg("email.account.deactivated.contact"),
                "supportEmail", supportEmail
            )
        ));

        return sendHtmlEmail(to, subject, html);
    }

    /**
     * Send notification when a user account is reactivated.
     */
    public Mono<Void> sendAccountReactivated(String to, String name) {
        String subject = msg("email.account.reactivated.subject");
        String displayName = name != null ? name : msg("email.default.user");
        String loginUrl = siteUrl + "/auth/login";

        String html = templateService.render("account-reactivated", baseVars(
            "#10b981 0%, #059669 100%",
            msg("email.account.reactivated.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "reactivatedTitle", msg("email.account.reactivated.title"),
                "reactivatedBody", msg("email.account.reactivated.body"),
                "accessText", msg("email.account.reactivated.access"),
                "loginUrl", loginUrl,
                "buttonText", msg("email.account.reactivated.button")
            )
        ));

        return sendHtmlEmail(to, subject, html);
    }

    // ── Template helpers ──────────────────────────────────────────────────────

    /** Build the base variable map shared by all templates (gradient, header, footer). */
    private Map<String, Object> baseVars(String gradient, String headerTitle, Map<String, Object> extra) {
        return baseVars(gradient, headerTitle, extra, null);
    }

    private Map<String, Object> baseVars(String gradient, String headerTitle, Map<String, Object> extra, @Nullable String extraFooter) {
        int currentYear = java.time.Year.now().getValue();
        var vars = new HashMap<String, Object>();
        vars.put("gradient", gradient);
        vars.put("headerTitle", headerTitle);
        vars.put("footerCopyright", msg("email.footer.copyright", currentYear));
        if (extraFooter != null) {
            vars.put("extraFooter", extraFooter);
        }
        vars.putAll(extra);
        return vars;
    }

    /**
     * Escape HTML special characters to prevent XSS in email content.
     */
    private String escapeHtml(String input) {
        return HtmlUtils.escapeHtml(input);
    }
}
