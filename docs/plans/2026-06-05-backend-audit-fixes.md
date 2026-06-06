# Backend Audit Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix audited backend defects (scheduled jobs not executing, public auth blocking, logout token handling, export limit errors, upload size validation, newsletter unsubscribe abuse) in `portfolio-blog-backend`.

**Architecture:** Keep reactive business logic in `Mono` methods and add `void @Scheduled` wrappers that subscribe. Treat invalid JWTs as anonymous and let `SecurityConfig` enforce protected routes. Replace generic runtime exceptions with `ResponseStatusException` using i18n keys. Move unsubscribe-by-email to a confirmation-token flow.

**Tech Stack:** Java 25, Spring Boot 4.1 (WebFlux), Reactor, R2DBC, JUnit 5, Mockito.

---

### Task 1: Subscribe reactive scheduled jobs

**Files:**
- Modify: `src/main/java/dev/catananti/service/AnalyticsService.java`
- Modify: `src/main/java/dev/catananti/service/RefreshTokenService.java`
- Modify: `src/main/java/dev/catananti/service/PasswordResetService.java`
- Modify: `src/main/java/dev/catananti/scheduler/ArticlePublishScheduler.java`
- Modify: `src/main/java/dev/catananti/metrics/BlogMetrics.java`
- Modify: `src/main/java/dev/catananti/scheduler/AuditLogCleanupScheduler.java`
- Modify: `src/main/java/dev/catananti/service/CacheWarmingService.java`
- Modify: `src/main/java/dev/catananti/service/NewsletterService.java`
- Test: `src/test/java/dev/catananti/metrics/BlogMetricsTest.java`

**Step 1: Write the failing test**

Add a new test to ensure the scheduled wrapper exists and calls the reactive method:

```java
@Test
@DisplayName("updateMetricsScheduled should call updateMetrics()")
void shouldInvokeScheduledWrapper() {
    BlogMetrics spy = spy(blogMetrics);
    doReturn(Mono.empty()).when(spy).updateMetrics();

    spy.updateMetricsScheduled();

    verify(spy).updateMetrics();
}
```

**Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=BlogMetricsTest#UpdateMetrics.shouldInvokeScheduledWrapper test`  
Expected: FAIL (method `updateMetricsScheduled` does not exist).

**Step 3: Write minimal implementation**

Add `void @Scheduled` wrappers and remove `@Scheduled` from the reactive methods:

```java
// AnalyticsService.java
@Scheduled(fixedRateString = "${scheduling.analytics-cleanup-ms:86400000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
public void cleanupOldEventsScheduled() {
    cleanupOldEvents().subscribe();
}
// Remove @Scheduled from cleanupOldEvents()
public Mono<Void> cleanupOldEvents() { ... }
```

```java
// RefreshTokenService.java
@Scheduled(fixedRateString = "${scheduling.refresh-token-cleanup-ms:3600000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
public void cleanupExpiredTokensScheduled() {
    cleanupExpiredTokens().subscribe();
}
// Remove @Scheduled from cleanupExpiredTokens()
public Mono<Void> cleanupExpiredTokens() { ... }
```

```java
// PasswordResetService.java
@Scheduled(fixedRateString = "${scheduling.password-reset-cleanup-ms:21600000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
public void cleanupExpiredTokensScheduled() {
    cleanupExpiredTokens().subscribe();
}
// Remove @Scheduled from cleanupExpiredTokens()
public Mono<Void> cleanupExpiredTokens() { ... }
```

```java
// ArticlePublishScheduler.java
@Scheduled(fixedRateString = "${app.scheduler.article-publish-rate:60000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
public void publishScheduledArticlesScheduled() {
    publishScheduledArticles().subscribe();
}
// Remove @Scheduled from publishScheduledArticles()
public Mono<Void> publishScheduledArticles() { ... }
```

