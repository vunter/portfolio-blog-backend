package dev.catananti.controller;

import dev.catananti.dto.GitHubRepoResponse;
import dev.catananti.service.GitHubService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Public, read-only proxy for the owner's GitHub repositories. Lets the frontend
 * fetch repos from the same origin instead of calling api.github.com directly.
 */
@RestController
@RequestMapping("/api/v1/github")
@Tag(name = "GitHub", description = "Public proxy for the owner's GitHub repositories")
public class GitHubController {

    private static final int MAX_LIMIT = 30;
    private static final int MIN_LIMIT = 1;

    private final GitHubService gitHubService;

    public GitHubController(GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    @GetMapping("/repos")
    @Operation(summary = "List the owner's public repositories sorted by recent push activity")
    public Flux<GitHubRepoResponse> repos(@RequestParam(defaultValue = "6") int limit) {
        int capped = Math.min(Math.max(limit, MIN_LIMIT), MAX_LIMIT);
        return gitHubService.getRepos(capped);
    }
}
