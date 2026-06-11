package dev.catananti.controller;

import dev.catananti.dto.GitHubRepoResponse;
import dev.catananti.service.GitHubService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubControllerTest {

    @Mock
    private GitHubService gitHubService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new GitHubController(gitHubService)).build();
    }

    private GitHubRepoResponse sampleRepo() {
        return new GitHubRepoResponse(
                1L, "demo", "vunter/demo", "A demo repo",
                "https://github.com/vunter/demo", null, 5, 2, "Java",
                List.of("spring"), "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
    }

    @Test
    void returnsReposWithDefaultLimit() {
        when(gitHubService.getRepos(6)).thenReturn(Flux.just(sampleRepo()));

        client.get().uri("/api/v1/github/repos")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(GitHubRepoResponse.class).hasSize(1);

        verify(gitHubService).getRepos(6);
    }

    @Test
    void capsLimitToMax() {
        when(gitHubService.getRepos(30)).thenReturn(Flux.empty());

        client.get().uri("/api/v1/github/repos?limit=999")
                .exchange()
                .expectStatus().isOk();

        verify(gitHubService).getRepos(30);
    }

    @Test
    void clampsLimitToAtLeastOne() {
        when(gitHubService.getRepos(1)).thenReturn(Flux.empty());

        client.get().uri("/api/v1/github/repos?limit=0")
                .exchange()
                .expectStatus().isOk();

        verify(gitHubService).getRepos(1);
    }

    @Test
    void serializesFullNameAsSnakeCase() {
        when(gitHubService.getRepos(6)).thenReturn(Flux.just(sampleRepo()));

        client.get().uri("/api/v1/github/repos")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].full_name").isEqualTo("vunter/demo")
                .jsonPath("$[0].stargazers_count").isEqualTo(5)
                .jsonPath("$[0].html_url").isEqualTo("https://github.com/vunter/demo");
    }
}
