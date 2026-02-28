package dev.catananti.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.*;
import dev.catananti.util.DigestUtils;
import dev.catananti.util.HtmlUtils;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
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
    private final HtmlSanitizerService htmlSanitizer;
    private final IdService idService;

    // Inline SVG icons for PDF contact info (no external font dependency)
    private static final String ICON_EMAIL = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\" viewBox=\"0 0 512 512\" style=\"vertical-align:-1px\"><path fill=\"#666\" d=\"M48 64C21.5 64 0 85.5 0 112c0 15.1 7.1 29.3 19.2 38.4L236.8 313.6c11.4 8.5 27 8.5 38.4 0L492.8 150.4c12.1-9.1 19.2-23.3 19.2-38.4c0-26.5-21.5-48-48-48H48zM0 176V384c0 35.3 28.7 64 64 64H448c35.3 0 64-28.7 64-64V176L294.4 339.2c-22.8 17.1-54 17.1-76.8 0L0 176z\"/></svg>";
    private static final String ICON_LINKEDIN = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\" viewBox=\"0 0 448 512\" style=\"vertical-align:-1px\"><path fill=\"#666\" d=\"M416 32H31.9C14.3 32 0 46.5 0 64.3v383.4C0 465.5 14.3 480 31.9 480H416c17.6 0 32-14.5 32-32.3V64.3c0-17.8-14.4-32.3-32-32.3zM135.4 416H69V202.2h66.5V416zm-33.2-243c-21.3 0-38.5-17.3-38.5-38.5S80.9 96 102.2 96c21.2 0 38.5 17.3 38.5 38.5 0 21.3-17.2 38.5-38.5 38.5zm282.1 243h-66.4V312c0-24.8-.5-56.7-34.5-56.7-34.6 0-39.9 27-39.9 54.9V416h-66.4V202.2h63.7v29.2h.9c8.9-16.8 30.6-34.5 62.9-34.5 67.2 0 79.7 44.3 79.7 101.9V416z\"/></svg>";
    private static final String ICON_GITHUB = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\" viewBox=\"0 0 496 512\" style=\"vertical-align:-1px\"><path fill=\"#666\" d=\"M165.9 397.4c0 2-2.3 3.6-5.2 3.6-3.3.3-5.6-1.3-5.6-3.6 0-2 2.3-3.6 5.2-3.6 3-.3 5.6 1.3 5.6 3.6zm-31.1-4.5c-.7 2 1.3 4.3 4.3 4.9 2.6 1 5.6 0 6.2-2s-1.3-4.3-4.3-5.2c-2.6-.7-5.5.3-6.2 2.3zm44.2-1.7c-2.9.7-4.9 2.6-4.6 4.9.3 2 2.9 3.3 5.9 2.6 2.9-.7 4.9-2.6 4.6-4.6-.3-1.9-3-3.2-5.9-2.9zM244.8 8C106.1 8 0 113.3 0 252c0 110.9 69.8 205.8 169.5 239.2 12.8 2.3 17.3-5.6 17.3-12.1 0-6.2-.3-40.4-.3-61.4 0 0-70 15-84.7-29.8 0 0-11.4-29.1-27.8-36.6 0 0-22.9-15.7 1.6-15.4 0 0 24.9 2 38.6 25.8 21.9 38.6 58.6 27.5 72.9 20.9 2.3-16 8.8-27.1 16-33.7-55.9-6.2-112.8-14.9-112.8-110.5 0-27.5 7.6-41.3 23.6-58.9-2.6-6.5-11.1-33.3 2.6-67.9 20.9-6.5 69 27 69 27 20-5.6 41.5-8.5 62.8-8.5s42.8 2.9 62.8 8.5c0 0 48.1-33.6 69-27 13.7 34.7 5.2 61.4 2.6 67.9 16 17.7 25.8 31.5 25.8 58.9 0 96.5-58.9 104.2-114.8 110.5 9.2 7.9 17 22.9 17 46.4 0 33.7-.3 75.4-.3 83.6 0 6.5 4.6 14.4 17.3 12.1C428.2 457.8 496 362.9 496 252 496 113.3 383.5 8 244.8 8z\"/></svg>";
    private static final String ICON_GLOBE = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\" viewBox=\"0 0 512 512\" style=\"vertical-align:-1px\"><path fill=\"#666\" d=\"M352 256c0 22.2-1.2 43.6-3.3 64H163.3c-2.2-20.4-3.3-41.8-3.3-64s1.2-43.6 3.3-64H348.7c2.2 20.4 3.3 41.8 3.3 64zm28.8-64H503.9c5.3 20.5 8.1 41.9 8.1 64s-2.8 43.5-8.1 64H380.8c2.1-20.6 3.2-42 3.2-64s-1.1-43.4-3.2-64zm112.6-32H376.7c-10-63.9-29.8-117.4-55.3-151.6c78.3 20.7 142 77.5 171.9 151.6zm-149.1 0H167.7c6.1-36.4 15.5-68.6 27-94.7 10.5-23.6 22.2-40.7 33.5-51.5C239.4 3.2 248.7 0 256 0s16.6 3.2 27.8 13.8c11.3 10.8 23 27.9 33.5 51.5 11.5 26 21 58.2 27 94.7zm-209.3 0H18.6C48.6 85.9 112.2 29.1 190.6 8.4C165.1 42.6 145.3 96.1 135.3 160zM8.1 192H131.2c-2.1 20.6-3.2 42-3.2 64s1.1 43.4 3.2 64H8.1C2.8 299.5 0 278.1 0 256s2.8-43.5 8.1-64zM194.7 446.6c-11.5-26-20.9-58.2-27-94.6H344.3c-6.1 36.4-15.5 68.6-27 94.6-10.5 23.6-22.2 40.7-33.5 51.5C272.6 508.8 263.3 512 256 512s-16.6-3.2-27.8-13.8c-11.3-10.8-23-27.9-33.5-51.5zM135.3 352c10 63.9 29.8 117.4 55.3 151.6C112.2 482.9 48.6 426.1 18.6 352H135.3zm245.1 0H493.4c-29.9 74.1-93.6 130.9-171.9 151.6 25.5-34.2 45.2-87.7 55.3-151.6z\"/></svg>";
    private static final String ICON_LOCATION = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\" viewBox=\"0 0 384 512\" style=\"vertical-align:-1px\"><path fill=\"#666\" d=\"M215.7 499.2C267 435 384 279.4 384 192C384 86 298 0 192 0S0 86 0 192c0 87.4 117 243 168.3 307.2c12.3 15.3 35.1 15.3 47.4 0zM192 128a64 64 0 1 1 0 128 64 64 0 1 1 0-128z\"/></svg>";
    private static final String ICON_PHONE = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\" viewBox=\"0 0 512 512\" style=\"vertical-align:-1px\"><path fill=\"#666\" d=\"M164.9 24.6c-7.7-18.6-28-28.5-47.4-23.2l-88 24C12.1 30.2 0 46 0 64C0 311.4 200.6 512 448 512c18 0 33.8-12.1 38.6-29.5l24-88c5.3-19.4-4.6-39.7-23.2-47.4l-96-40c-16.3-6.8-35.2-2.1-46.3 11.6L304.7 368C234.3 334.7 177.3 277.7 144 207.3L193.3 167c13.7-11.2 18.4-30 11.6-46.3l-40-96z\"/></svg>";

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
                .map(profile -> renderHtml(profile, headerLang));
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

    private Mono<Void> mergeAdditionalInfo(Long profileId, List<ResumeProfileRequest.AdditionalInfoEntry> incoming) {
        if (incoming == null) {
            return Mono.empty(); // null = field not sent, preserve existing data
        }
        if (incoming.isEmpty()) {
            return additionalInfoRepository.deleteByProfileId(profileId);
        }
        return additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .collectMap(ResumeAdditionalInfo::getId)
                .flatMap(existingMap -> {
                    var now = LocalDateTime.now();
                    Set<Long> keepIds = new HashSet<>();
                    List<ResumeAdditionalInfo> toSave = new ArrayList<>();
                    for (int i = 0; i < incoming.size(); i++) {
                        var e = incoming.get(i);
                        Long eid = parseId(e.getId());
                        int sortOrder = e.getSortOrder() != null ? e.getSortOrder() : i;
                        if (eid != null && existingMap.containsKey(eid)) {
                            var entity = existingMap.get(eid);
                            entity.setLabel(e.getLabel()); entity.setContent(e.getContent());
                            entity.setSortOrder(sortOrder); entity.setUpdatedAt(now); entity.setNewRecord(false);
                            keepIds.add(eid); toSave.add(entity);
                        } else {
                            toSave.add(ResumeAdditionalInfo.builder().id(idService.nextId()).profileId(profileId)
                                    .label(e.getLabel()).content(e.getContent()).sortOrder(sortOrder)
                                    .createdAt(now).updatedAt(now).newRecord(true).build());
                        }
                    }
                    return deleteAndSave(existingMap.keySet(), keepIds, additionalInfoRepository, toSave);
                });
    }

    private Mono<Void> mergeHomeCustomization(Long profileId, List<ResumeProfileRequest.HomeCustomizationEntry> incoming) {
        if (incoming == null) {
            return Mono.empty(); // null = field not sent, preserve existing data
        }
        if (incoming.isEmpty()) {
            return homeCustomizationRepository.deleteByProfileId(profileId);
        }
        return homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .collectMap(ResumeHomeCustomization::getId)
                .flatMap(existingMap -> {
                    var now = LocalDateTime.now();
                    Set<Long> keepIds = new HashSet<>();
                    List<ResumeHomeCustomization> toSave = new ArrayList<>();
                    for (int i = 0; i < incoming.size(); i++) {
                        var e = incoming.get(i);
                        Long eid = parseId(e.getId());
                        int sortOrder = e.getSortOrder() != null ? e.getSortOrder() : i;
                        if (eid != null && existingMap.containsKey(eid)) {
                            var entity = existingMap.get(eid);
                            entity.setLabel(e.getLabel()); entity.setContent(e.getContent());
                            entity.setSortOrder(sortOrder); entity.setUpdatedAt(now); entity.setNewRecord(false);
                            keepIds.add(eid); toSave.add(entity);
                        } else {
                            toSave.add(ResumeHomeCustomization.builder().id(idService.nextId()).profileId(profileId)
                                    .label(e.getLabel()).content(e.getContent()).sortOrder(sortOrder)
                                    .createdAt(now).updatedAt(now).newRecord(true).build());
                        }
                    }
                    return deleteAndSave(existingMap.keySet(), keepIds, homeCustomizationRepository, toSave);
                });
    }

    private Mono<Void> mergeTestimonials(Long profileId, List<ResumeProfileRequest.TestimonialEntry> incoming) {
        if (incoming == null) {
            return Mono.empty(); // null = field not sent, preserve existing data
        }
        if (incoming.isEmpty()) {
            return testimonialRepository.deleteByProfileId(profileId);
        }
        return testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .collectMap(ResumeTestimonial::getId)
                .flatMap(existingMap -> {
                    var now = LocalDateTime.now();
                    Set<Long> keepIds = new HashSet<>();
                    List<ResumeTestimonial> toSave = new ArrayList<>();
                    for (int i = 0; i < incoming.size(); i++) {
                        var e = incoming.get(i);
                        Long eid = parseId(e.getId());
                        int sortOrder = e.getSortOrder() != null ? e.getSortOrder() : i;
                        if (eid != null && existingMap.containsKey(eid)) {
                            var entity = existingMap.get(eid);
                            entity.setAuthorName(e.getAuthorName()); entity.setAuthorRole(e.getAuthorRole());
                            entity.setAuthorCompany(e.getAuthorCompany()); entity.setAuthorImageUrl(DigestUtils.sanitizeUrl(e.getAuthorImageUrl()));
                            entity.setText(e.getText()); entity.setAccentColor(e.getAccentColor());
                            entity.setSortOrder(sortOrder); entity.setUpdatedAt(now); entity.setNewRecord(false);
                            keepIds.add(eid); toSave.add(entity);
                        } else {
                            toSave.add(ResumeTestimonial.builder().id(idService.nextId()).profileId(profileId)
                                    .authorName(e.getAuthorName()).authorRole(e.getAuthorRole())
                                    .authorCompany(e.getAuthorCompany()).authorImageUrl(DigestUtils.sanitizeUrl(e.getAuthorImageUrl()))
                                    .text(e.getText()).accentColor(e.getAccentColor()).sortOrder(sortOrder)
                                    .createdAt(now).updatedAt(now).newRecord(true).build());
                        }
                    }
                    return deleteAndSave(existingMap.keySet(), keepIds, testimonialRepository, toSave);
                });
    }

    private Mono<Void> mergeProficiencies(Long profileId, List<ResumeProfileRequest.ProficiencyEntry> incoming) {
        if (incoming == null) {
            return Mono.empty(); // null = field not sent, preserve existing data
        }
        if (incoming.isEmpty()) {
            return proficiencyRepository.deleteByProfileId(profileId);
        }
        return proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .collectMap(ResumeProficiency::getId)
                .flatMap(existingMap -> {
                    var now = LocalDateTime.now();
                    Set<Long> keepIds = new HashSet<>();
                    List<ResumeProficiency> toSave = new ArrayList<>();
                    for (int i = 0; i < incoming.size(); i++) {
                        var e = incoming.get(i);
                        Long eid = parseId(e.getId());
                        int sortOrder = e.getSortOrder() != null ? e.getSortOrder() : i;
                        if (eid != null && existingMap.containsKey(eid)) {
                            var entity = existingMap.get(eid);
                            entity.setCategory(e.getCategory()); entity.setSkillName(e.getSkillName());
                            entity.setPercentage(e.getPercentage()); entity.setIcon(e.getIcon());
                            entity.setSortOrder(sortOrder); entity.setUpdatedAt(now); entity.setNewRecord(false);
                            keepIds.add(eid); toSave.add(entity);
                        } else {
                            toSave.add(ResumeProficiency.builder().id(idService.nextId()).profileId(profileId)
                                    .category(e.getCategory()).skillName(e.getSkillName())
                                    .percentage(e.getPercentage()).icon(e.getIcon()).sortOrder(sortOrder)
                                    .createdAt(now).updatedAt(now).newRecord(true).build());
                        }
                    }
                    return deleteAndSave(existingMap.keySet(), keepIds, proficiencyRepository, toSave);
                });
    }

    private Mono<Void> mergeProjects(Long profileId, List<ResumeProfileRequest.ProjectEntry> incoming) {
        if (incoming == null) {
            return Mono.empty(); // null = field not sent, preserve existing data
        }
        if (incoming.isEmpty()) {
            return projectRepository.deleteByProfileId(profileId);
        }
        return projectRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .collectMap(ResumeProject::getId)
                .flatMap(existingMap -> {
                    var now = LocalDateTime.now();
                    Set<Long> keepIds = new HashSet<>();
                    List<ResumeProject> toSave = new ArrayList<>();
                    for (int i = 0; i < incoming.size(); i++) {
                        var e = incoming.get(i);
                        Long eid = parseId(e.getId());
                        int sortOrder = e.getSortOrder() != null ? e.getSortOrder() : i;
                        String tagsJson = e.getTechTags() != null ? toJsonArray(e.getTechTags()) : "[]";
                        if (eid != null && existingMap.containsKey(eid)) {
                            var entity = existingMap.get(eid);
                            entity.setTitle(e.getTitle()); entity.setDescription(e.getDescription());
                            entity.setImageUrl(DigestUtils.sanitizeUrl(e.getImageUrl())); entity.setProjectUrl(DigestUtils.sanitizeUrl(e.getProjectUrl()));
                            entity.setRepoUrl(DigestUtils.sanitizeUrl(e.getRepoUrl())); entity.setTechTags(tagsJson);
                            entity.setFeatured(e.getFeatured());
                            entity.setSortOrder(sortOrder); entity.setUpdatedAt(now); entity.setNewRecord(false);
                            keepIds.add(eid); toSave.add(entity);
                        } else {
                            toSave.add(ResumeProject.builder().id(idService.nextId()).profileId(profileId)
                                    .title(e.getTitle()).description(e.getDescription())
                                    .imageUrl(DigestUtils.sanitizeUrl(e.getImageUrl())).projectUrl(DigestUtils.sanitizeUrl(e.getProjectUrl()))
                                    .repoUrl(DigestUtils.sanitizeUrl(e.getRepoUrl())).techTags(tagsJson).featured(e.getFeatured())
                                    .sortOrder(sortOrder).createdAt(now).updatedAt(now).newRecord(true).build());
                        }
                    }
                    return deleteAndSave(existingMap.keySet(), keepIds, projectRepository, toSave);
                });
    }

    private Mono<Void> mergeLearningTopics(Long profileId, List<ResumeProfileRequest.LearningTopicEntry> incoming) {
        if (incoming == null) {
            return Mono.empty(); // null = field not sent, preserve existing data
        }
        if (incoming.isEmpty()) {
            return learningTopicRepository.deleteByProfileId(profileId);
        }
        return learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)
                .collectMap(ResumeLearningTopic::getId)
                .flatMap(existingMap -> {
                    var now = LocalDateTime.now();
                    Set<Long> keepIds = new HashSet<>();
                    List<ResumeLearningTopic> toSave = new ArrayList<>();
                    for (int i = 0; i < incoming.size(); i++) {
                        var e = incoming.get(i);
                        Long eid = parseId(e.getId());
                        int sortOrder = e.getSortOrder() != null ? e.getSortOrder() : i;
                        if (eid != null && existingMap.containsKey(eid)) {
                            var entity = existingMap.get(eid);
                            entity.setTitle(e.getTitle()); entity.setEmoji(e.getEmoji());
                            entity.setDescription(e.getDescription()); entity.setColorTheme(e.getColorTheme());
                            entity.setSortOrder(sortOrder); entity.setUpdatedAt(now); entity.setNewRecord(false);
                            keepIds.add(eid); toSave.add(entity);
                        } else {
                            toSave.add(ResumeLearningTopic.builder().id(idService.nextId()).profileId(profileId)
                                    .title(e.getTitle()).emoji(e.getEmoji()).description(e.getDescription())
                                    .colorTheme(e.getColorTheme()).sortOrder(sortOrder)
                                    .createdAt(now).updatedAt(now).newRecord(true).build());
                        }
                    }
                    return deleteAndSave(existingMap.keySet(), keepIds, learningTopicRepository, toSave);
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

    // F-214: Upsert/merge pattern applied — see merge*() methods in child services
    private Mono<Void> deleteChildEntities(Long profileId) {
        return Mono.when(
                educationService.deleteByProfileId(profileId),
                experienceService.deleteByProfileId(profileId),
                skillService.deleteByProfileId(profileId),
                languageService.deleteByProfileId(profileId),
                certificationService.deleteByProfileId(profileId),
                additionalInfoRepository.deleteByProfileId(profileId),
                homeCustomizationRepository.deleteByProfileId(profileId),
                testimonialRepository.deleteByProfileId(profileId),
                proficiencyRepository.deleteByProfileId(profileId),
                projectRepository.deleteByProfileId(profileId),
                learningTopicRepository.deleteByProfileId(profileId)
        );
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

    private Mono<ResumeProfileResponse> buildFullResponse(ResumeProfile profile) {
        var educations = educationService.findByProfileId(profile.getId());
        var experiences = experienceService.findByProfileId(profile.getId());
        var skills = skillService.findByProfileId(profile.getId());

        var languages = languageService.findByProfileId(profile.getId());

        var certifications = certificationService.findByProfileId(profile.getId());

        var additional = additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profile.getId())
                .map(e -> ResumeProfileResponse.AdditionalInfoResponse.builder()
                        .id(String.valueOf(e.getId()))
                        .label(e.getLabel())
                        .content(e.getContent())
                        .sortOrder(e.getSortOrder())
                        .build())
                .collectList();

        var homeCustomization = homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profile.getId())
                .map(e -> ResumeProfileResponse.HomeCustomizationResponse.builder()
                        .id(String.valueOf(e.getId()))
                        .label(e.getLabel())
                        .content(e.getContent())
                        .sortOrder(e.getSortOrder())
                        .build())
                .collectList();

        var testimonials = testimonialRepository.findByProfileIdOrderBySortOrderAsc(profile.getId())
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
                .collectList();

        var proficiencies = proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profile.getId())
                .map(e -> ResumeProfileResponse.ProficiencyResponse.builder()
                        .id(String.valueOf(e.getId()))
                        .category(e.getCategory())
                        .skillName(e.getSkillName())
                        .percentage(e.getPercentage())
                        .icon(e.getIcon())
                        .sortOrder(e.getSortOrder())
                        .build())
                .collectList();

        var projects = projectRepository.findByProfileIdOrderBySortOrderAsc(profile.getId())
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
                .collectList();

        var learningTopics = learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profile.getId())
                .map(e -> ResumeProfileResponse.LearningTopicResponse.builder()
                        .id(String.valueOf(e.getId()))
                        .title(e.getTitle())
                        .emoji(e.getEmoji())
                        .description(e.getDescription())
                        .colorTheme(e.getColorTheme())
                        .sortOrder(e.getSortOrder())
                        .build())
                .collectList();

        // Mono.zip supports up to 8 args; split into two groups
        var group1 = Mono.zip(educations, experiences, skills, languages, certifications);
        var group2 = Mono.zip(additional, homeCustomization, testimonials, proficiencies, projects, learningTopics);

        return Mono.zip(group1, group2)
                .map(tuple -> {
                    var t1 = tuple.getT1();
                    var t2 = tuple.getT2();
                    return ResumeProfileResponse.builder()
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
                        .build();
                });
    }

    // ============================================
    // HTML GENERATION
    // ============================================

    private String renderHtml(ResumeProfileResponse profile, String lang) {
        boolean isPt = "pt".equalsIgnoreCase(lang);
        var sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html lang="%s">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s - %s</title>
                    <style>
                """.formatted(lang, escapeHtml(profile.getFullName()), isPt ? "Currículo" : "Resume"));
        sb.append(getResumeStyles());
        sb.append("""
                    </style>
                </head>
                <body>
                    <div class="page">
                """);

        // Header
        sb.append("""
                        <div class="header">
                            <div class="name">%s</div>
                """.formatted(escapeHtml(profile.getFullName()).toUpperCase()));
        if (profile.getTitle() != null) {
            sb.append("            <div class=\"title\">%s</div>\n".formatted(escapeHtml(profile.getTitle())));
        }
        var contactParts = new ArrayList<String>();
        if (StringUtils.hasText(profile.getLocation())) contactParts.add(ICON_LOCATION + " " + escapeHtml(profile.getLocation()));
        if (StringUtils.hasText(profile.getEmail())) contactParts.add(ICON_EMAIL + " <a href=\"mailto:%s\">%s</a>".formatted(escapeHtml(profile.getEmail()), escapeHtml(profile.getEmail())));
        if (StringUtils.hasText(profile.getLinkedin())) contactParts.add(ICON_LINKEDIN + " " + contactLink(profile.getLinkedin()));
        if (StringUtils.hasText(profile.getGithub())) contactParts.add(ICON_GITHUB + " " + contactLink(profile.getGithub()));
        if (StringUtils.hasText(profile.getWebsite())) contactParts.add(ICON_GLOBE + " " + contactLink(profile.getWebsite()));
        if (StringUtils.hasText(profile.getPhone())) contactParts.add(ICON_PHONE + " " + escapeHtml(profile.getPhone()));
        if (!contactParts.isEmpty()) {
            sb.append("            <div class=\"contact-info\">%s</div>\n".formatted(String.join(" | ", contactParts)));
        }
        sb.append("        </div>\n");

        // Professional Summary
        if (profile.getProfessionalSummary() != null && !profile.getProfessionalSummary().isBlank()) {
            sb.append("        <div class=\"section-header\">%s</div>\n".formatted(isPt ? "RESUMO PROFISSIONAL" : "PROFESSIONAL SUMMARY"));
            sb.append("        <div class=\"professional-summary\">%s</div>\n".formatted(sanitizeHtml(profile.getProfessionalSummary())));
        }

        // Education & Languages
        boolean hasEducation = profile.getEducations() != null && !profile.getEducations().isEmpty();
        boolean hasLanguages = profile.getLanguages() != null && !profile.getLanguages().isEmpty();
        if (hasEducation || hasLanguages) {
            sb.append("        <div class=\"section-header\">%s</div>\n".formatted(isPt ? "FORMAÇÃO & IDIOMAS" : "EDUCATION & LANGUAGES"));
            sb.append("        <div class=\"education-languages\">\n");
            if (hasEducation) {
                sb.append("            <div class=\"education-col\">\n");
                boolean firstEdu = true;
                for (var edu : profile.getEducations()) {
                    if (!firstEdu) {
                        sb.append("                <hr class=\"edu-divider\"/>\n");
                    }
                    firstEdu = false;
                    if (edu.getInstitution() != null) sb.append("                <strong>%s</strong>\n".formatted(escapeHtml(edu.getInstitution())));
                    if (edu.getLocation() != null) sb.append("                <div>%s</div>\n".formatted(escapeHtml(edu.getLocation())));
                    String dates = formatDateRange(edu.getStartDate(), edu.getEndDate());
                    if (!dates.isEmpty()) sb.append("                <div>%s</div>\n".formatted(dates));
                    if (edu.getDegree() != null) {
                        String degreeText = escapeHtml(edu.getDegree());
                        if (edu.getFieldOfStudy() != null && !edu.getFieldOfStudy().isBlank()) {
                            degreeText += " in " + escapeHtml(edu.getFieldOfStudy());
                        }
                        sb.append("                <div><strong>%s</strong></div>\n".formatted(degreeText));
                    }
                    if (edu.getDescription() != null && !edu.getDescription().isBlank()) {
                        sb.append("                <div>%s</div>\n".formatted(sanitizeHtml(edu.getDescription())));
                    }
                }
                sb.append("            </div>\n");
            }
            if (hasLanguages) {
                sb.append("            <div class=\"languages-col\">\n");
                sb.append("                <strong>%s</strong>\n".formatted(isPt ? "Idiomas" : "Languages"));
                for (var language : profile.getLanguages()) {
                    sb.append("                <div><strong>%s</strong> - %s</div>\n"
                            .formatted(escapeHtml(language.getName()), escapeHtml(language.getProficiency())));
                }
                sb.append("            </div>\n");
            }
            sb.append("        </div>\n");
        }

        // Technical Skills
        if (profile.getSkills() != null && !profile.getSkills().isEmpty()) {
            sb.append("        <div class=\"section-header\">%s</div>\n".formatted(isPt ? "HABILIDADES TÉCNICAS" : "TECHNICAL SKILLS"));
            sb.append("        <div class=\"skills-section\">\n");
            for (var skill : profile.getSkills()) {
                sb.append("            <div class=\"skill-line\">\n");
                sb.append("                <span class=\"skill-category\">%s:</span>\n".formatted(escapeHtml(skill.getCategory())));
                sb.append("                <span class=\"skill-content\">%s</span>\n".formatted(sanitizeHtml(skill.getContent())));
                sb.append("            </div>\n");
            }
            sb.append("        </div>\n");
        }

        // Interests
        if (profile.getInterests() != null && !profile.getInterests().isBlank()) {
            sb.append("        <div class=\"section-header\">%s</div>\n".formatted(isPt ? "INTERESSES" : "INTERESTS"));
            sb.append("        <div class=\"interests-text\">%s</div>\n".formatted(escapeHtml(profile.getInterests())));
        }

        // Professional Experience
        if (profile.getExperiences() != null && !profile.getExperiences().isEmpty()) {
            sb.append("        <div class=\"section-header\">%s</div>\n".formatted(isPt ? "EXPERIÊNCIA PROFISSIONAL" : "PROFESSIONAL EXPERIENCE"));
            sb.append("        <div class=\"experience-section\">\n");
            // Merge consecutive entries with same company and date range (multi-position support)
            var experiences = profile.getExperiences();
            int i = 0;
            while (i < experiences.size()) {
                var exp = experiences.get(i);
                String dates = formatDateRange(exp.getStartDate(), exp.getEndDate());
                // Find all consecutive entries sharing same company + dates
                int j = i + 1;
                while (j < experiences.size()
                        && exp.getCompany() != null
                        && exp.getCompany().equals(experiences.get(j).getCompany())
                        && dates.equals(formatDateRange(experiences.get(j).getStartDate(), experiences.get(j).getEndDate()))) {
                    j++;
                }
                // Render company header once
                sb.append("            <div class=\"experience-item\">\n");
                sb.append("                <div class=\"experience-header\">\n");
                sb.append("                    <span class=\"company-title\">%s</span>\n".formatted(escapeHtml(exp.getCompany())));
                if (!dates.isEmpty()) {
                    sb.append("                    <span class=\"date-range\">%s</span>\n".formatted(dates));
                }
                sb.append("                </div>\n");
                // Render all positions in this group
                for (int k = i; k < j; k++) {
                    var entry = experiences.get(k);
                    if (entry.getPosition() != null) {
                        sb.append("                <div class=\"position\">> %s</div>\n".formatted(escapeHtml(entry.getPosition())));
                    }
                    if (entry.getBullets() != null && !entry.getBullets().isEmpty()) {
                        sb.append("                <div class=\"experience-details\">\n");
                        sb.append("                    <ul>\n");
                        for (var bullet : entry.getBullets()) {
                            sb.append("                        <li>%s</li>\n".formatted(sanitizeHtml(bullet)));
                        }
                        sb.append("                    </ul>\n");
                        sb.append("                </div>\n");
                    }
                }
                sb.append("            </div>\n");
                i = j;
            }
            sb.append("        </div>\n");
        }

        // Certifications
        if (profile.getCertifications() != null && !profile.getCertifications().isEmpty()) {
            sb.append("        <div class=\"section-header\">%s</div>\n".formatted(isPt ? "CERTIFICAÇÕES" : "CERTIFICATIONS"));
            for (var cert : profile.getCertifications()) {
                sb.append("        <div class=\"additional-section\">\n");
                var certText = new StringBuilder();
                if (cert.getName() != null) certText.append("<strong>%s</strong>".formatted(escapeHtml(cert.getName())));
                if (cert.getIssuer() != null) certText.append(" %s %s".formatted(isPt ? "por" : "by", escapeHtml(cert.getIssuer())));
                if (cert.getIssueDate() != null && !cert.getIssueDate().isBlank()) certText.append(" (%s)".formatted(escapeHtml(cert.getIssueDate())));
                if (cert.getDescription() != null) certText.append(". %s".formatted(sanitizeHtml(cert.getDescription())));
                if (cert.getCredentialUrl() != null && !cert.getCredentialUrl().isBlank()) {
                    certText.append(" <a href=\"%s\" class=\"cert-link\">%s</a>".formatted(
                            escapeHtml(cert.getCredentialUrl()), isPt ? "Ver Certificado" : "View Certificate"));
                }
                sb.append("            %s\n".formatted(certText));
                sb.append("        </div>\n");
            }
        }

        // Additional Information
        if (profile.getAdditionalInfo() != null && !profile.getAdditionalInfo().isEmpty()) {
            sb.append("        <div class=\"section-header\">%s</div>\n".formatted(isPt ? "INFORMAÇÕES ADICIONAIS" : "ADDITIONAL INFORMATION"));
            for (var info : profile.getAdditionalInfo()) {
                sb.append("        <div class=\"additional-section\">\n");
                sb.append("            <strong>%s:</strong> %s\n".formatted(
                        escapeHtml(info.getLabel()), sanitizeHtml(info.getContent())));
                sb.append("        </div>\n");
            }
        }

        sb.append("""
                    </div>
                </body>
                </html>
                """);

        return sb.toString();
    }

    private String getResumeStyles() {
        return """
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body { font-family: 'Arial', 'Helvetica', sans-serif; background-color: #f5f5f5; padding: 20px; margin: 0; }
                @page { size: A4; margin: 8mm 8mm 8mm 8mm; }
                .page { width: 210mm; background-color: white; margin: 0 auto 20px; padding: 8mm 8mm; box-shadow: 0 0 10px rgba(0,0,0,0.1); line-height: 1.4; font-size: 10px; color: #333; }
                .header { text-align: center; margin-bottom: 14px; padding-bottom: 0; }
                .name { font-size: 18px; font-weight: bold; color: #000; margin-bottom: 2px; letter-spacing: 0.5px; }
                .title { font-size: 10px; color: #666; margin-bottom: 4px; }
                .contact-info { font-size: 9px; color: #666; letter-spacing: 0px; }
                .contact-info a { color: #0066cc; text-decoration: none; }
                .section-header { font-size: 10px; font-weight: bold; color: #000; padding: 2px 0; margin-top: 6px; margin-bottom: 4px; text-transform: uppercase; letter-spacing: 0.5px; border-bottom: 1px solid #ccc; page-break-after: avoid; }
                .professional-summary { text-align: justify; margin-bottom: 8px; line-height: 1.4; font-size: 10px; }
                .skills-section { margin-bottom: 8px; }
                .skill-line { margin-bottom: 3px; text-align: justify; line-height: 1.4; font-size: 10px; }
                .skill-category { font-weight: bold; display: inline; margin-right: 3px; }
                .skill-content { display: inline; }
                .education-languages { display: flex; gap: 40px; margin-bottom: 8px; font-size: 10px; }
                .education-col strong, .languages-col strong { font-weight: bold; display: block; margin-bottom: 2px; }
                .education-col, .languages-col { flex: 1; }
                .education-col div, .languages-col div { margin-bottom: 1px; }
                .edu-divider { border: none; border-top: 1px solid #ccc; margin: 6px 0; }
                .interests-text { text-align: justify; margin-bottom: 8px; line-height: 1.4; font-size: 10px; }
                .experience-section { margin-bottom: 0px; }
                .experience-item { margin-bottom: 8px; page-break-inside: avoid; orphans: 3; widows: 3; }
                .experience-header { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 1px; }
                .company-title { font-weight: bold; font-size: 10px; }
                .date-range { font-size: 10px; color: #666; }
                .position { font-weight: bold; font-size: 10px; margin-bottom: 2px; margin-top: 1px; }
                .experience-details { margin-left: 16px; font-size: 10px; }
                .experience-details ul { margin: 2px 0; padding-left: 12px; }
                .experience-details li { margin-bottom: 2px; line-height: 1.4; text-align: justify; }
                .experience-details strong { font-weight: bold; }
                .additional-section { margin-bottom: 8px; text-align: justify; line-height: 1.4; font-size: 10px; }
                .cert-link { color: #0066cc; text-decoration: none; }
                @media print {
                    body { padding: 0; margin: 0; background-color: white; }
                    @page { size: A4; margin: 8mm 8mm; }
                    .page { width: 100%; min-height: auto; padding: 0; box-shadow: none; margin: 0; }
                    .section-header { page-break-after: avoid; }
                    .experience-item { page-break-inside: avoid; orphans: 3; widows: 3; }
                    .header { page-break-after: avoid; }
                    .skills-section { page-break-inside: avoid; }
                    .education-languages { page-break-inside: avoid; }
                }
                """;
    }

    // ============================================
    // UTILITIES
    // ============================================

    private String formatDateRange(String start, String end) {
        if (start == null && end == null) return "";
        if (end == null || end.isBlank()) return "Since " + (start != null ? start : "");
        return (start != null ? start : "") + " - " + end;
    }

    private String escapeHtml(String text) {
        return HtmlUtils.escapeHtml(text);
    }

    /** Wrap a URL in a clickable <a> tag, displaying the domain path without protocol. */
    private String contactLink(String url) {
        String escaped = escapeHtml(url);
        String href = escaped.startsWith("http") ? escaped : "https://" + escaped;
        String display = escaped.replaceFirst("^https?://", "");
        return "<a href=\"%s\">%s</a>".formatted(href, display);
    }

    /**
     * Sanitize HTML content for resume fields that support rich text (links, bold, italic).
     * Allows safe inline tags while stripping dangerous elements.
     */
    private String sanitizeHtml(String text) {
        if (text == null) return "";
        return htmlSanitizer.sanitizeResumeContent(text);
    }

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
