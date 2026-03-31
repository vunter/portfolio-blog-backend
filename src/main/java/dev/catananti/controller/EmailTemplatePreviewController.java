package dev.catananti.controller;

import dev.catananti.service.EmailTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Year;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/settings/email-templates")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV')")
@Slf4j
public class EmailTemplatePreviewController {

    private final EmailTemplateService templateService;

    private static final String FOOTER = "© " + Year.now().getValue() + " Catananti Dev. All rights reserved.";
    private static final String SITE = "https://catananti.dev";

    /** Build base variables shared by all templates (gradient, headerTitle, footerCopyright). */
    private static Map<String, Object> baseVars(String gradient, String headerTitle, Map<String, Object> extra) {
        var vars = new HashMap<String, Object>();
        vars.put("gradient", gradient);
        vars.put("headerTitle", headerTitle);
        vars.put("footerCopyright", FOOTER);
        vars.putAll(extra);
        return vars;
    }

    private static Map<String, Object> baseVars(String gradient, String headerTitle, Map<String, Object> extra, String extraFooter) {
        var vars = baseVars(gradient, headerTitle, extra);
        vars.put("extraFooter", extraFooter);
        return vars;
    }

    private static final List<TemplateInfo> TEMPLATES = List.of(
        new TemplateInfo("registration-welcome", "Welcome Email", "Sent after registration",
            baseVars("#6366f1 0%, #4f46e5 100%", "Welcome!", Map.of(
                "greeting", "Hello John Doe,",
                "bodyText", "Welcome to Catananti Dev! Your account has been created successfully.",
                "exploreText", "Here's what you can explore:",
                "item1", "Read technical articles and tutorials",
                "item2", "Comment and interact with the community",
                "item3", "Bookmark your favorite articles",
                "visitText", "Visit our blog:",
                "siteUrl", SITE))),
        new TemplateInfo("password-reset", "Password Reset", "Password reset request",
            baseVars("#3b82f6 0%, #1d4ed8 100%", "Password Reset", Map.of(
                "greeting", "Hello John Doe,",
                "bodyText", "We received a request to reset your password. Click the button below:",
                "resetUrl", SITE + "/auth/reset-password?token=sample-token",
                "buttonText", "Reset Password",
                "importantTitle", "Important:",
                "expiresText", "This link expires in 1 hour.",
                "onceText", "This link can only be used once.",
                "ignoreText", "If you didn't request this, you can safely ignore this email.",
                "fallbackText", "If the button doesn't work, copy and paste this URL:"))),
        new TemplateInfo("password-changed", "Password Changed", "Confirmation of password change",
            baseVars("#10b981 0%, #059669 100%", "Password Changed", Map.of(
                "greeting", "Hello John Doe,",
                "successTitle", "Password Updated Successfully",
                "successBody", "Your password has been changed. If you did not make this change, please contact support immediately.",
                "warningTitle", "Wasn't you?",
                "warningBody", "If you did not change your password, your account may be compromised.",
                "supportEmail", "support@catananti.dev"))),
        new TemplateInfo("email-change-verify", "Email Change Verification", "Verify new email address",
            baseVars("#8b5cf6 0%, #6d28d9 100%", "Verify Email Change", Map.of(
                "greeting", "Hello John Doe,",
                "bodyText", "We received a request to change your email address. Click the button below to verify:",
                "verifyUrl", SITE + "/auth/verify-email-change?token=sample-token",
                "buttonText", "Verify New Email",
                "importantTitle", "Important:",
                "expiresText", "This link expires in 48 hours.",
                "onceText", "This link can only be used once.",
                "ignoreText", "If you didn't request this, you can safely ignore this email.",
                "fallbackText", "If the button doesn't work, copy and paste this URL:"))),
        new TemplateInfo("email-changed-with-revert", "Email Changed (Revert)", "Notification with revert option",
            baseVars("#f59e0b 0%, #d97706 100%", "Email Address Changed", Map.of(
                "greeting", "Hello John Doe,",
                "successTitle", "Email Changed Successfully",
                "successBody", "Your email address has been changed to new@example.com.",
                "warningTitle", "Wasn't you?",
                "warningBody", "If you did not request this change, click below to revert immediately.",
                "revertUrl", SITE + "/auth/revert-email?token=sample-token",
                "revertButtonText", "Revert Email Change",
                "revertExpires", "This revert link expires in 48 hours.",
                "fallbackText", "If the button doesn't work, copy and paste this URL:"))),
        new TemplateInfo("otp-verification", "OTP Verification", "One-time password delivery",
            baseVars("#8b5cf6 0%, #7c3aed 100%", "Verification Code", Map.of(
                "greeting", "Hello John Doe,",
                "bodyText", "Use the following code to verify your identity:",
                "otpCode", "482916",
                "importantTitle", "Important:",
                "expiresText", "This code expires in 5 minutes.",
                "neverShareText", "Never share this code with anyone.",
                "ignoreText", "If you didn't request this, you can safely ignore this email."))),
        new TemplateInfo("magic-link", "Magic Link", "Passwordless login link",
            baseVars("#8b5cf6 0%, #6d28d9 100%", "Magic Link Login", Map.of(
                "greeting", "Hello John Doe,",
                "bodyText", "Click the button below to log in to your account:",
                "magicLinkUrl", SITE + "/auth/magic?token=sample-token",
                "buttonText", "Log In",
                "securityTitle", "Security Notice:",
                "expiresText", "This link expires in 15 minutes.",
                "onceText", "This link can only be used once.",
                "ignoreText", "If you didn't request this, you can safely ignore this email.",
                "fallbackText", "If the button doesn't work, copy and paste this URL:"))),
        new TemplateInfo("comment-notification", "Comment Notification", "New comment on article",
            baseVars("#f59e0b 0%, #d97706 100%", "New Comment", Map.of(
                "greeting", "Hello Leonardo,",
                "bodyText", "Jane Smith commented on your article \"Building Reactive Microservices\":",
                "commentContent", "Great article! Really enjoyed reading about WebFlux and R2DBC.",
                "articleUrl", SITE + "/blog/building-reactive-microservices",
                "viewText", "View Comment"))),
        new TemplateInfo("newsletter-confirmation", "Newsletter Confirmation", "Confirm subscription",
            baseVars("#667eea 0%, #764ba2 100%", "Confirm Subscription", Map.of(
                "greeting", "Hello John Doe,",
                "bodyText", "Thank you for subscribing to our newsletter! Please confirm your subscription:",
                "actionText", "Click the button below to confirm:",
                "confirmUrl", SITE + "/newsletter/confirm?token=sample-token",
                "buttonText", "Confirm Subscription",
                "disclaimer", "If you didn't subscribe, you can safely ignore this email."))),
        new TemplateInfo("newsletter-welcome", "Newsletter Welcome", "After subscription confirmed",
            baseVars("#10b981 0%, #059669 100%", "Welcome to the Newsletter!", Map.of(
                "greeting", "Hello John Doe,",
                "bodyText", "Your subscription has been confirmed! Welcome to our community.",
                "receiveText", "You will now receive:",
                "item1", "Latest articles and tutorials",
                "item2", "Exclusive content and insights",
                "item3", "Community updates and announcements",
                "visitText", "Visit our blog:",
                "siteUrl", SITE))),
        new TemplateInfo("new-article-notification", "New Article", "Published article notification",
            baseVars("#3b82f6 0%, #1d4ed8 100%", "New Article Published", Map.of(
                "greeting", "Hello John Doe,",
                "introText", "A new article has been published:",
                "articleTitle", "Building Reactive Microservices with Spring WebFlux",
                "articleExcerpt", "Learn how to build high-performance reactive microservices using Spring WebFlux, R2DBC, and Kotlin coroutines...",
                "articleUrl", SITE + "/blog/building-reactive-microservices",
                "buttonText", "Read Article"),
                "<p><a href=\"" + SITE + "/newsletter/unsubscribe?token=sample\" style=\"color: #6b7280;\">Unsubscribe from notifications</a></p>")),
        new TemplateInfo("contact-notification", "Contact Form (Admin)", "Admin receives contact message",
            baseVars("#0ea5e9 0%, #0284c7 100%", "New Contact Message", Map.of(
                "greeting", "Hello Admin,",
                "introText", "You have received a new message from the contact form:",
                "fromLabel", "From:",
                "senderName", "Jane Smith",
                "senderEmail", "jane@example.com",
                "subjectLabel", "Subject:",
                "messageSubject", "Collaboration Inquiry",
                "messageContent", "Hi, I'd like to discuss a potential project collaboration. I was impressed by your work on reactive microservices.",
                "footerNote", "This message was sent via the contact form on catananti.dev."))),
        new TemplateInfo("contact-auto-reply", "Contact Auto-Reply", "Auto-reply to contact sender",
            baseVars("#0ea5e9 0%, #0284c7 100%", "Message Received", Map.of(
                "greeting", "Hello Jane Smith,",
                "bodyText", "Thank you for reaching out! I've received your message and will get back to you soon.",
                "summaryTitle", "Your message summary:",
                "subjectLabel", "Subject:",
                "messageSubject", "Collaboration Inquiry",
                "messagePreview", "Hi, I'd like to discuss a potential project collaboration...",
                "responseText", "I typically respond within 24-48 hours.",
                "visitText", "In the meantime, feel free to explore the blog:",
                "siteUrl", SITE,
                "buttonText", "Visit Blog"))),
        new TemplateInfo("account-lockout", "Account Lockout", "Failed login attempts alert",
            baseVars("#dc2626 0%, #b91c1c 100%", "Account Locked", Map.of(
                "greeting", "Hello John Doe,",
                "alertTitle", "Security Alert",
                "alertBody", "Your account has been temporarily locked due to 5 failed login attempts.",
                "detailsTitle", "Details:",
                "failedAttemptsText", "Failed attempts: 5",
                "lockDurationText", "Lock duration: 30 minutes",
                "sourceIpText", "Source IP: 192.168.1.100",
                "ifYouText", "If this was you, wait for the lockout period to expire and try again.",
                "ifNotYouText", "If this wasn't you, consider changing your password immediately.",
                "supportText", "Need help? Contact <a href=\"mailto:support@catananti.dev\">support@catananti.dev</a>."),
                "<p>This is an automated security notification.</p>")),
        new TemplateInfo("account-deactivated", "Account Deactivated", "Account deactivation notice",
            baseVars("#dc2626 0%, #b91c1c 100%", "Account Deactivated", Map.of(
                "greeting", "Hello John Doe,",
                "deactivatedTitle", "Account Deactivated",
                "deactivatedBody", "Your account has been deactivated.",
                "effectText", "This means:",
                "effect1", "You can no longer log in",
                "effect2", "Your comments will be hidden",
                "effect3", "Your bookmarks will be preserved",
                "contactText", "If you believe this was a mistake, contact support:",
                "supportEmail", "support@catananti.dev"))),
        new TemplateInfo("account-reactivated", "Account Reactivated", "Account reactivation notice",
            baseVars("#10b981 0%, #059669 100%", "Account Reactivated", Map.of(
                "greeting", "Hello John Doe,",
                "reactivatedTitle", "Account Reactivated",
                "reactivatedBody", "Your account has been reactivated. You can now log in and access all features.",
                "accessText", "Log in to your account:",
                "loginUrl", SITE + "/auth/login",
                "buttonText", "Log In"))),
        new TemplateInfo("role-upgrade-request", "Role Upgrade Request", "Admin notification of role request",
            baseVars("#f59e0b 0%, #d97706 100%", "Role Upgrade Request", Map.of(
                "userName", "Jane Smith",
                "userEmail", "jane@example.com",
                "requestedRole", "DEV",
                "reason", "I've been contributing articles and would like to help manage content."))),
        new TemplateInfo("role-request-approved", "Role Request Approved", "Role upgrade approved",
            baseVars("#10b981 0%, #059669 100%", "Role Upgrade Approved", Map.of(
                "greeting", "Hello Jane Smith,",
                "approvedTitle", "Role Upgraded!",
                "approvedBody", "Your role upgrade request has been approved.",
                "previousRoleLabel", "Previous role:",
                "previousRole", "VIEWER",
                "newRoleLabel", "New role:",
                "newRole", "DEV",
                "effectiveText", "The new permissions are effective immediately.",
                "siteUrl", SITE,
                "buttonText", "Go to Dashboard"))),
        new TemplateInfo("role-request-rejected", "Role Request Rejected", "Role upgrade rejected",
            baseVars("#f59e0b 0%, #d97706 100%", "Role Request Update", Map.of(
                "greeting", "Hello Jane Smith,",
                "rejectedTitle", "Role Request Not Approved",
                "rejectedBody", "Your role upgrade request was not approved at this time.",
                "requestedRoleLabel", "Requested role:",
                "requestedRole", "DEV",
                "currentRoleLabel", "Current role:",
                "currentRole", "VIEWER",
                "contactText", "For questions, contact:",
                "supportEmail", "support@catananti.dev")))
    );

