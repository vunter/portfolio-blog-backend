package dev.catananti.dto;

/**
 * What account deletion will touch, shown before the holder decides: the
 * deletion screen must inform the linked subscription (and offer to cancel it)
 * and give a sense of the public content whose authorship survives.
 * {@code newsletterStatus} is null when no subscription is linked.
 */
public record DeletionPreviewResponse(
        boolean newsletterLinked,
        String newsletterStatus,
        long commentsCount,
        long articlesCount) {
}
