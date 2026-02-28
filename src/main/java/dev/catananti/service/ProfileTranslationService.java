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
 * Collects all text fields into a keyed map, sends them in a single batch to DeepL
 * for efficiency, then maps the results back by key to a new ResumeProfileResponse.
 *
 * Fields that are NOT translated: fullName, email, phone, linkedin, github, website,
 * dates (startDate/endDate/issueDate), credentialUrl, IDs, sortOrder.
 */
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
        // F-200: Key-based field mapping — each text gets a unique key, eliminating fragile index tracking
        java.util.LinkedHashMap<String, String> textMap = new java.util.LinkedHashMap<>();

        // Root fields
        textMap.put("title", profile.getTitle());
        textMap.put("location", profile.getLocation());
        textMap.put("professionalSummary", profile.getProfessionalSummary());
        textMap.put("interests", profile.getInterests());

        // Education fields
        List<EducationResponse> educations = profile.getEducations() != null ? profile.getEducations() : List.of();
        for (int i = 0; i < educations.size(); i++) {
            var edu = educations.get(i);
            textMap.put("edu." + i + ".institution", edu.getInstitution());
            textMap.put("edu." + i + ".location", edu.getLocation());
            textMap.put("edu." + i + ".degree", edu.getDegree());
            textMap.put("edu." + i + ".fieldOfStudy", edu.getFieldOfStudy());
            textMap.put("edu." + i + ".description", edu.getDescription());
            textMap.put("edu." + i + ".startDate", edu.getStartDate());
            textMap.put("edu." + i + ".endDate", edu.getEndDate());
        }

        // Experience fields
        List<ExperienceResponse> experiences = profile.getExperiences() != null ? profile.getExperiences() : List.of();
        for (int i = 0; i < experiences.size(); i++) {
            var exp = experiences.get(i);
            textMap.put("exp." + i + ".position", exp.getPosition());
            textMap.put("exp." + i + ".startDate", exp.getStartDate());
            textMap.put("exp." + i + ".endDate", exp.getEndDate());
            List<String> bullets = exp.getBullets() != null ? exp.getBullets() : List.of();
            for (int j = 0; j < bullets.size(); j++) {
                textMap.put("exp." + i + ".bullet." + j, bullets.get(j));
            }
        }

        // Skill fields
        List<SkillResponse> skills = profile.getSkills() != null ? profile.getSkills() : List.of();
        for (int i = 0; i < skills.size(); i++) {
            textMap.put("skill." + i + ".category", skills.get(i).getCategory());
            textMap.put("skill." + i + ".content", skills.get(i).getContent());
        }

        // Language fields
        List<LanguageResponse> languages = profile.getLanguages() != null ? profile.getLanguages() : List.of();
        for (int i = 0; i < languages.size(); i++) {
            textMap.put("lang." + i + ".name", languages.get(i).getName());
            textMap.put("lang." + i + ".proficiency", languages.get(i).getProficiency());
        }

        // Certification fields
        List<CertificationResponse> certifications = profile.getCertifications() != null ? profile.getCertifications() : List.of();
        for (int i = 0; i < certifications.size(); i++) {
            textMap.put("cert." + i + ".name", certifications.get(i).getName());
            textMap.put("cert." + i + ".issuer", certifications.get(i).getIssuer());
            textMap.put("cert." + i + ".description", certifications.get(i).getDescription());
        }

        // AdditionalInfo fields
        List<AdditionalInfoResponse> additionalInfo = profile.getAdditionalInfo() != null ? profile.getAdditionalInfo() : List.of();
        for (int i = 0; i < additionalInfo.size(); i++) {
            textMap.put("addInfo." + i + ".label", additionalInfo.get(i).getLabel());
            textMap.put("addInfo." + i + ".content", additionalInfo.get(i).getContent());
        }

        // HomeCustomization fields
        List<ResumeProfileResponse.HomeCustomizationResponse> homeCustomization = profile.getHomeCustomization() != null ? profile.getHomeCustomization() : List.of();
        for (int i = 0; i < homeCustomization.size(); i++) {
            textMap.put("home." + i + ".label", homeCustomization.get(i).getLabel());
            textMap.put("home." + i + ".content", homeCustomization.get(i).getContent());
        }

        // Convert to ordered lists for batch translation
        List<String> keys = new ArrayList<>(textMap.keySet());
        List<String> values = new ArrayList<>(textMap.values());

        log.info("Translating profile: {} texts to {}", values.size(), targetLang);

        return translationService.translateBatch(values, targetLang)
                .map(translated -> {
                    // Build key-to-translated lookup
                    java.util.Map<String, String> t = new java.util.HashMap<>();
                    for (int i = 0; i < keys.size(); i++) {
                        t.put(keys.get(i), translated.get(i));
                    }

                    // Reconstruct profile with translated texts
                    ResumeProfileResponse result = ResumeProfileResponse.builder()
                            .id(profile.getId())
                            .ownerId(profile.getOwnerId())
                            .locale(profile.getLocale())
                            .fullName(profile.getFullName())
                            .title(t.get("title"))
                            .email(profile.getEmail())
                            .phone(profile.getPhone())
                            .linkedin(profile.getLinkedin())
                            .github(profile.getGithub())
                            .website(profile.getWebsite())
                            .location(t.get("location"))
                            .professionalSummary(t.get("professionalSummary"))
                            .interests(t.get("interests"))
                            .workMode(profile.getWorkMode())
                            .timezone(profile.getTimezone())
                            .employmentType(profile.getEmploymentType())
                            .createdAt(profile.getCreatedAt())
                            .updatedAt(profile.getUpdatedAt())
                            .build();

                    // Educations
                    List<EducationResponse> translatedEducations = new ArrayList<>();
                    for (int i = 0; i < educations.size(); i++) {
                        var edu = educations.get(i);
                        translatedEducations.add(EducationResponse.builder()
                                .id(edu.getId())
                                .institution(t.get("edu." + i + ".institution"))
                                .location(t.get("edu." + i + ".location"))
                                .degree(t.get("edu." + i + ".degree"))
                                .fieldOfStudy(t.get("edu." + i + ".fieldOfStudy"))
                                .description(t.get("edu." + i + ".description"))
                                .startDate(t.get("edu." + i + ".startDate"))
                                .endDate(t.get("edu." + i + ".endDate"))
                                .sortOrder(edu.getSortOrder())
                                .build());
                    }
                    result.setEducations(translatedEducations);

                    // Experiences
                    List<ExperienceResponse> translatedExperiences = new ArrayList<>();
                    for (int i = 0; i < experiences.size(); i++) {
                        var exp = experiences.get(i);
                        List<String> translatedBullets = new ArrayList<>();
                        List<String> origBullets = exp.getBullets() != null ? exp.getBullets() : List.of();
                        for (int j = 0; j < origBullets.size(); j++) {
                            translatedBullets.add(t.get("exp." + i + ".bullet." + j));
                        }
                        translatedExperiences.add(ExperienceResponse.builder()
                                .id(exp.getId())
                                .company(exp.getCompany())
                                .position(t.get("exp." + i + ".position"))
                                .startDate(t.get("exp." + i + ".startDate"))
                                .endDate(t.get("exp." + i + ".endDate"))
                                .bullets(translatedBullets)
                                .sortOrder(exp.getSortOrder())
                                .build());
                    }
                    result.setExperiences(translatedExperiences);

                    // Skills
                    List<SkillResponse> translatedSkills = new ArrayList<>();
                    for (int i = 0; i < skills.size(); i++) {
                        translatedSkills.add(SkillResponse.builder()
                                .id(skills.get(i).getId())
                                .category(t.get("skill." + i + ".category"))
                                .content(t.get("skill." + i + ".content"))
                                .sortOrder(skills.get(i).getSortOrder())
                                .build());
                    }
                    result.setSkills(translatedSkills);

                    // Languages
                    List<LanguageResponse> translatedLanguages = new ArrayList<>();
                    for (int i = 0; i < languages.size(); i++) {
                        translatedLanguages.add(LanguageResponse.builder()
                                .id(languages.get(i).getId())
                                .name(t.get("lang." + i + ".name"))
                                .proficiency(t.get("lang." + i + ".proficiency"))
                                .sortOrder(languages.get(i).getSortOrder())
                                .build());
                    }
                    result.setLanguages(translatedLanguages);

                    // Certifications
                    List<CertificationResponse> translatedCerts = new ArrayList<>();
                    for (int i = 0; i < certifications.size(); i++) {
                        translatedCerts.add(CertificationResponse.builder()
                                .id(certifications.get(i).getId())
                                .name(t.get("cert." + i + ".name"))
                                .issuer(t.get("cert." + i + ".issuer"))
                                .description(t.get("cert." + i + ".description"))
                                .issueDate(certifications.get(i).getIssueDate())
                                .credentialUrl(certifications.get(i).getCredentialUrl())
                                .sortOrder(certifications.get(i).getSortOrder())
                                .build());
                    }
                    result.setCertifications(translatedCerts);

                    // Additional Info
                    List<AdditionalInfoResponse> translatedAddInfo = new ArrayList<>();
                    for (int i = 0; i < additionalInfo.size(); i++) {
                        translatedAddInfo.add(AdditionalInfoResponse.builder()
                                .id(additionalInfo.get(i).getId())
                                .label(t.get("addInfo." + i + ".label"))
                                .content(t.get("addInfo." + i + ".content"))
                                .sortOrder(additionalInfo.get(i).getSortOrder())
                                .build());
                    }
                    result.setAdditionalInfo(translatedAddInfo);

                    // Home Customization
                    List<ResumeProfileResponse.HomeCustomizationResponse> translatedHomeCust = new ArrayList<>();
                    for (int i = 0; i < homeCustomization.size(); i++) {
                        translatedHomeCust.add(ResumeProfileResponse.HomeCustomizationResponse.builder()
                                .id(homeCustomization.get(i).getId())
                                .label(t.get("home." + i + ".label"))
                                .content(t.get("home." + i + ".content"))
                                .sortOrder(homeCustomization.get(i).getSortOrder())
                                .build());
                    }
                    result.setHomeCustomization(translatedHomeCust);

                    // Preserve fields that are not translated
                    result.setTestimonials(profile.getTestimonials());
                    result.setProficiencies(profile.getProficiencies());
                    result.setProjects(profile.getProjects());
                    result.setLearningTopics(profile.getLearningTopics());

                    log.info("Profile translation complete: {} fields → {}", keys.size(), targetLang);
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
