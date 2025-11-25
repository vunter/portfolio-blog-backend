package dev.catananti.controller;

import dev.catananti.dto.TagRequest;
import dev.catananti.dto.TagResponse;
import dev.catananti.entity.UserRole;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.TagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Tag management scoped by role:
 * - ADMIN sees all tags
 * - DEV/EDITOR sees only tags linked to their articles
 * All roles can create/edit/delete tags (needed for article editing).
 */
@RestController
@RequestMapping("/api/v1/admin/tags")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV', 'EDITOR')")
@Slf4j
public class AdminTagController {

    private final TagService tagService;
    private final UserRepository userRepository;

    @GetMapping
    public Mono<List<TagResponse>> getAllTags(@RequestParam(required = false) String locale) {
        log.debug("Admin fetching tags");
        return getCurrentUser().flatMap(user -> {
            if (UserRole.ADMIN.matches(user.getRole())) {
                return tagService.getAllTags(locale).collectList();
            } else {
                // DEV/EDITOR: only tags linked to their articles
                return tagService.getTagsByAuthorId(user.getId(), locale).collectList();
            }
        });
    }

    @GetMapping("/{id}")
    public Mono<TagResponse> getTagById(@PathVariable Long id) {
        log.debug("Admin fetching tag by id: {}", id);
        return tagService.getTagById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<TagResponse> createTag(@Valid @RequestBody TagRequest request) {
        log.info("Creating new tag");
        return tagService.createTag(request);
    }

    @PutMapping("/{id}")
    public Mono<TagResponse> updateTag(
            @PathVariable Long id,
            @Valid @RequestBody TagRequest request) {
        log.info("Updating tag: id={}", id);
        return tagService.updateTag(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteTag(@PathVariable Long id) {
        log.info("Deleting tag: id={}", id);
        return tagService.deleteTag(id);
    }

    private Mono<dev.catananti.entity.User> getCurrentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth != null && auth.isAuthenticated())
                .map(auth -> auth.getName())
                .flatMap(email -> userRepository.findByEmail(email));
    }
}
