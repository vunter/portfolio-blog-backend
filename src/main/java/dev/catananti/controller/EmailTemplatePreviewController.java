package dev.catananti.controller;

import dev.catananti.service.EmailTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/settings/email-templates")
@RequiredArgsConstructor
@Slf4j
public class EmailTemplatePreviewController {

    private final EmailTemplateService templateService;

    private static final List<TemplateInfo> TEMPLATES = List.of(
        new TemplateInfo("registration-welcome", "Welcome Email", "Sent after registration",
            Map.of("userName", "John Doe", "loginUrl", "https://catananti.dev/auth/login")),
        new TemplateInfo("password-reset", "Password Reset", "Password reset request",
            Map.of("userName", "John Doe", "resetUrl", "https://catananti.dev/auth/reset?token=sample-token", "expirationHours", "1")),
        new TemplateInfo("password-changed", "Password Changed", "Confirmation of password change",
            Map.of("userName", "John Doe")),
        new TemplateInfo("email-change-verify", "Email Change Verification", "Verify new email address",
            Map.of("userName", "John Doe", "newEmail", "new@example.com", "verifyUrl", "https://catananti.dev/auth/verify?token=sample")),
        new TemplateInfo("email-changed-with-revert", "Email Changed (Revert)", "Notification with revert option",
            Map.of("userName", "John Doe", "oldEmail", "old@example.com", "newEmail", "new@example.com", "revertUrl", "https://catananti.dev/auth/revert?token=sample", "revertHours", "48")),
        new TemplateInfo("otp-verification", "OTP Verification", "One-time password delivery",
            Map.of("userName", "John Doe", "otpCode", "123456", "expirationMinutes", "5")),
        new TemplateInfo("magic-link", "Magic Link", "Passwordless login link",
            Map.of("userName", "John Doe", "magicUrl", "https://catananti.dev/auth/magic?token=sample", "expirationMinutes", "15")),
        new TemplateInfo("comment-notification", "Comment Notification", "New comment on article",
            Map.of("authorName", "John Doe", "commentAuthor", "Jane Smith", "articleTitle", "My First Blog Post", "commentContent", "Great article! Really enjoyed reading this.", "articleUrl", "https://catananti.dev/blog/my-first-post")),
        new TemplateInfo("newsletter-confirmation", "Newsletter Confirmation", "Confirm subscription",
            Map.of("confirmUrl", "https://catananti.dev/newsletter/confirm?token=sample")),
        new TemplateInfo("newsletter-welcome", "Newsletter Welcome", "After subscription confirmed",
            Map.of("unsubscribeUrl", "https://catananti.dev/newsletter/unsubscribe?token=sample")),
        new TemplateInfo("new-article-notification", "New Article", "Published article notification",
            Map.of("articleTitle", "My Latest Blog Post", "articleExcerpt", "This is a preview of the article content...", "articleUrl", "https://catananti.dev/blog/latest-post", "unsubscribeUrl", "https://catananti.dev/newsletter/unsubscribe")),
        new TemplateInfo("contact-notification", "Contact Form (Admin)", "Admin receives contact message",
            Map.of("senderName", "Jane Smith", "senderEmail", "jane@example.com", "subject", "Collaboration Inquiry", "messageContent", "Hi, I'd like to discuss a potential project collaboration.")),
        new TemplateInfo("contact-auto-reply", "Contact Auto-Reply", "Auto-reply to contact sender",
            Map.of("senderName", "Jane Smith")),
        new TemplateInfo("account-lockout", "Account Lockout", "Failed login attempts alert",
            Map.of("userName", "John Doe", "ipAddress", "192.168.1.100", "attemptCount", "5")),
        new TemplateInfo("account-deactivated", "Account Deactivated", "Account deactivation notice",
            Map.of("userName", "John Doe")),
        new TemplateInfo("account-reactivated", "Account Reactivated", "Account reactivation notice",
            Map.of("userName", "John Doe", "loginUrl", "https://catananti.dev/auth/login")),
        new TemplateInfo("role-upgrade-request", "Role Upgrade Request", "Admin notification of role request",
            Map.of("userName", "Jane Smith", "userEmail", "jane@example.com", "requestedRole", "EDITOR", "currentRole", "VIEWER", "adminUrl", "https://catananti.dev/admin/users")),
        new TemplateInfo("role-request-approved", "Role Request Approved", "Role upgrade approved",
            Map.of("userName", "Jane Smith", "newRole", "EDITOR")),
        new TemplateInfo("role-request-rejected", "Role Request Rejected", "Role upgrade rejected",
            Map.of("userName", "Jane Smith", "requestedRole", "EDITOR", "reason", "Insufficient contribution history"))
    );

    record TemplateInfo(String id, String name, String description, Map<String, Object> sampleData) {}

    @GetMapping
    public Mono<List<Map<String, Object>>> listTemplates() {
        return Mono.just(TEMPLATES.stream().map(t -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", t.id());
            map.put("name", t.name());
            map.put("description", t.description());
            return map;
        }).collect(Collectors.toList()));
    }

    @GetMapping(value = "/{templateId}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<String> previewTemplate(@PathVariable String templateId) {
        return Mono.fromCallable(() -> {
            TemplateInfo template = TEMPLATES.stream()
                .filter(t -> t.id().equals(templateId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Template not found: " + templateId));

            return templateService.render(templateId, template.sampleData());
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
