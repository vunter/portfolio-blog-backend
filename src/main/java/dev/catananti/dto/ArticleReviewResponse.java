package dev.catananti.dto;

import dev.catananti.entity.ArticleReview;

import java.time.LocalDateTime;

/**
 * AUD19C-3: API-facing projection of {@link ArticleReview}. Snowflake ids exceed
 * JavaScript's Number.MAX_SAFE_INTEGER, so they are serialized as strings — the raw
 * entity (Long id/articleId/reviewerId) silently lost precision in the browser.
 */
public record ArticleReviewResponse(
        String id,
        String articleId,
        String reviewerId,
        String status,
        String feedback,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ArticleReviewResponse from(ArticleReview review) {
        return new ArticleReviewResponse(
                asString(review.getId()),
                asString(review.getArticleId()),
                asString(review.getReviewerId()),
                review.getStatus() != null ? review.getStatus().name() : null,
                review.getFeedback(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }

    private static String asString(Long value) {
        return value != null ? String.valueOf(value) : null;
    }
}
