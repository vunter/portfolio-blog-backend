package dev.catananti.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/csp-report")
@Slf4j
public class CspReportController {

    @PostMapping
    public Mono<ResponseEntity<Void>> reportViolation(@RequestBody String report) {
        log.warn("CSP Violation: {}", report);
        return Mono.just(ResponseEntity.noContent().build());
    }
}
