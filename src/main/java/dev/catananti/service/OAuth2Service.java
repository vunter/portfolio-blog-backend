package dev.catananti.service;

import dev.catananti.dto.TokenResponse;
import dev.catananti.entity.User;
import dev.catananti.entity.UserSocialAccount;
import dev.catananti.repository.UserRepository;
import dev.catananti.repository.UserSocialAccountRepository;
import dev.catananti.security.JwtTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class OAuth2Service {

    private final UserRepository userRepository;
    private final UserSocialAccountRepository socialAccountRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider tokenProvider;
    private final IdService idService;
    private final WebClient webClient;
    private final ReactiveStringRedisTemplate redisTemplate;

    private static final String OAUTH2_STATE_PREFIX = "oauth2:state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(5);
    private static final RedisScript<String> GETDEL_SCRIPT = RedisScript.of(
            "local val = redis.call('GET', KEYS[1]); if val then redis.call('DEL', KEYS[1]); end; return val;",
            String.class);

    @Value("${oauth2.google.client-id:}")
    private String googleClientId;
    @Value("${oauth2.google.client-secret:}")
    private String googleClientSecret;
    @Value("${oauth2.github.client-id:}")
    private String githubClientId;
    @Value("${oauth2.github.client-secret:}")
    private String githubClientSecret;
    @Value("${oauth2.redirect-base-url:}")
    private String redirectBaseUrl;
    @Value("${jwt.expiration-ms:900000}")
    private long jwtExpirationMs;

    public OAuth2Service(UserRepository userRepository,
                         UserSocialAccountRepository socialAccountRepository,
                         RefreshTokenService refreshTokenService,
                         JwtTokenProvider tokenProvider,
                         IdService idService,
                         ReactiveStringRedisTemplate redisTemplate) {
        this.userRepository = userRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.refreshTokenService = refreshTokenService;
        this.tokenProvider = tokenProvider;
        this.idService = idService;
        this.redisTemplate = redisTemplate;
        this.webClient = WebClient.builder().build();
    }

    public boolean isGoogleEnabled() {
        return googleClientId != null && !googleClientId.isBlank();
    }

    public boolean isGithubEnabled() {
        return githubClientId != null && !githubClientId.isBlank();
    }

    public String getGoogleAuthUrl(String state) {
        return "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + googleClientId
                + "&redirect_uri=" + redirectBaseUrl + "/api/v1/admin/auth/oauth2/callback/google"
                + "&response_type=code"
                + "&scope=openid%20email%20profile"
                + "&state=" + state
                + "&access_type=offline";
    }

    public String getGithubAuthUrl(String state) {
        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + githubClientId
                + "&redirect_uri=" + redirectBaseUrl + "/api/v1/admin/auth/oauth2/callback/github"
                + "&scope=user:email"
                + "&state=" + state;
    }

    /**
     * Store OAuth2 state in Redis with short TTL for CSRF protection.
     */
    public Mono<Boolean> storeState(String state) {
        return redisTemplate.opsForValue()
                .set(OAUTH2_STATE_PREFIX + state, "1", STATE_TTL);
    }

    /**
     * Validate and consume OAuth2 state (one-time use).
     */
    public Mono<Boolean> validateAndConsumeState(String state) {
        if (state == null || state.isBlank()) {
            return Mono.just(false);
        }
        String key = OAUTH2_STATE_PREFIX + state;
        return redisTemplate.execute(GETDEL_SCRIPT, Collections.singletonList(key))
                .next()
                .map(val -> true)
                .defaultIfEmpty(false);
    }

    @SuppressWarnings("unchecked")
    public Mono<TokenResponse> handleGoogleCallback(String code, String clientIp) {
        return webClient.post()
                .uri("https://oauth2.googleapis.com/token")
                .body(BodyInserters.fromFormData("code", code)
                        .with("client_id", googleClientId)
                        .with("client_secret", googleClientSecret)
                        .with("redirect_uri", redirectBaseUrl + "/api/v1/admin/auth/oauth2/callback/google")
                        .with("grant_type", "authorization_code"))
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(tokenData -> {
                    String accessToken = (String) tokenData.get("access_token");
                    return webClient.get()
                            .uri("https://www.googleapis.com/oauth2/v2/userinfo")
                            .header("Authorization", "Bearer " + accessToken)
                            .retrieve()
                            .bodyToMono(Map.class);
                })
                .flatMap(userInfo -> {
                    String providerId = (String) userInfo.get("id");
                    String email = (String) userInfo.get("email");
                    String name = (String) userInfo.get("name");
                    String avatar = (String) userInfo.get("picture");
                    Boolean emailVerified = (Boolean) userInfo.get("email_verified");
                    if (!Boolean.TRUE.equals(emailVerified)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Email not verified by Google. Please verify your email and try again."));
                    }
                    return findOrCreateUser("google", providerId, email, name, avatar, clientIp, true);
                })
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    log.error("Google OAuth2 error: {}", e.getMessage());
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Google authentication failed"));
                });
    }

    @SuppressWarnings("unchecked")
    public Mono<TokenResponse> handleGithubCallback(String code, String clientIp) {
        return webClient.post()
                .uri("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .body(BodyInserters.fromFormData("code", code)
                        .with("client_id", githubClientId)
                        .with("client_secret", githubClientSecret))
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(tokenData -> {
                    String accessToken = (String) tokenData.get("access_token");
                    var userInfoMono = webClient.get()
                            .uri("https://api.github.com/user")
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Accept", "application/json")
                            .retrieve()
                            .bodyToMono(Map.class);
                    var emailsMono = webClient.get()
                            .uri("https://api.github.com/user/emails")
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Accept", "application/json")
                            .retrieve()
                            .bodyToFlux(Map.class)
                            .collectList();
                    return Mono.zip(userInfoMono, emailsMono);
                })
                .flatMap(tuple -> {
                    Map userInfo = tuple.getT1();
                    List<Map> emails = (List<Map>) (List<?>) tuple.getT2();
                    String providerId = String.valueOf(userInfo.get("id"));
                    String name = (String) userInfo.get("name");
                    String avatar = (String) userInfo.get("avatar_url");
                    String login = (String) userInfo.get("login");
                    if (name == null || name.isBlank()) name = login;
                    // Use only verified email from GitHub /user/emails endpoint
                    String email = emails.stream()
                            .filter(e -> Boolean.TRUE.equals(e.get("verified")) && Boolean.TRUE.equals(e.get("primary")))
                            .map(e -> (String) e.get("email"))
                            .findFirst()
                            .orElse(null);
                    if (email == null) {
                        email = emails.stream()
                                .filter(e -> Boolean.TRUE.equals(e.get("verified")))
                                .map(e -> (String) e.get("email"))
                                .findFirst()
                                .orElse(null);
                    }
                    if (email == null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "No verified email found on GitHub. Please verify your email and try again."));
                    }
                    return findOrCreateUser("github", providerId, email, name, avatar, clientIp, true);
                })
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    log.error("GitHub OAuth2 error: {}", e.getMessage());
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub authentication failed"));
                });
    }

    /**
     * Link a social account to an already authenticated user.
     */
    public Mono<UserSocialAccount> linkAccount(Long userId, String provider, String providerId,
                                                String email, String name, String avatar) {
        return socialAccountRepository.findByProviderAndProviderId(provider, providerId)
                .flatMap(existing -> {
                    if (existing.getUserId().equals(userId)) {
                        return Mono.just(existing);
                    }
                    return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                            "This " + provider + " account is already linked to another user"));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    var account = UserSocialAccount.builder()
                            .id(idService.nextId())
                            .userId(userId)
                            .provider(provider)
                            .providerId(providerId)
                            .providerEmail(email)
                            .displayName(name)
                            .avatarUrl(avatar)
                            .linkedAt(LocalDateTime.now())
                            .build();
                    return socialAccountRepository.save(account);
                }));
    }

    /**
     * Unlink a social account. User must have a password or another linked provider.
     */
    public Mono<Void> unlinkAccount(Long userId, String provider) {
        return userRepository.findById(userId)
                .flatMap(user -> {
                    boolean hasPassword = user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
                    if (!hasPassword) {
                        return socialAccountRepository.countByUserId(userId)
                                .flatMap(count -> {
                                    if (count <= 1) {
                                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                "Cannot unlink last login method. Set a password first."));
                                    }
                                    return socialAccountRepository.deleteByUserIdAndProvider(userId, provider);
                                });
                    }
                    return socialAccountRepository.deleteByUserIdAndProvider(userId, provider);
                });
    }

    private Mono<TokenResponse> findOrCreateUser(String provider, String providerId,
                                                   String email, String name, String avatar,
                                                   String clientIp, boolean emailVerified) {
        return socialAccountRepository.findByProviderAndProviderId(provider, providerId)
                .flatMap(existing -> userRepository.findById(existing.getUserId())
                        .flatMap(user -> issueTokens(user, clientIp)))
                .switchIfEmpty(Mono.defer(() -> {
                    if (email == null || email.isBlank()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Email not provided by " + provider + ". Please grant email access."));
                    }
                    if (!emailVerified) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Email not verified by " + provider + ". Please verify your email and try again."));
                    }
                    return userRepository.findByEmail(email.toLowerCase())
                            .flatMap(existingUser -> {
                                return linkAccount(existingUser.getId(), provider, providerId, email, name, avatar)
                                        .then(issueTokens(existingUser, clientIp));
                            })
                            .switchIfEmpty(Mono.defer(() -> createSocialUser(provider, providerId, email, name, avatar, clientIp)));
                }))
                .retryWhen(Retry.max(1)
                        .filter(e -> e instanceof DataIntegrityViolationException)
                        .doBeforeRetry(signal -> log.warn("Retrying findOrCreateUser after unique constraint violation")));
    }

    private Mono<TokenResponse> createSocialUser(String provider, String providerId,
                                                    String email, String name, String avatar,
                                                    String clientIp) {
        var now = LocalDateTime.now();
        var user = User.builder()
                .id(idService.nextId())
                .email(email.toLowerCase())
                .name(name != null ? name : email.split("@")[0])
                .username(generateUsername(email))
                .role("VIEWER")
                .emailVerified(true)
                .avatarUrl(avatar)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return userRepository.save(user)
                .flatMap(savedUser -> linkAccount(savedUser.getId(), provider, providerId, email, name, avatar)
                        .then(issueTokens(savedUser, clientIp)));
    }

    private Mono<TokenResponse> issueTokens(User user, String clientIp) {
        String accessToken = tokenProvider.generateToken(user.getEmail(), user.getRole());
        return refreshTokenService.createRefreshToken(user.getId(), clientIp, "OAuth2")
                .map(refreshToken -> TokenResponse.builder()
                        .accessToken(accessToken)
                        .refreshToken(refreshToken.getToken())
                        .tokenType("Bearer")
                        .expiresIn(jwtExpirationMs / 1000)
                        .email(user.getEmail())
                        .name(user.getName())
                        .build());
    }

    private String generateUsername(String email) {
        String base = email.split("@")[0].replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        if (base.length() < 3) base = base + "user";
        return base + "-" + System.currentTimeMillis() % 10000;
    }
}
