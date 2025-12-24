package dev.catananti.util;

import dev.catananti.config.LocaleConstants;

import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for sorting resume entries (experiences, educations) by date.
 * Parses multilingual date strings like "June 2024", "Giugno 2024", "Julho 2025", "2025".
 * <p>
 * Sorting rules:
 * <ul>
 *   <li>Entries with no end date (ongoing) come first</li>
 *   <li>Then sorted by start date descending (most recent first)</li>
 *   <li>If start dates are equal, entries with later end dates come first</li>
 * </ul>
 */
public final class ResumeDateSorter {

    private ResumeDateSorter() {}

    /** Sentinel: ongoing entries sort as "far future" so they appear first in descending order. */
    private static final YearMonth FAR_FUTURE = YearMonth.of(9999, 12);

    /** Sentinel: unparseable dates sort as "far past" so they appear last. */
    private static final YearMonth FAR_PAST = YearMonth.of(1900, 1);

    /** Month name → Month mapping for all supported locales. Built once at class load. */
    private static final Map<String, Month> MONTH_LOOKUP = buildMonthLookup();

    /** Pattern: "MonthName Year" or just "Year". */
    private static final Pattern MONTH_YEAR_PATTERN = Pattern.compile("^(.+?)\\s+(\\d{4})$");
    private static final Pattern YEAR_ONLY_PATTERN = Pattern.compile("^(\\d{4})$");

    /** Tokens that mean "ongoing" / "present". */
    private static final java.util.Set<String> ONGOING_TOKENS = java.util.Set.of(
            "present", "presente", "atual", "corrente", "actual", "actuellement",
            "gegenwart", "attuale", "現在", "当前"
    );

    /**
     * Comparator for experiences: most recent first (descending by start date).
     * Ongoing entries (no end date) always come first.
     */
    public static <T> Comparator<T> experienceComparator(
            java.util.function.Function<T, String> startDateFn,
            java.util.function.Function<T, String> endDateFn) {
        return (a, b) -> {
            boolean aOngoing = isOngoing(endDateFn.apply(a));
            boolean bOngoing = isOngoing(endDateFn.apply(b));

            // Ongoing entries first
            if (aOngoing != bOngoing) {
                return aOngoing ? -1 : 1;
            }

            // Then by start date descending (most recent first)
            YearMonth aStart = parseDate(startDateFn.apply(a));
            YearMonth bStart = parseDate(startDateFn.apply(b));
            int cmp = bStart.compareTo(aStart);
            if (cmp != 0) return cmp;

            // If start dates equal, later end date first
            YearMonth aEnd = aOngoing ? FAR_FUTURE : parseDate(endDateFn.apply(a));
            YearMonth bEnd = bOngoing ? FAR_FUTURE : parseDate(endDateFn.apply(b));
            return bEnd.compareTo(aEnd);
        };
    }

    /**
     * Comparator for education: ongoing/most recent first, completed/oldest last.
     * Same logic as experience — ongoing first, then descending by start date.
     */
    public static <T> Comparator<T> educationComparator(
            java.util.function.Function<T, String> startDateFn,
            java.util.function.Function<T, String> endDateFn) {
        return experienceComparator(startDateFn, endDateFn);
    }

    /**
     * Check if an end date represents an ongoing entry.
     */
    static boolean isOngoing(String endDate) {
        if (endDate == null || endDate.isBlank()) return true;
        return ONGOING_TOKENS.contains(endDate.trim().toLowerCase());
    }

    /**
     * Parse a multilingual date string like "June 2024", "Giugno 2024", "2025" into a YearMonth.
     * Returns FAR_PAST if unparseable.
     */
    static YearMonth parseDate(String date) {
        if (date == null || date.isBlank()) return FAR_PAST;
        String trimmed = date.trim();

        // Try "Year only" pattern first (e.g., "2025")
        Matcher yearOnly = YEAR_ONLY_PATTERN.matcher(trimmed);
        if (yearOnly.matches()) {
            return YearMonth.of(Integer.parseInt(yearOnly.group(1)), 1);
        }

        // Try "Month Year" pattern (e.g., "June 2024", "Giugno 2024")
        Matcher monthYear = MONTH_YEAR_PATTERN.matcher(trimmed);
        if (monthYear.matches()) {
            String monthStr = monthYear.group(1).trim().toLowerCase();
            int year = Integer.parseInt(monthYear.group(2));
            Month month = MONTH_LOOKUP.get(monthStr);
            if (month != null) {
                return YearMonth.of(year, month);
            }
        }

        return FAR_PAST;
    }

    /**
     * Build a lookup map of month name (in all supported locales) → Month enum.
     * Covers both full names ("January", "Janeiro", "Gennaio") and short names ("Jan", "Jan", "Gen").
     */
    private static Map<String, Month> buildMonthLookup() {
        Map<String, Month> lookup = new HashMap<>();
        for (Locale locale : LocaleConstants.SUPPORTED_LOCALES) {
            for (Month month : Month.values()) {
                // Full name
                String full = month.getDisplayName(TextStyle.FULL, locale).toLowerCase();
                lookup.put(full, month);
                // Short name
                String shortName = month.getDisplayName(TextStyle.SHORT, locale).toLowerCase();
                // Remove trailing dot from abbreviated month names (e.g., "jan." → "jan")
                lookup.put(shortName.replace(".", ""), month);
                lookup.put(shortName, month);
                // Standalone forms (some locales differ)
                String fullStandalone = month.getDisplayName(TextStyle.FULL_STANDALONE, locale).toLowerCase();
                lookup.put(fullStandalone, month);
                String shortStandalone = month.getDisplayName(TextStyle.SHORT_STANDALONE, locale).toLowerCase();
                lookup.put(shortStandalone.replace(".", ""), month);
                lookup.put(shortStandalone, month);
            }
        }
        return Map.copyOf(lookup);
    }
}
