package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.ResumeCertification;
import dev.catananti.repository.ResumeCertificationRepository;
import dev.catananti.util.DigestUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
                    .credentialUrl(DigestUtils.sanitizeUrl(e.getCredentialUrl()))
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
     * F-214: Merge certification entries — update existing, insert new, delete removed.
     */
    public Mono<Void> mergeCertifications(Long profileId, List<ResumeProfileRequest.CertificationEntry> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return deleteByProfileId(profileId);
        }
        return certificationRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .collectMap(ResumeCertification::getId)
                .flatMap(existingMap -> {
                    var now = LocalDateTime.now();
                    Set<Long> keepIds = new HashSet<>();
                    List<ResumeCertification> toSave = new ArrayList<>();

                    for (int i = 0; i < incoming.size(); i++) {
                        var entry = incoming.get(i);
                        Long existingId = parseId(entry.getId());
                        int sortOrder = entry.getSortOrder() != null ? entry.getSortOrder() : i;

                        if (existingId != null && existingMap.containsKey(existingId)) {
                            var entity = existingMap.get(existingId);
                            entity.setName(entry.getName());
                            entity.setIssuer(entry.getIssuer());
                            entity.setIssueDate(entry.getIssueDate());
                            entity.setCredentialUrl(DigestUtils.sanitizeUrl(entry.getCredentialUrl()));
                            entity.setDescription(entry.getDescription());
                            entity.setSortOrder(sortOrder);
                            entity.setUpdatedAt(now);
                            entity.setNewRecord(false);
                            keepIds.add(existingId);
                            toSave.add(entity);
                        } else {
                            toSave.add(ResumeCertification.builder()
                                    .id(idService.nextId()).profileId(profileId)
                                    .name(entry.getName()).issuer(entry.getIssuer())
                                    .issueDate(entry.getIssueDate()).credentialUrl(DigestUtils.sanitizeUrl(entry.getCredentialUrl()))
                                    .description(entry.getDescription()).sortOrder(sortOrder)
                                    .createdAt(now).updatedAt(now).newRecord(true)
                                    .build());
                        }
                    }

                    List<Long> toDelete = existingMap.keySet().stream()
                            .filter(id -> !keepIds.contains(id)).toList();
                    Mono<Void> deleteMono = toDelete.isEmpty()
                            ? Mono.empty()
                            : certificationRepository.deleteAllById(toDelete).then();

                    return deleteMono.then(certificationRepository.saveAll(toSave).then());
                });
    }

    private static Long parseId(String id) {
        if (id == null || id.isBlank()) return null;
        try { return Long.parseLong(id); } catch (NumberFormatException e) { return null; }
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
