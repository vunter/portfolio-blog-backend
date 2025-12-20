package dev.catananti.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for creating/updating a full resume profile with all sections.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeProfileRequest {

    @NotBlank(message = "Full name is required")
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

    @Valid
    private List<EducationEntry> educations;
    @Valid
    private List<ExperienceEntry> experiences;
    @Valid
    private List<SkillEntry> skills;
    @Valid
    private List<LanguageEntry> languages;
    @Valid
    private List<CertificationEntry> certifications;
    @Valid
    private List<AdditionalInfoEntry> additionalInfo;
    @Valid
    private List<HomeCustomizationEntry> homeCustomization;
    @Valid
    private List<TestimonialEntry> testimonials;
    @Valid
    private List<ProficiencyEntry> proficiencies;
    @Valid
    private List<ProjectEntry> projects;
    @Valid
    private List<LearningTopicEntry> learningTopics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EducationEntry {
        private String id;
        @NotBlank(message = "Institution is required")
        @Size(max = 255)
        private String institution;
        @Size(max = 255)
        private String location;
        @Size(max = 255)
        private String degree;
        @Size(max = 255)
        private String fieldOfStudy;
        @Size(max = 20)
        private String startDate;
        @Size(max = 20)
        private String endDate;
        @Size(max = 2000)
        private String description;
        @Builder.Default
        private Integer sortOrder = 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExperienceEntry {
        private String id;
        @NotBlank(message = "Company is required")
        @Size(max = 255)
        private String company;
        @NotBlank(message = "Position is required")
        @Size(max = 255)
        private String position;
        @Size(max = 20)
        private String startDate;
        @Size(max = 20)
        private String endDate;
        private List<String> bullets;
        @Builder.Default
        private Integer sortOrder = 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillEntry {
        private String id;
        @NotBlank(message = "Category is required")
        @Size(max = 255)
        private String category;
        @NotBlank(message = "Content is required")
        @Size(max = 2000)
        private String content;
        @Builder.Default
        private Integer sortOrder = 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LanguageEntry {
        private String id;
        @NotBlank(message = "Language name is required")
        @Size(max = 100)
        private String name;
        @Size(max = 50)
        private String proficiency;
        @Builder.Default
        private Integer sortOrder = 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CertificationEntry {
        private String id;
        @NotBlank(message = "Certification name is required")
        @Size(max = 255)
        private String name;
        @Size(max = 255)
        private String issuer;
        @Size(max = 20)
        private String issueDate;
        @Size(max = 2048)
        private String credentialUrl;
        @Size(max = 2000)
        private String description;
        @Builder.Default
        private Integer sortOrder = 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdditionalInfoEntry {
        private String id;
        @NotBlank(message = "Label is required")
        @Size(max = 255)
        private String label;
        @Size(max = 2000)
        private String content;
        @Builder.Default
        private Integer sortOrder = 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HomeCustomizationEntry {
        private String id;
        @NotBlank(message = "Label is required")
        @Size(max = 255)
        private String label;
        @Size(max = 2000)
        private String content;
        @Builder.Default
        private Integer sortOrder = 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestimonialEntry {
        private String id;
        @NotBlank(message = "Author name is required")
        @Size(max = 255)
        private String authorName;
        @Size(max = 255)
        private String authorRole;
        @Size(max = 255)
        private String authorCompany;
        @Size(max = 2048)
        private String authorImageUrl;
        @NotBlank(message = "Testimonial text is required")
        @Size(max = 2000)
        private String text;
        @Size(max = 20)
        private String accentColor;
        @Builder.Default
        private Integer sortOrder = 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProficiencyEntry {
        private String id;
        @NotBlank(message = "Category is required")
        @Size(max = 255)
        private String category;
        @NotBlank(message = "Skill name is required")
        @Size(max = 255)
        private String skillName;
        private Integer percentage;
        @Size(max = 100)
        private String icon;
        @Builder.Default
        private Integer sortOrder = 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProjectEntry {
        private String id;
        @NotBlank(message = "Project title is required")
        @Size(max = 255)
        private String title;
        @Size(max = 2000)
        private String description;
        @Size(max = 2048)
        private String imageUrl;
        @Size(max = 2048)
        private String projectUrl;
        @Size(max = 2048)
        private String repoUrl;
        private List<String> techTags;
        private Boolean featured;
        @Builder.Default
        private Integer sortOrder = 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LearningTopicEntry {
        private String id;
        @NotBlank(message = "Topic title is required")
        @Size(max = 255)
        private String title;
        @Size(max = 500)
        private String emoji;
        @Size(max = 2000)
        private String description;
        @Size(max = 50)
        private String colorTheme;
        @Builder.Default
        private Integer sortOrder = 0;
    }
}
