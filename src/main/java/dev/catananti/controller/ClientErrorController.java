package dev.catananti.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Q13.3: Receives client-side error reports from the frontend.
 * Logs as structured JSON (via logback-spring.xml) for aggregation.
 */
@RestController
@RequestMapping("/api/v1/client-errors")
@Slf4j
public class ClientErrorController {

    @PostMapping
    public Mono<ResponseEntity<Void>> reportError(@Valid @RequestBody ClientErrorReport report) {
        log.warn("Client error: message={}, url={}, source={}, userAgent={}",
                report.message(), report.url(), report.source(), report.userAgent());
        return Mono.just(ResponseEntity.noContent().build());
    }

    public record ClientErrorReport(
            @NotBlank @Size(max = 2000) String message,
            @Size(max = 500) String url,
            @Size(max = 200) String source,
            @Size(max = 10000) String stack,
            @Size(max = 500) String userAgent
    ) {}
}
