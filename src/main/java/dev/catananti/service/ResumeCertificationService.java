package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.ResumeCertification;
import dev.catananti.repository.ResumeCertificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Service for managing resume certification entries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeCertificationService {

    private final ResumeCertificationRepository certificationRepository;
    private final IdService idService;

    /**
     * Save certification entries for a profile.
     */
    // TODO F-216: Validate credential URLs to reject javascript:/data:/vbscript: schemes
    public Mono<Void> saveCertifications(Long profileId, List<ResumeProfileRequest.CertificationEntry> certifications) {
        if (certifications == null || certifications.isEmpty()) {
            log.debug("No certifications to save for profileId={}", profileId);
            return Mono.empty();
        }
        log.info("Saving {} certifications for profileId={}", certifications.size(), profileId);
        var now = LocalDateTime.now();
        var entities = IntStream.range(0, certifications.size()).mapToObj(i -> {
            var e = certifications.get(i);
            return ResumeCertification.builder()
                    .id(idService.nextId())
                    .profileId(profileId)
                    .name(e.getName())
                    .issuer(e.getIssuer())
                    .issueDate(e.getIssueDate())
                    .credentialUrl(e.getCredentialUrl())
                    .description(e.getDescription())
                    .sortOrder(e.getSortOrder() != null ? e.getSortOrder() : i)
                    .createdAt(now).updatedAt(now)
                    .newRecord(true)
                    .build();
        }).toList();
        return certificationRepository.saveAll(entities).then();
    }

    /**
     * Delete all certification entries for a profile.
     */
    public Mono<Void> deleteByProfileId(Long profileId) {
        log.info("Deleting all certifications for profileId={}", profileId);
        return certificationRepository.deleteByProfileId(profileId);
    }

    /**
     * Find all certification entries for a profile and map to response DTOs.
     */
    public Mono<List<ResumeProfileResponse.CertificationResponse>> findByProfileId(Long profileId) {
        log.debug("Finding certifications for profileId={}", profileId);
        return certificationRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .map(e -> ResumeProfileResponse.CertificationResponse.builder()
                        .id(String.valueOf(e.getId()))
                        .name(e.getName())
                        .issuer(e.getIssuer())
                        .issueDate(e.getIssueDate())
                        .credentialUrl(e.getCredentialUrl())
                        .description(e.getDescription())
                        .sortOrder(e.getSortOrder())
                        .build())
                .collectList();
    }
}
