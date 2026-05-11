package dev.catananti.controller;

import dev.catananti.dto.CommentRequest;
import dev.catananti.dto.CommentResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.CommentService;
import dev.catananti.service.InteractionDeduplicationService;
import dev.catananti.service.RecaptchaService;
import dev.catananti.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import dev.catananti.config.PaginationConfig;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.core.env.Environment;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/articles/{slug}/comments")
@RequiredArgsConstructor
@Validated
@Tag(name = "Comments", description = "Comment management endpoints")
@Slf4j
public class CommentController {

    private final CommentService commentService;
    private final RecaptchaService recaptchaService;
    private final UserService userService;
    private final Optional<InteractionDeduplicationService> deduplicationService;
    private final PaginationConfig paginationConfig;
    private final Environment environment;

    @PostConstruct
    void logDeduplicationStatus() {
        if (deduplicationService.isEmpty()) {
            if (java.util.List.of(environment.getActiveProfiles()).contains("prod")) {
                throw new IllegalStateException(
                        "InteractionDeduplicationService is required in production but not available. "
                        + "Ensure Redis (spring.cache.type=redis) is configured.");
            }
            log.warn("InteractionDeduplicationService is NOT available — comment like deduplication is DISABLED.");
        }
    }

    @GetMapping
    @Operation(summary = "Get approved comments for article", description = "Returns paginated list of approved comments")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Comments retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Article not found")
    })
    public Mono<PageResponse<CommentResponse>> getComments(
            @PathVariable
            @Size(min = 1, max = 200, message = "Slug must be 1-200 characters")
            @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format")
            @Parameter(description = "Article slug") String slug,
            @RequestParam(defaultValue = "0") @Min(0) @Parameter(description = "Page number") int page,
            @RequestParam(defaultValue = "20") @Min(1) @Parameter(description = "Page size") int size,
            @RequestParam(defaultValue = "liked") @Parameter(description = "Sort order: recent, liked, oldest") String sort) {
        size = paginationConfig.clampPageSize(size);
        log.debug("Fetching comments for slug={}, page={}, size={}, sort={}", slug, page, size, sort);
        return commentService.getApprovedCommentsByArticleSlugPaginated(slug, page, size, sort);
    }

    @GetMapping("/count")
    @Operation(summary = "Get comment count for article")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Count retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Article not found")
    })
    public Mono<Long> getCommentCount(
            @PathVariable 
            @Size(min = 1, max = 200, message = "Slug must be 1-200 characters") 
            @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format")
            @Parameter(description = "Article slug") String slug) {
        log.debug("Fetching comment count for slug={}", slug);
        return commentService.getCommentCountByArticleSlug(slug);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new comment", description = "Submit a comment for moderation")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Comment created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "404", description = "Article not found"),
            @ApiResponse(responseCode = "429", description = "Too many requests")
    })
    public Mono<CommentResponse> createComment(
            @PathVariable 
            @Size(min = 1, max = 200, message = "Slug must be 1-200 characters") 
            @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format")
            @Parameter(description = "Article slug") String slug,
            @Valid @RequestBody CommentRequest request,
            @AuthenticationPrincipal String email) {
        log.info("Creating comment for slug={} by user={}", slug, PiiMasker.maskEmail(email));
        return userService.getUserByEmail(email)
                .flatMap(user -> {
                    request.setAuthorName(user.getName() != null ? user.getName() : user.getUsername());
                    request.setAuthorEmail(user.getEmail());
                    return recaptchaService.verify(request.getRecaptchaToken(), "comment")
                            .then(commentService.createComment(slug, request));
                });
    }

    @PostMapping("/{commentId}/like")
    @Operation(summary = "Toggle comment like", description = "Like or unlike a comment (toggle, deduplicated per IP)")
    public Mono<Map<String, Object>> toggleCommentLike(
            @PathVariable @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String slug,
            @PathVariable Long commentId,
            ServerHttpRequest request) {
        // Q2.6: UPDATE ... RETURNING returns the new count atomically — one roundtrip.
        return deduplicationService
                .map(svc -> svc.hasLikedComment(commentId, request)
                        .flatMap(alreadyLiked -> {
                            if (alreadyLiked) {
                                return svc.removeCommentLike(commentId, request)
                                        .then(commentService.unlikeCommentAndReturnCount(commentId))
                                        .map(count -> Map.<String, Object>of("liked", false, "likesCount", count));
                            } else {
                                return svc.recordCommentLikeIfNew(commentId, request)
                                        .then(commentService.likeCommentAndReturnCount(commentId))
                                        .map(count -> Map.<String, Object>of("liked", true, "likesCount", count));
                            }
                        }))
                .orElseGet(() -> commentService.likeCommentAndReturnCount(commentId)
                        .map(count -> Map.<String, Object>of("liked", true, "likesCount", count)));
    }

    @GetMapping("/{commentId}/like/status")
    @Operation(summary = "Check comment like status")
    public Mono<Map<String, Object>> getCommentLikeStatus(
            @PathVariable @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String slug,
            @PathVariable Long commentId,
            ServerHttpRequest request) {
        Mono<Boolean> hasLikedMono = deduplicationService
                .map(svc -> svc.hasLikedComment(commentId, request))
                .orElseGet(() -> Mono.just(false));

        return hasLikedMono.zipWith(commentService.getCommentLikeCount(commentId))
                .map(tuple -> Map.<String, Object>of("liked", tuple.getT1(), "likesCount", tuple.getT2()));
    }

    /**
     * Batch like-status lookup. Replaces the N+1 client pattern of one HTTP call per comment.
     * Returns a map keyed by commentId of {@code {liked, likesCount}} entries, with a hard cap
     * to prevent abuse.
     */
    @PostMapping("/like/status/batch")
    @Operation(summary = "Batch comment like-status lookup")
    public Mono<Map<String, Map<String, Object>>> batchCommentLikeStatus(
            @PathVariable @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String slug,
            @RequestBody @Valid BatchLikeStatusRequest body,
            ServerHttpRequest request) {
        if (body == null || body.commentIds() == null || body.commentIds().isEmpty()) {
            return Mono.just(Map.of());
        }
        java.util.List<Long> ids = body.commentIds().stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .limit(200) // safety cap — one article should never need more
                .toList();

        return reactor.core.publisher.Flux.fromIterable(ids)
                .flatMap(id -> {
                    Mono<Boolean> hasLikedMono = deduplicationService
                            .map(svc -> svc.hasLikedComment(id, request))
                            .orElseGet(() -> Mono.just(false));
                    return hasLikedMono.zipWith(commentService.getCommentLikeCount(id))
                            .map(t -> Map.entry(String.valueOf(id),
                                    Map.<String, Object>of("liked", t.getT1(), "likesCount", t.getT2())));
                }, 16) // bounded concurrency
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    public record BatchLikeStatusRequest(
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Size(min = 1, max = 200)
            java.util.List<Long> commentIds) {}
}
