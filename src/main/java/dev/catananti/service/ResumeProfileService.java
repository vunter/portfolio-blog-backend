package dev.catananti.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.*;
import dev.catananti.util.DigestUtils;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Service for managing resume profile data (experiences, education, skills, etc.)
 * and generating HTML resumes from stored profile information.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeProfileService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private final ObjectMapper objectMapper;
    private final ResumeProfileRepository profileRepository;
    private final ResumeEducationService educationService;
    private final ResumeExperienceService experienceService;
    private final ResumeSkillService skillService;
    private final ResumeLanguageService languageService;
    private final ResumeCertificationService certificationService;
    private final ResumeAdditionalInfoRepository additionalInfoRepository;
    private final ResumeHomeCustomizationRepository homeCustomizationRepository;
    private final ResumeTestimonialRepository testimonialRepository;
    private final ResumeProficiencyRepository proficiencyRepository;
    private final ResumeProjectRepository projectRepository;
    private final ResumeLearningTopicRepository learningTopicRepository;
    private final ResumeHtmlRenderer htmlRenderer;
    private final IdService idService;
    private final org.springframework.r2dbc.core.DatabaseClient databaseClient;

    /**
     * Get the full resume profile for a user in a specific locale.
     * Returns empty Mono if profile does not exist for that locale.
     */
    public Mono<ResumeProfileResponse> getProfileByOwnerId(Long ownerId, String locale) {
        String resolvedLocale = normalizeLocale(locale);
        log.debug("Getting profile for ownerId={}, locale='{}'", ownerId, resolvedLocale);
        return profileRepository.findByOwnerIdAndLocale(ownerId, resolvedLocale)
                .flatMap(this::buildFullResponse);
    }

    /**
     * Get the full resume profile for a user in a specific locale.
     * Throws ResourceNotFoundException if not found.
     */
    public Mono<ResumeProfileResponse> getProfileByOwnerIdOrThrow(Long ownerId, String locale) {
        String resolvedLocale = normalizeLocale(locale);
        return profileRepository.findByOwnerIdAndLocale(ownerId, resolvedLocale)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(
                        "Resume profile not found for locale: " + resolvedLocale)))
                .flatMap(this::buildFullResponse);
    }

    /**
     * Get the full resume profile for a user, trying the given locale first,
     * then falling back by language prefix, then "en", then any. Throws if none found.
     * <p>
     * Fallback chain: exact locale → language prefix (e.g., "pt" matches "pt-br") → "en" → any.
     */
    public Mono<ResumeProfileResponse> getProfileByOwnerIdWithFallback(Long ownerId, String locale) {
        String resolvedLocale = normalizeLocale(locale);
        String langPrefix = resolvedLocale.contains("-") ? resolvedLocale.split("-")[0] : resolvedLocale;
        log.debug("Getting profile with fallback for ownerId={}, locale='{}', langPrefix='{}'", ownerId, resolvedLocale, langPrefix);
        return profileRepository.findByOwnerIdAndLocale(ownerId, resolvedLocale)
                // Try prefix match (e.g., "pt" matches "pt-br")
                .switchIfEmpty(Mono.defer(() -> profileRepository.findByOwnerIdAndLocalePrefix(ownerId, langPrefix)))
                .switchIfEmpty(Mono.defer(() -> profileRepository.findByOwnerIdAndLocale(ownerId, "en")))
                .switchIfEmpty(Mono.defer(() -> profileRepository.findByOwnerId(ownerId).next()))
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Resume profile not found")))
                .flatMap(this::buildFullResponse);
    }

    /**
     * Check if a profile exists for a user in any locale.
     */
    public Mono<Boolean> profileExists(Long ownerId) {
        return profileRepository.findByOwnerId(ownerId)
                .hasElements();
    }

    /**
     * List all available locales for a user's profile.
     */
    public Mono<List<String>> listProfileLocales(Long ownerId) {
        return profileRepository.findByOwnerId(ownerId)
                .map(ResumeProfile::getLocale)
                .collectList();
    }

    /**
     * Create or update the full resume profile for a user in a specific locale.
     */
    @Transactional
    public Mono<ResumeProfileResponse> saveProfile(Long ownerId, ResumeProfileRequest request, String locale) {
        String resolvedLocale = normalizeLocale(locale);
        log.info("Saving resume profile for ownerId={}, locale='{}'", ownerId, resolvedLocale);
        return profileRepository.findByOwnerIdAndLocale(ownerId, resolvedLocale)
                .flatMap(existing -> updateExistingProfile(existing, request))
                .switchIfEmpty(Mono.defer(() -> createNewProfile(ownerId, request, resolvedLocale)))
                .flatMap(this::buildFullResponse);
    }

    /**
     * Generate HTML resume from the stored profile, following the template structure.
     * Uses the lang parameter both for profile locale lookup (with fallback) and for section headers.
     * @param ownerId the profile owner's user ID
     * @param lang language/locale code (e.g., "en", "pt", "pt-br"). Defaults to "en".
     */
    public Mono<String> generateResumeHtml(Long ownerId, String lang) {
        String resolvedLang = normalizeLocale(lang);
        // For section headers, use the base language ("pt" for "pt-br", "en" for "en", etc.)
        String headerLang = resolvedLang.contains("-") ? resolvedLang.split("-")[0] : resolvedLang;
        log.info("Generating resume HTML for ownerId={}, lang='{}'", ownerId, resolvedLang);
        return getProfileByOwnerIdWithFallback(ownerId, resolvedLang)
                .map(profile -> htmlRenderer.renderHtml(profile, headerLang));
    }

    /**
     * Generate HTML resume from the stored profile with default language (English).
     */
    public Mono<String> generateResumeHtml(Long ownerId) {
        return generateResumeHtml(ownerId, "en");
    }

    // ============================================
    // PRIVATE HELPERS
    // ============================================

    private Mono<ResumeProfile> createNewProfile(Long ownerId, ResumeProfileRequest request, String locale) {
        var now = LocalDateTime.now();
        var profile = ResumeProfile.builder()
                .id(idService.nextId())
                .ownerId(ownerId)
                .locale(locale)
                .fullName(request.getFullName())
                .title(request.getTitle())
                .email(request.getEmail())
                .phone(request.getPhone())
                .linkedin(DigestUtils.sanitizeUrl(request.getLinkedin()))
                .github(DigestUtils.sanitizeUrl(request.getGithub()))
                .website(DigestUtils.sanitizeUrl(request.getWebsite()))
                .location(request.getLocation())
                .professionalSummary(request.getProfessionalSummary())
                .interests(request.getInterests())
                .workMode(request.getWorkMode())
                .timezone(request.getTimezone())
                .employmentType(request.getEmploymentType())
                .createdAt(now)
                .updatedAt(now)
                .newRecord(true)
                .build();

        return profileRepository.save(profile)
                .flatMap(saved -> saveChildEntities(saved.getId(), request).thenReturn(saved));
    }

    private Mono<ResumeProfile> updateExistingProfile(ResumeProfile existing, ResumeProfileRequest request) {
        existing.setFullName(request.getFullName());
        existing.setTitle(request.getTitle());
        existing.setEmail(request.getEmail());
        existing.setPhone(request.getPhone());
        existing.setLinkedin(DigestUtils.sanitizeUrl(request.getLinkedin()));
        existing.setGithub(DigestUtils.sanitizeUrl(request.getGithub()));
        existing.setWebsite(DigestUtils.sanitizeUrl(request.getWebsite()));
        existing.setLocation(request.getLocation());
        existing.setProfessionalSummary(request.getProfessionalSummary());
        existing.setInterests(request.getInterests());
        // Only update HC-managed fields if explicitly sent (null = skip)
        if (request.getWorkMode() != null) existing.setWorkMode(request.getWorkMode());
        if (request.getTimezone() != null) existing.setTimezone(request.getTimezone());
        if (request.getEmploymentType() != null) existing.setEmploymentType(request.getEmploymentType());
        existing.setUpdatedAt(LocalDateTime.now());
        existing.setNewRecord(false);

        // F-214: Use merge pattern instead of delete-all/recreate to preserve IDs and reduce DB operations
        return profileRepository.save(existing)
                .flatMap(saved -> mergeChildEntities(saved.getId(), request).thenReturn(saved));
    }

    /**
     * F-214: Merge child entities — update existing, insert new, delete removed.
     * Uses delegated service merge methods for the 5 service-managed types,
     * and inline merge logic for the 6 repository-managed types.
     */
    private Mono<Void> mergeChildEntities(Long profileId, ResumeProfileRequest request) {
        List<Mono<Void>> ops = new ArrayList<>();

        // Delegated services with merge support
        ops.add(educationService.mergeEducations(profileId, request.getEducations()));
        ops.add(experienceService.mergeExperiences(profileId, request.getExperiences()));
        ops.add(skillService.mergeSkills(profileId, request.getSkills()));
        ops.add(languageService.mergeLanguages(profileId, request.getLanguages()));
        ops.add(certificationService.mergeCertifications(profileId, request.getCertifications()));

        // Inline merge for the 6 repository-managed types
        ops.add(mergeAdditionalInfo(profileId, request.getAdditionalInfo()));
        ops.add(mergeHomeCustomization(profileId, request.getHomeCustomization()));
        ops.add(mergeTestimonials(profileId, request.getTestimonials()));
        ops.add(mergeProficiencies(profileId, request.getProficiencies()));
        ops.add(mergeProjects(profileId, request.getProjects()));
        ops.add(mergeLearningTopics(profileId, request.getLearningTopics()));

        return Mono.when(ops);
    }

    // ==================== F-214: Inline Merge Methods ====================

    /**
     * Per-entity update/build logic for {@link #mergeChildren}. Given an incoming
     * request entry, the matching existing entity (or {@code null} for an insert),
     * the resolved sortOrder and the shared timestamp, returns the entity to save.
     * For updates the caller mutates and returns {@code existing}; for inserts it
     * builds a fresh entity (with a new id and {@code newRecord(true)}).
     */
    @FunctionalInterface
    private interface EntryUpserter<E, T> {
        T upsert(E entry, T existing, int sortOrder, LocalDateTime now, Long profileId);
    }

    /**
     * F-214: Generic merge scaffold shared by the repository-managed child types.
     * Mirrors {@link #deleteAndSave}: null = skip, empty = delete-all, otherwise
     * load existing by id, iterate incoming (defaulting sortOrder to the index),
     * update-or-build via {@code upserter}, then delete removed + save kept/new.
     */
    private <E, T> Mono<Void> mergeChildren(
            Long profileId,
            List<E> incoming,
            Flux<T> existingFlux,
            Mono<Void> deleteAllMono,
            java.util.function.Function<T, Long> entityId,
            java.util.function.Function<E, String> entryId,
            java.util.function.Function<E, Integer> entrySortOrder,
            org.springframework.data.repository.reactive.ReactiveCrudRepository<T, Long> repo,
            EntryUpserter<E, T> upserter) {
        if (incoming == null) {
            return Mono.empty(); // null = field not sent, preserve existing data
        }
        if (incoming.isEmpty()) {
            return deleteAllMono;
        }
        return existingFlux
                .collectMap(entityId)
                .flatMap(existingMap -> {
                    var now = LocalDateTime.now();
                    Set<Long> keepIds = new HashSet<>();
                    List<T> toSave = new ArrayList<>();
                    for (int i = 0; i < incoming.size(); i++) {
                        var e = incoming.get(i);
                        Long eid = parseId(entryId.apply(e));
                        Integer rawSort = entrySortOrder.apply(e);
                        int sortOrder = rawSort != null ? rawSort : i;
                        if (eid != null && existingMap.containsKey(eid)) {
                            toSave.add(upserter.upsert(e, existingMap.get(eid), sortOrder, now, profileId));
                            keepIds.add(eid);
                        } else {
                            toSave.add(upserter.upsert(e, null, sortOrder, now, profileId));
                        }
                    }
                    return deleteAndSave(existingMap.keySet(), keepIds, repo, toSave);
                });
    }

    private Mono<Void> mergeAdditionalInfo(Long profileId, List<ResumeProfileRequest.AdditionalInfoEntry> incoming) {
        return mergeChildren(profileId, incoming,
                additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId),
                additionalInfoRepository.deleteByProfileId(profileId),
                ResumeAdditionalInfo::getId,
                ResumeProfileRequest.AdditionalInfoEntry::getId,
                ResumeProfileRequest.AdditionalInfoEntry::getSortOrder,
                additionalInfoRepository,
                (e, entity, sortOrder, now, pid) -> {
                    if (entity != null) {
                        entity.setLabel(e.getLabel()); entity.setContent(e.getContent());
                        entity.setSortOrder(sortOrder); entity.setUpdatedAt(now); entity.setNewRecord(false);
                        return entity;
                    }
                    return ResumeAdditionalInfo.builder().id(idService.nextId()).profileId(pid)
                            .label(e.getLabel()).content(e.getContent()).sortOrder(sortOrder)
                            .createdAt(now).updatedAt(now).newRecord(true).build();
                });
    }

    private Mono<Void> mergeHomeCustomization(Long profileId, List<ResumeProfileRequest.HomeCustomizationEntry> incoming) {
        return mergeChildren(profileId, incoming,
                homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId),
                homeCustomizationRepository.deleteByProfileId(profileId),
                ResumeHomeCustomization::getId,
                ResumeProfileRequest.HomeCustomizationEntry::getId,
                ResumeProfileRequest.HomeCustomizationEntry::getSortOrder,
                homeCustomizationRepository,
                (e, entity, sortOrder, now, pid) -> {
                    if (entity != null) {
                        entity.setLabel(e.getLabel()); entity.setContent(e.getContent());
                        entity.setSortOrder(sortOrder); entity.setUpdatedAt(now); entity.setNewRecord(false);
                        return entity;
                    }
                    return ResumeHomeCustomization.builder().id(idService.nextId()).profileId(pid)
                            .label(e.getLabel()).content(e.getContent()).sortOrder(sortOrder)
                            .createdAt(now).updatedAt(now).newRecord(true).build();
                });
    }

    private Mono<Void> mergeTestimonials(Long profileId, List<ResumeProfileRequest.TestimonialEntry> incoming) {
        return mergeChildren(profileId, incoming,
                testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId),
                testimonialRepository.deleteByProfileId(profileId),
                ResumeTestimonial::getId,
                ResumeProfileRequest.TestimonialEntry::getId,
                ResumeProfileRequest.TestimonialEntry::getSortOrder,
                testimonialRepository,
                (e, entity, sortOrder, now, pid) -> {
                    if (entity != null) {
                        entity.setAuthorName(e.getAuthorName()); entity.setAuthorRole(e.getAuthorRole());
                        entity.setAuthorCompany(e.getAuthorCompany()); entity.setAuthorImageUrl(DigestUtils.sanitizeUrl(e.getAuthorImageUrl()));
                        entity.setText(e.getText()); entity.setAccentColor(e.getAccentColor());
                        entity.setSortOrder(sortOrder); entity.setUpdatedAt(now); entity.setNewRecord(false);
                        return entity;
                    }
                    return ResumeTestimonial.builder().id(idService.nextId()).profileId(pid)
                            .authorName(e.getAuthorName()).authorRole(e.getAuthorRole())
                            .authorCompany(e.getAuthorCompany()).authorImageUrl(DigestUtils.sanitizeUrl(e.getAuthorImageUrl()))
                            .text(e.getText()).accentColor(e.getAccentColor()).sortOrder(sortOrder)
                            .createdAt(now).updatedAt(now).newRecord(true).build();
                });
    }

    private Mono<Void> mergeProficiencies(Long profileId, List<ResumeProfileRequest.ProficiencyEntry> incoming) {
        return mergeChildren(profileId, incoming,
                proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId),
                proficiencyRepository.deleteByProfileId(profileId),
                ResumeProficiency::getId,
                ResumeProfileRequest.ProficiencyEntry::getId,
                ResumeProfileRequest.ProficiencyEntry::getSortOrder,
                proficiencyRepository,
                (e, entity, sortOrder, now, pid) -> {
                    if (entity != null) {
                        entity.setCategory(e.getCategory()); entity.setSkillName(e.getSkillName());
                        entity.setPercentage(e.getPercentage()); entity.setIcon(e.getIcon());
                        entity.setSortOrder(sortOrder); entity.setUpdatedAt(now); entity.setNewRecord(false);
                        return entity;
                    }
                    return ResumeProficiency.builder().id(idService.nextId()).profileId(pid)
                            .category(e.getCategory()).skillName(e.getSkillName())
                            .percentage(e.getPercentage()).icon(e.getIcon()).sortOrder(sortOrder)
                            .createdAt(now).updatedAt(now).newRecord(true).build();
                });
    }

    private Mono<Void> mergeProjects(Long profileId, List<ResumeProfileRequest.ProjectEntry> incoming) {
        return mergeChildren(profileId, incoming,
                projectRepository.findByProfileIdOrderBySortOrderAsc(profileId),
                projectRepository.deleteByProfileId(profileId),
                ResumeProject::getId,
                ResumeProfileRequest.ProjectEntry::getId,
                ResumeProfileRequest.ProjectEntry::getSortOrder,
                projectRepository,
                (e, entity, sortOrder, now, pid) -> {
                    String tagsJson = e.getTechTags() != null ? toJsonArray(e.getTechTags()) : "[]";
                    if (entity != null) {
                        entity.setTitle(e.getTitle()); entity.setDescription(e.getDescription());
                        entity.setImageUrl(DigestUtils.sanitizeUrl(e.getImageUrl())); entity.setProjectUrl(DigestUtils.sanitizeUrl(e.getProjectUrl()));
                        entity.setRepoUrl(DigestUtils.sanitizeUrl(e.getRepoUrl())); entity.setTechTags(tagsJson);
                        entity.setFeatured(e.getFeatured());
                        entity.setSortOrder(sortOrder); entity.setUpdatedAt(now); entity.setNewRecord(false);
                        return entity;
                    }
                    return ResumeProject.builder().id(idService.nextId()).profileId(pid)
                            .title(e.getTitle()).description(e.getDescription())
                            .imageUrl(DigestUtils.sanitizeUrl(e.getImageUrl())).projectUrl(DigestUtils.sanitizeUrl(e.getProjectUrl()))
                            .repoUrl(DigestUtils.sanitizeUrl(e.getRepoUrl())).techTags(tagsJson).featured(e.getFeatured())
                            .sortOrder(sortOrder).createdAt(now).updatedAt(now).newRecord(true).build();
                });
    }

    private Mono<Void> mergeLearningTopics(Long profileId, List<ResumeProfileRequest.LearningTopicEntry> incoming) {
        return mergeChildren(profileId, incoming,
                learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId),
                learningTopicRepository.deleteByProfileId(profileId),
                ResumeLearningTopic::getId,
                ResumeProfileRequest.LearningTopicEntry::getId,
                ResumeProfileRequest.LearningTopicEntry::getSortOrder,
                learningTopicRepository,
                (e, entity, sortOrder, now, pid) -> {
                    if (entity != null) {
                        entity.setTitle(e.getTitle()); entity.setEmoji(e.getEmoji());
                        entity.setDescription(e.getDescription()); entity.setColorTheme(e.getColorTheme());
                        entity.setSortOrder(sortOrder); entity.setUpdatedAt(now); entity.setNewRecord(false);
                        return entity;
                    }
                    return ResumeLearningTopic.builder().id(idService.nextId()).profileId(pid)
                            .title(e.getTitle()).emoji(e.getEmoji()).description(e.getDescription())
                            .colorTheme(e.getColorTheme()).sortOrder(sortOrder)
                            .createdAt(now).updatedAt(now).newRecord(true).build();
                });
    }

    /**
     * Generic helper: delete removed entities and save updated/new entities.
     */
    @SuppressWarnings("unchecked")
    private <T> Mono<Void> deleteAndSave(Set<Long> existingIds, Set<Long> keepIds,
                                          org.springframework.data.repository.reactive.ReactiveCrudRepository<T, Long> repo,
                                          List<T> toSave) {
        List<Long> toDelete = existingIds.stream().filter(id -> !keepIds.contains(id)).toList();
        Mono<Void> deleteMono = toDelete.isEmpty() ? Mono.empty() : repo.deleteAllById(toDelete).then();
        return deleteMono.then(repo.saveAll(toSave).then());
    }

    private static Long parseId(String id) {
        if (id == null || id.isBlank()) return null;
        try { return Long.parseLong(id); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Hard-delete all resume child entries imported from LinkedIn (source='linkedin')
     * across all locales for the given user. Returns total rows deleted.
     * Satisfies GDPR Art. 17 right to erasure for LinkedIn-imported data.
     */
    @Transactional
    public Mono<Long> deleteLinkedInData(Long ownerId) {
        String[] tables = {
            "resume_educations", "resume_experiences", "resume_skills", "resume_languages",
            "resume_certifications", "resume_additional_info", "resume_home_customization",
            "resume_testimonials", "resume_proficiencies", "resume_projects", "resume_learning_topics"
        };
        return profileRepository.findByOwnerId(ownerId)
                .concatMap(profile -> Flux.fromArray(tables)
                        .concatMap(table -> databaseClient.sql(
                                "DELETE FROM " + table + " WHERE profile_id = :pid AND source = 'linkedin'")
                                .bind("pid", profile.getId())
                                .fetch()
                                .rowsUpdated()))
                .reduce(0L, Long::sum);
    }

    private Mono<Void> saveChildEntities(Long profileId, ResumeProfileRequest request) {
        var now = LocalDateTime.now();
        List<Mono<Void>> ops = new ArrayList<>();

        ops.add(educationService.saveEducations(profileId, request.getEducations()));
        ops.add(experienceService.saveExperiences(profileId, request.getExperiences()));
        ops.add(skillService.saveSkills(profileId, request.getSkills()));
        ops.add(languageService.saveLanguages(profileId, request.getLanguages()));
        ops.add(certificationService.saveCertifications(profileId, request.getCertifications()));

        if (request.getAdditionalInfo() != null) {
            var entities = IntStream.range(0, request.getAdditionalInfo().size()).mapToObj(i -> {
                var e = request.getAdditionalInfo().get(i);
                return ResumeAdditionalInfo.builder()
                        .id(idService.nextId())
                        .profileId(profileId)
                        .label(e.getLabel())
                        .content(e.getContent())
                        .sortOrder(e.getSortOrder() != null ? e.getSortOrder() : i)
                        .createdAt(now).updatedAt(now)
                        .newRecord(true)
                        .build();
            }).toList();
            ops.add(additionalInfoRepository.saveAll(entities).then());
        }

        if (request.getHomeCustomization() != null) {
            var entities = IntStream.range(0, request.getHomeCustomization().size()).mapToObj(i -> {
                var e = request.getHomeCustomization().get(i);
                return ResumeHomeCustomization.builder()
                        .id(idService.nextId())
                        .profileId(profileId)
                        .label(e.getLabel())
                        .content(e.getContent())
                        .sortOrder(e.getSortOrder() != null ? e.getSortOrder() : i)
                        .createdAt(now).updatedAt(now)
                        .newRecord(true)
                        .build();
            }).toList();
            ops.add(homeCustomizationRepository.saveAll(entities).then());
        }

        if (request.getTestimonials() != null) {
            var entities = IntStream.range(0, request.getTestimonials().size()).mapToObj(i -> {
                var e = request.getTestimonials().get(i);
                return ResumeTestimonial.builder()
                        .id(idService.nextId())
                        .profileId(profileId)
                        .authorName(e.getAuthorName())
                        .authorRole(e.getAuthorRole())
                        .authorCompany(e.getAuthorCompany())
                        .authorImageUrl(DigestUtils.sanitizeUrl(e.getAuthorImageUrl()))
                        .text(e.getText())
                        .accentColor(e.getAccentColor())
                        .sortOrder(e.getSortOrder() != null ? e.getSortOrder() : i)
                        .createdAt(now).updatedAt(now)
                        .newRecord(true)
                        .build();
            }).toList();
            ops.add(testimonialRepository.saveAll(entities).then());
        }

        if (request.getProficiencies() != null) {
            var entities = IntStream.range(0, request.getProficiencies().size()).mapToObj(i -> {
                var e = request.getProficiencies().get(i);
                return ResumeProficiency.builder()
                        .id(idService.nextId())
                        .profileId(profileId)
                        .category(e.getCategory())
                        .skillName(e.getSkillName())
                        .percentage(e.getPercentage())
                        .icon(e.getIcon())
                        .sortOrder(e.getSortOrder() != null ? e.getSortOrder() : i)
                        .createdAt(now).updatedAt(now)
                        .newRecord(true)
                        .build();
            }).toList();
            ops.add(proficiencyRepository.saveAll(entities).then());
        }

        if (request.getProjects() != null) {
            var entities = IntStream.range(0, request.getProjects().size()).mapToObj(i -> {
                var e = request.getProjects().get(i);
                String tagsJson = e.getTechTags() != null ? toJsonArray(e.getTechTags()) : "[]";
                return ResumeProject.builder()
                        .id(idService.nextId())
                        .profileId(profileId)
                        .title(e.getTitle())
                        .description(e.getDescription())
                        .imageUrl(DigestUtils.sanitizeUrl(e.getImageUrl()))
                        .projectUrl(DigestUtils.sanitizeUrl(e.getProjectUrl()))
                        .repoUrl(DigestUtils.sanitizeUrl(e.getRepoUrl()))
                        .techTags(tagsJson)
                        .featured(e.getFeatured())
                        .sortOrder(e.getSortOrder() != null ? e.getSortOrder() : i)
                        .createdAt(now).updatedAt(now)
                        .newRecord(true)
                        .build();
            }).toList();
            ops.add(projectRepository.saveAll(entities).then());
        }

        if (request.getLearningTopics() != null) {
            var entities = IntStream.range(0, request.getLearningTopics().size()).mapToObj(i -> {
                var e = request.getLearningTopics().get(i);
                return ResumeLearningTopic.builder()
                        .id(idService.nextId())
                        .profileId(profileId)
                        .title(e.getTitle())
                        .emoji(e.getEmoji())
                        .description(e.getDescription())
                        .colorTheme(e.getColorTheme())
                        .sortOrder(e.getSortOrder() != null ? e.getSortOrder() : i)
                        .createdAt(now).updatedAt(now)
                        .newRecord(true)
                        .build();
            }).toList();
            ops.add(learningTopicRepository.saveAll(entities).then());
        }

        return ops.isEmpty() ? Mono.empty() : Mono.when(ops);
    }

    /**
     * Loads all child entities for a profile and assembles the full response.
     * Queries are split into two sequential groups (max 6 concurrent connections each)
     * to avoid exhausting the R2DBC connection pool under concurrent resume requests.
     */
    private Mono<ResumeProfileResponse> buildFullResponse(ResumeProfile profile) {
        var pid = profile.getId();

        // Group 1: core resume sections (5 concurrent queries — safe within pool bounds)
        var group1 = Mono.zip(
                educationService.findByProfileId(pid),
                experienceService.findByProfileId(pid),
                skillService.findByProfileId(pid),
                languageService.findByProfileId(pid),
                certificationService.findByProfileId(pid)
        );

        // Group 2 runs AFTER group 1 completes — total peak is 6, not 11
        return group1.flatMap(t1 -> {
            var group2 = Mono.zip(
                    additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(pid)
                            .map(e -> ResumeProfileResponse.AdditionalInfoResponse.builder()
                                    .id(String.valueOf(e.getId()))
                                    .label(e.getLabel())
                                    .content(e.getContent())
                                    .sortOrder(e.getSortOrder())
                                    .build())
                            .collectList(),
                    homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(pid)
                            .map(e -> ResumeProfileResponse.HomeCustomizationResponse.builder()
                                    .id(String.valueOf(e.getId()))
                                    .label(e.getLabel())
                                    .content(e.getContent())
                                    .sortOrder(e.getSortOrder())
                                    .build())
                            .collectList(),
                    testimonialRepository.findByProfileIdOrderBySortOrderAsc(pid)
                            .map(e -> ResumeProfileResponse.TestimonialResponse.builder()
                                    .id(String.valueOf(e.getId()))
                                    .authorName(e.getAuthorName())
                                    .authorRole(e.getAuthorRole())
                                    .authorCompany(e.getAuthorCompany())
                                    .authorImageUrl(e.getAuthorImageUrl())
                                    .text(e.getText())
                                    .accentColor(e.getAccentColor())
                                    .sortOrder(e.getSortOrder())
                                    .build())
                            .collectList(),
                    proficiencyRepository.findByProfileIdOrderBySortOrderAsc(pid)
                            .map(e -> ResumeProfileResponse.ProficiencyResponse.builder()
                                    .id(String.valueOf(e.getId()))
                                    .category(e.getCategory())
                                    .skillName(e.getSkillName())
                                    .percentage(e.getPercentage())
                                    .icon(e.getIcon())
                                    .sortOrder(e.getSortOrder())
                                    .build())
                            .collectList(),
                    projectRepository.findByProfileIdOrderBySortOrderAsc(pid)
                            .map(e -> ResumeProfileResponse.ProjectResponse.builder()
                                    .id(String.valueOf(e.getId()))
                                    .title(e.getTitle())
                                    .description(e.getDescription())
                                    .imageUrl(e.getImageUrl())
                                    .projectUrl(e.getProjectUrl())
                                    .repoUrl(e.getRepoUrl())
                                    .techTags(fromJsonArray(e.getTechTags()))
                                    .featured(e.getFeatured())
                                    .sortOrder(e.getSortOrder())
                                    .build())
                            .collectList(),
                    learningTopicRepository.findByProfileIdOrderBySortOrderAsc(pid)
                            .map(e -> ResumeProfileResponse.LearningTopicResponse.builder()
                                    .id(String.valueOf(e.getId()))
                                    .title(e.getTitle())
                                    .emoji(e.getEmoji())
                                    .description(e.getDescription())
                                    .colorTheme(e.getColorTheme())
                                    .sortOrder(e.getSortOrder())
                                    .build())
                            .collectList()
            );

            return group2.map(t2 -> ResumeProfileResponse.builder()
                    .id(String.valueOf(profile.getId()))
                    .ownerId(String.valueOf(profile.getOwnerId()))
                    .locale(profile.getLocale())
                    .fullName(profile.getFullName())
                    .title(profile.getTitle())
                    .email(profile.getEmail())
                    .phone(profile.getPhone())
                    .linkedin(profile.getLinkedin())
                    .github(profile.getGithub())
                    .website(profile.getWebsite())
                    .location(profile.getLocation())
                    .professionalSummary(profile.getProfessionalSummary())
                    .interests(profile.getInterests())
                    .workMode(profile.getWorkMode())
                    .timezone(profile.getTimezone())
                    .employmentType(profile.getEmploymentType())
                    .createdAt(profile.getCreatedAt())
                    .updatedAt(profile.getUpdatedAt())
                    .educations(t1.getT1())
                    .experiences(t1.getT2())
                    .skills(t1.getT3())
                    .languages(t1.getT4())
                    .certifications(t1.getT5())
                    .additionalInfo(t2.getT1())
                    .homeCustomization(t2.getT2())
                    .testimonials(t2.getT3())
                    .proficiencies(t2.getT4())
                    .projects(t2.getT5())
                    .learningTopics(t2.getT6())
                    .build());
        });
    }

    // ============================================
    // UTILITIES
    // ============================================

    private String toJsonArray(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize list to JSON", e);
        }
    }

    private List<String> fromJsonArray(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON array '{}', returning empty list: {}", json, e.getMessage());
            return List.of();
        }
    }

    /**
     * Normalize locale to a simple lowercase code (e.g. "PT-BR" → "pt-br", "EN" → "en").
     * Defaults to "en" if null or blank.
     */
    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) return "en";
        return locale.toLowerCase().trim();
    }
}
