package dev.catananti.service;

import dev.catananti.entity.SiteSetting;
import dev.catananti.repository.SiteSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SiteSettingsServiceTest {

    @Mock private SiteSettingRepository repository;
    @Mock private IdService idService;

    @InjectMocks
    private SiteSettingsService siteSettingsService;

    private SiteSetting siteNameSetting;
    private SiteSetting postsPerPageSetting;
    private SiteSetting commentsEnabledSetting;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(siteSettingsService, "siteUrl", "https://catananti.dev");
        ReflectionTestUtils.setField(siteSettingsService, "activeProfile", "prod");

        siteNameSetting = SiteSetting.builder()
                .id(1L)
                .settingKey("siteName")
                .settingValue("My Blog")
                .settingType("STRING")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        postsPerPageSetting = SiteSetting.builder()
                .id(2L)
                .settingKey("postsPerPage")
                .settingValue("20")
                .settingType("INTEGER")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        commentsEnabledSetting = SiteSetting.builder()
                .id(3L)
                .settingKey("commentsEnabled")
                .settingValue("false")
                .settingType("BOOLEAN")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ==========================================
    // getAllSettings
    // ==========================================
    @Nested
    @DisplayName("getAllSettings")
    class GetAllSettings {

        @Test
        @DisplayName("Should return defaults when no settings stored")
        void shouldReturnDefaultsWhenEmpty() {
            when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(Flux.empty());

            StepVerifier.create(siteSettingsService.getAllSettings())
                    .assertNext(settings -> {
                        assertThat(settings).containsKey("siteName");
                        assertThat(settings).containsKey("siteUrl");
                        assertThat(settings).containsKey("cacheEnabled");
                        assertThat(settings).containsKey("postsPerPage");
                        assertThat(settings).containsKey("commentsEnabled");
                        assertThat(settings).containsKey("newsletterEnabled");

                        // Default values
                        assertThat(settings.get("siteName")).isEqualTo("Leonardo Catananti - Blog");
                        assertThat(settings.get("siteUrl")).isEqualTo("https://catananti.dev");
                        assertThat(settings.get("cacheEnabled")).isEqualTo(true); // not "dev" profile
                        assertThat(settings.get("postsPerPage")).isEqualTo(10);
                        assertThat(settings.get("commentsEnabled")).isEqualTo(true);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should override defaults with stored values")
        void shouldOverrideDefaultsWithStored() {
            when(repository.findAllByOrderBySettingKeyAsc())
                    .thenReturn(Flux.just(siteNameSetting, postsPerPageSetting, commentsEnabledSetting));

            StepVerifier.create(siteSettingsService.getAllSettings())
                    .assertNext(settings -> {
                        assertThat(settings.get("siteName")).isEqualTo("My Blog");
                        assertThat(settings.get("postsPerPage")).isEqualTo(20);
                        assertThat(settings.get("commentsEnabled")).isEqualTo(false);
                        // Dynamic defaults still present
                        assertThat(settings.get("siteUrl")).isEqualTo("https://catananti.dev");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should set cacheEnabled to false in dev profile")
        void shouldDisableCacheInDevProfile() {
            ReflectionTestUtils.setField(siteSettingsService, "activeProfile", "dev");
            when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(Flux.empty());

            StepVerifier.create(siteSettingsService.getAllSettings())
                    .assertNext(settings -> assertThat(settings.get("cacheEnabled")).isEqualTo(false))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should parse BOOLEAN stored values correctly")
        void shouldParseBooleanValues() {
            SiteSetting trueSetting = SiteSetting.builder()
                    .id(10L).settingKey("analyticsEnabled")
                    .settingValue("true").settingType("BOOLEAN").build();

            when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(Flux.just(trueSetting));

            StepVerifier.create(siteSettingsService.getAllSettings())
                    .assertNext(settings ->
                            assertThat(settings.get("analyticsEnabled")).isEqualTo(true))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should parse INTEGER stored values correctly")
        void shouldParseIntegerValues() {
            when(repository.findAllByOrderBySettingKeyAsc())
                    .thenReturn(Flux.just(postsPerPageSetting));

            StepVerifier.create(siteSettingsService.getAllSettings())
                    .assertNext(settings ->
                            assertThat(settings.get("postsPerPage")).isEqualTo(20))
                    .verifyComplete();
        }
    }

    // ==========================================
    // updateSettings
    // ==========================================
    @Nested
    @DisplayName("updateSettings")
    class UpdateSettings {

        @Test
        @DisplayName("Should return all settings when input is null")
        void shouldReturnAllSettingsWhenNull() {
            when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(Flux.empty());

            StepVerifier.create(siteSettingsService.updateSettings(null))
                    .assertNext(settings -> assertThat(settings).containsKey("siteName"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return all settings when input is empty")
        void shouldReturnAllSettingsWhenEmpty() {
            when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(Flux.empty());

            StepVerifier.create(siteSettingsService.updateSettings(Map.of()))
                    .assertNext(settings -> assertThat(settings).containsKey("siteName"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should update existing setting")
        void shouldUpdateExistingSetting() {
            when(repository.findBySettingKey("siteName")).thenReturn(Mono.just(siteNameSetting));
            when(repository.save(any(SiteSetting.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(repository.findAllByOrderBySettingKeyAsc())
                    .thenReturn(Flux.just(siteNameSetting));

            StepVerifier.create(siteSettingsService.updateSettings(Map.of("siteName", "Updated Blog")))
                    .assertNext(settings -> assertThat(settings).isNotNull())
                    .verifyComplete();

            verify(repository).save(argThat(s ->
                    "Updated Blog".equals(s.getSettingValue()) && !s.isNewRecord()));
        }

        @Test
        @DisplayName("Should create new setting if not exists")
        void shouldCreateNewSetting() {
            when(repository.findBySettingKey("customKey")).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(999L);
            when(repository.save(any(SiteSetting.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(Flux.empty());

            StepVerifier.create(siteSettingsService.updateSettings(Map.of("customKey", "customValue")))
                    .assertNext(settings -> assertThat(settings).isNotNull())
                    .verifyComplete();

            verify(repository).save(argThat(s ->
                    "customKey".equals(s.getSettingKey()) && "customValue".equals(s.getSettingValue())));
        }

        @Test
        @DisplayName("Should not allow updating siteUrl (computed key)")
        void shouldNotAllowUpdatingSiteUrl() {
            when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(Flux.empty());

            StepVerifier.create(siteSettingsService.updateSettings(Map.of("siteUrl", "https://evil.com")))
                    .assertNext(settings -> assertThat(settings.get("siteUrl")).isEqualTo("https://catananti.dev"))
                    .verifyComplete();

            verify(repository, never()).findBySettingKey("siteUrl");
        }

        @Test
        @DisplayName("Should not allow updating cacheEnabled (computed key)")
        void shouldNotAllowUpdatingCacheEnabled() {
            when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(Flux.empty());

            StepVerifier.create(siteSettingsService.updateSettings(Map.of("cacheEnabled", false)))
                    .assertNext(settings -> assertThat(settings.get("cacheEnabled")).isEqualTo(true))
                    .verifyComplete();

            verify(repository, never()).findBySettingKey("cacheEnabled");
        }

        @Test
        @DisplayName("Should filter out entries with blank keys")
        void shouldFilterBlankKeys() {
            when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(Flux.empty());

            StepVerifier.create(siteSettingsService.updateSettings(Map.of("", "value")))
                    .assertNext(settings -> assertThat(settings).isNotNull())
                    .verifyComplete();

            verify(repository, never()).findBySettingKey("");
        }

        @Test
        @DisplayName("Should infer BOOLEAN type for true/false values")
        void shouldInferBooleanType() {
            when(repository.findBySettingKey("commentsEnabled")).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(100L);
            when(repository.save(any(SiteSetting.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(Flux.empty());

            StepVerifier.create(siteSettingsService.updateSettings(Map.of("commentsEnabled", "true")))
                    .assertNext(settings -> assertThat(settings).isNotNull())
                    .verifyComplete();

            verify(repository).save(argThat(s -> "BOOLEAN".equals(s.getSettingType())));
        }

        @Test
        @DisplayName("Should infer INTEGER type for numeric values")
        void shouldInferIntegerType() {
            when(repository.findBySettingKey("postsPerPage")).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(101L);
            when(repository.save(any(SiteSetting.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(Flux.empty());

            StepVerifier.create(siteSettingsService.updateSettings(Map.of("postsPerPage", "15")))
                    .assertNext(settings -> assertThat(settings).isNotNull())
                    .verifyComplete();

            verify(repository).save(argThat(s -> "INTEGER".equals(s.getSettingType())));
        }
    }

    // ==========================================
    // getSetting
    // ==========================================
    @Nested
    @DisplayName("getSetting")
    class GetSetting {

        @Test
        @DisplayName("Should return stored setting value")
        void shouldReturnStoredValue() {
            when(repository.findBySettingKey("siteName")).thenReturn(Mono.just(siteNameSetting));

            StepVerifier.create(siteSettingsService.getSetting("siteName"))
                    .assertNext(value -> assertThat(value).isEqualTo("My Blog"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return default for known key not in DB")
        void shouldReturnDefaultWhenNotStored() {
            when(repository.findBySettingKey("siteName")).thenReturn(Mono.empty());

            StepVerifier.create(siteSettingsService.getSetting("siteName"))
                    .assertNext(value -> assertThat(value).isEqualTo("Leonardo Catananti - Blog"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return siteUrl from config for siteUrl key")
        void shouldReturnSiteUrlFromConfig() {
            when(repository.findBySettingKey("siteUrl")).thenReturn(Mono.empty());

            StepVerifier.create(siteSettingsService.getSetting("siteUrl"))
                    .assertNext(value -> assertThat(value).isEqualTo("https://catananti.dev"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return cacheEnabled based on active profile")
        void shouldReturnCacheEnabledBasedOnProfile() {
            when(repository.findBySettingKey("cacheEnabled")).thenReturn(Mono.empty());

            StepVerifier.create(siteSettingsService.getSetting("cacheEnabled"))
                    .assertNext(value -> assertThat(value).isEqualTo(true))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty for unknown key with no default")
        void shouldReturnEmptyForUnknownKey() {
            when(repository.findBySettingKey("unknownKey")).thenReturn(Mono.empty());

            StepVerifier.create(siteSettingsService.getSetting("unknownKey"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should parse stored boolean correctly")
        void shouldParseStoredBoolean() {
            when(repository.findBySettingKey("commentsEnabled"))
                    .thenReturn(Mono.just(commentsEnabledSetting));

            StepVerifier.create(siteSettingsService.getSetting("commentsEnabled"))
                    .assertNext(value -> {
                        assertThat(value).isInstanceOf(Boolean.class);
                        assertThat(value).isEqualTo(false);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should parse stored integer correctly")
        void shouldParseStoredInteger() {
            when(repository.findBySettingKey("postsPerPage"))
                    .thenReturn(Mono.just(postsPerPageSetting));

            StepVerifier.create(siteSettingsService.getSetting("postsPerPage"))
                    .assertNext(value -> {
                        assertThat(value).isInstanceOf(Integer.class);
                        assertThat(value).isEqualTo(20);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return string value for STRING type")
        void shouldReturnStringValue() {
            when(repository.findBySettingKey("siteName"))
                    .thenReturn(Mono.just(siteNameSetting));

            StepVerifier.create(siteSettingsService.getSetting("siteName"))
                    .assertNext(value -> {
                        assertThat(value).isInstanceOf(String.class);
                        assertThat(value).isEqualTo("My Blog");
                    })
                    .verifyComplete();
        }
    }
}
