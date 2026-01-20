package dev.catananti.util;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeDateSorterTest {

    // ============ parseDate tests ============

    @Test
    void parseDate_englishMonthYear() {
        assertThat(ResumeDateSorter.parseDate("June 2024")).isEqualTo(YearMonth.of(2024, 6));
        assertThat(ResumeDateSorter.parseDate("January 2017")).isEqualTo(YearMonth.of(2017, 1));
        assertThat(ResumeDateSorter.parseDate("December 2020")).isEqualTo(YearMonth.of(2020, 12));
    }

    @Test
    void parseDate_italianMonthYear() {
        assertThat(ResumeDateSorter.parseDate("Giugno 2024")).isEqualTo(YearMonth.of(2024, 6));
        assertThat(ResumeDateSorter.parseDate("Gennaio 2017")).isEqualTo(YearMonth.of(2017, 1));
        assertThat(ResumeDateSorter.parseDate("Dicembre 2020")).isEqualTo(YearMonth.of(2020, 12));
        assertThat(ResumeDateSorter.parseDate("Luglio 2025")).isEqualTo(YearMonth.of(2025, 7));
    }

    @Test
    void parseDate_portugueseMonthYear() {
        assertThat(ResumeDateSorter.parseDate("Julho 2025")).isEqualTo(YearMonth.of(2025, 7));
    }

    @Test
    void parseDate_yearOnly() {
        assertThat(ResumeDateSorter.parseDate("2025")).isEqualTo(YearMonth.of(2025, 1));
        assertThat(ResumeDateSorter.parseDate("2017")).isEqualTo(YearMonth.of(2017, 1));
    }

    @Test
    void parseDate_nullOrBlank_returnsFarPast() {
        assertThat(ResumeDateSorter.parseDate(null)).isEqualTo(YearMonth.of(1900, 1));
        assertThat(ResumeDateSorter.parseDate("")).isEqualTo(YearMonth.of(1900, 1));
        assertThat(ResumeDateSorter.parseDate("   ")).isEqualTo(YearMonth.of(1900, 1));
    }

    // ============ isOngoing tests ============

    @Test
    void isOngoing_nullOrBlank_true() {
        assertThat(ResumeDateSorter.isOngoing(null)).isTrue();
        assertThat(ResumeDateSorter.isOngoing("")).isTrue();
        assertThat(ResumeDateSorter.isOngoing("  ")).isTrue();
    }

    @Test
    void isOngoing_presentTokens_true() {
        assertThat(ResumeDateSorter.isOngoing("Present")).isTrue();
        assertThat(ResumeDateSorter.isOngoing("Presente")).isTrue();
        assertThat(ResumeDateSorter.isOngoing("Atual")).isTrue();
        assertThat(ResumeDateSorter.isOngoing("Attuale")).isTrue();
    }

    @Test
    void isOngoing_dateString_false() {
        assertThat(ResumeDateSorter.isOngoing("June 2024")).isFalse();
        assertThat(ResumeDateSorter.isOngoing("December 2020")).isFalse();
    }

    // ============ Comparator tests ============

    record Entry(String startDate, String endDate) {}

    @Test
    void experienceComparator_ongoingFirst() {
        var entries = List.of(
            new Entry("June 2018", "July 2019"),
            new Entry("June 2024", null),          // ongoing
            new Entry("August 2022", "June 2023")
        );

        var sorted = entries.stream()
                .sorted(ResumeDateSorter.experienceComparator(Entry::startDate, Entry::endDate))
                .toList();

        assertThat(sorted.get(0).startDate()).isEqualTo("June 2024");      // ongoing first
        assertThat(sorted.get(1).startDate()).isEqualTo("August 2022");    // then most recent
        assertThat(sorted.get(2).startDate()).isEqualTo("June 2018");      // oldest last
    }

    @Test
    void experienceComparator_presentTokenOngoing() {
        var entries = List.of(
            new Entry("June 2018", "July 2019"),
            new Entry("June 2024", "Present")       // ongoing via token
        );

        var sorted = entries.stream()
                .sorted(ResumeDateSorter.experienceComparator(Entry::startDate, Entry::endDate))
                .toList();

        assertThat(sorted.get(0).startDate()).isEqualTo("June 2024");     // ongoing first
        assertThat(sorted.get(1).startDate()).isEqualTo("June 2018");
    }

    @Test
    void experienceComparator_descendingByStartDate() {
        var entries = List.of(
            new Entry("February 2016", "June 2018"),
            new Entry("September 2021", "September 2022"),
            new Entry("June 2019", "June 2021"),
            new Entry("August 2023", "June 2024")
        );

        var sorted = entries.stream()
                .sorted(ResumeDateSorter.experienceComparator(Entry::startDate, Entry::endDate))
                .toList();

        assertThat(sorted.get(0).startDate()).isEqualTo("August 2023");
        assertThat(sorted.get(1).startDate()).isEqualTo("September 2021");
        assertThat(sorted.get(2).startDate()).isEqualTo("June 2019");
        assertThat(sorted.get(3).startDate()).isEqualTo("February 2016");
    }

    @Test
    void educationComparator_ongoingFirst_thenDescending() {
        var entries = List.of(
            new Entry("January 2017", "December 2020"),
            new Entry("July 2025", null)            // ongoing postgraduate
        );

        var sorted = entries.stream()
                .sorted(ResumeDateSorter.educationComparator(Entry::startDate, Entry::endDate))
                .toList();

        assertThat(sorted.get(0).startDate()).isEqualTo("July 2025");      // ongoing first
        assertThat(sorted.get(1).startDate()).isEqualTo("January 2017");   // completed last
    }

    @Test
    void educationComparator_italianDates() {
        var entries = List.of(
            new Entry("Gennaio 2017", "Dicembre 2020"),
            new Entry("Luglio 2025", null)
        );

        var sorted = entries.stream()
                .sorted(ResumeDateSorter.educationComparator(Entry::startDate, Entry::endDate))
                .toList();

        assertThat(sorted.get(0).startDate()).isEqualTo("Luglio 2025");
        assertThat(sorted.get(1).startDate()).isEqualTo("Gennaio 2017");
    }

    @Test
    void experienceComparator_sameStartDate_laterEndFirst() {
        var entries = List.of(
            new Entry("June 2021", "September 2021"),
            new Entry("June 2021", "December 2021")
        );

        var sorted = entries.stream()
                .sorted(ResumeDateSorter.experienceComparator(Entry::startDate, Entry::endDate))
                .toList();

        assertThat(sorted.get(0).endDate()).isEqualTo("December 2021");  // later end first
        assertThat(sorted.get(1).endDate()).isEqualTo("September 2021");
    }

    @Test
    void fullExperienceTimeline_matchesExpectedOrder() {
        // Real data from the DB
        var entries = List.of(
            new Entry("February 2016", "June 2018"),
            new Entry("June 2018", "July 2019"),
            new Entry("June 2019", "June 2021"),
            new Entry("June 2021", "September 2021"),
            new Entry("September 2021", "September 2022"),
            new Entry("August 2022", "June 2023"),
            new Entry("August 2023", "June 2024"),
            new Entry("June 2024", "Present")
        );

        var sorted = entries.stream()
                .sorted(ResumeDateSorter.experienceComparator(Entry::startDate, Entry::endDate))
                .toList();

        // Expected: ongoing first, then most recent to oldest
        assertThat(sorted.get(0).endDate()).isEqualTo("Present");
        assertThat(sorted.get(1).startDate()).isEqualTo("August 2023");
        assertThat(sorted.get(2).startDate()).isEqualTo("August 2022");
        assertThat(sorted.get(3).startDate()).isEqualTo("September 2021");
        assertThat(sorted.get(4).startDate()).isEqualTo("June 2021");
        assertThat(sorted.get(5).startDate()).isEqualTo("June 2019");
        assertThat(sorted.get(6).startDate()).isEqualTo("June 2018");
        assertThat(sorted.get(7).startDate()).isEqualTo("February 2016");
    }
}
