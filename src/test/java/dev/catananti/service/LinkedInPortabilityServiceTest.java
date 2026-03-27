package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("LinkedInPortabilityService")
class LinkedInPortabilityServiceTest {

    private LinkedInPortabilityService service;
    private ReactiveStringRedisTemplate redisTemplate;
    private ReactiveValueOperations<String, String> valueOps;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new LinkedInPortabilityService(redisTemplate);

        setField("clientId", "test-client-id");
        setField("clientSecret", "test-client-secret");
        setField("redirectBaseUrl", "https://example.com");
        setField("portabilityEnabled", true);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = LinkedInPortabilityService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    @Nested
    @DisplayName("isPortabilityEnabled")
    class IsPortabilityEnabled {

        @Test
        @DisplayName("should return true when enabled and clientId is set")
        void shouldReturnTrueWhenEnabledAndClientIdSet() {
            assertThat(service.isPortabilityEnabled()).isTrue();
        }

        @Test
        @DisplayName("should return false when disabled")
        void shouldReturnFalseWhenDisabled() throws Exception {
            setField("portabilityEnabled", false);
            assertThat(service.isPortabilityEnabled()).isFalse();
        }

        @Test
        @DisplayName("should return false when clientId is blank")
        void shouldReturnFalseWhenClientIdBlank() throws Exception {
            setField("clientId", "");
            assertThat(service.isPortabilityEnabled()).isFalse();
        }

