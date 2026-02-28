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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
     * F-214: Merge education entries — update existing, insert new, delete removed.
     * Preserves IDs for entries the client sends back, avoiding unnecessary deletes.
     */
    public Mono<Void> mergeEducations(Long profileId, List<ResumeProfileRequest.EducationEntry> incoming) {
        if (incoming == null) {
            return Mono.empty(); // null = field not sent, preserve existing data
        }
        if (incoming.isEmpty()) {
            return deleteByProfileId(profileId);
        }
        return educationRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .collectMap(ResumeEducation::getId)
                .flatMap(existingMap -> {
                    var now = LocalDateTime.now();
                    Set<Long> keepIds = new HashSet<>();
                    List<ResumeEducation> toSave = new ArrayList<>();

                    for (int i = 0; i < incoming.size(); i++) {
                        var entry = incoming.get(i);
                        Long existingId = parseId(entry.getId());
                        int sortOrder = entry.getSortOrder() != null ? entry.getSortOrder() : i;

                        if (existingId != null && existingMap.containsKey(existingId)) {
                            var entity = existingMap.get(existingId);
                            entity.setInstitution(entry.getInstitution());
                            entity.setLocation(entry.getLocation());
                            entity.setDegree(entry.getDegree());
                            entity.setFieldOfStudy(entry.getFieldOfStudy());
                            entity.setStartDate(entry.getStartDate());
                            entity.setEndDate(entry.getEndDate());
                            entity.setDescription(entry.getDescription());
                            entity.setSortOrder(sortOrder);
                            entity.setUpdatedAt(now);
                            entity.setNewRecord(false);
                            keepIds.add(existingId);
                            toSave.add(entity);
                        } else {
                            toSave.add(ResumeEducation.builder()
                                    .id(idService.nextId()).profileId(profileId)
                                    .institution(entry.getInstitution()).location(entry.getLocation())
                                    .degree(entry.getDegree()).fieldOfStudy(entry.getFieldOfStudy())
                                    .startDate(entry.getStartDate()).endDate(entry.getEndDate())
                                    .description(entry.getDescription()).sortOrder(sortOrder)
                                    .createdAt(now).updatedAt(now).newRecord(true)
                                    .build());
                        }
                    }

                    List<Long> toDelete = existingMap.keySet().stream()
                            .filter(id -> !keepIds.contains(id)).toList();
                    Mono<Void> deleteMono = toDelete.isEmpty()
                            ? Mono.empty()
                            : educationRepository.deleteAllById(toDelete).then();

                    return deleteMono.then(educationRepository.saveAll(toSave).then());
                });
    }

    private static Long parseId(String id) {
        if (id == null || id.isBlank()) return null;
        try { return Long.parseLong(id); } catch (NumberFormatException e) { return null; }
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
