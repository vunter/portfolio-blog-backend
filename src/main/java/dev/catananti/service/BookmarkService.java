package dev.catananti.service;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Bookmark;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.BookmarkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

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

    /**
     * Hash visitor ID with SHA-256 for privacy.
     */
    // TODO F-159: Add salt to visitor ID hash for better privacy or use HMAC
    public String hashVisitorId(String visitorId) {
        return dev.catananti.util.DigestUtils.sha256Hex(visitorId);
    }

    public Mono<PageResponse<ArticleResponse>> getBookmarks(String visitorId, int page, int size) {
        String hashedId = hashVisitorId(visitorId);
        int offset = page * size;

        return bookmarkRepository.findByVisitorHash(hashedId, size, offset)
                .concatMap(bookmark -> articleRepository.findById(bookmark.getArticleId()))
                .concatMap(articleService::enrichArticleWithMetadata)
                .map(articleService::mapToResponse)
                .collectList()
                .zipWith(bookmarkRepository.countByVisitorHash(hashedId))
                .map(tuple -> PageResponse.of(tuple.getT1(), page, size, tuple.getT2()));
    }

    public Mono<Boolean> isBookmarked(String visitorId, String articleSlug) {
        String hashedId = hashVisitorId(visitorId);
        return articleRepository.findBySlug(articleSlug)
                .flatMap(article -> bookmarkRepository.findByArticleIdAndVisitorHash(article.getId(), hashedId)
                        .map(_ -> true)
                        .defaultIfEmpty(false))
                .defaultIfEmpty(false);
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
                                    .map(_ -> true);
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
