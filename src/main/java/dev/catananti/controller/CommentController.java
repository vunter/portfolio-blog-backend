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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) @Parameter(description = "Page size (1-100)") int size,
            @RequestParam(defaultValue = "liked") @Parameter(description = "Sort order: recent, liked, oldest") String sort) {
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
        log.info("Creating comment for slug={} by user={}", slug, email);
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
        Mono<Boolean> toggleMono = deduplicationService
                .map(svc -> svc.hasLikedComment(commentId, request)
                        .flatMap(alreadyLiked -> {
                            if (alreadyLiked) {
                                return svc.removeCommentLike(commentId, request)
                                        .then(commentService.unlikeComment(commentId))
                                        .thenReturn(false);
                            } else {
                                return svc.recordCommentLikeIfNew(commentId, request)
                                        .then(commentService.likeComment(commentId))
                                        .thenReturn(true);
                            }
                        }))
                .orElseGet(() -> commentService.likeComment(commentId).thenReturn(true));

        return toggleMono.flatMap(liked -> commentService.getCommentLikeCount(commentId)
                .map(count -> Map.<String, Object>of("liked", liked, "likesCount", count)));
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
}
