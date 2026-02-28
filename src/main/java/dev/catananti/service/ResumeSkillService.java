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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
     * F-214: Merge skill entries — update existing, insert new, delete removed.
     */
    public Mono<Void> mergeSkills(Long profileId, List<ResumeProfileRequest.SkillEntry> incoming) {
        if (incoming == null) {
            return Mono.empty(); // null = field not sent, preserve existing data
        }
        if (incoming.isEmpty()) {
            return deleteByProfileId(profileId);
        }
        return skillRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .collectMap(ResumeSkill::getId)
                .flatMap(existingMap -> {
                    var now = LocalDateTime.now();
                    Set<Long> keepIds = new HashSet<>();
                    List<ResumeSkill> toSave = new ArrayList<>();

                    for (int i = 0; i < incoming.size(); i++) {
                        var entry = incoming.get(i);
                        Long existingId = parseId(entry.getId());
                        int sortOrder = entry.getSortOrder() != null ? entry.getSortOrder() : i;

                        if (existingId != null && existingMap.containsKey(existingId)) {
                            var entity = existingMap.get(existingId);
                            entity.setCategory(entry.getCategory());
                            entity.setContent(entry.getContent());
                            entity.setSortOrder(sortOrder);
                            entity.setUpdatedAt(now);
                            entity.setNewRecord(false);
                            keepIds.add(existingId);
                            toSave.add(entity);
                        } else {
                            toSave.add(ResumeSkill.builder()
                                    .id(idService.nextId()).profileId(profileId)
                                    .category(entry.getCategory()).content(entry.getContent())
                                    .sortOrder(sortOrder)
                                    .createdAt(now).updatedAt(now).newRecord(true)
                                    .build());
                        }
                    }

                    List<Long> toDelete = existingMap.keySet().stream()
                            .filter(id -> !keepIds.contains(id)).toList();
                    Mono<Void> deleteMono = toDelete.isEmpty()
                            ? Mono.empty()
                            : skillRepository.deleteAllById(toDelete).then();

                    return deleteMono.then(skillRepository.saveAll(toSave).then());
                });
    }

    private static Long parseId(String id) {
        if (id == null || id.isBlank()) return null;
        try { return Long.parseLong(id); } catch (NumberFormatException e) { return null; }
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
