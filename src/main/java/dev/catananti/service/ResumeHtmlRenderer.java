package dev.catananti.service;

import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.util.HtmlUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Properties;

/**
 * Renders a {@link ResumeProfileResponse} into the self-contained HTML/CSS
 * document consumed by the PDF generator. Extracted verbatim from
 * ResumeProfileService (ARCH-1) so the string output stays byte-identical.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeHtmlRenderer {

    private final HtmlSanitizerService htmlSanitizer;

    // Contact-line SVG icons used by the PDF renderer. Loaded once from
    // classpath:/templates/resume/contact-icons.properties — keeping markup
    // out of the service body makes icon updates a no-recompile change.
    private String ICON_EMAIL;
    private String ICON_LINKEDIN;
    private String ICON_GITHUB;
    private String ICON_GLOBE;
    private String ICON_LOCATION;
    private String ICON_PHONE;

    @PostConstruct
    void loadContactIcons() {
        Properties props = new Properties();
        try (InputStream in = new ClassPathResource("templates/resume/contact-icons.properties").getInputStream()) {
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to load resume contact icons; PDF contact lines will render without glyphs", e);
        }
        this.ICON_EMAIL = props.getProperty("email", "");
        this.ICON_LINKEDIN = props.getProperty("linkedin", "");
        this.ICON_GITHUB = props.getProperty("github", "");
        this.ICON_GLOBE = props.getProperty("globe", "");
        this.ICON_LOCATION = props.getProperty("location", "");
        this.ICON_PHONE = props.getProperty("phone", "");
    }

    // ============================================
    // HTML GENERATION
    // ============================================

    // skipcq: JAVA-R1000 — single-template HTML generator: the cyclomatic complexity is
    // inherent to the number of optional resume sections emitted inline, and the output
    // must stay byte-identical (asserted by tests), so method decomposition is deferred.
    String renderHtml(ResumeProfileResponse profile, String lang) {
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
}
