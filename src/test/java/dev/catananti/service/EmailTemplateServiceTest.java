package dev.catananti.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for EmailTemplateService.
 * <p>
 * Uses the REAL Thymeleaf engine (no mocks) to verify that every email template
 * renders without errors. This catches Thymeleaf syntax issues, missing fragment
 * definitions, and broken layout includes at test time rather than at runtime.
 * </p>
 */
class EmailTemplateServiceTest {

 private static EmailTemplateService service;

 @BeforeAll
 static void setUp() {
 service = new EmailTemplateService();
 }

 // ── Shared variable builders ──────────────────────────────────────────────

 private static Map<String, Object> baseVars(String gradient, String headerTitle) {
 return baseVars(gradient, headerTitle, null);
 }

 private static Map<String, Object> baseVars(String gradient, String headerTitle, String extraFooter) {
 var vars = new HashMap<String, Object>();
 vars.put("gradient", gradient);
 vars.put("headerTitle", headerTitle);
 vars.put("footerCopyright", "© 2026 Portfolio Blog. All rights reserved.");
 if (extraFooter != null) {
 vars.put("extraFooter", extraFooter);
 }
 return vars;
 }

 private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> extra) {
 var merged = new HashMap<>(base);
 merged.putAll(extra);
 return merged;
 }

 // ── Parameterised test for ALL 18 templates ──────────────────────────────

 static Stream<Arguments> allTemplates() {
 return Stream.of(
 Arguments.of("contact-notification", merge(
 baseVars("#0ea5e9 0%, #0284c7 100%", "New Contact Message"),
 Map.of(
 "greeting", "Hello Admin,",
 "introText", "A new contact message has been received:",
 "fromLabel", "From",
 "senderName", "John Doe",
 "senderEmail", "john@example.com",
 "subjectLabel", "Subject",
 "messageSubject", "Test Contact",
 "messageContent", "Hello, this is a test message.",
 "footerNote", "Sent via Portfolio Contact Form."
 )
 )),
 Arguments.of("contact-auto-reply", merge(
 baseVars("#0ea5e9 0%, #0284c7 100%", "Message Received"),
 Map.of(
 "greeting", "Hello John,",
 "bodyText", "Thank you for your message.",
 "summaryTitle", "Your Message Summary",
 "subjectLabel", "Subject",
 "messageSubject", "Test Subject",
 "messagePreview", "Preview text...",
 "responseText", "We will respond within 48 hours.",
 "visitText", "Visit our site",
 "siteUrl", "http://localhost",
 "buttonText", "Visit Site"
 )
 )),
 Arguments.of("password-reset", merge(
 baseVars("#3b82f6 0%, #1d4ed8 100%", "Password Reset"),
 Map.of(
 "greeting", "Hello User,",
 "bodyText", "You requested a password reset.",
 "resetUrl", "http://localhost/reset?token=abc",
 "buttonText", "Reset Password",
 "importantTitle", "Important",
 "expiresText", "This link expires in 30 minutes.",
 "onceText", "Can only be used once.",
 "ignoreText", "If you didn't request this, ignore this email.",
 "fallbackText", "Or copy this link:"
 )
 )),
 Arguments.of("password-changed", merge(
 baseVars("#10b981 0%, #059669 100%", "Password Changed"),
 Map.of(
 "greeting", "Hello User,",
 "successTitle", "Success!",
 "successBody", "Your password has been changed.",
 "warningTitle", "Wasn't you?",
 "warningBody", "Contact support immediately.",
 "supportEmail", "support@test.com"
 )
 )),
 Arguments.of("registration-welcome", merge(
 baseVars("#6366f1 0%, #4f46e5 100%", "Welcome!"),
 Map.of(
 "greeting", "Hello John,",
 "bodyText", "Welcome to Portfolio Blog!",
 "exploreText", "Here's what you can do:",
 "item1", "Read articles",
 "item2", "Leave comments",
 "item3", "Subscribe to newsletter",
 "visitText", "Visit our blog",
 "siteUrl", "http://localhost"
 )
 )),
 Arguments.of("otp-verification", merge(
 baseVars("#8b5cf6 0%, #7c3aed 100%", "OTP Verification"),
 Map.of(
 "greeting", "Hello User,",
 "bodyText", "Use this code to verify your identity:",
 "otpCode", "123456",
 "importantTitle", "Important",
 "expiresText", "Code expires in 5 minutes.",
 "neverShareText", "Never share this code.",
 "ignoreText", "If you didn't request this, ignore."
 )
 )),
 Arguments.of("magic-link", merge(
 baseVars("#8b5cf6 0%, #6d28d9 100%", "Magic Link Login"),
 Map.of(
 "greeting", "Hello User,",
 "bodyText", "Click the button to sign in.",
 "magicLinkUrl", "http://localhost/magic?token=abc",
 "buttonText", "Sign In",
 "securityTitle", "Security Notice",
 "expiresText", "Link expires in 15 minutes.",
 "onceText", "Can only be used once.",
 "ignoreText", "If you didn't request this, ignore.",
 "fallbackText", "Or copy this link:"
 )
 )),
 Arguments.of("newsletter-confirmation", merge(
 baseVars("#667eea 0%, #764ba2 100%", "Confirm Subscription"),
 Map.of(
 "greeting", "Hello Subscriber,",
 "bodyText", "Please confirm your subscription.",
 "actionText", "Click below to confirm:",
 "confirmUrl", "http://localhost/confirm?token=abc",
 "buttonText", "Confirm",
 "disclaimer", "If you didn't subscribe, ignore this."
 )
 )),
 Arguments.of("newsletter-welcome", merge(
 baseVars("#10b981 0%, #059669 100%", "Welcome, Subscriber!"),
 Map.of(
 "greeting", "Hello Subscriber,",
 "bodyText", "You're now subscribed!",
 "receiveText", "You'll receive:",
 "item1", "New articles",
 "item2", "Weekly digest",
 "item3", "Exclusive content",
 "visitText", "Visit our blog",
 "siteUrl", "http://localhost"
 )
 )),
 Arguments.of("new-article-notification", merge(
 baseVars("#3b82f6 0%, #1d4ed8 100%", "New Article", "<p><a href=\"#\">Unsubscribe</a></p>"),
 Map.of(
 "greeting", "Hello Subscriber,",
 "introText", "A new article has been published:",
 "articleTitle", "Test Article",
 "articleExcerpt", "This is a test excerpt.",
 "articleUrl", "http://localhost/blog/test",
 "buttonText", "Read Article"
 )
 )),
 Arguments.of("comment-notification", merge(
 baseVars("#f59e0b 0%, #d97706 100%", "New Comment"),
 Map.of(
 "greeting", "Hello Author,",
 "bodyText", "Someone commented on your article.",
 "commentContent", "Great article!",
 "articleUrl", "http://localhost/blog/test",
 "viewText", "View Comment"
 )
 )),
 Arguments.of("account-lockout", merge(
 baseVars("#dc2626 0%, #b91c1c 100%", "Account Locked", "<p>Automated security notification.</p>"),
 Map.of(
 "greeting", "Hello User,",
 "alertTitle", "Security Alert",
 "alertBody", "Multiple failed login attempts detected.",
 "detailsTitle", "Details",
 "failedAttemptsText", "5 failed attempts",
 "lockDurationText", "Locked for 30 minutes",
 "sourceIpText", "IP: 192.168.1.1",
 "ifYouText", "If this was you, wait 30 minutes.",
 "ifNotYouText", "If not, change your password.",
 "supportText", "Contact support@test.com"
 )
 )),
 Arguments.of("dev-promotion", merge(
 baseVars("#6366f1 0%, #4f46e5 100%", "Welcome to the Team!"),
 Map.of(
 "displayName", "John Developer",
 "newEmail", "john@catananti.dev",
 "currentEmail", "john@gmail.com"
 )
 )),
 Arguments.of("role-upgrade-request", merge(
 baseVars("#f59e0b 0%, #d97706 100%", "Role Upgrade Request"),
 Map.of(
 "userName", "Jane User",
 "userEmail", "jane@example.com",
 "requestedRole", "DEV",
 "reason", "I want to contribute articles."
 )
 )),
 Arguments.of("role-request-approved", merge(
 baseVars("#10b981 0%, #059669 100%", "Role Request Approved"),
 Map.of(
 "greeting", "Hello Jane,",
 "approvedTitle", "Request Approved!",
 "approvedBody", "Your role upgrade request has been approved.",
 "previousRoleLabel", "Previous Role",
 "previousRole", "USER",
 "newRoleLabel", "New Role",
 "newRole", "DEV",
 "effectiveText", "Changes are effective immediately.",
 "siteUrl", "http://localhost",
 "buttonText", "Go to Dashboard"
 )
 )),
 Arguments.of("role-request-rejected", merge(
 baseVars("#f59e0b 0%, #d97706 100%", "Role Request Update"),
 Map.of(
 "greeting", "Hello Jane,",
 "rejectedTitle", "Request Not Approved",
 "rejectedBody", "Your role upgrade request was not approved at this time.",
 "requestedRoleLabel", "Requested Role",
 "requestedRole", "ADMIN",
 "currentRoleLabel", "Current Role",
 "currentRole", "USER",
 "contactText", "Contact support for more info.",
 "supportEmail", "support@test.com"
 )
 )),
 Arguments.of("account-deactivated", merge(
 baseVars("#dc2626 0%, #b91c1c 100%", "Account Deactivated"),
 Map.of(
 "greeting", "Hello User,",
 "deactivatedTitle", "Account Deactivated",
 "deactivatedBody", "Your account has been deactivated.",
 "effectText", "This means:",
 "effect1", "Cannot log in",
 "effect2", "Cannot post comments",
 "effect3", "Content preserved",
 "contactText", "Contact support to reactivate.",
 "supportEmail", "support@test.com"
 )
 )),
 Arguments.of("account-reactivated", merge(
 baseVars("#10b981 0%, #059669 100%", "Account Reactivated"),
 Map.of(
 "greeting", "Hello User,",
 "reactivatedTitle", "Welcome Back!",
 "reactivatedBody", "Your account has been reactivated.",
 "accessText", "You can now log in again.",
 "loginUrl", "http://localhost/auth/login",
 "buttonText", "Log In"
 )
 ))
 );
 }

 @ParameterizedTest(name = "Template: {0}")
 @MethodSource("allTemplates")
 @DisplayName("All email templates render successfully with valid variables")
 void allTemplatesRenderSuccessfully(String templateName, Map<String, Object> vars) {
 String html = service.render(templateName, vars);

 assertNotNull(html, templateName + " rendered null");
 assertFalse(html.isBlank(), templateName + " rendered blank");
 // Verify layout was included (header structure + footer copyright text)
 assertTrue(html.contains("<html"), templateName + " missing <html> — layout not applied");
 assertTrue(html.contains("All rights reserved"), templateName + " missing footer copyright — layout not applied");
 assertTrue(html.contains("class=\"content\""), templateName + " missing content div — layout not applied");
 }

 @Test
 @DisplayName("Layout template produces complete HTML structure")
 void layoutProducesCompleteStructure() {
 String html = service.render("contact-notification", merge(
 baseVars("#0ea5e9 0%, #0284c7 100%", "Test Header"),
 Map.of(
 "greeting", "Hello,",
 "introText", "Intro",
 "fromLabel", "From",
 "senderName", "Test",
 "senderEmail", "test@test.com",
 "subjectLabel", "Subject",
 "messageSubject", "Test",
 "messageContent", "Message body",
 "footerNote", "Footer note"
 )
 ));

 // Structure checks
 assertTrue(html.contains("<html"), "Missing <html> tag");
 assertTrue(html.contains("</html>"), "Missing closing </html>");
 assertTrue(html.contains("Test Header"), "Header title not rendered");
 assertTrue(html.contains("Message body"), "Content not inserted into layout");
 }

 @Test
 @DisplayName("Each template includes its specific icon emoji")
 void templatesIncludeCorrectIcons() {
 // Spot-check a few templates for their unique icons
 Map<String, String> iconMap = Map.of(
 "password-reset", "",
 "account-lockout", "",
 "otp-verification", "",
 "newsletter-welcome", "",
 "magic-link", ""
 );

 for (var entry : iconMap.entrySet()) {
 String templateName = entry.getKey();
 // Find matching args from allTemplates
 var args = allTemplates()
 .filter(a -> a.get()[0].equals(templateName))
 .findFirst()
 .orElseThrow();
 @SuppressWarnings("unchecked")
 Map<String, Object> vars = (Map<String, Object>) args.get()[1];

 String html = service.render(templateName, vars);
 assertTrue(html.contains(entry.getValue()),
 templateName + " should contain icon " + entry.getValue());
 }
 }
}
