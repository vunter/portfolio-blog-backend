package dev.catananti.controller;

import dev.catananti.dto.TagResponse;
import dev.catananti.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
@Validated
@io.swagger.v3.oas.annotations.tags.Tag(name = "Tags", description = "Public tag endpoints")
@Slf4j
public class TagController {

    private final TagService tagService;

    @GetMapping
    @Operation(summary = "Get all tags", description = "Retrieve all tags with article counts")
    // TODO F-111: Add pagination to getAllTags — currently returns all tags in a single response
    public Mono<List<TagResponse>> getAllTags(
            @Parameter(description = "Locale for translations") @RequestParam(required = false) String locale) {
        log.debug("Fetching all tags");
        return tagService.getAllTags(locale).collectList();
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get tag by slug", description = "Retrieve a specific tag by its slug")
    public Mono<TagResponse> getTagBySlug(
            @Parameter(description = "Tag slug", required = true) @PathVariable @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String slug,
            @Parameter(description = "Locale for translations") @RequestParam(required = false) String locale) {
        log.debug("Fetching tag by slug={}", slug);
        return tagService.getTagBySlug(slug, locale);
    }
}
