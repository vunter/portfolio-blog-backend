package dev.catananti.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.catananti.service.ContentModerationService.ModerationResult;
import dev.catananti.service.ContentModerationService.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ContentModerationServiceTest {

    @Mock
    private ObjectMapper objectMapper;

    private ContentModerationService moderationService;

    @BeforeEach
    void setUp() throws Exception {
        moderationService = new ContentModerationService(objectMapper);

        // Inject word lists directly via reflection instead of relying on @PostConstruct
        Map<String, Map<String, List<String>>> wordLists = new HashMap<>();

        Map<String, List<String>> enWords = new HashMap<>();
        enWords.put("high", List.of("slurx", "slury"));
        enWords.put("medium", List.of("badword"));
        enWords.put("low", List.of("mildword"));
        wordLists.put("en", enWords);

        Map<String, List<String>> frWords = new HashMap<>();
        frWords.put("high", List.of("motgrave"));
        frWords.put("medium", List.of("motmoyen"));
        wordLists.put("fr", frWords);

        Field field = ContentModerationService.class.getDeclaredField("wordLists");
        field.setAccessible(true);
        field.set(moderationService, wordLists);
    }

    // ==================== Null / blank input ====================

    @Test
    @DisplayName("analyzeContent should return NONE and safe for null input")
    void analyzeContent_ShouldReturnSafe_WhenNull() {
        ModerationResult result = moderationService.analyzeContent(null, "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.NONE);
        assertThat(result.isSafe()).isTrue();
        assertThat(result.getReasons()).isEmpty();
    }

    @Test
    @DisplayName("analyzeContent should return NONE and safe for blank input")
    void analyzeContent_ShouldReturnSafe_WhenBlank() {
        ModerationResult result = moderationService.analyzeContent("   ", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.NONE);
        assertThat(result.isSafe()).isTrue();
        assertThat(result.getReasons()).isEmpty();
    }

    // ==================== High severity ====================

    @Test
    @DisplayName("analyzeContent should detect high severity words")
    void analyzeContent_ShouldDetectHighSeverity() {
        ModerationResult result = moderationService.analyzeContent("This contains slurx in it", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.getReasons()).contains("en:high:slurx");
    }

    @Test
    @DisplayName("analyzeContent should detect multiple high severity words")
    void analyzeContent_ShouldDetectMultipleHighSeverityWords() {
        ModerationResult result = moderationService.analyzeContent("slurx and slury here", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.getReasons()).contains("en:high:slurx", "en:high:slury");
    }

    // ==================== Medium severity ====================

    @Test
    @DisplayName("analyzeContent should detect medium severity words")
    void analyzeContent_ShouldDetectMediumSeverity() {
        ModerationResult result = moderationService.analyzeContent("This has badword inside", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.getReasons()).contains("en:medium:badword");
    }

    // ==================== Low severity ====================

    @Test
    @DisplayName("analyzeContent should detect low severity words and mark as safe")
    void analyzeContent_ShouldDetectLowSeverity_ButStillSafe() {
        ModerationResult result = moderationService.analyzeContent("Just a mildword here", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.LOW);
        assertThat(result.isSafe()).isTrue(); // LOW is considered safe
        assertThat(result.getReasons()).contains("en:low:mildword");
    }

    // ==================== Clean text ====================

    @Test
    @DisplayName("analyzeContent should return safe for clean text")
    void analyzeContent_ShouldReturnSafe_WhenTextClean() {
        ModerationResult result = moderationService.analyzeContent("This is a perfectly fine comment.", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.NONE);
        assertThat(result.isSafe()).isTrue();
        assertThat(result.getReasons()).isEmpty();
    }

    // ==================== Leet speak normalization ====================

    @Test
    @DisplayName("analyzeContent should detect leet speak obfuscation")
    void analyzeContent_ShouldDetectLeetSpeak() {
        // "slur1" with leet: "$lur1" -> after normalization: "sluri" -- won't match.
        // Use "b@dword" -> after normalization: "badword"
        ModerationResult result = moderationService.analyzeContent("This has b@dword inside", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.getReasons()).contains("en:medium:badword");
    }

    @Test
    @DisplayName("analyzeContent should detect obfuscation with dots/dashes/underscores")
    void analyzeContent_ShouldDetectObfuscatedWords() {
        // "b.a.d.w.o.r.d" -> deobfuscated to "badword"; parentheses preserve word boundaries
        ModerationResult result = moderationService.analyzeContent("(b.a.d.w.o.r.d)", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.isSafe()).isFalse();
    }

    // ==================== Multi-language ====================

    @Test
    @DisplayName("analyzeContent should check words in the specified locale")
    void analyzeContent_ShouldCheckSpecifiedLocale() {
        ModerationResult result = moderationService.analyzeContent("Ceci est motgrave", "fr");

        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.getReasons()).contains("fr:high:motgrave");
    }

    @Test
    @DisplayName("analyzeContent should check all languages regardless of specified locale")
    void analyzeContent_ShouldCheckAllLanguages() {
        // French word in English locale should still be detected
        ModerationResult result = moderationService.analyzeContent("Text with motgrave", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.getReasons()).contains("fr:high:motgrave");
    }

    @Test
    @DisplayName("analyzeContent should handle null locale by defaulting to en")
    void analyzeContent_ShouldHandleNullLocale() {
        ModerationResult result = moderationService.analyzeContent("Clean text", null);

        assertThat(result.getSeverity()).isEqualTo(Severity.NONE);
        assertThat(result.isSafe()).isTrue();
    }

    // ==================== Embedded images ====================

    @Test
    @DisplayName("analyzeContent should detect HTML img tags")
    void analyzeContent_ShouldDetectHtmlImgTags() {
        ModerationResult result = moderationService.analyzeContent("Check this <img src='evil.jpg'>", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.getReasons()).contains("embedded_image_detected");
    }

    @Test
    @DisplayName("analyzeContent should detect bbcode img tags")
    void analyzeContent_ShouldDetectBbcodeImgTags() {
        ModerationResult result = moderationService.analyzeContent("Check this [img]http://evil.jpg[/img]", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.getReasons()).contains("embedded_image_detected");
    }

    @Test
    @DisplayName("analyzeContent should detect data:image URIs")
    void analyzeContent_ShouldDetectDataImageUris() {
        ModerationResult result = moderationService.analyzeContent("Inline data:image/png;base64,abc", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.getReasons()).contains("embedded_image_detected");
    }

    // ==================== Excessive caps ====================

    @Test
    @DisplayName("analyzeContent should detect excessive caps (shouting)")
    void analyzeContent_ShouldDetectExcessiveCaps() {
        ModerationResult result = moderationService.analyzeContent(
                "WHY ARE YOU DOING THISAAAAAAAAAAAAAAAAAAA", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.LOW);
        assertThat(result.isSafe()).isTrue(); // LOW is still safe
        assertThat(result.getReasons()).contains("excessive_caps");
    }

    @Test
    @DisplayName("analyzeContent should not flag short caps sequences")
    void analyzeContent_ShouldNotFlagShortCaps() {
        ModerationResult result = moderationService.analyzeContent("THIS IS OK", "en");

        assertThat(result.getReasons()).doesNotContain("excessive_caps");
    }

    // ==================== Severity precedence ====================

    @Test
    @DisplayName("analyzeContent should return highest severity when multiple issues found")
    void analyzeContent_ShouldReturnHighestSeverity() {
        // Contains high + low severity words
        ModerationResult result = moderationService.analyzeContent("slurx and mildword", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.getReasons()).contains("en:high:slurx", "en:low:mildword");
    }

    @Test
    @DisplayName("analyzeContent should combine profanity and structural issues")
    void analyzeContent_ShouldCombineProfanityAndStructuralIssues() {
        ModerationResult result = moderationService.analyzeContent(
                "slurx <img src='x'> AAAAAAAAAAAAAAAAAAAAA", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(result.isSafe()).isFalse();
        assertThat(result.getReasons()).contains("en:high:slurx", "embedded_image_detected", "excessive_caps");
    }

    // ==================== Word boundary detection ====================

    @Test
    @DisplayName("analyzeContent should not match partial words")
    void analyzeContent_ShouldNotMatchPartialWords() {
        // "class" contains "ass" but it should not match if "ass" is not in the word list
        // "slur1" should not match "slur10" unless slur1 ends at word boundary
        ModerationResult result = moderationService.analyzeContent("The badwordsmith was here", "en");

        // "badword" appears at start of "badwordsmith" but is followed by 's' (letter), so no word boundary
        assertThat(result.getReasons()).doesNotContain("en:medium:badword");
    }

    @Test
    @DisplayName("analyzeContent should match words at start of text")
    void analyzeContent_ShouldMatchWordsAtStart() {
        ModerationResult result = moderationService.analyzeContent("badword is here", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.getReasons()).contains("en:medium:badword");
    }

    @Test
    @DisplayName("analyzeContent should match words at end of text")
    void analyzeContent_ShouldMatchWordsAtEnd() {
        ModerationResult result = moderationService.analyzeContent("this is a badword", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.getReasons()).contains("en:medium:badword");
    }

    // ==================== Case insensitivity ====================

    @Test
    @DisplayName("analyzeContent should be case insensitive")
    void analyzeContent_ShouldBeCaseInsensitive() {
        ModerationResult result = moderationService.analyzeContent("This has BADWORD inside", "en");

        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.getReasons()).contains("en:medium:badword");
    }
}
