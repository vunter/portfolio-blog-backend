package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.ResumeSkill;
import dev.catananti.repository.ResumeSkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Service for managing resume skill entries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeSkillService {

    private final ResumeSkillRepository skillRepository;
    private final IdService idService;

    /**
     * Save skill entries for a profile.
     */
    public Mono<Void> saveSkills(Long profileId, List<ResumeProfileRequest.SkillEntry> skills) {
        if (skills == null || skills.isEmpty()) {
            log.debug("No skills to save for profileId={}", profileId);
            return Mono.empty();
        }
        log.info("Saving {} skill entries for profileId={}", skills.size(), profileId);
        var now = LocalDateTime.now();
        var entities = IntStream.range(0, skills.size()).mapToObj(i -> {
            var e = skills.get(i);
            return ResumeSkill.builder()
                    .id(idService.nextId())
                    .profileId(profileId)
                    .category(e.getCategory())
                    .content(e.getContent())
                    .sortOrder(e.getSortOrder() != null ? e.getSortOrder() : i)
                    .createdAt(now).updatedAt(now)
                    .newRecord(true)
                    .build();
        }).toList();
        return skillRepository.saveAll(entities).then();
    }

    /**
     * Delete all skill entries for a profile.
     */
    public Mono<Void> deleteByProfileId(Long profileId) {
        log.info("Deleting all skill entries for profileId={}", profileId);
        return skillRepository.deleteByProfileId(profileId);
    }

    /**
     * Find all skill entries for a profile and map to response DTOs.
     */
    public Mono<List<ResumeProfileResponse.SkillResponse>> findByProfileId(Long profileId) {
        log.debug("Finding skill entries for profileId={}", profileId);
        return skillRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .map(e -> ResumeProfileResponse.SkillResponse.builder()
                        .id(String.valueOf(e.getId()))
                        .category(e.getCategory())
                        .content(e.getContent())
                        .sortOrder(e.getSortOrder())
                        .build())
                .collectList();
    }
}
