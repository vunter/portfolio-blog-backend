package dev.catananti.controller;

import dev.catananti.dto.TagRequest;
import dev.catananti.dto.TagResponse;
import dev.catananti.dto.UserResponse;
import dev.catananti.entity.UserRole;
import dev.catananti.service.UserService;
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
 * - DEV sees only tags linked to their articles
 * All roles can create/edit/delete tags (needed for article editing).
 */
@RestController
@RequestMapping("/api/v1/admin/tags")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV')")
@Slf4j
public class AdminTagController {

    private final TagService tagService;
    private final UserService userService;

    @GetMapping
    public Mono<List<TagResponse>> getAllTags(@RequestParam(required = false) String locale) {
        log.debug("Admin fetching tags");
        return getCurrentUser().flatMap(user -> {
            if (UserRole.ADMIN.matches(user.getRole())) {
                return tagService.getAllTags(locale).collectList();
            } else {
                // DEV: only tags linked to their articles
                return tagService.getTagsByAuthorId(Long.valueOf(user.getId()), locale).collectList();
            }
        });
    }

    // AUD19C-5: GET /{id} removed — verified zero callers (the frontend edits tags
    // from the list it already holds).

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
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<Void> deleteTag(@PathVariable Long id) {
        log.info("Deleting tag: id={}", id);
        return tagService.deleteTag(id);
    }

    private Mono<UserResponse> getCurrentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth != null && auth.isAuthenticated())
                .map(auth -> auth.getName())
                .flatMap(userService::getUserByEmail);
    }
}
