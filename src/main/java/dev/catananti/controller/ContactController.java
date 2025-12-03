package dev.catananti.controller;

import dev.catananti.dto.ContactRequest;
import dev.catananti.dto.ContactResponse;
import dev.catananti.service.ContactService;
import dev.catananti.service.RecaptchaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Public contact endpoint — only handles message submission.
 * Admin contact management is in {@link AdminContactController}.
 * TODO F-082: Add rate limiting to contact form submission (e.g., 5 per hour per IP)
 */
@RestController
@RequestMapping("/api/v1/contact")
@RequiredArgsConstructor
@Slf4j
public class ContactController {

    private final ContactService contactService;
    private final RecaptchaService recaptchaService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ContactResponse> sendMessage(@Valid @RequestBody ContactRequest request) {
        // F-081: Mask PII in logs — only show local part prefix
        String email = request.getEmail();
        int atIdx = email != null ? email.indexOf('@') : -1;
        String maskedEmail = atIdx > 0 ? email.substring(0, Math.min(3, atIdx)) + "***" : "***";
        log.debug("Received contact message from: {}", maskedEmail);
        return recaptchaService.verify(request.getRecaptchaToken(), "contact")
                .then(contactService.saveContact(request));
    }
}
