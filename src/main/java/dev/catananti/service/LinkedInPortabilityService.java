package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LinkedIn Member Data Portability (DMA) import service.
 * Uses the r_dma_portability_3rd_party scope (separate from login OIDC)
 * to fetch profile snapshot data via the Member Snapshot API.
 */
@Service
@Slf4j
public class LinkedInPortabilityService {

    private static final String SNAPSHOT_API = "https://api.linkedin.com/rest/memberSnapshotData";
    private static final String TOKEN_URL = "https://www.linkedin.com/oauth/v2/accessToken";
    private static final String AUTH_URL = "https://www.linkedin.com/oauth/v2/authorization";
    private static final String REDIS_PREFIX = "linkedin:import:";
    private static final Duration IMPORT_TTL = Duration.ofMinutes(10);

    private static final List<String> DOMAINS = List.of(
            "PROFILE", "POSITIONS", "SKILLS", "EDUCATION",
            "CERTIFICATIONS", "LANGUAGES", "PROJECTS"
    );

    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final WebClient webClient;
    private final ReactiveStringRedisTemplate redisTemplate;

    @Value("${app.linkedin.portability.enabled:false}")
    private boolean portabilityEnabled;

    @Value("${oauth2.linkedin.client-id:}")
    private String clientId;

    @Value("${oauth2.linkedin.client-secret:}")
    private String clientSecret;

    @Value("${oauth2.redirect-base-url:}")
    private String redirectBaseUrl;

    public LinkedInPortabilityService(ReactiveStringRedisTemplate redisTemplate,
                                      tools.jackson.databind.ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.webClient = WebClient.builder().build();
        this.objectMapper = objectMapper;
    }

    public boolean isPortabilityEnabled() {
        return portabilityEnabled && clientId != null && !clientId.isBlank();
    }

