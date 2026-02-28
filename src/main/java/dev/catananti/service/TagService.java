package dev.catananti.service;

import dev.catananti.dto.PageResponse;
import dev.catananti.dto.TagRequest;
import dev.catananti.dto.TagResponse;
import dev.catananti.entity.LocalizedText;
import dev.catananti.entity.Tag;
import dev.catananti.exception.DuplicateResourceException;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class TagService {

    private static final Pattern DIACRITICALS = Pattern.compile("[\\p{InCombiningDiacriticalMarks}]");
    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LEADING_TRAILING_HYPHENS = Pattern.compile("^-+|-+$");

    private final TagRepository tagRepository;
    private final IdService idService;
    private final DatabaseClient databaseClient;

    public Flux<TagResponse> getAllTags(String locale) {
        return tagRepository.findAll()
                .collectList()
                .flatMapMany(tags -> {
                    if (tags.isEmpty()) {
                        return Flux.empty();
                    }
                    List<Long> tagIds = tags.stream().map(Tag::getId).toList();
                    return batchFetchArticleCounts(tagIds)
                            .flatMapMany(countMap -> Flux.fromIterable(tags)
                                    .map(tag -> buildTagResponse(tag, locale,
                                            countMap.getOrDefault(tag.getId(), 0))));
                });
    }

    public Mono<PageResponse<TagResponse>> getAllTagsPaginated(String locale, int page, int size) {
        int offset = page * size;
        return tagRepository.findAllPaginated(size, offset)
                .collectList()
                .flatMap(tags -> {
                    if (tags.isEmpty()) {
                        return tagRepository.countAll()
                                .map(total -> PageResponse.of(List.of(), page, size, total));
                    }
                    List<Long> tagIds = tags.stream().map(Tag::getId).toList();
                    return batchFetchArticleCounts(tagIds)
                            .flatMap(countMap -> {
                                List<TagResponse> responses = tags.stream()
                                        .map(tag -> buildTagResponse(tag, locale,
                                                countMap.getOrDefault(tag.getId(), 0)))
                                        .toList();
                                return tagRepository.countAll()
                                        .map(total -> PageResponse.of(responses, page, size, total));
                            });
                });
    }

    public Mono<TagResponse> getTagBySlug(String slug, String locale) {
        return tagRepository.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Tag", "slug", slug)))
                .flatMap(tag -> toResponseWithCount(tag, locale));
    }

    public Mono<TagResponse> getTagById(Long id) {
        return tagRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Tag", "id", id)))
                .flatMap(tag -> toResponseWithCount(tag, null));
    }

    @Transactional
    public Mono<TagResponse> createTag(TagRequest request) {
        // Auto-generate slug from name if not provided
        if (request.getSlug() == null || request.getSlug().isBlank()) {
            request.setSlug(generateSlug(request.getName()));
        }
        return tagRepository.existsBySlug(request.getSlug())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new DuplicateResourceException("Tag", "slug", request.getSlug()));
                    }

                    Tag tag = Tag.builder()
                            .id(idService.nextId())
                            .name(LocalizedText.ofEnglish(request.getName()))
                            .slug(request.getSlug())
                            .description(request.getDescription() != null
                                    ? LocalizedText.ofEnglish(request.getDescription()) : null)
                            .color(request.getColor())
                            .createdAt(LocalDateTime.now())
                            .build();

                    return tagRepository.save(tag)
                            .doOnSuccess(t -> log.info("Tag created: {}", t.getSlug()))
                            .flatMap(t -> toResponseWithCount(t, null));
                });
    }

    @Transactional
    public Mono<TagResponse> updateTag(Long id, TagRequest request) {
        return tagRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Tag", "id", id)))
                .flatMap(tag -> {
                    // Preserve existing translations, update English
                    LocalizedText names = tag.getName() != null ? new LocalizedText(tag.getName().getTranslations()) : new LocalizedText();
                    names.put("en", request.getName());
                    tag.setName(names);

                    tag.setSlug(request.getSlug());

                    if (request.getDescription() != null) {
                        LocalizedText descs = tag.getDescription() != null ? new LocalizedText(tag.getDescription().getTranslations()) : new LocalizedText();
                        descs.put("en", request.getDescription());
                        tag.setDescription(descs);
                    } else {
                        tag.setDescription(null);
                    }

                    tag.setColor(request.getColor());

                    return tagRepository.save(tag)
                            .doOnSuccess(t -> log.info("Tag updated: {}", t.getSlug()))
                            .flatMap(t -> toResponseWithCount(t, null));
                });
    }

    @Transactional
    public Mono<Void> deleteTag(Long id) {
        return tagRepository.findById(id)
                .flatMap(tag -> tagRepository.deleteById(id)
                        .doOnSuccess(v -> log.info("Tag deleted: {} (slug={})", id, tag.getSlug()))
                )
                .then(); // Idempotent: if tag not found, complete silently
    }

    private Mono<TagResponse> toResponseWithCount(Tag tag, String locale) {
        return tagRepository.countPublishedArticlesByTagId(tag.getId())
                .defaultIfEmpty(0L)
                .map(count -> buildTagResponse(tag, locale, count.intValue()));
    }

    /**
     * Build a TagResponse, resolving name/description for the given locale.
     * The full translations map is included for admin views.
     */
    private static TagResponse buildTagResponse(Tag tag, String locale, int articleCount) {
        return TagResponse.builder()
                .id(String.valueOf(tag.getId()))
                .name(tag.getName() != null ? tag.getName().get(locale) : null)
                .slug(tag.getSlug())
                .description(tag.getDescription() != null ? tag.getDescription().get(locale) : null)
                .color(tag.getColor())
                .names(tag.getName() != null ? tag.getName().getTranslations() : null)
                .descriptions(tag.getDescription() != null ? tag.getDescription().getTranslations() : null)
                .articleCount(articleCount)
                .createdAt(tag.getCreatedAt())
                .updatedAt(tag.getUpdatedAt())
                .build();
    }

    /**
     * Generate a URL-safe slug from a tag name.
     * Removes diacritics, converts to lowercase, replaces non-alphanumeric with hyphens.
     */
    private String generateSlug(String name) {
        if (name == null || name.isBlank()) {
            return "untitled";
        }
        String normalized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD);
        String stripped = DIACRITICALS.matcher(normalized).replaceAll("");
        String slugged = NON_SLUG_CHARS.matcher(stripped.toLowerCase()).replaceAll("-");
        return LEADING_TRAILING_HYPHENS.matcher(slugged).replaceAll("");
    }

    /**
     * F-232: Merge source tag into target tag.
     * Reassigns all articles from source tag to target tag, then deletes the source tag.
     */
    @Transactional
    public Mono<TagResponse> mergeTags(Long sourceTagId, Long targetTagId) {
        if (sourceTagId.equals(targetTagId)) {
            return Mono.error(new IllegalArgumentException("Source and target tags must be different"));
        }
        return Mono.zip(
                tagRepository.findById(sourceTagId)
                        .switchIfEmpty(Mono.error(new ResourceNotFoundException("Tag", "id", sourceTagId))),
                tagRepository.findById(targetTagId)
                        .switchIfEmpty(Mono.error(new ResourceNotFoundException("Tag", "id", targetTagId)))
        ).flatMap(tuple -> {
            Tag source = tuple.getT1();
            Tag target = tuple.getT2();
            // Reassign articles: update article_tags rows from source to target, skip duplicates
            return databaseClient.sql(
                    "UPDATE article_tags SET tag_id = :targetId WHERE tag_id = :sourceId " +
                    "AND article_id NOT IN (SELECT article_id FROM article_tags WHERE tag_id = :targetId)")
                    .bind("targetId", targetTagId)
                    .bind("sourceId", sourceTagId)
                    .fetch().rowsUpdated()
                    .then(databaseClient.sql("DELETE FROM article_tags WHERE tag_id = :sourceId")
                            .bind("sourceId", sourceTagId)
                            .fetch().rowsUpdated())
                    .then(tagRepository.deleteById(sourceTagId))
                    .then(toResponseWithCount(target, null))
                    .doOnSuccess(t -> log.info("Merged tag {} into {} (slug={})", source.getSlug(), target.getSlug(), target.getSlug()));
        });
    }

    /**
     * MIN-07: Service-layer access for feed controllers (Sitemap).
     * Returns raw Tag entities.
     */
    public Flux<Tag> findAllTagEntities() {
        return tagRepository.findAll();
    }

    /**
     * Get tags linked to articles by a specific author (for DEV/EDITOR dashboard).
     */
    public Flux<TagResponse> getTagsByAuthorId(Long authorId, String locale) {
        return tagRepository.findByAuthorId(authorId)
                .collectList()
                .flatMapMany(tags -> {
                    if (tags.isEmpty()) {
                        return Flux.empty();
                    }
                    List<Long> tagIds = tags.stream().map(Tag::getId).toList();
                    return batchFetchArticleCounts(tagIds)
                            .flatMapMany(countMap -> Flux.fromIterable(tags)
                                    .map(tag -> buildTagResponse(tag, locale,
                                            countMap.getOrDefault(tag.getId(), 0))));
                });
    }

    /**
     * Batch-fetch published article counts for a list of tag IDs in a single query.
     */
    private Mono<Map<Long, Integer>> batchFetchArticleCounts(List<Long> tagIds) {
        return databaseClient.sql(
                "SELECT at.tag_id, COUNT(*) as cnt FROM article_tags at " +
                "JOIN articles a ON a.id = at.article_id " +
                "WHERE a.status = 'PUBLISHED' AND at.tag_id = ANY(:ids) GROUP BY at.tag_id")
                .bind("ids", tagIds.toArray(new Long[0]))
                .map((row, meta) -> Map.entry(
                        row.get("tag_id", Long.class),
                        row.get("cnt", Long.class).intValue()))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }
}
