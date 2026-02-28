package dev.catananti.controller;

import dev.catananti.dto.SubscribeRequest;
import dev.catananti.service.NewsletterService;
import dev.catananti.service.RecaptchaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/newsletter")
@RequiredArgsConstructor
@Tag(name = "Newsletter", description = "Newsletter subscription endpoints")
@Slf4j
public class NewsletterController {

    private final NewsletterService newsletterService;
    private final RecaptchaService recaptchaService;
    private final MessageSource messageSource;

    private String msg(Locale locale, String key) {
        return messageSource.getMessage(key, null, key, locale);
    }

    @PostMapping("/subscribe")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(summary = "Subscribe to newsletter", description = "Subscribe an email to the newsletter")
    public Mono<Map<String, String>> subscribe(@Valid @RequestBody SubscribeRequest request) {
        log.info("Newsletter subscription request");
        return recaptchaService.verify(request.getRecaptchaToken(), "subscribe")
                .then(newsletterService.subscribe(request))
                // F-084: Extracted repeated locale resolution to localizeResult()
                .flatMap(this::localizeResult);
    }

    @GetMapping("/confirm")
    @Operation(summary = "Confirm subscription", description = "Confirm newsletter subscription with token")
    public Mono<Map<String, String>> confirmSubscription(
            @RequestParam @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min = 10, max = 200) String token) {
        log.info("Newsletter subscription confirmation: token={}", token);
        return newsletterService.confirmSubscription(token)
                .flatMap(this::localizeResult);
    }

    @PostMapping("/unsubscribe")
    @Operation(summary = "Unsubscribe from newsletter", description = "Unsubscribe an email from the newsletter")
    public Mono<Map<String, String>> unsubscribe(
            @RequestParam @Email(message = "Invalid email format") String email) {
        log.info("Newsletter unsubscription: email={}", email);
        // Always return success to prevent email enumeration
        return newsletterService.unsubscribe(email)
                .flatMap(this::localizeResult)
                .onErrorResume(e -> localizeKey("success.generic_unsubscribe"))
                .switchIfEmpty(localizeKey("success.generic_unsubscribe"));
    }

    @GetMapping("/unsubscribe")
    @Operation(summary = "Unsubscribe by token", description = "Unsubscribe using a unique token from email")
    public Mono<Map<String, String>> unsubscribeByToken(
            @RequestParam @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min = 10, max = 200) String token) {
        log.info("Newsletter unsubscription by token: token={}", token);
        return newsletterService.unsubscribeByToken(token)
                .flatMap(this::localizeResult);
    }

    // F-084: Extracted repeated locale resolution pattern (was duplicated 5x)
    private Mono<Map<String, String>> localizeResult(Map<String, String> result) {
        return Mono.deferContextual(ctx -> {
            Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
            String message = result.get("message");
            return Mono.just(Map.of("message", msg(locale, message)));
        });
    }

    private Mono<Map<String, String>> localizeKey(String key) {
        return Mono.deferContextual(ctx -> {
            Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
            return Mono.just(Map.of("message", msg(locale, key)));
        });
    }
}