    public String getPortabilityAuthUrl(String state) {
        return AUTH_URL
                + "?response_type=code"
                + "&client_id=" + clientId
                + "&redirect_uri=" + redirectBaseUrl + "/api/v1/resume/import/linkedin/callback"
                + "&scope=r_dma_portability_3rd_party"
                + "&state=" + state;
    }

    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> exchangeCodeForToken(String code) {
        return webClient.post()
                .uri(TOKEN_URL)
                .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                        .with("code", code)
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret)
                        .with("redirect_uri", redirectBaseUrl + "/api/v1/resume/import/linkedin/callback"))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(15))
                .flatMap(body -> Mono.fromCallable(() -> {
                    try {
                        var mapper = objectMapper;
                        return (Map<String, Object>) mapper.readValue(body, Map.class);
                    } catch (Exception e) {
                        log.error("Failed to parse LinkedIn token response", e);
                        throw new RuntimeException("Failed to parse LinkedIn token response", e);
                    }
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()));
    }

    /**
     * Fetch all 7 domains in parallel and map to a ResumeProfileRequest.
     */
    public Mono<ResumeProfileRequest> importProfile(String accessToken) {
        log.info("Starting LinkedIn portability import for {} domains", DOMAINS.size());

        // Fetch all domains in parallel
        Mono<List<Map<String, Object>>> profileMono = fetchDomain(accessToken, "PROFILE");
        Mono<List<Map<String, Object>>> positionsMono = fetchDomain(accessToken, "POSITIONS");
        Mono<List<Map<String, Object>>> skillsMono = fetchDomain(accessToken, "SKILLS");
        Mono<List<Map<String, Object>>> educationMono = fetchDomain(accessToken, "EDUCATION");
        Mono<List<Map<String, Object>>> certsMono = fetchDomain(accessToken, "CERTIFICATIONS");
        Mono<List<Map<String, Object>>> langsMono = fetchDomain(accessToken, "LANGUAGES");
        Mono<List<Map<String, Object>>> projectsMono = fetchDomain(accessToken, "PROJECTS");

        return Mono.zip(profileMono, positionsMono, skillsMono, educationMono, certsMono, langsMono, projectsMono)
                .map(tuple -> mapToProfileRequest(
                        tuple.getT1(), tuple.getT2(), tuple.getT3(),
                        tuple.getT4(), tuple.getT5(), tuple.getT6(), tuple.getT7()))
                .doOnSuccess(r -> log.info("LinkedIn portability import completed for '{}'", r.getFullName()))
                .doOnError(e -> log.error("LinkedIn portability import failed: {}", e.getMessage(), e));
    }

    public Mono<String> storeImportResult(String json, String userIdentifier) {
        String key = UUID.randomUUID().toString();
        String fullKey = REDIS_PREFIX + userIdentifier + ":" + key;
        return redisTemplate.opsForValue()
                .set(fullKey, json, IMPORT_TTL)
                .thenReturn(key);
    }

    public Mono<String> retrieveImportResult(String key, String userIdentifier) {
        String fullKey = REDIS_PREFIX + userIdentifier + ":" + key;
        return redisTemplate.opsForValue().getAndDelete(fullKey);
    }

    // ── Domain fetching with pagination ──────────────────────────────────

    private Mono<List<Map<String, Object>>> fetchDomain(String accessToken, String domain) {
        return fetchDomainPage(accessToken, domain, null)
                .expand(page -> {
                    if (page.nextHref() == null || page.nextHref().isBlank()) {
                        return Mono.empty();
                    }
                    return fetchDomainPage(accessToken, domain, page.nextHref());
                })
                .flatMapIterable(SnapshotPage::data)
                .collectList()
                .doOnNext(list -> log.debug("Fetched {} records for domain {}", list.size(), domain))
                .onErrorResume(e -> {
                    log.warn("Failed to fetch domain {}: {}", domain, e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<SnapshotPage> fetchDomainPage(String accessToken, String domain, String nextHref) {
        Mono<String> responseMono;
        if (nextHref != null) {
            responseMono = callSnapshotApi(accessToken, nextHref);
        } else {
            String uri = SNAPSHOT_API + "?domain=" + domain + "&count=100";
            responseMono = callSnapshotApi(accessToken, uri);
        }

        return responseMono.flatMap(body -> Mono.fromCallable(() -> {
            try {
                var mapper = objectMapper;
                Map<String, Object> json = (Map<String, Object>) mapper.readValue(body, Map.class);
                List<Map<String, Object>> elements = new ArrayList<>();

                Object elementsObj = json.get("elements");
                if (elementsObj instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            elements.add((Map<String, Object>) map);
                        }
                    }
                }

                // Extract next page link from paging metadata
                String next = null;
                Object pagingObj = json.get("paging");
                if (pagingObj instanceof Map<?, ?> paging) {
                    Object linksObj = paging.get("links");
                    if (linksObj instanceof List<?> links) {
                        for (Object linkObj : links) {
                            if (linkObj instanceof Map<?, ?> link) {
                                if ("next".equals(link.get("rel"))) {
                                    next = (String) link.get("href");
                                    break;
                                }
                            }
                        }
                    }
                }

                return new SnapshotPage(elements, next);
            } catch (Exception e) {
                log.error("Failed to parse LinkedIn snapshot response for {}", domain, e);
                throw new RuntimeException("Failed to parse LinkedIn snapshot response for " + domain, e);
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()));
    }

    private Mono<String> callSnapshotApi(String accessToken, String uri) {
        return webClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("LinkedIn-Version", "202401")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30));
    }

    // ── Mapping ──────────────────────────────────────────────────────────

    private ResumeProfileRequest mapToProfileRequest(
            List<Map<String, Object>> profile,
            List<Map<String, Object>> positions,
            List<Map<String, Object>> skills,
            List<Map<String, Object>> education,
            List<Map<String, Object>> certifications,
            List<Map<String, Object>> languages,
            List<Map<String, Object>> projects) {

        var builder = ResumeProfileRequest.builder();

        // PROFILE
        if (!profile.isEmpty()) {
            Map<String, String> flat = flattenEntries(profile);
            String firstName = flat.getOrDefault("First Name", "");
            String lastName = flat.getOrDefault("Last Name", "");
            builder.fullName((firstName + " " + lastName).trim());
            builder.title(flat.get("Headline"));
            builder.professionalSummary(flat.get("Summary"));
            builder.location(flat.get("Geo Location"));
            builder.website(flat.get("Websites"));
        }

        // POSITIONS → experiences
        builder.experiences(mapPositions(positions));

        // EDUCATION
        builder.educations(mapEducation(education));

        // SKILLS
        builder.skills(mapSkills(skills));

        // CERTIFICATIONS
        builder.certifications(mapCertifications(certifications));

        // LANGUAGES
        builder.languages(mapLanguages(languages));

        // PROJECTS
        builder.projects(mapProjects(projects));

        return builder.build();
    }

    private List<ResumeProfileRequest.ExperienceEntry> mapPositions(List<Map<String, Object>> positions) {
        List<ResumeProfileRequest.ExperienceEntry> result = new ArrayList<>();
        int order = 0;
        for (Map<String, Object> entry : positions) {
            Map<String, String> flat = flattenEntry(entry);
            result.add(ResumeProfileRequest.ExperienceEntry.builder()
                    .company(flat.getOrDefault("Company Name", "Unknown"))
                    .position(flat.getOrDefault("Title", "Unknown"))
                    .startDate(parseLinkedInDate(flat.get("Started On")))
                    .endDate(parseLinkedInDate(flat.get("Finished On")))
                    .bullets(splitDescription(flat.get("Description")))
                    .sortOrder(order++)
                    .build());
        }
        return result;
    }

    private List<ResumeProfileRequest.EducationEntry> mapEducation(List<Map<String, Object>> education) {
        List<ResumeProfileRequest.EducationEntry> result = new ArrayList<>();
        int order = 0;
        for (Map<String, Object> entry : education) {
            Map<String, String> flat = flattenEntry(entry);
            result.add(ResumeProfileRequest.EducationEntry.builder()
                    .institution(flat.getOrDefault("School Name", "Unknown"))
                    .degree(flat.get("Degree Name"))
                    .description(flat.get("Notes"))
                    .startDate(parseLinkedInDate(flat.get("Start Date")))
                    .endDate(parseLinkedInDate(flat.get("End Date")))
                    .sortOrder(order++)
                    .build());
        }
        return result;
    }

    private List<ResumeProfileRequest.SkillEntry> mapSkills(List<Map<String, Object>> skills) {
        if (skills.isEmpty()) {
            return Collections.emptyList();
        }
        String joined = skills.stream()
                .map(entry -> {
                    Map<String, String> flat = flattenEntry(entry);
                    return flat.getOrDefault("Name", "");
                })
                .filter(name -> !name.isBlank())
                .collect(Collectors.joining(", "));

        if (joined.isBlank()) {
            return Collections.emptyList();
        }
        return List.of(ResumeProfileRequest.SkillEntry.builder()
                .category("LinkedIn Skills")
                .content(joined)
                .sortOrder(0)
                .build());
    }

    private List<ResumeProfileRequest.CertificationEntry> mapCertifications(List<Map<String, Object>> certs) {
        List<ResumeProfileRequest.CertificationEntry> result = new ArrayList<>();
        int order = 0;
        for (Map<String, Object> entry : certs) {
            Map<String, String> flat = flattenEntry(entry);
            result.add(ResumeProfileRequest.CertificationEntry.builder()
                    .name(flat.getOrDefault("Name", "Unknown"))
                    .issuer(flat.get("Authority"))
                    .credentialUrl(flat.get("Url"))
                    .issueDate(parseLinkedInDate(flat.get("Start Date")))
                    .sortOrder(order++)
                    .build());
        }
        return result;
    }

    private List<ResumeProfileRequest.LanguageEntry> mapLanguages(List<Map<String, Object>> languages) {
        List<ResumeProfileRequest.LanguageEntry> result = new ArrayList<>();
        int order = 0;
        for (Map<String, Object> entry : languages) {
            Map<String, String> flat = flattenEntry(entry);
            result.add(ResumeProfileRequest.LanguageEntry.builder()
                    .name(flat.getOrDefault("Name", "Unknown"))
                    .proficiency(flat.get("Proficiency"))
                    .sortOrder(order++)
                    .build());
        }
        return result;
    }

    private List<ResumeProfileRequest.ProjectEntry> mapProjects(List<Map<String, Object>> projects) {
        List<ResumeProfileRequest.ProjectEntry> result = new ArrayList<>();
        int order = 0;
        for (Map<String, Object> entry : projects) {
            Map<String, String> flat = flattenEntry(entry);
            result.add(ResumeProfileRequest.ProjectEntry.builder()
                    .title(flat.getOrDefault("Title", "Untitled"))
                    .description(flat.get("Description"))
                    .projectUrl(flat.get("Url"))
                    .sortOrder(order++)
                    .build());
        }
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Flatten a list of snapshot entries into a single key-value map.
     * Each entry has a flat key/value structure from the Member Snapshot API.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> flattenEntries(List<Map<String, Object>> entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            result.putAll(flattenEntry(entry));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> flattenEntry(Map<String, Object> entry) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> kv : entry.entrySet()) {
            if (kv.getValue() != null) {
                result.put(kv.getKey(), String.valueOf(kv.getValue()));
            }
        }
        return result;
    }

    /**
     * Parse LinkedIn date formats into normalized form.
     * Handles: "Mon YYYY" → "YYYY-MM", "YYYY" → "YYYY", "MM/YYYY" → "YYYY-MM", null → null
     */
    private String parseLinkedInDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        raw = raw.trim();

        // "MM/YYYY" format
        if (raw.matches("\\d{1,2}/\\d{4}")) {
            String[] parts = raw.split("/");
            return parts[1] + "-" + String.format("%02d", Integer.parseInt(parts[0]));
        }

        // "YYYY" format
        if (raw.matches("\\d{4}")) {
            return raw;
        }

        // "Mon YYYY" or "Month YYYY" format
        if (raw.matches("[A-Za-z]+ \\d{4}")) {
            String[] parts = raw.split(" ");
            String monthStr = parts[0];
            String year = parts[1];
            int month = parseMonth(monthStr);
            if (month > 0) {
                return year + "-" + String.format("%02d", month);
            }
            return year;
        }

        return raw;
    }

    private int parseMonth(String month) {
        return switch (month.substring(0, Math.min(3, month.length())).toLowerCase()) {
            case "jan" -> 1;
            case "feb" -> 2;
            case "mar" -> 3;
            case "apr" -> 4;
            case "may" -> 5;
            case "jun" -> 6;
            case "jul" -> 7;
            case "aug" -> 8;
            case "sep" -> 9;
            case "oct" -> 10;
            case "nov" -> 11;
            case "dec" -> 12;
            default -> 0;
        };
    }

    /**
     * Split a description into bullet points.
     * Splits on newlines, bullet chars (•, -, *), or returns single-item list.
     */
    private List<String> splitDescription(String description) {
        if (description == null || description.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(description.split("\\r?\\n|[•\\-\\*]\\s"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private record SnapshotPage(List<Map<String, Object>> data, String nextHref) {}
}
