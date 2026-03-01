package dev.catananti.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ContentModerationService {

    private final ObjectMapper objectMapper;

    public enum Severity { NONE, LOW, MEDIUM, HIGH }

    @Data
    @Builder
    public static class ModerationResult {
        private Severity severity;
        private boolean safe;
        private List<String> reasons;
    }

    private Map<String, Map<String, List<String>>> wordLists = new HashMap<>();

    private static final Map<Character, Character> LEET_MAP = Map.ofEntries(
            Map.entry('@', 'a'), Map.entry('4', 'a'),
            Map.entry('3', 'e'), Map.entry('€', 'e'),
            Map.entry('1', 'i'), Map.entry('!', 'i'),
            Map.entry('0', 'o'), Map.entry('$', 's'),
            Map.entry('5', 's'), Map.entry('7', 't'),
            Map.entry('+', 't'), Map.entry('8', 'b')
    );

    private static final Pattern OBFUSCATION_PATTERN = Pattern.compile("[.\\-_*\\s]+");
    private static final Pattern HTML_IMG_PATTERN = Pattern.compile("<\\s*img\\b|\\[img\\]|data:image/", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXCESSIVE_CAPS = Pattern.compile("[A-Z]{20,}");

    @PostConstruct
    void loadWordLists() {
        try {
            InputStream is = new ClassPathResource("moderation/profanity-words.json").getInputStream();
            wordLists = objectMapper.readValue(is, new TypeReference<>() {});
            int total = wordLists.values().stream()
                    .flatMap(m -> m.values().stream())
                    .mapToInt(List::size)
                    .sum();
            log.info("Loaded {} profanity words across {} languages", total, wordLists.size());
        } catch (Exception e) {
            log.error("Failed to load profanity word lists", e);
            wordLists = new HashMap<>();
        }
    }

    public ModerationResult analyzeContent(String text, String locale) {
        if (text == null || text.isBlank()) {
            return ModerationResult.builder().severity(Severity.NONE).safe(true).reasons(List.of()).build();
        }

        List<String> reasons = new ArrayList<>();
        Severity maxSeverity = Severity.NONE;

        String normalized = normalizeLeetSpeak(text.toLowerCase());
        String deobfuscated = OBFUSCATION_PATTERN.matcher(normalized).replaceAll("");

        // Check all languages, prioritizing the specified locale
        List<String> langsToCheck = new ArrayList<>();
        String lang = locale != null ? locale.substring(0, Math.min(2, locale.length())).toLowerCase() : "en";
        langsToCheck.add(lang);
        for (String l : wordLists.keySet()) {
            if (!l.equals(lang)) langsToCheck.add(l);
        }

        for (String checkLang : langsToCheck) {
            Map<String, List<String>> langWords = wordLists.get(checkLang);
            if (langWords == null) continue;

            Severity langSeverity = checkWordList(normalized, deobfuscated, langWords, reasons, checkLang);
            if (langSeverity.ordinal() > maxSeverity.ordinal()) {
                maxSeverity = langSeverity;
            }
        }

        // Check for embedded images
        if (HTML_IMG_PATTERN.matcher(text).find()) {
            reasons.add("embedded_image_detected");
            if (Severity.MEDIUM.ordinal() > maxSeverity.ordinal()) {
                maxSeverity = Severity.MEDIUM;
            }
        }

        // Check for excessive caps (shouting)
        if (EXCESSIVE_CAPS.matcher(text).find()) {
            reasons.add("excessive_caps");
            if (Severity.LOW.ordinal() > maxSeverity.ordinal()) {
                maxSeverity = Severity.LOW;
            }
        }

        return ModerationResult.builder()
                .severity(maxSeverity)
                .safe(maxSeverity == Severity.NONE || maxSeverity == Severity.LOW)
                .reasons(reasons)
                .build();
    }

    private Severity checkWordList(String normalized, String deobfuscated,
                                    Map<String, List<String>> langWords,
                                    List<String> reasons, String lang) {
        Severity max = Severity.NONE;

        for (Map.Entry<String, List<String>> entry : langWords.entrySet()) {
            Severity severity = switch (entry.getKey()) {
                case "high" -> Severity.HIGH;
                case "medium" -> Severity.MEDIUM;
                case "low" -> Severity.LOW;
                default -> Severity.NONE;
            };

            for (String word : entry.getValue()) {
                String normalizedWord = word.toLowerCase();
                if (containsWord(normalized, normalizedWord) || containsWord(deobfuscated, normalizedWord)) {
                    reasons.add(lang + ":" + entry.getKey() + ":" + normalizedWord);
                    if (severity.ordinal() > max.ordinal()) {
                        max = severity;
                    }
                }
            }
        }

        return max;
    }

    private boolean containsWord(String text, String word) {
        if (word.contains(" ")) {
            return text.contains(word);
        }
        int idx = text.indexOf(word);
        while (idx >= 0) {
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            boolean endOk = idx + word.length() >= text.length()
                    || !Character.isLetterOrDigit(text.charAt(idx + word.length()));
            if (startOk && endOk) return true;
            idx = text.indexOf(word, idx + 1);
        }
        return false;
    }

    private String normalizeLeetSpeak(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            sb.append(LEET_MAP.getOrDefault(c, c));
        }
        return sb.toString();
    }
}
