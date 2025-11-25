package dev.catananti.service;

import dev.catananti.dto.TagRequest;
import dev.catananti.dto.TagResponse;
import dev.catananti.entity.LocalizedText;
import dev.catananti.entity.Tag;
import dev.catananti.exception.DuplicateResourceException;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
// TODO F-232: Add tag merge/alias functionality for consolidating similar tags
public class TagService {

    private static final Pattern DIACRITICALS = Pattern.compile("[\\p{InCombiningDiacriticalMarks}]");
    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LEADING_TRAILING_HYPHENS = Pattern.compile("^-+|-+$");

    private final TagRepository tagRepository;
    private final IdService idService;

    public Flux<TagResponse> getAllTags(String locale) {
        return tagRepository.findAll()
                .flatMap(tag -> toResponseWithCount(tag, locale));
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

    // TODO F-227: Use LEFT JOIN with COUNT instead of N+1 count queries per tag
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
                .flatMap(tag -> toResponseWithCount(tag, locale));
    }
}
