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
import java.util.List;
import java.util.stream.IntStream;

/**
 * Service for managing resume work experience entries.
 */
// TODO F-211: Standardize error handling to use consistent exception types
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
