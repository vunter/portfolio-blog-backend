package dev.catananti.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.ResumeExperience;
import dev.catananti.repository.ResumeExperienceRepository;
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
 * Service for managing resume work experience entries.
 * Uses ResourceNotFoundException for missing entities and IllegalArgumentException for invalid input.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResumeExperienceService {

    private final ResumeExperienceRepository experienceRepository;
    private final IdService idService;
    private final ObjectMapper objectMapper;

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    /**
     * Save experience entries for a profile.
     */
    public Mono<Void> saveExperiences(Long profileId, List<ResumeProfileRequest.ExperienceEntry> experiences) {
        if (experiences == null || experiences.isEmpty()) {
            return Mono.empty();
        }
        var now = LocalDateTime.now();
        var entities = IntStream.range(0, experiences.size()).mapToObj(i -> {
            var e = experiences.get(i);
            String bulletsJson = e.getBullets() != null ? toJsonArray(e.getBullets()) : "[]";
            return ResumeExperience.builder()
                    .id(idService.nextId())
                    .profileId(profileId)
                    .company(e.getCompany())
                    .position(e.getPosition())
                    .startDate(e.getStartDate())
                    .endDate(e.getEndDate())
                    .bullets(bulletsJson)
                    .sortOrder(e.getSortOrder() != null ? e.getSortOrder() : i)
                    .createdAt(now).updatedAt(now)
                    .newRecord(true)
                    .build();
        }).toList();
        return experienceRepository.saveAll(entities).then();
    }

    /**
     * Delete all experience entries for a profile.
     */
    public Mono<Void> deleteByProfileId(Long profileId) {
        return experienceRepository.deleteByProfileId(profileId);
    }

    /**
     * F-214: Merge experience entries — update existing, insert new, delete removed.
     */
    public Mono<Void> mergeExperiences(Long profileId, List<ResumeProfileRequest.ExperienceEntry> incoming) {
        if (incoming == null) {
            return Mono.empty(); // null = field not sent, preserve existing data
        }
        if (incoming.isEmpty()) {
            return deleteByProfileId(profileId);
        }
        return experienceRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .collectMap(ResumeExperience::getId)
                .flatMap(existingMap -> {
                    var now = LocalDateTime.now();
                    Set<Long> keepIds = new HashSet<>();
                    List<ResumeExperience> toSave = new ArrayList<>();

                    for (int i = 0; i < incoming.size(); i++) {
                        var entry = incoming.get(i);
                        Long existingId = parseId(entry.getId());
                        int sortOrder = entry.getSortOrder() != null ? entry.getSortOrder() : i;
                        String bulletsJson = entry.getBullets() != null ? toJsonArray(entry.getBullets()) : "[]";

                        if (existingId != null && existingMap.containsKey(existingId)) {
                            var entity = existingMap.get(existingId);
                            entity.setCompany(entry.getCompany());
                            entity.setPosition(entry.getPosition());
                            entity.setStartDate(entry.getStartDate());
                            entity.setEndDate(entry.getEndDate());
                            entity.setBullets(bulletsJson);
                            entity.setSortOrder(sortOrder);
                            entity.setUpdatedAt(now);
                            entity.setNewRecord(false);
                            keepIds.add(existingId);
                            toSave.add(entity);
                        } else {
                            toSave.add(ResumeExperience.builder()
                                    .id(idService.nextId()).profileId(profileId)
                                    .company(entry.getCompany()).position(entry.getPosition())
                                    .startDate(entry.getStartDate()).endDate(entry.getEndDate())
                                    .bullets(bulletsJson).sortOrder(sortOrder)
                                    .createdAt(now).updatedAt(now).newRecord(true)
                                    .build());
                        }
                    }

                    List<Long> toDelete = existingMap.keySet().stream()
                            .filter(id -> !keepIds.contains(id)).toList();
                    Mono<Void> deleteMono = toDelete.isEmpty()
                            ? Mono.empty()
                            : experienceRepository.deleteAllById(toDelete).then();

                    return deleteMono.then(experienceRepository.saveAll(toSave).then());
                });
    }

    private static Long parseId(String id) {
        if (id == null || id.isBlank()) return null;
        try { return Long.parseLong(id); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Find all experience entries for a profile and map to response DTOs.
     */
    public Mono<List<ResumeProfileResponse.ExperienceResponse>> findByProfileId(Long profileId) {
        return experienceRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .map(e -> ResumeProfileResponse.ExperienceResponse.builder()
                        .id(String.valueOf(e.getId()))
                        .company(e.getCompany())
                        .position(e.getPosition())
                        .startDate(e.getStartDate())
                        .endDate(e.getEndDate())
                        .bullets(fromJsonArray(e.getBullets()))
                        .sortOrder(e.getSortOrder())
                        .build())
                .collectList()
                .map(list -> list.stream()
                        .sorted(ResumeDateSorter.experienceComparator(
                                ResumeProfileResponse.ExperienceResponse::getStartDate,
                                ResumeProfileResponse.ExperienceResponse::getEndDate))
                        .toList());
    }

    // ============================================
    // JSON UTILITIES (using Jackson ObjectMapper)
    // ============================================

    String toJsonArray(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize bullets to JSON, falling back to empty array: {}", e.getMessage());
            return "[]";
        }
    }

    List<String> fromJsonArray(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize bullets JSON, returning empty list: {}", e.getMessage());
            return List.of();
        }
    }
}