    record TemplateInfo(String id, String name, String description, Map<String, Object> sampleData) {}

    @GetMapping
    public Mono<List<Map<String, Object>>> listTemplates() {
        return Mono.fromCallable(() -> TEMPLATES.stream().map(t -> t.id()).collect(Collectors.toList()))
            .flatMap(ids -> {
                // Check which templates have DB overrides
                return templateService.getOverriddenTemplateIds()
                    .collectList()
                    .map(overriddenIds -> TEMPLATES.stream().map(t -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("id", t.id());
                        map.put("name", t.name());
                        map.put("description", t.description());
                        map.put("customized", overriddenIds.contains(t.id()));
                        return map;
                    }).collect(Collectors.toList()));
            });
    }

    @GetMapping(value = "/{templateId}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<String>> previewTemplate(@PathVariable String templateId) {
        TemplateInfo template = findTemplate(templateId);
        return templateService.ensureCacheLoaded().then(
            templateService.getTemplateSource(templateId)
                .map(source -> {
                    String rendered;
                    if (source.isOverride()) {
                        rendered = templateService.renderFromString(source.html(), template.sampleData(), templateId);
                    } else {
                        rendered = templateService.render(template.id(), template.sampleData());
                    }
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data: https:;")
                            .header("X-Content-Type-Options", "nosniff")
                            .body(rendered);
                }));
    }

    /** Get raw HTML source (DB override or classpath file) + placeholders + custom vars. */
    @GetMapping(value = "/{templateId}/source", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> getTemplateSource(@PathVariable String templateId) {
        TemplateInfo template = findTemplate(templateId);
        return templateService.ensureCacheLoaded().then(
            templateService.getTemplateSource(templateId)
                .flatMap(source -> {
                    // Fetch custom vars for this template to include in response
                    var globalVars = templateService.listCustomVariables("__global__").collectList();
                    var templateVars = templateService.listCustomVariables(templateId).collectList();
                    return Mono.zip(globalVars, templateVars).map(tuple -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("templateId", templateId);
                        result.put("html", source.html());
                        result.put("isOverride", source.isOverride());
                        result.put("placeholders", template.sampleData());
                        result.put("customVariables", Map.of(
                            "global", tuple.getT1().stream().map(this::customVarToMap).toList(),
                            "template", tuple.getT2().stream().map(this::customVarToMap).toList()
                        ));
                        return result;
                    });
                }));
    }

    /** Save template override (custom HTML). Strips script tags before persisting. */
    @PutMapping("/{templateId}")
    public Mono<Map<String, String>> updateTemplate(
            @PathVariable String templateId,
            @RequestBody Map<String, String> body,
            org.springframework.security.core.Authentication auth) {
        findTemplate(templateId);
        String html = body.get("html");
        if (html == null || html.isBlank()) {
            return Mono.error(new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "HTML content is required"));
        }
        // Sanitize: strip script/iframe/object tags before persisting
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        doc.select("script, iframe, object, embed, applet").remove();
        String sanitizedHtml = doc.html();
        String user = auth != null ? auth.getName() : "admin";
        return templateService.saveOverride(templateId, sanitizedHtml, user)
            .thenReturn(Map.of("message", "Template saved", "templateId", templateId));
    }

    /** Delete template override (revert to default). */
    @DeleteMapping("/{templateId}")
    public Mono<Map<String, String>> deleteTemplate(@PathVariable String templateId) {
        findTemplate(templateId);
        return templateService.deleteOverride(templateId)
            .map(deleted -> deleted
                ? Map.of("message", "Template reverted to default", "templateId", templateId)
                : Map.of("message", "No override found", "templateId", templateId));
    }

    /** Preview custom HTML with sample data (without saving). */
    @PostMapping(value = "/{templateId}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<String>> previewCustomHtml(@PathVariable String templateId, @RequestBody Map<String, String> body) {
        TemplateInfo template = findTemplate(templateId);
        String html = body.get("html");
        if (html == null || html.isBlank()) {
            return Mono.error(new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "HTML content is required"));
        }
        return templateService.ensureCacheLoaded().then(
            Mono.fromCallable(() -> templateService.renderFromString(html, template.sampleData(), templateId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(rendered -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                        .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data: https:;")
                        .header("X-Content-Type-Options", "nosniff")
                        .body(rendered)));
    }

    // ==================== Custom Variables CRUD ====================

    /** List custom variables. Optional ?templateId= filter. */
    @GetMapping("/custom-variables")
    public Mono<Map<String, Object>> listCustomVariables(@RequestParam(required = false) String templateId) {
        return templateService.listCustomVariables(templateId)
            .map(this::customVarToMap)
            .collectList()
            .map(vars -> Map.of("variables", vars));
    }

    /** Create or update a custom variable. */
    @PostMapping("/custom-variables")
    public Mono<Map<String, Object>> createCustomVariable(@RequestBody Map<String, String> body) {
        String key = body.get("key");
        String value = body.get("value");
        if (key == null || key.isBlank() || value == null) {
            return Mono.error(new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "key and value are required"));
        }
        if (!key.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
            return Mono.error(new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Key must be a valid variable name (letters, digits, underscores)"));
        }
        return templateService.saveCustomVariable(key, value, body.get("description"), body.get("templateId"), body.get("locale"))
            .map(v -> customVarToMap(v));
    }

    /** Update a custom variable by ID. */
    @PutMapping("/custom-variables/{id}")
    public Mono<Map<String, Object>> updateCustomVariable(@PathVariable long id, @RequestBody Map<String, String> body) {
        String value = body.get("value");
        if (value == null) {
            return Mono.error(new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "value is required"));
        }
        return templateService.updateCustomVariable(id, value, body.get("description"))
            .map(this::customVarToMap)
            .switchIfEmpty(Mono.error(new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Variable not found")));
    }

    /** Delete a custom variable by ID. */
    @DeleteMapping("/custom-variables/{id}")
    public Mono<Map<String, String>> deleteCustomVariable(@PathVariable long id) {
        return templateService.deleteCustomVariable(id)
            .map(deleted -> deleted
                ? Map.of("message", "Variable deleted")
                : Map.of("message", "Variable not found"));
    }

    private Map<String, Object> customVarToMap(EmailTemplateService.CustomVar v) {
        return Map.of(
            "id", v.id(),
            "key", v.key(),
            "value", v.value(),
            "description", v.description() != null ? v.description() : "",
            "templateId", v.templateId(),
            "locale", v.locale()
        );
    }

    private TemplateInfo findTemplate(String templateId) {
        return TEMPLATES.stream()
            .filter(t -> t.id().equals(templateId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Template not found: " + templateId));
    }
}
