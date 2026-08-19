package dev.catananti.controller;

import dev.catananti.entity.UserSocialAccount;
import dev.catananti.repository.UserRepository;
import dev.catananti.repository.UserSocialAccountRepository;
import dev.catananti.security.AuthCookieService;
import dev.catananti.service.OAuth2Service;
import dev.catananti.util.IpAddressExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/auth/oauth2")
@RequiredArgsConstructor
@Slf4j
public class OAuth2Controller {

    private final OAuth2Service oAuth2Service;
    private final UserRepository userRepository;
    private final UserSocialAccountRepository socialAccountRepository;
    // AUD19C-C1B: shared auth-cookie contract (the inline copy here had drifted:
    // access maxAge came from the response's expiresIn and refresh was always 7d).
    private final AuthCookieService authCookieService;

    @Value("${jwt.cookie.secure:true}")
    private boolean cookieSecure;

    @Value("${jwt.cookie.domain:}")
    private String cookieDomain;

    @GetMapping("/providers")
    public Map<String, Boolean> getAvailableProviders() {
        return Map.of(
                "google", oAuth2Service.isGoogleEnabled(),
                "github", oAuth2Service.isGithubEnabled(),
                "linkedin", oAuth2Service.isLinkedinEnabled()
        );
    }

    /** SEG-2: short-lived cookie binding the OAuth2 'state' nonce to the initiating browser. */
    private static final String OAUTH_STATE_COOKIE = "oauth_state";

