package dev.catananti.service;

import dev.catananti.dto.GitHubRepoResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Server-side proxy for the owner's public GitHub repositories.
 *
 * <p>The browser used to call api.github.com directly, which leaks the dependency
 * onto every visitor's network (CORS errors + transient GitHub 5xx) and shares
 * the unauthenticated 60-req/h rate limit across all visitors by IP. Fetching it
 * here instead means the frontend calls a same-origin endpoint, the result is
 * cached in Redis, and an optional token raises the rate limit. The widget is
 * non-critical, so any GitHub error degrades to an empty list rather than an
 * error surfaced to the client.</p>
 */
@Service
@Slf4j
public class GitHubService {

    private static final String CACHE_KEY = "github-repos::list";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final CacheService cacheService;
    private final String username;
    private final String token;

    public GitHubService(
            WebClient.Builder webClientBuilder,
            CacheService cacheService,
            @Value("${app.github.username:vunter}") String username,
            @Value("${app.github.token:}") String token,
            @Value("${app.github.api-base-url:https://api.github.com}") String apiBaseUrl) {
        this.webClient = webClientBuilder.baseUrl(apiBaseUrl).build();
        this.cacheService = cacheService;
        this.username = username;
        this.token = token;
    }

    /**
     * Owner's public repositories sorted by most recent push. Served from the
     * Redis cache when warm; on a miss it fetches from GitHub and caches a
     * non-empty result. Returns an empty stream if no username is configured or
     * GitHub is unreachable.
     */
    public Flux<GitHubRepoResponse> getRepos(int limit) {
        if (username == null || username.isBlank()) {
            return Flux.empty();
        }
        return cacheService.get(CACHE_KEY, GitHubRepoResponse[].class)
                .flatMapMany(Flux::fromArray)
                .switchIfEmpty(fetchAndCache(limit));
    }

    private Flux<GitHubRepoResponse> fetchAndCache(int limit) {
        return fetchFromGitHub(limit)
                .collectList()
                // Only cache a successful, non-empty fetch so a transient GitHub
                // failure isn't pinned in the cache for the full TTL.
                .flatMap(repos -> repos.isEmpty()
                        ? Mono.just(repos)
                        : cacheService.set(CACHE_KEY, repos, CACHE_TTL).thenReturn(repos))
                .flatMapMany(Flux::fromIterable);
    }

    private Flux<GitHubRepoResponse> fetchFromGitHub(int limit) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/users/{username}/repos")
                        .queryParam("sort", "pushed")
                        .queryParam("direction", "desc")
                        .queryParam("per_page", limit)
                        .build(username))
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .headers(headers -> {
                    if (token != null && !token.isBlank()) {
                        headers.setBearerAuth(token);
                    }
                })
                .retrieve()
                .bodyToFlux(GitHubRepoResponse.class)
                .timeout(REQUEST_TIMEOUT)
                .onErrorResume(error -> {
                    log.warn("GitHub repos fetch failed for user '{}': {}", username, error.toString());
                    return Flux.empty();
                });
    }
}
