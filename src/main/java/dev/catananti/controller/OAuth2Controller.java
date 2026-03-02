package dev.catananti.controller;

import dev.catananti.entity.UserSocialAccount;
import dev.catananti.repository.UserRepository;
import dev.catananti.repository.UserSocialAccountRepository;
import dev.catananti.service.OAuth2Service;
import dev.catananti.util.IpAddressExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @GetMapping("/providers")
    public Map<String, Boolean> getAvailableProviders() {
        return Map.of(
                "google", oAuth2Service.isGoogleEnabled(),
                "github", oAuth2Service.isGithubEnabled()
        );
    }

    @GetMapping("/authorize/{provider}")
    public Mono<ResponseEntity<Void>> authorize(@PathVariable String provider) {
        String state = UUID.randomUUID().toString();
        String authUrl = switch (provider.toLowerCase()) {
            case "google" -> oAuth2Service.getGoogleAuthUrl(state);
            case "github" -> oAuth2Service.getGithubAuthUrl(state);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider: " + provider);
        };
        return oAuth2Service.storeState(state)
                .thenReturn(ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(authUrl))
                        .<Void>build());
    }

    @GetMapping("/callback/{provider}")
    public Mono<ResponseEntity<Void>> callback(@PathVariable String provider,
                                                @RequestParam String code,
                                                @RequestParam String state,
                                                ServerHttpRequest httpRequest,
                                                ServerHttpResponse httpResponse) {
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
                        default -> Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider"));
                    };

                    return callbackMono.map(tokenResponse -> {
                        httpResponse.addCookie(ResponseCookie.from("access_token", tokenResponse.getAccessToken())
                                .httpOnly(true).secure(true).path("/").sameSite("Lax")
                                .maxAge(tokenResponse.getExpiresIn()).build());
                        httpResponse.addCookie(ResponseCookie.from("refresh_token", tokenResponse.getRefreshToken())
                                .httpOnly(true).secure(true).path("/api/v1/admin/auth")
                                .sameSite("Lax").maxAge(7 * 24 * 3600).build());
                        return ResponseEntity.status(HttpStatus.FOUND)
                                .location(URI.create("/admin"))
                                .<Void>build();
                    });
                });
    }

    @GetMapping("/accounts")
    public Flux<Map<String, Object>> getLinkedAccounts(@AuthenticationPrincipal String email) {
        return userRepository.findByEmail(email)
                .flatMapMany(user -> socialAccountRepository.findByUserId(user.getId()))
                .map(account -> Map.<String, Object>of(
                        "id", account.getId(),
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