```java
// BlogMetrics.java
@Scheduled(fixedRateString = "${scheduling.metrics-update-ms:60000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
public void updateMetricsScheduled() {
    updateMetrics().subscribe();
}
// Remove @Scheduled from updateMetrics()
public Mono<Void> updateMetrics() { ... }
```

```java
// AuditLogCleanupScheduler.java
@Scheduled(cron = "${app.audit.cleanup-cron:0 0 2 * * *}")
public void cleanupOldAuditLogsScheduled() {
    cleanupOldAuditLogs().subscribe();
}
// Remove @Scheduled from cleanupOldAuditLogs()
public Mono<Void> cleanupOldAuditLogs() { ... }
```

```java
// CacheWarmingService.java
@Scheduled(fixedRateString = "${cache.warming.refresh-rate-ms:300000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
public void refreshPopularContentScheduled() {
    refreshPopularContent().subscribe();
}
// Remove @Scheduled from refreshPopularContent()
public Mono<Void> refreshPopularContent() { ... }
```

```java
// NewsletterService.java
@Scheduled(cron = "${scheduling.newsletter-cleanup-cron:0 0 3 * * *}")
public void cleanupExpiredPendingSubscriptionsScheduled() {
    cleanupExpiredPendingSubscriptions().subscribe();
}
// Remove @Scheduled from cleanupExpiredPendingSubscriptions()
public Mono<Void> cleanupExpiredPendingSubscriptions() { ... }
```

**Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=BlogMetricsTest#UpdateMetrics.shouldInvokeScheduledWrapper test`  
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/dev/catananti/service/AnalyticsService.java \
  src/main/java/dev/catananti/service/RefreshTokenService.java \
  src/main/java/dev/catananti/service/PasswordResetService.java \
  src/main/java/dev/catananti/scheduler/ArticlePublishScheduler.java \
  src/main/java/dev/catananti/metrics/BlogMetrics.java \
  src/main/java/dev/catananti/scheduler/AuditLogCleanupScheduler.java \
  src/main/java/dev/catananti/service/CacheWarmingService.java \
  src/main/java/dev/catananti/service/NewsletterService.java \
  src/test/java/dev/catananti/metrics/BlogMetricsTest.java
git commit -m "fix: subscribe scheduled jobs" -m "" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 2: Allow invalid JWTs on public routes

**Files:**
- Modify: `src/main/java/dev/catananti/security/JwtAuthenticationFilter.java`
- Test: `src/test/java/dev/catananti/security/JwtAuthenticationFilterTest.java`

**Step 1: Write the failing test**

Add a test asserting invalid tokens do not short-circuit the filter:

```java
@Test
@DisplayName("Should allow invalid JWT to pass through (anonymous)")
void shouldAllowInvalidJwt() {
    MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/articles")
        .header("Authorization", "Bearer " + INVALID_JWT)
        .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    when(tokenProvider.validateAndParseClaims(INVALID_JWT))
        .thenReturn(JwtTokenProvider.TokenValidationResult.invalid("Invalid token"));

    Mono<Void> result = filter.filter(exchange, passThroughChain());

    StepVerifier.create(result).verifyComplete();
    assertThat(exchange.getResponse().getStatusCode()).isNull();
}
```

**Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=JwtAuthenticationFilterTest#NoAuthenticationTests.shouldAllowInvalidJwt test`  
Expected: FAIL (current behavior sets 401).

**Step 3: Write minimal implementation**

In `JwtAuthenticationFilter.filter`, treat invalid/blacklisted/invalid-subject tokens as anonymous:

```java
if (!validation.valid()) {
    clearAccessTokenCookie(exchange);
    log.warn("Invalid JWT for path: {}", path);
    return chain.filter(exchange);
}
```

And in invalid-subject + blacklisted branches, replace `isExemptPath(...)` + `unauthorizedResponse(...)` with `clearAccessTokenCookie(...)` and `return chain.filter(exchange);`.  
Remove `AUTH_EXEMPT_PATHS` and `isExemptPath(...)` if unused.

**Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=JwtAuthenticationFilterTest#NoAuthenticationTests.shouldAllowInvalidJwt test`  
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/dev/catananti/security/JwtAuthenticationFilter.java \
  src/test/java/dev/catananti/security/JwtAuthenticationFilterTest.java
git commit -m "fix: treat invalid jwt as anonymous" -m "" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 3: Revoke all refresh cookies on logout

**Files:**
- Modify: `src/main/java/dev/catananti/controller/AuthController.java`
- Test: `src/test/java/dev/catananti/controller/AuthControllerTest.java`

**Step 1: Write the failing test**

Add a new test to `AuthControllerTest`:

```java
@Test
@DisplayName("Should revoke all refresh_token cookies on logout")
void shouldRevokeAllRefreshTokens() {
    MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
    cookies.add("refresh_token", new HttpCookie("refresh_token", "refresh-1"));
    cookies.add("refresh_token", new HttpCookie("refresh_token", "refresh-2"));
    when(mockRequest.getCookies()).thenReturn(cookies);

    when(authService.logout(eq("refresh-1"), any())).thenReturn(Mono.empty());
    when(refreshTokenService.revokeToken("refresh-2")).thenReturn(Mono.empty());

    StepVerifier.create(authController.logout(mockRequest, mockResponse, mockExchange)).verifyComplete();

    verify(authService).logout(eq("refresh-1"), any());
    verify(refreshTokenService).revokeToken("refresh-2");
}
```

**Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=AuthControllerTest#Logout.shouldRevokeAllRefreshTokens test`  
Expected: FAIL (only first cookie is used).

**Step 3: Write minimal implementation**

Add a helper to read all refresh token cookies and update `logout(...)`:

```java
private java.util.List<String> extractRefreshTokensFromCookies(ServerHttpRequest request) {
    return request.getCookies()
        .getOrDefault(REFRESH_TOKEN_COOKIE, java.util.Collections.emptyList())
        .stream()
        .map(HttpCookie::getValue)
        .filter(StringUtils::hasText)
        .distinct()
        .toList();
}
```

```java
public Mono<Void> logout(ServerHttpRequest httpRequest, ServerHttpResponse httpResponse, ServerWebExchange exchange) {
    List<String> refreshTokens = extractRefreshTokensFromCookies(httpRequest);
    String accessToken = extractAccessTokenFromCookie(httpRequest);

    Mono<Void> revokeRest = reactor.core.publisher.Flux.fromIterable(refreshTokens)
        .skip(1)
        .flatMap(refreshTokenService::revokeToken)
        .then();

    Mono<Void> logoutMono = Mono.empty();
    if (!refreshTokens.isEmpty()) {
        logoutMono = authService.logout(refreshTokens.get(0), accessToken).then(revokeRest);
    } else if (accessToken != null) {
        logoutMono = authService.logout(null, accessToken);
    }

    return logoutMono
        .then(Mono.fromRunnable(() -> clearAuthCookies(httpResponse)))
        .then(rotateCsrfToken(exchange))
        .onErrorResume(e -> { ... });
}
```

**Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=AuthControllerTest#Logout.shouldRevokeAllRefreshTokens test`  
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/dev/catananti/controller/AuthController.java \
  src/test/java/dev/catananti/controller/AuthControllerTest.java
git commit -m "fix: revoke all refresh cookies on logout" -m "" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 4: Export limit should return 4xx

**Files:**
- Modify: `src/main/java/dev/catananti/controller/AdminExportController.java`
- Modify: `src/main/resources/messages*.properties`
- Test: `src/test/java/dev/catananti/controller/AdminExportControllerTest.java`

**Step 1: Write the failing test**

Add a new test:

