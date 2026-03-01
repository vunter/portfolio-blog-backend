package dev.catananti.controller;

import dev.catananti.dto.AnalyticsComparison;
import dev.catananti.dto.AnalyticsSummary;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.AnalyticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsControllerTest {

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminAnalyticsController controller;

    private <T> Mono<T> withAdminAuth(Mono<T> mono) {
        lenient().when(userRepository.findByEmail("admin@test.com"))
                .thenReturn(Mono.just(dev.catananti.entity.User.builder()
                        .id(1L).email("admin@test.com").name("Admin").role("ADMIN").build()));
        var auth = new UsernamePasswordAuthenticationToken("admin@test.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        return mono.contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                Mono.just(new SecurityContextImpl(auth))));
    }

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

            StepVerifier.create(withAdminAuth(controller.getAnalyticsSummary(30)))
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

            StepVerifier.create(withAdminAuth(controller.getAnalyticsSummary(7)))
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

            StepVerifier.create(withAdminAuth(controller.getAnalytics("30d")))
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

            StepVerifier.create(withAdminAuth(controller.getAnalytics("7d")))
                    .assertNext(result -> assertThat(result.getTotalViews()).isEqualTo(1000L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should default to 30 days for invalid period")
        void shouldDefaultTo30ForInvalidPeriod() {
            AnalyticsSummary summary = buildSummary();
            when(analyticsService.getAnalyticsSummary(30)).thenReturn(Mono.just(summary));

            StepVerifier.create(withAdminAuth(controller.getAnalytics("invalid")))
                    .assertNext(result -> assertThat(result.getTotalViews()).isEqualTo(1000L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should default to 30 days for blank period")
        void shouldDefaultTo30ForBlankPeriod() {
            AnalyticsSummary summary = buildSummary();
            when(analyticsService.getAnalyticsSummary(30)).thenReturn(Mono.just(summary));

            StepVerifier.create(withAdminAuth(controller.getAnalytics("")))
                    .assertNext(result -> assertThat(result.getTotalViews()).isEqualTo(1000L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should clamp period to max 365")
        void shouldClampPeriodToMax365() {
            AnalyticsSummary summary = buildSummary();
            when(analyticsService.getAnalyticsSummary(365)).thenReturn(Mono.just(summary));

            StepVerifier.create(withAdminAuth(controller.getAnalytics("999d")))
                    .assertNext(result -> assertThat(result.getTotalViews()).isEqualTo(1000L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle numeric period without suffix")
        void shouldHandleNumericPeriodWithoutSuffix() {
            AnalyticsSummary summary = buildSummary();
            when(analyticsService.getAnalyticsSummary(14)).thenReturn(Mono.just(summary));

            StepVerifier.create(withAdminAuth(controller.getAnalytics("14")))
                    .assertNext(result -> assertThat(result.getTotalViews()).isEqualTo(1000L))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/analytics/compare")
    class GetAnalyticsComparison {

        @Test
        @DisplayName("Should return comparison for default period")
        void shouldReturnComparisonDefault() {
            AnalyticsComparison comparison = AnalyticsComparison.builder()
                    .currentViews(1000L).currentLikes(250L).currentShares(50L)
                    .previousViews(800L).previousLikes(200L).previousShares(40L)
                    .build();
            when(analyticsService.getAnalyticsComparison(30)).thenReturn(Mono.just(comparison));

            StepVerifier.create(withAdminAuth(controller.getAnalyticsComparison("30d")))
                    .assertNext(result -> {
                        assertThat(result.getCurrentViews()).isEqualTo(1000L);
                        assertThat(result.getPreviousViews()).isEqualTo(800L);
                        assertThat(result.getCurrentLikes()).isEqualTo(250L);
                        assertThat(result.getPreviousLikes()).isEqualTo(200L);
                    })
                    .verifyComplete();

            verify(analyticsService).getAnalyticsComparison(30);
        }

        @Test
        @DisplayName("Should return comparison for 7d period")
        void shouldReturnComparison7d() {
            AnalyticsComparison comparison = AnalyticsComparison.builder()
                    .currentViews(500L).currentLikes(100L).currentShares(20L)
                    .previousViews(400L).previousLikes(90L).previousShares(25L)
                    .build();
            when(analyticsService.getAnalyticsComparison(7)).thenReturn(Mono.just(comparison));

            StepVerifier.create(withAdminAuth(controller.getAnalyticsComparison("7d")))
                    .assertNext(result -> {
                        assertThat(result.getCurrentViews()).isEqualTo(500L);
                        assertThat(result.getCurrentShares()).isEqualTo(20L);
                        assertThat(result.getPreviousShares()).isEqualTo(25L);
                    })
                    .verifyComplete();
        }
    }
}
