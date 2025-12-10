package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileRequest.*;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.dto.ResumeProfileResponse.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the translation of all translatable fields in a resume profile.
 * Collects all text fields, sends them in a single batch to DeepL for efficiency,
 * then maps the results back to a new ResumeProfileResponse.
 *
 * Fields that are NOT translated: fullName, email, phone, linkedin, github, website,
 * dates (startDate/endDate/issueDate), credentialUrl, IDs, sortOrder.
 */
// TODO F-200: Use key-based field mapping instead of fragile index-based mapping
@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileTranslationService {

    private final TranslationService translationService;

    /**
     * Check if translation is available.
     */
    public boolean isAvailable() {
        return translationService.isAvailable();
    }

    /**
     * Translate all translatable fields in a profile to the target language.
     * Returns a NEW ResumeProfileResponse with translated content (original is not modified).
     *
     * @param profile    the source profile
     * @param targetLang target language (e.g., "en", "pt")
     * @return translated profile
     */
    public Mono<ResumeProfileResponse> translateProfile(ResumeProfileResponse profile, String targetLang) {
        // Collect all translatable texts into a flat list
        List<String> texts = new ArrayList<>();

        // Root fields (indices 0-3)
        texts.add(profile.getTitle());                 // 0
        texts.add(profile.getLocation());              // 1
        texts.add(profile.getProfessionalSummary());   // 2
        texts.add(profile.getInterests());             // 3

        int rootFieldCount = texts.size(); // 4

        // Education fields: institution, location, degree, fieldOfStudy, description, startDate, endDate (7 per entry)
        List<EducationResponse> educations = profile.getEducations() != null ? profile.getEducations() : List.of();
        for (var edu : educations) {
            texts.add(edu.getInstitution());
            texts.add(edu.getLocation());
            texts.add(edu.getDegree());
            texts.add(edu.getFieldOfStudy());
            texts.add(edu.getDescription());
            texts.add(edu.getStartDate());     // e.g., "January 2017" → "Janeiro 2017"
            texts.add(edu.getEndDate());       // e.g., "Present" → "Atual"
        }

        // Experience fields: position, startDate, endDate + each bullet (variable-length per entry)
        // Track structure: [positionIdx, startDateIdx, endDateIdx, bulletCount, bulletStartIdx]
        List<ExperienceResponse> experiences = profile.getExperiences() != null ? profile.getExperiences() : List.of();
        List<int[]> expStructure = new ArrayList<>();
        for (var exp : experiences) {
            int posIdx = texts.size();
            texts.add(exp.getPosition());
            int startDateIdx = texts.size();
            texts.add(exp.getStartDate());   // e.g., "June 2024" → "Junho 2024"
            int endDateIdx = texts.size();
            texts.add(exp.getEndDate());     // e.g., "Present" → "Atual"
            List<String> bullets = exp.getBullets() != null ? exp.getBullets() : List.of();
            int bulletStart = texts.size();
            texts.addAll(bullets);
            expStructure.add(new int[]{posIdx, startDateIdx, endDateIdx, bullets.size(), bulletStart});
        }

        // Skill fields: category, content (2 per entry)
        List<SkillResponse> skills = profile.getSkills() != null ? profile.getSkills() : List.of();
        int skillsStart = texts.size();
        for (var skill : skills) {
            texts.add(skill.getCategory());
            texts.add(skill.getContent());
        }

        // Language fields: name + proficiency (2 per entry)
        List<LanguageResponse> languages = profile.getLanguages() != null ? profile.getLanguages() : List.of();
        int languagesStart = texts.size();
        for (var lang : languages) {
            texts.add(lang.getName());         // e.g., "English" → "Inglês"
            texts.add(lang.getProficiency());
        }

        // Certification fields: name, issuer, description (3 per entry)
        List<CertificationResponse> certifications = profile.getCertifications() != null ? profile.getCertifications() : List.of();
        int certsStart = texts.size();
        for (var cert : certifications) {
            texts.add(cert.getName());
            texts.add(cert.getIssuer());
            texts.add(cert.getDescription());
        }

        // AdditionalInfo fields: label, content (2 per entry)
        List<AdditionalInfoResponse> additionalInfo = profile.getAdditionalInfo() != null ? profile.getAdditionalInfo() : List.of();
        int addInfoStart = texts.size();
        for (var info : additionalInfo) {
            texts.add(info.getLabel());
            texts.add(info.getContent());
        }

        // HomeCustomization fields: label, content (2 per entry)
        List<ResumeProfileResponse.HomeCustomizationResponse> homeCustomization = profile.getHomeCustomization() != null ? profile.getHomeCustomization() : List.of();
        int homeCustomizationStart = texts.size();
        for (var hc : homeCustomization) {
            texts.add(hc.getLabel());
            texts.add(hc.getContent());
        }

        log.info("Translating profile: {} texts to {}", texts.size(), targetLang);

        return translationService.translateBatch(texts, targetLang)
                .map(translated -> {
                    // Reconstruct profile with translated texts
                    ResumeProfileResponse result = ResumeProfileResponse.builder()
                            .id(profile.getId())
                            .ownerId(profile.getOwnerId())
                            .locale(profile.getLocale())       // Preserve locale
                            .fullName(profile.getFullName())   // NOT translated
                            .title(translated.get(0))
                            .email(profile.getEmail())         // NOT translated
                            .phone(profile.getPhone())         // NOT translated
                            .linkedin(profile.getLinkedin())   // NOT translated
                            .github(profile.getGithub())       // NOT translated
                            .website(profile.getWebsite())     // NOT translated
                            .location(translated.get(1))
                            .professionalSummary(translated.get(2))
                            .interests(translated.get(3))
                            .workMode(profile.getWorkMode())           // Preserve
                            .timezone(profile.getTimezone())           // Preserve
                            .employmentType(profile.getEmploymentType()) // Preserve
                            .createdAt(profile.getCreatedAt())
                            .updatedAt(profile.getUpdatedAt())
                            .build();

                    // Educations
                    int eduIdx = rootFieldCount;
                    List<EducationResponse> translatedEducations = new ArrayList<>();
                    for (var edu : educations) {
                        translatedEducations.add(EducationResponse.builder()
                                .id(edu.getId())
                                .institution(translated.get(eduIdx))
                                .location(translated.get(eduIdx + 1))
                                .degree(translated.get(eduIdx + 2))
                                .fieldOfStudy(translated.get(eduIdx + 3))
                                .description(translated.get(eduIdx + 4))
                                .startDate(translated.get(eduIdx + 5))
                                .endDate(translated.get(eduIdx + 6))
                                .sortOrder(edu.getSortOrder())
                                .build());
                        eduIdx += 7;
                    }
                    result.setEducations(translatedEducations);

                    // Experiences
                    List<ExperienceResponse> translatedExperiences = new ArrayList<>();
                    for (int i = 0; i < experiences.size(); i++) {
                        var exp = experiences.get(i);
                        int[] struct = expStructure.get(i);
                        String translatedPosition = translated.get(struct[0]);
                        String translatedStartDate = translated.get(struct[1]);
                        String translatedEndDate = translated.get(struct[2]);
                        List<String> translatedBullets = new ArrayList<>();
                        for (int j = 0; j < struct[3]; j++) {
                            translatedBullets.add(translated.get(struct[4] + j));
                        }
                        translatedExperiences.add(ExperienceResponse.builder()
                                .id(exp.getId())
                                .company(exp.getCompany())     // NOT translated (proper noun)
                                .position(translatedPosition)
                                .startDate(translatedStartDate)
                                .endDate(translatedEndDate)
                                .bullets(translatedBullets)
                                .sortOrder(exp.getSortOrder())
                                .build());
                    }
                    result.setExperiences(translatedExperiences);

                    // Skills
                    List<SkillResponse> translatedSkills = new ArrayList<>();
                    int sIdx = skillsStart;
                    for (var skill : skills) {
                        translatedSkills.add(SkillResponse.builder()
                                .id(skill.getId())
                                .category(translated.get(sIdx))
                                .content(translated.get(sIdx + 1))
                                .sortOrder(skill.getSortOrder())
                                .build());
                        sIdx += 2;
                    }
                    result.setSkills(translatedSkills);

                    // Languages
                    List<LanguageResponse> translatedLanguages = new ArrayList<>();
                    int lIdx = languagesStart;
                    for (var lang : languages) {
                        translatedLanguages.add(LanguageResponse.builder()
                                .id(lang.getId())
                                .name(translated.get(lIdx))            // e.g., "Inglês"
                                .proficiency(translated.get(lIdx + 1))
                                .sortOrder(lang.getSortOrder())
                                .build());
                        lIdx += 2;
                    }
                    result.setLanguages(translatedLanguages);

                    // Certifications
                    List<CertificationResponse> translatedCerts = new ArrayList<>();
                    int cIdx = certsStart;
                    for (var cert : certifications) {
                        translatedCerts.add(CertificationResponse.builder()
                                .id(cert.getId())
                                .name(translated.get(cIdx))
                                .issuer(translated.get(cIdx + 1))
                                .description(translated.get(cIdx + 2))
                                .issueDate(cert.getIssueDate())
                                .credentialUrl(cert.getCredentialUrl())
                                .sortOrder(cert.getSortOrder())
                                .build());
                        cIdx += 3;
                    }
                    result.setCertifications(translatedCerts);

                    // Additional Info
                    List<AdditionalInfoResponse> translatedAddInfo = new ArrayList<>();
                    int aIdx = addInfoStart;
                    for (var info : additionalInfo) {
                        translatedAddInfo.add(AdditionalInfoResponse.builder()
                                .id(info.getId())
                                .label(translated.get(aIdx))
                                .content(translated.get(aIdx + 1))
                                .sortOrder(info.getSortOrder())
                                .build());
                        aIdx += 2;
                    }
                    result.setAdditionalInfo(translatedAddInfo);

                    // Home Customization
                    List<ResumeProfileResponse.HomeCustomizationResponse> translatedHomeCust = new ArrayList<>();
                    int hcIdx = homeCustomizationStart;
                    for (var hc : homeCustomization) {
                        translatedHomeCust.add(ResumeProfileResponse.HomeCustomizationResponse.builder()
                                .id(hc.getId())
                                .label(translated.get(hcIdx))
                                .content(translated.get(hcIdx + 1))
                                .sortOrder(hc.getSortOrder())
                                .build());
                        hcIdx += 2;
                    }
                    result.setHomeCustomization(translatedHomeCust);

                    // Preserve fields that are not translated
                    result.setTestimonials(profile.getTestimonials());
                    result.setProficiencies(profile.getProficiencies());
                    result.setProjects(profile.getProjects());
                    result.setLearningTopics(profile.getLearningTopics());

                    log.info("Profile translation complete: {} → {}", 
                            texts.size() + " fields", targetLang);
                    return result;
                });
    }

    /**
     * Convert a ResumeProfileResponse to a ResumeProfileRequest for saving.
     * Used when auto-saving translated profiles.
     */
    public ResumeProfileRequest toRequest(ResumeProfileResponse profile) {
        return ResumeProfileRequest.builder()
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
                .educations(profile.getEducations() != null ? profile.getEducations().stream()
                        .map(e -> ResumeProfileRequest.EducationEntry.builder()
                                .institution(e.getInstitution())
                                .location(e.getLocation())
                                .degree(e.getDegree())
                                .fieldOfStudy(e.getFieldOfStudy())
                                .startDate(e.getStartDate())
                                .endDate(e.getEndDate())
                                .description(e.getDescription())
                                .sortOrder(e.getSortOrder())
                                .build())
                        .toList() : List.of())
                .experiences(profile.getExperiences() != null ? profile.getExperiences().stream()
                        .map(e -> ResumeProfileRequest.ExperienceEntry.builder()
                                .company(e.getCompany())
                                .position(e.getPosition())
                                .startDate(e.getStartDate())
                                .endDate(e.getEndDate())
                                .bullets(e.getBullets())
                                .sortOrder(e.getSortOrder())
                                .build())
                        .toList() : List.of())
                .skills(profile.getSkills() != null ? profile.getSkills().stream()
                        .map(e -> ResumeProfileRequest.SkillEntry.builder()
                                .category(e.getCategory())
                                .content(e.getContent())
                                .sortOrder(e.getSortOrder())
                                .build())
                        .toList() : List.of())
                .languages(profile.getLanguages() != null ? profile.getLanguages().stream()
                        .map(e -> ResumeProfileRequest.LanguageEntry.builder()
                                .name(e.getName())
                                .proficiency(e.getProficiency())
                                .sortOrder(e.getSortOrder())
                                .build())
                        .toList() : List.of())
                .certifications(profile.getCertifications() != null ? profile.getCertifications().stream()
                        .map(e -> ResumeProfileRequest.CertificationEntry.builder()
                                .name(e.getName())
                                .issuer(e.getIssuer())
                                .issueDate(e.getIssueDate())
                                .credentialUrl(e.getCredentialUrl())
                                .description(e.getDescription())
                                .sortOrder(e.getSortOrder())
                                .build())
                        .toList() : List.of())
                .additionalInfo(profile.getAdditionalInfo() != null ? profile.getAdditionalInfo().stream()
                        .map(e -> ResumeProfileRequest.AdditionalInfoEntry.builder()
                                .label(e.getLabel())
                                .content(e.getContent())
                                .sortOrder(e.getSortOrder())
                                .build())
                        .toList() : List.of())
                .homeCustomization(profile.getHomeCustomization() != null ? profile.getHomeCustomization().stream()
                        .map(e -> ResumeProfileRequest.HomeCustomizationEntry.builder()
                                .label(e.getLabel())
                                .content(e.getContent())
                                .sortOrder(e.getSortOrder())
                                .build())
                        .toList() : List.of())
                .build();
    }
}
