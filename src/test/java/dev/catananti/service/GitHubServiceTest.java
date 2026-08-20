package dev.catananti.service;

import dev.catananti.dto.GitHubRepoResponse;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitHubServiceTest {

    private MockWebServer server;

    @Mock
    private CacheService cacheService;

    private GitHubService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        when(cacheService.get(anyString(), eq(GitHubRepoResponse[].class)))
                .thenReturn(Mono.<GitHubRepoResponse[]>empty());
        when(cacheService.set(anyString(), any(), any())).thenReturn(Mono.just(true));
        service = new GitHubService(WebClient.builder(), cacheService, "vunter", "", server.url("/").toString());
    }

    @AfterEach
    void tearDown() {
        // okhttp 5 / mockwebserver3: MockWebServer is Closeable; shutdown() is gone.
        server.close();
    }

    @Test
    void fetchesAndMapsReposIgnoringUnknownFields() {
        server.enqueue(new MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("""
                        [{
                          "id": 1,
                          "name": "demo",
                          "full_name": "vunter/demo",
                          "description": "A demo",
                          "html_url": "https://github.com/vunter/demo",
                          "homepage": null,
                          "stargazers_count": 5,
                          "forks_count": 2,
                          "language": "Java",
                          "topics": ["spring", "java"],
                          "created_at": "2024-01-01T00:00:00Z",
                          "updated_at": "2025-01-01T00:00:00Z",
                          "pushed_at": "2026-01-01T00:00:00Z",
                          "unmapped_field": "ignored"
                        }]
                        """)
                .build());

        StepVerifier.create(service.getRepos(6).collectList())
                .assertNext(repos -> {
                    assertThat(repos).hasSize(1);
                    GitHubRepoResponse r = repos.get(0);
                    assertThat(r.fullName()).isEqualTo("vunter/demo");
                    assertThat(r.stargazersCount()).isEqualTo(5);
                    assertThat(r.forksCount()).isEqualTo(2);
                    assertThat(r.language()).isEqualTo("Java");
                    assertThat(r.topics()).containsExactly("spring", "java");
                    assertThat(r.htmlUrl()).isEqualTo("https://github.com/vunter/demo");
                })
                .verifyComplete();
    }

    @Test
    void returnsEmptyOnGitHubError() {
        server.enqueue(new MockResponse.Builder().code(504).build());

        StepVerifier.create(service.getRepos(6).collectList())
                .assertNext(repos -> assertThat(repos).isEmpty())
                .verifyComplete();
    }

    @Test
    void returnsEmptyWhenUsernameBlank() {
        GitHubService blank =
                new GitHubService(WebClient.builder(), cacheService, "  ", "", server.url("/").toString());

        StepVerifier.create(blank.getRepos(6))
                .verifyComplete();
    }
}
