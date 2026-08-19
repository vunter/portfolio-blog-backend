package dev.catananti.service;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Article;
import dev.catananti.entity.Bookmark;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.BookmarkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service layer for bookmark operations (CQ-01).
 * Extracted from BookmarkController to enforce Controller → Service → Repository pattern.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final ArticleRepository articleRepository;
    private final ArticleService articleService;
    private final IdService idService;

    @Value("${app.bookmark.hash-salt:default-bookmark-salt}")
    private String hashSalt;

    /**
     * Hash visitor ID with SHA-256 and salt for privacy.
     */
    public String hashVisitorId(String visitorId) {
        return dev.catananti.util.DigestUtils.sha256Hex(hashSalt + visitorId);
    }

    public Mono<PageResponse<ArticleResponse>> getBookmarks(String visitorId, int page, int size) {
        String hashedId = hashVisitorId(visitorId);
        int offset = page * size;

        // NP-2: load the page's articles in one batch (findAllById + the constant-query
        // enrichArticlesWithMetadata) instead of 4 sequential queries per bookmark.
        return bookmarkRepository.findByVisitorHash(hashedId, size, offset)
                .map(Bookmark::getArticleId)
                .collectList()
                .flatMap(this::loadArticlesInBookmarkOrder)
                .zipWith(bookmarkRepository.countByVisitorHash(hashedId))
                .map(tuple -> PageResponse.of(tuple.getT1(), page, size, tuple.getT2()));
    }

    private Mono<List<ArticleResponse>> loadArticlesInBookmarkOrder(List<Long> articleIds) {
        if (articleIds.isEmpty()) {
            return Mono.just(List.of());
        }
        return articleRepository.findAllById(articleIds)
                .collectList()
                .flatMap(articleService::enrichArticlesWithMetadata)
                .map(articles -> {
                    Map<Long, Article> byId = articles.stream()
                            .collect(Collectors.toMap(Article::getId, Function.identity()));
                    return articleIds.stream()
                            .map(byId::get)
                            .filter(Objects::nonNull)
                            .map(articleService::mapToResponse)
                            .toList();
                });
    }

    public Mono<Boolean> addBookmark(String visitorId, String articleSlug) {
        String hashedId = hashVisitorId(visitorId);
        return articleRepository.findBySlug(articleSlug)
                .flatMap(article -> bookmarkRepository.findByArticleIdAndVisitorHash(article.getId(), hashedId)
                        .map(_ -> true)
                        .switchIfEmpty(Mono.defer(() -> {
                            Bookmark bookmark = Bookmark.builder()
                                    .id(idService.nextId())
                                    .articleId(article.getId())
                                    .visitorHash(hashedId)
                                    .createdAt(LocalDateTime.now())
                                    .build();
                            return bookmarkRepository.save(bookmark)
                                    .doOnSuccess(b -> log.debug("Bookmark added: article={}, visitor={}", articleSlug, visitorId))
                                    .map(_ -> true)
                                    // BUG-3: TOCTOU vs uq_bookmark_visitor — a concurrent insert makes
                                    // the loser throw DuplicateKeyException (otherwise surfaced as 500).
                                    // It already exists, so treat it as already-bookmarked (idempotent).
                                    .onErrorResume(org.springframework.dao.DuplicateKeyException.class, e -> {
                                        log.debug("Bookmark already existed (concurrent insert): article={}, visitor={}",
                                                articleSlug, visitorId);
                                        return Mono.just(true);
                                    });
                        })))
                .defaultIfEmpty(false);
    }

    public Mono<Void> removeBookmark(String visitorId, String articleSlug) {
        String hashedId = hashVisitorId(visitorId);
        return articleRepository.findBySlug(articleSlug)
                .flatMap(article -> bookmarkRepository.deleteByArticleIdAndVisitorHash(article.getId(), hashedId))
                .doOnSuccess(_ -> log.debug("Bookmark removed: article={}, visitor={}", articleSlug, visitorId));
    }
}
