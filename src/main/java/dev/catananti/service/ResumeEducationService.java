package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.ResumeEducation;
import dev.catananti.repository.ResumeEducationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import dev.catananti.util.ResumeDateSorter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Service for managing resume education entries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeEducationService {

    private final ResumeEducationRepository educationRepository;
    private final IdService idService;

    /**
     * Save education entries for a profile.
     */
    public Mono<Void> saveEducations(Long profileId, List<ResumeProfileRequest.EducationEntry> educations) {
        if (educations == null || educations.isEmpty()) {
            log.debug("No educations to save for profileId={}", profileId);
            return Mono.empty();
        }
        log.info("Saving {} education entries for profileId={}", educations.size(), profileId);
        var now = LocalDateTime.now();
        var entities = IntStream.range(0, educations.size()).mapToObj(i -> {
            var e = educations.get(i);
            return ResumeEducation.builder()
                    .id(idService.nextId())
                    .profileId(profileId)
                    .institution(e.getInstitution())
                    .location(e.getLocation())
                    .degree(e.getDegree())
                    .fieldOfStudy(e.getFieldOfStudy())
                    .startDate(e.getStartDate())
                    .endDate(e.getEndDate())
                    .description(e.getDescription())
                    .sortOrder(e.getSortOrder() != null ? e.getSortOrder() : i)
                    .createdAt(now).updatedAt(now)
                    .newRecord(true)
                    .build();
        }).toList();
        return educationRepository.saveAll(entities).then();
    }

    /**
     * Delete all education entries for a profile.
     */
    public Mono<Void> deleteByProfileId(Long profileId) {
        log.info("Deleting all education entries for profileId={}", profileId);
        return educationRepository.deleteByProfileId(profileId);
    }

    /**
     * Find all education entries for a profile and map to response DTOs.
     */
    public Mono<List<ResumeProfileResponse.EducationResponse>> findByProfileId(Long profileId) {
        log.debug("Finding education entries for profileId={}", profileId);
        return educationRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .map(e -> ResumeProfileResponse.EducationResponse.builder()
                        .id(String.valueOf(e.getId()))
                        .institution(e.getInstitution())
                        .location(e.getLocation())
                        .degree(e.getDegree())
                        .fieldOfStudy(e.getFieldOfStudy())
                        .startDate(e.getStartDate())
                        .endDate(e.getEndDate())
                        .description(e.getDescription())
                        .sortOrder(e.getSortOrder())
                        .build())
                .collectList()
                .map(list -> list.stream()
                        .sorted(ResumeDateSorter.educationComparator(
                                ResumeProfileResponse.EducationResponse::getStartDate,
                                ResumeProfileResponse.EducationResponse::getEndDate))
                        .toList());
    }
}