```java
@Test
@DisplayName("Should reject export when limit exceeded")
void shouldRejectExportWhenLimitExceeded() {
    ReflectionTestUtils.setField(controller, "maxExportArticles", 1);
    when(articleRepository.countAll()).thenReturn(Mono.just(2L));

    StepVerifier.create(controller.exportBlog("Admin"))
        .expectErrorSatisfies(ex ->
            assertThat(ex).isInstanceOf(org.springframework.web.server.ResponseStatusException.class))
        .verify();
}
```

**Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=AdminExportControllerTest#ExportBlog.shouldRejectExportWhenLimitExceeded test`  
Expected: FAIL (IllegalStateException maps to 500).

**Step 3: Write minimal implementation**

Change `checkExportLimit()`:

```java
if (count > maxExportArticles) {
    return Mono.error(new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "error.export_limit_exceeded"));
}
```

Add i18n keys in `messages.properties`, `messages_en.properties`, `messages_pt_BR.properties`,
`messages_es.properties`, `messages_it.properties`:

```
error.export_limit_exceeded=Export limit exceeded. Maximum {0} articles allowed.
```

**Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=AdminExportControllerTest#ExportBlog.shouldRejectExportWhenLimitExceeded test`  
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/dev/catananti/controller/AdminExportController.java \
  src/main/resources/messages.properties \
  src/main/resources/messages_en.properties \
  src/main/resources/messages_pt_BR.properties \
  src/main/resources/messages_es.properties \
  src/main/resources/messages_it.properties \
  src/test/java/dev/catananti/controller/AdminExportControllerTest.java
git commit -m "fix: return 4xx when export limit exceeded" -m "" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 5: Enforce upload size before buffering

**Files:**
- Modify: `src/main/java/dev/catananti/service/MediaService.java`
- Test: `src/test/java/dev/catananti/service/MediaServiceTest.java`

**Step 1: Write the failing test**

Add a test to reject large content-length early:

```java
@Test
@DisplayName("Should reject when Content-Length exceeds max before buffering")
void upload_ShouldReject_WhenContentLengthTooLarge() throws Exception {
    setMaxFileSize(100);
    byte[] jpegBytes = createJpegBytes(10);
    FilePart filePart = createMockFilePart("photo.jpg", MediaType.IMAGE_JPEG, jpegBytes);
    filePart.headers().setContentLength(200);

    StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
        .expectErrorMatches(ex ->
            ex instanceof ResponseStatusException rse
                && rse.getStatusCode().value() == 400)
        .verify();
}
```

**Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=MediaServiceTest#Upload.upload_ShouldReject_WhenContentLengthTooLarge test`  
Expected: FAIL (content-length is ignored).

**Step 3: Write minimal implementation**

Add a size check before reading:

```java
long contentLength = filePart.headers().getContentLength();
if (contentLength > maxFileSize) {
    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "File size exceeds maximum allowed: " + maxFileSize + " bytes"));
}
```

Limit buffering in `readFileBytes`:

```java
return DataBufferUtils.join(filePart.content(), Math.toIntExact(maxFileSize + 1))
    .map(dataBuffer -> {
        byte[] bytes = new byte[dataBuffer.readableByteCount()];
        dataBuffer.read(bytes);
        DataBufferUtils.release(dataBuffer);
        return bytes;
    });
```

**Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=MediaServiceTest#Upload.upload_ShouldReject_WhenContentLengthTooLarge test`  
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/dev/catananti/service/MediaService.java \
  src/test/java/dev/catananti/service/MediaServiceTest.java
git commit -m "fix: enforce upload size before buffering" -m "" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 6: Unsubscribe-by-email confirmation flow

**Files:**
- Modify: `src/main/java/dev/catananti/controller/NewsletterController.java`
- Modify: `src/main/java/dev/catananti/service/NewsletterService.java`
- Modify: `src/main/java/dev/catananti/service/EmailService.java`
- Create: `src/main/resources/templates/email/newsletter-unsubscribe-confirmation.html`
- Modify: `src/main/resources/messages*.properties`
- Test: `src/test/java/dev/catananti/controller/NewsletterControllerTest.java`

