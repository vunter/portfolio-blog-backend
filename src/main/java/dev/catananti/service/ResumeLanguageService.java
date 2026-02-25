package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.ResumeLanguage;
import dev.catananti.repository.ResumeLanguageRepository;
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
 * Service for managing resume language entries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeLanguageService {

    private final ResumeLanguageRepository languageRepository;
    private final IdService idService;

    /**
     * Save language entries for a profile.
     */
    public Mono<Void> saveLanguages(Long profileId, List<ResumeProfileRequest.LanguageEntry> languages) {
        if (languages == null || languages.isEmpty()) {
            log.debug("No languages to save for profileId={}", profileId);
            return Mono.empty();
        }
        log.info("Saving {} language entries for profileId={}", languages.size(), profileId);
        var now = LocalDateTime.now();
        var entities = IntStream.range(0, languages.size()).mapToObj(i -> {
            var e = languages.get(i);
            return ResumeLanguage.builder()
                    .id(idService.nextId())
                    .profileId(profileId)
                    .name(e.getName())
                    .proficiency(e.getProficiency())
                    .sortOrder(e.getSortOrder() != null ? e.getSortOrder() : i)
                    .createdAt(now).updatedAt(now)
                    .newRecord(true)
                    .build();
        }).toList();
        return languageRepository.saveAll(entities).then();
    }

    /**
     * Delete all language entries for a profile.
     */
    public Mono<Void> deleteByProfileId(Long profileId) {
        log.info("Deleting all language entries for profileId={}", profileId);
        return languageRepository.deleteByProfileId(profileId);
    }

    /**
     * F-214: Merge language entries — update existing, insert new, delete removed.
     */
    public Mono<Void> mergeLanguages(Long profileId, List<ResumeProfileRequest.LanguageEntry> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return deleteByProfileId(profileId);
        }
        return languageRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .collectMap(ResumeLanguage::getId)
                .flatMap(existingMap -> {
                    var now = LocalDateTime.now();
                    Set<Long> keepIds = new HashSet<>();
                    List<ResumeLanguage> toSave = new ArrayList<>();

                    for (int i = 0; i < incoming.size(); i++) {
                        var entry = incoming.get(i);
                        Long existingId = parseId(entry.getId());
                        int sortOrder = entry.getSortOrder() != null ? entry.getSortOrder() : i;

                        if (existingId != null && existingMap.containsKey(existingId)) {
                            var entity = existingMap.get(existingId);
                            entity.setName(entry.getName());
                            entity.setProficiency(entry.getProficiency());
                            entity.setSortOrder(sortOrder);
                            entity.setUpdatedAt(now);
                            entity.setNewRecord(false);
                            keepIds.add(existingId);
                            toSave.add(entity);
                        } else {
                            toSave.add(ResumeLanguage.builder()
                                    .id(idService.nextId()).profileId(profileId)
                                    .name(entry.getName()).proficiency(entry.getProficiency())
                                    .sortOrder(sortOrder)
                                    .createdAt(now).updatedAt(now).newRecord(true)
                                    .build());
                        }
                    }

                    List<Long> toDelete = existingMap.keySet().stream()
                            .filter(id -> !keepIds.contains(id)).toList();
                    Mono<Void> deleteMono = toDelete.isEmpty()
                            ? Mono.empty()
                            : languageRepository.deleteAllById(toDelete).then();

                    return deleteMono.then(languageRepository.saveAll(toSave).then());
                });
    }

    private static Long parseId(String id) {
        if (id == null || id.isBlank()) return null;
        try { return Long.parseLong(id); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Find all language entries for a profile and map to response DTOs.
     */
    public Mono<List<ResumeProfileResponse.LanguageResponse>> findByProfileId(Long profileId) {
        log.debug("Finding language entries for profileId={}", profileId);
        return languageRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .map(e -> ResumeProfileResponse.LanguageResponse.builder()
                        .id(String.valueOf(e.getId()))
                        .name(e.getName())
                        .proficiency(e.getProficiency())
                        .sortOrder(e.getSortOrder())
                        .build())
                .collectList();
    }
}
