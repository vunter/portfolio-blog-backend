package dev.catananti.controller;

import dev.catananti.dto.CommentResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.CommentService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/comments")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV', 'EDITOR')")
@Validated
@Slf4j
public class AdminCommentController {

    private final CommentService commentService;

    @GetMapping
    public Mono<PageResponse<CommentResponse>> getCommentsByStatus(
            @RequestParam(defaultValue = "ALL") @Pattern(regexp = "^(ALL|PENDING|APPROVED|REJECTED|SPAM)$", message = "Invalid status. Must be ALL, PENDING, APPROVED, REJECTED, or SPAM") String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.debug("Fetching comments: status={}, page={}, size={}", status, page, size);
        return commentService.getAdminCommentsByStatus(status, page, size);
    }

    @GetMapping("/article/{articleId}")
    public Mono<List<CommentResponse>> getCommentsByArticle(@PathVariable Long articleId) {
        log.debug("Fetching comments for article: {}", articleId);
        return commentService.getAdminCommentsByArticleId(articleId)
                .take(500)
                .collectList();
    }

    @RequestMapping(value = "/{id}/approve", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public Mono<CommentResponse> approveComment(@PathVariable Long id) {
        log.info("Approving comment: id={}", id);
        return commentService.adminApproveComment(id);
    }

    @RequestMapping(value = "/{id}/reject", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public Mono<CommentResponse> rejectComment(@PathVariable Long id) {
        log.info("Rejecting comment: id={}", id);
        return commentService.adminRejectComment(id);
    }

    @RequestMapping(value = "/{id}/spam", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public Mono<CommentResponse> markAsSpam(@PathVariable Long id) {
        log.info("Marking comment as spam: id={}", id);
        return commentService.adminMarkAsSpam(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteComment(@PathVariable Long id) {
        log.info("Deleting comment: id={}", id);
        return commentService.adminDeleteComment(id);
    }
}
