package dev.catananti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO containing the full resume profile with all sub-sections.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeProfileResponse {

    private String id;
    private String ownerId;
    private String locale;
    private String fullName;
    private String title;
    private String email;
    private String phone;
    private String linkedin;
    private String github;
    private String website;
    private String location;
    private String professionalSummary;
    private String interests;
    private String workMode;
    private String timezone;
    private String employmentType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<EducationResponse> educations;
    private List<ExperienceResponse> experiences;
    private List<SkillResponse> skills;
    private List<LanguageResponse> languages;
    private List<CertificationResponse> certifications;
    private List<AdditionalInfoResponse> additionalInfo;
    private List<HomeCustomizationResponse> homeCustomization;
    private List<TestimonialResponse> testimonials;
    private List<ProficiencyResponse> proficiencies;
    private List<ProjectResponse> projects;
    private List<LearningTopicResponse> learningTopics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EducationResponse {
        private String id;
        private String institution;
        private String location;
        private String degree;
        private String fieldOfStudy;
        private String startDate;
        private String endDate;
        private String description;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExperienceResponse {
        private String id;
        private String company;
        private String position;
        private String startDate;
        private String endDate;
        private List<String> bullets;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillResponse {
        private String id;
        private String category;
        private String content;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LanguageResponse {
        private String id;
        private String name;
        private String proficiency;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CertificationResponse {
        private String id;
        private String name;
        private String issuer;
        private String issueDate;
        private String credentialUrl;
        private String description;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdditionalInfoResponse {
        private String id;
        private String label;
        private String content;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HomeCustomizationResponse {
        private String id;
        private String label;
        private String content;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestimonialResponse {
        private String id;
        private String authorName;
        private String authorRole;
        private String authorCompany;
        private String authorImageUrl;
        private String text;
        private String accentColor;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProficiencyResponse {
        private String id;
        private String category;
        private String skillName;
        private Integer percentage;
        private String icon;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProjectResponse {
        private String id;
        private String title;
        private String description;
        private String imageUrl;
        private String projectUrl;
        private String repoUrl;
        private List<String> techTags;
        private Boolean featured;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LearningTopicResponse {
        private String id;
        private String title;
        private String emoji;
        private String description;
        private String colorTheme;
        private Integer sortOrder;
    }
}