    @GetMapping("/authorize/{provider}")
    public Mono<ResponseEntity<Void>> authorize(@PathVariable String provider) {
        String state = UUID.randomUUID().toString();
        String authUrl = switch (provider.toLowerCase()) {
            case "google" -> oAuth2Service.getGoogleAuthUrl(state);
            case "github" -> oAuth2Service.getGithubAuthUrl(state);
            case "linkedin" -> oAuth2Service.getLinkedinAuthUrl(state);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "error.unsupported_provider");
        };
        // SEG-2: bind the state nonce to this browser via a short-lived HttpOnly cookie so
        // the callback can require it to match the 'state' query param (anti login-CSRF /
        // session-fixation). The Redis one-time-state check remains as defense in depth.
        ResponseCookie.ResponseCookieBuilder stateBuilder = ResponseCookie.from(OAUTH_STATE_COOKIE, state)
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/").maxAge(Duration.ofMinutes(5));
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            stateBuilder.domain(cookieDomain);
        }
        ResponseCookie stateCookie = stateBuilder.build();
        return oAuth2Service.storeState(state)
                .thenReturn(ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(authUrl))
                        .header(HttpHeaders.SET_COOKIE, stateCookie.toString())
                        .<Void>build());
    }

    /** SEG-2: clear the short-lived state-binding cookie once it has been consumed/rejected. */
    private void clearStateCookie(ServerHttpResponse httpResponse) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(OAUTH_STATE_COOKIE, "")
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/").maxAge(0);
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        httpResponse.addCookie(builder.build());
    }

    private static final java.util.regex.Pattern UUID_PATTERN =
            java.util.regex.Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    @GetMapping("/callback/{provider}")
    public Mono<ResponseEntity<Void>> callback(@PathVariable String provider,
                                                @RequestParam String code,
                                                @RequestParam String state,
                                                ServerHttpRequest httpRequest,
                                                ServerHttpResponse httpResponse) {
        if (state == null || !UUID_PATTERN.matcher(state).matches()) {
            log.warn("OAuth2 callback with invalid state format: {}", state);
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid OAuth2 state parameter"));
        }
        // SEG-2: bind state to the initiating browser — the 'oauth_state' cookie set in
        // authorize() must be present and equal to the 'state' query param. A mismatch/absence
        // means the callback was not initiated by this browser (login-CSRF / session-fixation),
        // so fail with an auth error BEFORE consuming the one-time Redis state.
        HttpCookie stateCookie = httpRequest.getCookies().getFirst(OAUTH_STATE_COOKIE);
        String cookieState = stateCookie != null ? stateCookie.getValue() : null;
        if (cookieState == null || cookieState.isBlank() || !cookieState.equals(state)) {
            log.warn("OAuth2 callback state/cookie mismatch for provider={} (cookie present={})",
                    provider, stateCookie != null);
            clearStateCookie(httpResponse);
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid or expired OAuth2 state. Please try again."));
        }
        // One-time state binding has served its purpose; drop the cookie regardless of outcome.
        clearStateCookie(httpResponse);
        return oAuth2Service.validateAndConsumeState(state)
                .flatMap(valid -> {
                    if (!valid) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Invalid or expired OAuth2 state. Please try again."));
                    }
                    String clientIp = IpAddressExtractor.extractClientIp(httpRequest);
                    log.info("OAuth2 callback for provider={} from IP={}", provider, clientIp);

                    Mono<dev.catananti.dto.TokenResponse> callbackMono = switch (provider.toLowerCase()) {
                        case "google" -> oAuth2Service.handleGoogleCallback(code, clientIp);
                        case "github" -> oAuth2Service.handleGithubCallback(code, clientIp);
                        case "linkedin" -> oAuth2Service.handleLinkedinCallback(code, clientIp);
                        default -> Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "error.unsupported_provider"));
                    };

                    return callbackMono.map(tokenResponse -> {
                        // AUD18-A10: MFA-enabled accounts get a challenge instead of tokens —
                        // mirror the password flow. There is no session yet, so no auth cookies
                        // are set; the short-lived mfaToken travels in the URL *fragment*
                        // (never sent to the server, so it cannot land in access logs) and the
                        // SPA callback page routes to /auth/mfa-verify exactly like password
                        // login does.
                        if (Boolean.TRUE.equals(tokenResponse.getMfaRequired())) {
                            StringBuilder fragment = new StringBuilder("mfa_required=true&mfa_token=")
                                    .append(java.net.URLEncoder.encode(tokenResponse.getMfaToken(), java.nio.charset.StandardCharsets.UTF_8));
                            if (tokenResponse.getEmail() != null) {
                                fragment.append("&email=")
                                        .append(java.net.URLEncoder.encode(tokenResponse.getEmail(), java.nio.charset.StandardCharsets.UTF_8));
                            }
                            return ResponseEntity.status(HttpStatus.FOUND)
                                    .location(URI.create("/auth/oauth-callback#" + fragment))
                                    .<Void>build();
                        }
                        // AUD19C-C1B: unified on AuthCookieService — access maxAge now comes from
                        // jwt.expiration (same value expiresIn is derived from) and refresh keeps
                        // its previous 7-day lifetime via rememberMe=true.
                        authCookieService.addAccessTokenCookie(httpResponse, tokenResponse.getAccessToken());
                        authCookieService.addRefreshTokenCookie(httpResponse, tokenResponse.getRefreshToken(), true);
                        // Coerce to a non-negative long so a malformed expiresIn cannot inject CR/LF
                        // into the Location header or build a malformed redirect target.
                        long expiresIn = Math.max(0L, tokenResponse.getExpiresIn());
                        return ResponseEntity.status(HttpStatus.FOUND)
                                .location(URI.create("/auth/oauth-callback?expires_in=" + expiresIn))
                                .<Void>build();
                    });
                });
    }

    @GetMapping("/accounts")
    public Flux<Map<String, Object>> getLinkedAccounts(@AuthenticationPrincipal String email) {
        return userRepository.findByEmail(email)
                .flatMapMany(user -> socialAccountRepository.findByUserId(user.getId()))
                .map(account -> Map.<String, Object>of(
                        // AUD19C-SESSID: Snowflake ids > 2^53 lose precision as JSON numbers
                        "id", String.valueOf(account.getId()),
                        "provider", account.getProvider(),
                        "providerEmail", account.getProviderEmail() != null ? account.getProviderEmail() : "",
                        "displayName", account.getDisplayName() != null ? account.getDisplayName() : "",
                        "linkedAt", account.getLinkedAt().toString()
                ));
    }

    @DeleteMapping("/accounts/{provider}")
    public Mono<ResponseEntity<Void>> unlinkAccount(@AuthenticationPrincipal String email,
                                                      @PathVariable String provider) {
        return userRepository.findByEmail(email)
                .flatMap(user -> oAuth2Service.unlinkAccount(user.getId(), provider.toLowerCase()))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
