package dev.catananti.controller;

import dev.catananti.util.LogSafe;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/csp-report")
@Slf4j
public class CspReportController {

    private static final int MAX_REPORT_BYTES = 4096;

    @PostMapping
    public Mono<ResponseEntity<Void>> reportViolation(@RequestBody @Size(max = MAX_REPORT_BYTES) String report) {
        log.warn("CSP Violation: {}", LogSafe.sanitize(report, MAX_REPORT_BYTES));
        return Mono.just(ResponseEntity.noContent().build());
    }
}