        @Test
        @DisplayName("should return false when clientId is null")
        void shouldReturnFalseWhenClientIdNull() throws Exception {
            setField("clientId", null);
            assertThat(service.isPortabilityEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("getPortabilityAuthUrl")
    class GetPortabilityAuthUrl {

        @Test
        @DisplayName("should build correct authorization URL with state")
        void shouldBuildCorrectAuthUrl() {
            String url = service.getPortabilityAuthUrl("random-state-123");

            assertThat(url).contains("response_type=code");
            assertThat(url).contains("client_id=test-client-id");
            assertThat(url).contains("redirect_uri=https://example.com/api/v1/resume/import/linkedin/callback");
            assertThat(url).contains("scope=r_dma_portability_3rd_party");
            assertThat(url).contains("state=random-state-123");
        }

        @Test
        @DisplayName("should start with LinkedIn authorization URL")
        void shouldStartWithLinkedInAuthUrl() {
            String url = service.getPortabilityAuthUrl("state");
            assertThat(url).startsWith("https://www.linkedin.com/oauth/v2/authorization?");
        }
    }

    @Nested
    @DisplayName("storeImportResult")
    class StoreImportResult {

        @Test
        @DisplayName("should store JSON in Redis and return key")
        void shouldStoreAndReturnKey() {
            when(valueOps.set(anyString(), eq("{\"test\":true}"), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.storeImportResult("{\"test\":true}", "user123"))
                    .assertNext(key -> assertThat(key).isNotBlank())
                    .verifyComplete();

            verify(valueOps).set(argThat(k -> k.startsWith("linkedin:import:user123:")),
                    eq("{\"test\":true}"), eq(Duration.ofMinutes(10)));
        }
    }

    @Nested
    @DisplayName("retrieveImportResult")
    class RetrieveImportResult {

        @Test
        @DisplayName("should retrieve and delete from Redis")
        void shouldRetrieveAndDelete() {
            String fullKey = "linkedin:import:user123:key-abc";
            when(valueOps.getAndDelete(fullKey)).thenReturn(Mono.just("{\"data\":\"value\"}"));

            StepVerifier.create(service.retrieveImportResult("key-abc", "user123"))
                    .expectNext("{\"data\":\"value\"}")
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty when key does not exist")
        void shouldReturnEmptyWhenKeyNotFound() {
            when(valueOps.getAndDelete(anyString())).thenReturn(Mono.empty());

            StepVerifier.create(service.retrieveImportResult("missing-key", "user123"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("mapToProfileRequest (via reflection)")
    class MapToProfileRequest {

        @Test
        @DisplayName("should map profile domain to full name, title, summary")
        void shouldMapProfileFields() throws Exception {
            List<Map<String, Object>> profile = List.of(Map.of(
                    "First Name", "John",
                    "Last Name", "Doe",
                    "Headline", "Software Engineer",
                    "Summary", "Experienced developer",
                    "Geo Location", "New York"
            ));

            ResumeProfileRequest result = invokeMapToProfileRequest(
                    profile, empty(), empty(), empty(), empty(), empty(), empty());

            assertThat(result.getFullName()).isEqualTo("John Doe");
            assertThat(result.getTitle()).isEqualTo("Software Engineer");
            assertThat(result.getProfessionalSummary()).isEqualTo("Experienced developer");
            assertThat(result.getLocation()).isEqualTo("New York");
        }

        @Test
        @DisplayName("should map positions to experiences")
        void shouldMapPositions() throws Exception {
            List<Map<String, Object>> positions = List.of(
                    Map.of("Company Name", "Acme Corp", "Title", "Senior Dev",
                            "Started On", "Jan 2020", "Description", "Built microservices"),
                    Map.of("Company Name", "StartupX", "Title", "Junior Dev",
                            "Started On", "6/2017", "Finished On", "12/2019")
            );

            ResumeProfileRequest result = invokeMapToProfileRequest(
                    empty(), positions, empty(), empty(), empty(), empty(), empty());

            assertThat(result.getExperiences()).hasSize(2);
            assertThat(result.getExperiences().get(0).getCompany()).isEqualTo("Acme Corp");
            assertThat(result.getExperiences().get(0).getStartDate()).isEqualTo("2020-01");
            assertThat(result.getExperiences().get(0).getBullets()).containsExactly("Built microservices");
            assertThat(result.getExperiences().get(1).getStartDate()).isEqualTo("2017-06");
            assertThat(result.getExperiences().get(1).getEndDate()).isEqualTo("2019-12");
        }

        @Test
        @DisplayName("should map skills into single category")
        void shouldMapSkills() throws Exception {
            List<Map<String, Object>> skills = List.of(
                    Map.of("Name", "Java"),
                    Map.of("Name", "Spring Boot"),
                    Map.of("Name", "Docker")
            );

            ResumeProfileRequest result = invokeMapToProfileRequest(
                    empty(), empty(), skills, empty(), empty(), empty(), empty());

            assertThat(result.getSkills()).hasSize(1);
            assertThat(result.getSkills().get(0).getCategory()).isEqualTo("LinkedIn Skills");
            assertThat(result.getSkills().get(0).getContent()).isEqualTo("Java, Spring Boot, Docker");
        }

        @Test
        @DisplayName("should return empty skills when all names are blank")
        void shouldReturnEmptySkillsForBlankNames() throws Exception {
            List<Map<String, Object>> skills = List.of(
                    Map.of("Name", ""),
                    Map.of("Name", " ")
            );

            ResumeProfileRequest result = invokeMapToProfileRequest(
                    empty(), empty(), skills, empty(), empty(), empty(), empty());

            assertThat(result.getSkills()).isEmpty();
        }

        @Test
        @DisplayName("should map education entries")
        void shouldMapEducation() throws Exception {
            List<Map<String, Object>> education = List.of(Map.of(
                    "School Name", "MIT",
                    "Degree Name", "BS Computer Science",
                    "Start Date", "2014",
                    "End Date", "2018"
            ));

            ResumeProfileRequest result = invokeMapToProfileRequest(
                    empty(), empty(), empty(), education, empty(), empty(), empty());

            assertThat(result.getEducations()).hasSize(1);
            assertThat(result.getEducations().get(0).getInstitution()).isEqualTo("MIT");
            assertThat(result.getEducations().get(0).getDegree()).isEqualTo("BS Computer Science");
            assertThat(result.getEducations().get(0).getStartDate()).isEqualTo("2014");
        }

        @Test
        @DisplayName("should map certifications")
        void shouldMapCertifications() throws Exception {
            List<Map<String, Object>> certs = List.of(Map.of(
                    "Name", "AWS Solutions Architect",
                    "Authority", "Amazon",
                    "Url", "https://cert.example.com"
            ));

            ResumeProfileRequest result = invokeMapToProfileRequest(
                    empty(), empty(), empty(), empty(), certs, empty(), empty());

            assertThat(result.getCertifications()).hasSize(1);
            assertThat(result.getCertifications().get(0).getName()).isEqualTo("AWS Solutions Architect");
            assertThat(result.getCertifications().get(0).getIssuer()).isEqualTo("Amazon");
            assertThat(result.getCertifications().get(0).getCredentialUrl()).isEqualTo("https://cert.example.com");
        }

        @Test
        @DisplayName("should map languages")
        void shouldMapLanguages() throws Exception {
            List<Map<String, Object>> langs = List.of(
                    Map.of("Name", "English", "Proficiency", "Native"),
                    Map.of("Name", "Portuguese", "Proficiency", "Professional")
            );

            ResumeProfileRequest result = invokeMapToProfileRequest(
                    empty(), empty(), empty(), empty(), empty(), langs, empty());

            assertThat(result.getLanguages()).hasSize(2);
            assertThat(result.getLanguages().get(0).getName()).isEqualTo("English");
            assertThat(result.getLanguages().get(1).getProficiency()).isEqualTo("Professional");
        }

        @Test
        @DisplayName("should map projects")
        void shouldMapProjects() throws Exception {
            List<Map<String, Object>> projects = List.of(Map.of(
                    "Title", "Portfolio Site",
                    "Description", "Personal website",
                    "Url", "https://example.com"
            ));

            ResumeProfileRequest result = invokeMapToProfileRequest(
                    empty(), empty(), empty(), empty(), empty(), empty(), projects);

            assertThat(result.getProjects()).hasSize(1);
            assertThat(result.getProjects().get(0).getTitle()).isEqualTo("Portfolio Site");
            assertThat(result.getProjects().get(0).getProjectUrl()).isEqualTo("https://example.com");
        }

        @Test
        @DisplayName("should handle all empty domains")
        void shouldHandleAllEmpty() throws Exception {
            ResumeProfileRequest result = invokeMapToProfileRequest(
                    empty(), empty(), empty(), empty(), empty(), empty(), empty());

            assertThat(result.getFullName()).isNull();
            assertThat(result.getTitle()).isNull();
            assertThat(result.getExperiences()).isEmpty();
            assertThat(result.getSkills()).isEmpty();
        }
    }

    @Nested
    @DisplayName("parseLinkedInDate (via reflection)")
    class ParseLinkedInDate {

        @Test
        @DisplayName("should parse 'Mon YYYY' format")
        void shouldParseMonthYearFormat() throws Exception {
            assertThat(invokeParseDate("Jan 2020")).isEqualTo("2020-01");
            assertThat(invokeParseDate("December 2019")).isEqualTo("2019-12");
        }

        @Test
        @DisplayName("should parse 'MM/YYYY' format")
        void shouldParseSlashFormat() throws Exception {
            assertThat(invokeParseDate("6/2017")).isEqualTo("2017-06");
            assertThat(invokeParseDate("12/2021")).isEqualTo("2021-12");
        }

        @Test
        @DisplayName("should parse 'YYYY' format")
        void shouldParseYearOnly() throws Exception {
            assertThat(invokeParseDate("2018")).isEqualTo("2018");
        }

        @Test
        @DisplayName("should return null for null or blank")
        void shouldReturnNullForBlank() throws Exception {
            assertThat(invokeParseDate(null)).isNull();
            assertThat(invokeParseDate("")).isNull();
            assertThat(invokeParseDate("   ")).isNull();
        }

        @Test
        @DisplayName("should return raw string for unknown format")
        void shouldReturnRawForUnknown() throws Exception {
            assertThat(invokeParseDate("2020-01-15")).isEqualTo("2020-01-15");
        }
    }

    @Nested
    @DisplayName("splitDescription (via reflection)")
    class SplitDescription {

        @Test
        @DisplayName("should split on newlines")
        void shouldSplitOnNewlines() throws Exception {
            List<String> result = invokeSplitDescription("Line one\nLine two\nLine three");
            assertThat(result).containsExactly("Line one", "Line two", "Line three");
        }

        @Test
        @DisplayName("should split on bullet characters")
        void shouldSplitOnBullets() throws Exception {
            List<String> result = invokeSplitDescription("• First item• Second item");
            assertThat(result).containsExactly("First item", "Second item");
        }

        @Test
        @DisplayName("should return empty for null or blank")
        void shouldReturnEmptyForNull() throws Exception {
            assertThat(invokeSplitDescription(null)).isEmpty();
            assertThat(invokeSplitDescription("")).isEmpty();
            assertThat(invokeSplitDescription("   ")).isEmpty();
        }

        @Test
        @DisplayName("should return single item for no separators")
        void shouldReturnSingleItem() throws Exception {
            assertThat(invokeSplitDescription("Single description")).containsExactly("Single description");
        }
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ResumeProfileRequest invokeMapToProfileRequest(
            List<Map<String, Object>> profile,
            List<Map<String, Object>> positions,
            List<Map<String, Object>> skills,
            List<Map<String, Object>> education,
            List<Map<String, Object>> certifications,
            List<Map<String, Object>> languages,
            List<Map<String, Object>> projects) throws Exception {

        Method method = LinkedInPortabilityService.class.getDeclaredMethod("mapToProfileRequest",
                List.class, List.class, List.class, List.class, List.class, List.class, List.class);
        method.setAccessible(true);
        return (ResumeProfileRequest) method.invoke(service, profile, positions, skills, education,
                certifications, languages, projects);
    }

    private String invokeParseDate(String raw) throws Exception {
        Method method = LinkedInPortabilityService.class.getDeclaredMethod("parseLinkedInDate", String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, raw);
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeSplitDescription(String description) throws Exception {
        Method method = LinkedInPortabilityService.class.getDeclaredMethod("splitDescription", String.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(service, description);
    }

    private List<Map<String, Object>> empty() {
        return Collections.emptyList();
    }
}
