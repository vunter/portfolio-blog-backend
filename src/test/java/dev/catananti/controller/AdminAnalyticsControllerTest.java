package dev.catananti.controller;

import dev.catananti.dto.AnalyticsSummary;
import dev.catananti.service.AnalyticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsControllerTest {

    @Mock
    private AnalyticsService analyticsService;

    @InjectMocks
    private AdminAnalyticsController controller;

    private AnalyticsSummary buildSummary() {
        return AnalyticsSummary.builder()
                .totalViews(1000L)
                .totalLikes(250L)
                .totalShares(50L)
                .uniqueVisitors(700L)
                .dailyViews(List.of())
                .topArticles(List.of())
                .topReferrers(List.of())
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/admin/analytics/summary")
    class GetAnalyticsSummary {

        @Test
        @DisplayName("Should return analytics summary for default 30 days")
        void shouldReturnAnalyticsSummaryDefault() {
            AnalyticsSummary summary = buildSummary();
            when(analyticsService.getAnalyticsSummary(30)).thenReturn(Mono.just(summary));

            StepVerifier.create(controller.getAnalyticsSummary(30))
                    .assertNext(result -> {
                        assertThat(result.getTotalViews()).isEqualTo(1000L);
                        assertThat(result.getTotalLikes()).isEqualTo(250L);
                        assertThat(result.getTotalShares()).isEqualTo(50L);
                        assertThat(result.getUniqueVisitors()).isEqualTo(700L);
                    })
                    .verifyComplete();

            verify(analyticsService).getAnalyticsSummary(30);
        }

        @Test
        @DisplayName("Should return analytics summary for custom days")
        void shouldReturnAnalyticsSummaryCustomDays() {
            AnalyticsSummary summary = buildSummary();
            when(analyticsService.getAnalyticsSummary(7)).thenReturn(Mono.just(summary));

            StepVerifier.create(controller.getAnalyticsSummary(7))
                    .assertNext(result -> assertThat(result.getTotalViews()).isEqualTo(1000L))
                    .verifyComplete();

            verify(analyticsService).getAnalyticsSummary(7);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/analytics")
    class GetAnalytics {

        @Test
        @DisplayName("Should return analytics with period string '30d'")
        void shouldReturnAnalyticsWithPeriod30d() {
            AnalyticsSummary summary = buildSummary();
            when(analyticsService.getAnalyticsSummary(30)).thenReturn(Mono.just(summary));

            StepVerifier.create(controller.getAnalytics("30d"))
                    .assertNext(result -> {
                        assertThat(result.getTotalViews()).isEqualTo(1000L);
                        assertThat(result.getUniqueVisitors()).isEqualTo(700L);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return analytics with period string '7d'")
        void shouldReturnAnalyticsWithPeriod7d() {
            AnalyticsSummary summary = buildSummary();
            when(analyticsService.getAnalyticsSummary(7)).thenReturn(Mono.just(summary));

            StepVerifier.create(controller.getAnalytics("7d"))
                    .assertNext(result -> assertThat(result.getTotalViews()).isEqualTo(1000L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should default to 30 days for invalid period")
        void shouldDefaultTo30ForInvalidPeriod() {
            AnalyticsSummary summary = buildSummary();
            when(analyticsService.getAnalyticsSummary(30)).thenReturn(Mono.just(summary));

            StepVerifier.create(controller.getAnalytics("invalid"))
                    .assertNext(result -> assertThat(result.getTotalViews()).isEqualTo(1000L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should default to 30 days for blank period")
        void shouldDefaultTo30ForBlankPeriod() {
            AnalyticsSummary summary = buildSummary();
            when(analyticsService.getAnalyticsSummary(30)).thenReturn(Mono.just(summary));

            StepVerifier.create(controller.getAnalytics(""))
                    .assertNext(result -> assertThat(result.getTotalViews()).isEqualTo(1000L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should clamp period to max 365")
        void shouldClampPeriodToMax365() {
            AnalyticsSummary summary = buildSummary();
            when(analyticsService.getAnalyticsSummary(365)).thenReturn(Mono.just(summary));

            StepVerifier.create(controller.getAnalytics("999d"))
                    .assertNext(result -> assertThat(result.getTotalViews()).isEqualTo(1000L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle numeric period without suffix")
        void shouldHandleNumericPeriodWithoutSuffix() {
            AnalyticsSummary summary = buildSummary();
            when(analyticsService.getAnalyticsSummary(14)).thenReturn(Mono.just(summary));

            StepVerifier.create(controller.getAnalytics("14"))
                    .assertNext(result -> assertThat(result.getTotalViews()).isEqualTo(1000L))
                    .verifyComplete();
        }
    }
}
