package dev.catananti.controller;

import dev.catananti.dto.ContactResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.ContactService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Admin contact management endpoints.
 * Separated from public {@link ContactController} following the /api/v1/admin/** convention.
 */
@RestController
@RequestMapping("/api/v1/admin/contact")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Slf4j
public class AdminContactController {

    private final ContactService contactService;

    @GetMapping("/messages")
    public Mono<PageResponse<ContactResponse>> getAllMessages(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.debug("Fetching contact messages: page={}, size={}", page, size);
        return contactService.getAllContactsPaginated(page, size);
    }

    @GetMapping("/messages/{id}")
    public Mono<ContactResponse> getMessage(@PathVariable String id) {
        log.debug("Fetching contact message: id={}", id);
        return contactService.getContactById(id);
    }

    @PutMapping("/messages/{id}/read")
    public Mono<ContactResponse> markAsRead(@PathVariable String id) {
        log.info("Marking contact message as read: id={}", id);
        return contactService.markAsRead(id);
    }

    @DeleteMapping("/messages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteMessage(@PathVariable String id) {
        log.info("Deleting contact message: id={}", id);
        return contactService.deleteContact(id);
    }
}