**Step 1: Write the failing test**

Update the unsubscribe test to expect `requestUnsubscribe`:

```java
when(newsletterService.requestUnsubscribe("user@example.com"))
    .thenReturn(Mono.just(Map.of("message", "success.newsletter_unsubscribe_requested")));

StepVerifier.create(newsletterController.unsubscribe("user@example.com"))
    .assertNext(response -> assertThat(response).containsKey("message"))
    .verifyComplete();

verify(newsletterService).requestUnsubscribe("user@example.com");
```

**Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=NewsletterControllerTest#Unsubscribe.shouldReturnGenericSuccess test`  
Expected: FAIL (controller calls `unsubscribe`).

**Step 3: Write minimal implementation**

Add a request flow in `NewsletterService`:

```java
public Mono<Map<String, String>> requestUnsubscribe(String email) {
    return subscriberRepository.findByEmail(email)
        .flatMap(subscriber -> {
            if (subscriber.getUnsubscribeToken() == null) {
                subscriber.setUnsubscribeToken(UUID.randomUUID().toString());
            }
            return subscriberRepository.save(subscriber)
                .flatMap(s -> emailService.sendNewsletterUnsubscribeConfirmation(
                    s.getEmail(), s.getName(), s.getUnsubscribeToken()))
                .thenReturn(Map.of("message", "success.newsletter_unsubscribe_requested"));
        });
}
```

Update controller to call `requestUnsubscribe(...)` and keep generic success fallback.

Add `EmailService.sendNewsletterUnsubscribeConfirmation(...)` using a new template:

```java
String subject = msg("email.newsletter.unsubscribe.subject");
String confirmUrl = siteUrl + "/newsletter/unsubscribe?token=" + unsubscribeToken;
String html = templateService.render("newsletter-unsubscribe-confirmation", baseVars(
    "#ef4444 0%, #dc2626 100%",
    msg("email.newsletter.unsubscribe.header"),
    Map.of(
        "greeting", msg("email.greeting", displayName),
        "bodyText", msg("email.newsletter.unsubscribe.body"),
        "actionText", msg("email.newsletter.unsubscribe.action"),
        "confirmUrl", confirmUrl,
        "buttonText", msg("email.newsletter.unsubscribe.button"),
        "disclaimer", msg("email.newsletter.unsubscribe.disclaimer")
    )
));
return sendHtmlEmail(to, subject, html);
```

Create `newsletter-unsubscribe-confirmation.html` based on `newsletter-confirmation.html`.

Add i18n keys to all `messages*.properties`:

```
success.newsletter_unsubscribe_requested=If the email was subscribed, a confirmation link was sent.
email.newsletter.unsubscribe.subject=Confirm your unsubscription
email.newsletter.unsubscribe.header=Unsubscribe confirmation
email.newsletter.unsubscribe.body=You requested to unsubscribe from the newsletter.
email.newsletter.unsubscribe.action=Click below to confirm.
email.newsletter.unsubscribe.button=Confirm Unsubscribe
email.newsletter.unsubscribe.disclaimer=If you did not request this, ignore this email.
```

**Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=NewsletterControllerTest#Unsubscribe.shouldReturnGenericSuccess test`  
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/dev/catananti/controller/NewsletterController.java \
  src/main/java/dev/catananti/service/NewsletterService.java \
  src/main/java/dev/catananti/service/EmailService.java \
  src/main/resources/templates/email/newsletter-unsubscribe-confirmation.html \
  src/main/resources/messages.properties \
  src/main/resources/messages_en.properties \
  src/main/resources/messages_pt_BR.properties \
  src/main/resources/messages_es.properties \
  src/main/resources/messages_it.properties \
  src/test/java/dev/catananti/controller/NewsletterControllerTest.java
git commit -m "feat: confirm newsletter unsubscribe by email" -m "" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```
