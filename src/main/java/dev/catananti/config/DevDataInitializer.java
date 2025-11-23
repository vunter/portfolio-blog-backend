package dev.catananti.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.catananti.entity.User;
import dev.catananti.repository.ResumeTemplateRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.IdService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Initializes development seed data on startup from JSON/Markdown/HTML files under classpath:dev/.
 * Only active in the 'dev' profile.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevDataInitializer {
    private final UserRepository userRepository;
    private final ResumeTemplateRepository resumeTemplateRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdService idService;
    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    private static final String DEV_ADMIN_EMAIL = "admin@catananti.dev";
    private static final String DEV_ADMIN_NAME = "Leonardo Catananti";
    private static final String RESUME_SLUG = "leonardo-catananti";
    private static final String RESUME_NAME = "Leonardo Catananti - Senior Backend Engineer";
    @Value("${dev.admin.password:dev-s3cur3-p@ssw0rd-2026}") private String devAdminPassword;
    @Value("${dev.resume.html-path:classpath:dev/resume-template.html}") private String resumeHtmlFilePath;
    private Map<String, Long> tagIdMap = new HashMap<>();

    private String loadResource(String path) {
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new RuntimeException("Failed to load resource: " + path, e); }
    }

    private JsonNode loadJson(String path) {
        try { return objectMapper.readTree(loadResource(path)); }
        catch (IOException e) { throw new RuntimeException("Failed to parse JSON: " + path, e); }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeDevData() {
        log.info("Initializing development data (with 2s delay for schema init)...");
        Mono.delay(java.time.Duration.ofSeconds(2))
                .then(initializeAdminUser())
                .flatMap(adminId -> initializeResumeTemplate()
                        .then(initializeResumeProfile(adminId))
                        .then(initializeTags())
                        .then(initializeBlogPosts(adminId))
                        .then(initializeComments()))
                .subscribe(
                        result -> log.info("Development data initialization completed (including blog posts, tags, comments)"),
                        error -> log.error("Failed to initialize development data: {}", error.getMessage(), error));
    }

    private Mono<Long> initializeAdminUser() {
        return userRepository.existsByEmail(DEV_ADMIN_EMAIL).flatMap(exists -> {
            if (exists) {
                log.info("Dev admin user already exists: {}***@{}",
                        DEV_ADMIN_EMAIL.substring(0, Math.min(3, DEV_ADMIN_EMAIL.indexOf('@'))),
                        DEV_ADMIN_EMAIL.substring(DEV_ADMIN_EMAIL.indexOf('@') + 1));
                return userRepository.findByEmail(DEV_ADMIN_EMAIL).map(User::getId);
            }
            long userId = idService.nextId();
            LocalDateTime now = LocalDateTime.now();
            return databaseClient.sql("INSERT INTO users (id, email, password_hash, name, role, created_at, updated_at) VALUES (:id, :email, :passwordHash, :name, :role, :createdAt, :updatedAt)")
                    .bind("id", userId).bind("email", DEV_ADMIN_EMAIL).bind("passwordHash", passwordEncoder.encode(devAdminPassword))
                    .bind("name", DEV_ADMIN_NAME).bind("role", "ADMIN").bind("createdAt", now).bind("updatedAt", now)
                    .fetch().rowsUpdated().doOnSuccess(r -> log.info("Dev admin user created: {}***@{}",
                            DEV_ADMIN_EMAIL.substring(0, Math.min(3, DEV_ADMIN_EMAIL.indexOf('@'))),
                            DEV_ADMIN_EMAIL.substring(DEV_ADMIN_EMAIL.indexOf('@') + 1))).thenReturn(userId);
        });
    }

    private Mono<Long> initializeResumeTemplate() {
        return resumeTemplateRepository.existsBySlug(RESUME_SLUG).flatMap(exists -> {
            if (exists) { log.info("Resume template already exists: {}", RESUME_SLUG); return Mono.just(0L); }
            return userRepository.findByEmail(DEV_ADMIN_EMAIL).flatMap(admin -> {
                long templateId = idService.nextId();
                LocalDateTime now = LocalDateTime.now();
                return databaseClient.sql("INSERT INTO resume_templates (id, slug, url_alias, name, description, html_content, css_content, status, owner_id, version, is_default, paper_size, orientation, download_count, created_at, updated_at) VALUES (:id, :slug, :urlAlias, :name, :description, :htmlContent, :cssContent, :status, :ownerId, :version, :isDefault, :paperSize, :orientation, :downloadCount, :createdAt, :updatedAt)")
                        .bind("id", templateId).bind("slug", RESUME_SLUG).bind("urlAlias", RESUME_SLUG).bind("name", RESUME_NAME)
                        .bind("description", "Professional resume for Senior Backend Engineer position")
                        .bind("htmlContent", loadResource("dev/resume-template.html")).bind("cssContent", "")
                        .bind("status", "ACTIVE").bind("ownerId", admin.getId()).bind("version", 1).bind("isDefault", true)
                        .bind("paperSize", "A4").bind("orientation", "PORTRAIT").bind("downloadCount", 0)
                        .bind("createdAt", now).bind("updatedAt", now).fetch().rowsUpdated()
                        .doOnSuccess(r -> log.info("Resume template created: {} (status: ACTIVE)", RESUME_SLUG)).thenReturn(templateId);
            });
        });
    }

    private Mono<Void> initializeResumeProfile(long adminUserId) {
        return databaseClient.sql("SELECT COUNT(*) AS cnt FROM resume_profiles WHERE owner_id = :ownerId")
                .bind("ownerId", adminUserId).fetch().one().flatMap(row -> {
                    if (((Number) row.get("cnt")).longValue() > 0) { log.info("Resume profile already exists, skipping"); return Mono.empty(); }
                    log.info("Creating dev resume profile...");
                    JsonNode p = loadJson("dev/seed-profile.json");
                    long profileId = idService.nextId();
                    LocalDateTime now = LocalDateTime.now();
                    return databaseClient.sql("INSERT INTO resume_profiles (id, owner_id, locale, full_name, title, email, phone, linkedin, github, website, location, professional_summary, interests, created_at, updated_at) VALUES (:id, :ownerId, :locale, :fullName, :title, :email, :phone, :linkedin, :github, :website, :location, :summary, :interests, :createdAt, :updatedAt)")
                            .bind("id", profileId).bind("ownerId", adminUserId).bind("locale", p.path("locale").asText())
                            .bind("fullName", p.path("fullName").asText()).bind("title", p.path("title").asText())
                            .bind("email", p.path("email").asText()).bind("phone", p.path("phone").asText())
                            .bind("linkedin", p.path("linkedin").asText()).bind("github", p.path("github").asText())
                            .bind("website", p.path("website").asText()).bind("location", p.path("location").asText())
                            .bind("summary", p.path("professionalSummary").asText()).bind("interests", p.path("interests").asText())
                            .bind("createdAt", now).bind("updatedAt", now).fetch().rowsUpdated()
                            .then(insertProfileSections(profileId, p, now))
                            .doOnSuccess(v -> log.info("Resume profile created with all sections"));
                });
    }

    private Mono<Void> insertProfileSections(long pid, JsonNode p, LocalDateTime now) {
        Mono<Void> c = Mono.empty();
        for (JsonNode e : p.path("educations"))
            c = c.then(databaseClient.sql("INSERT INTO resume_educations (id,profile_id,institution,location,degree,field_of_study,start_date,end_date,description,sort_order,created_at,updated_at) VALUES (:id,:pid,:institution,:location,:degree,:fos,:sd,:ed,:desc,:so,:ca,:ua)")
                    .bind("id",idService.nextId()).bind("pid",pid).bind("institution",e.path("institution").asText()).bind("location",e.path("location").asText()).bind("degree",e.path("degree").asText()).bind("fos",e.path("fieldOfStudy").asText()).bind("sd",e.path("startDate").asText()).bind("ed",e.path("endDate").asText()).bind("desc",e.path("description").asText("")).bind("so",e.path("sortOrder").asInt()).bind("ca",now).bind("ua",now).fetch().rowsUpdated().then());
        for (JsonNode e : p.path("experiences"))
            c = c.then(databaseClient.sql("INSERT INTO resume_experiences (id,profile_id,company,position,start_date,end_date,bullets,sort_order,created_at,updated_at) VALUES (:id,:pid,:company,:position,:sd,:ed,:bullets,:so,:ca,:ua)")
                    .bind("id",idService.nextId()).bind("pid",pid).bind("company",e.path("company").asText()).bind("position",e.path("position").asText()).bind("sd",e.path("startDate").asText()).bind("ed",e.path("endDate").asText()).bind("bullets",e.path("bullets").toString()).bind("so",e.path("sortOrder").asInt()).bind("ca",now).bind("ua",now).fetch().rowsUpdated().then());
        for (JsonNode e : p.path("skills"))
            c = c.then(databaseClient.sql("INSERT INTO resume_skills (id,profile_id,category,content,sort_order,created_at,updated_at) VALUES (:id,:pid,:cat,:content,:so,:ca,:ua)")
                    .bind("id",idService.nextId()).bind("pid",pid).bind("cat",e.path("category").asText()).bind("content",e.path("content").asText()).bind("so",e.path("sortOrder").asInt()).bind("ca",now).bind("ua",now).fetch().rowsUpdated().then());
        for (JsonNode e : p.path("languages"))
            c = c.then(databaseClient.sql("INSERT INTO resume_languages (id,profile_id,name,proficiency,sort_order,created_at,updated_at) VALUES (:id,:pid,:name,:prof,:so,:ca,:ua)")
                    .bind("id",idService.nextId()).bind("pid",pid).bind("name",e.path("name").asText()).bind("prof",e.path("proficiency").asText()).bind("so",e.path("sortOrder").asInt()).bind("ca",now).bind("ua",now).fetch().rowsUpdated().then());
        for (JsonNode e : p.path("certifications"))
            c = c.then(databaseClient.sql("INSERT INTO resume_certifications (id,profile_id,name,issuer,issue_date,credential_url,description,sort_order,created_at,updated_at) VALUES (:id,:pid,:name,:issuer,:iDate,:cUrl,:desc,:so,:ca,:ua)")
                    .bind("id",idService.nextId()).bind("pid",pid).bind("name",e.path("name").asText()).bind("issuer",e.path("issuer").asText()).bind("iDate",e.path("issueDate").asText("")).bind("cUrl",e.path("credentialUrl").asText("")).bind("desc",e.path("description").asText("")).bind("so",e.path("sortOrder").asInt()).bind("ca",now).bind("ua",now).fetch().rowsUpdated().then());
        for (JsonNode e : p.path("additionalInfo"))
            c = c.then(databaseClient.sql("INSERT INTO resume_additional_info (id,profile_id,label,content,sort_order,created_at,updated_at) VALUES (:id,:pid,:label,:content,:so,:ca,:ua)")
                    .bind("id",idService.nextId()).bind("pid",pid).bind("label",e.path("label").asText()).bind("content",e.path("content").asText()).bind("so",e.path("sortOrder").asInt()).bind("ca",now).bind("ua",now).fetch().rowsUpdated().then());
        return c;
    }

    private Mono<Void> initializeTags() {
        return databaseClient.sql("SELECT COUNT(*) AS cnt FROM tags").fetch().one().flatMap(row -> {
            if (((Number) row.get("cnt")).longValue() > 0) { log.info("Tags already exist, skipping"); return Mono.empty(); }
            log.info("Creating dev tags...");
            JsonNode tags = loadJson("dev/seed-tags.json");
            LocalDateTime now = LocalDateTime.now();
            return Flux.fromIterable(tags).concatMap(t -> {
                long id = idService.nextId(); String slug = t.path("slug").asText(); tagIdMap.put(slug, id);
                return databaseClient.sql("INSERT INTO tags (id,name,slug,description,color,created_at,updated_at) VALUES (:id,:name,:slug,:desc,:color,:ca,:ua)")
                        .bind("id",id).bind("name",t.path("name").asText()).bind("slug",slug).bind("desc",t.path("description").asText())
                        .bind("color",t.path("color").asText()).bind("ca",now).bind("ua",now).fetch().rowsUpdated();
            }).then().doOnSuccess(v -> log.info("Created {} dev tags", tags.size()));
        });
    }

    private Mono<Void> initializeBlogPosts(Long adminId) {
        return databaseClient.sql("SELECT COUNT(*) AS cnt FROM articles").fetch().one().flatMap(row -> {
            if (((Number) row.get("cnt")).longValue() > 0) { log.info("Articles already exist, skipping"); return Mono.empty(); }
            log.info("Creating dev blog posts...");
            Mono<Void> ensureTagIds = tagIdMap.isEmpty() ? loadExistingTagIds() : Mono.empty();
            return ensureTagIds.then(Mono.defer(() -> createAllArticles(adminId)));
        });
    }

    private Mono<Void> loadExistingTagIds() {
        return databaseClient.sql("SELECT id, slug FROM tags").fetch().all().collectList().doOnNext(rows -> {
            for (var row : rows) tagIdMap.put((String) row.get("slug"), ((Number) row.get("id")).longValue());
            log.info("Loaded {} existing tag IDs", rows.size());
        }).then();
    }

    private Mono<Void> createAllArticles(Long adminId) {
        JsonNode articles = loadJson("dev/seed-articles.json");
        LocalDateTime now = LocalDateTime.now();
        return Flux.fromIterable(articles).concatMap(a -> {
            long articleId = idService.nextId();
            String content = loadResource("dev/" + a.path("contentFile").asText());
            boolean isDraft = "DRAFT".equals(a.path("status").asText());
            LocalDateTime publishedAt = isDraft ? now : now.minusDays(a.path("publishedDaysAgo").asInt());
            String coverUrl = a.path("coverImageUrl").isNull() ? "" : a.path("coverImageUrl").asText("");
            return databaseClient.sql("INSERT INTO articles (id,slug,title,subtitle,content,excerpt,cover_image_url,author_id,status,published_at,reading_time_minutes,views_count,likes_count,created_at,updated_at) VALUES (:id,:slug,:title,:subtitle,:content,:excerpt,:coverUrl,:authorId,:status,:pubAt,:rt,:views,:likes,:ca,:ua)")
                    .bind("id",articleId).bind("slug",a.path("slug").asText()).bind("title",a.path("title").asText())
                    .bind("subtitle",a.path("subtitle").asText()).bind("content",content).bind("excerpt",a.path("excerpt").asText())
                    .bind("coverUrl",coverUrl).bind("authorId",adminId).bind("status",a.path("status").asText())
                    .bind("pubAt",publishedAt).bind("rt",a.path("readingTime").asInt())
                    .bind("views",a.path("views").asInt()).bind("likes",a.path("likes").asInt())
                    .bind("ca",now).bind("ua",now).fetch().rowsUpdated()
                    .then(insertArticleTags(articleId, a.path("tagSlugs")));
        }).then().doOnSuccess(v -> log.info("Created {} dev blog posts", articles.size()));
    }

    private Mono<Void> insertArticleTags(long articleId, JsonNode tagSlugs) {
        return Flux.fromIterable(tagSlugs).concatMap(s -> {
            Long tagId = tagIdMap.get(s.asText());
            if (tagId == null) return Mono.empty();
            return databaseClient.sql("INSERT INTO article_tags (article_id,tag_id) VALUES (:aid,:tid)")
                    .bind("aid",articleId).bind("tid",tagId).fetch().rowsUpdated();
        }).then();
    }

    private Mono<Void> initializeComments() {
        return databaseClient.sql("SELECT COUNT(*) AS cnt FROM comments").fetch().one().flatMap(row -> {
            if (((Number) row.get("cnt")).longValue() > 0) { log.info("Comments already exist, skipping"); return Mono.empty(); }
            log.info("Creating dev comments...");
            return databaseClient.sql("SELECT id FROM articles WHERE status = 'PUBLISHED' ORDER BY published_at DESC LIMIT 3")
                    .fetch().all().collectList().flatMap(articles -> {
                        if (articles.isEmpty()) return Mono.empty();
                        long[] aids = { ((Number)articles.get(0).get("id")).longValue(),
                                articles.size()>1 ? ((Number)articles.get(1).get("id")).longValue() : ((Number)articles.get(0).get("id")).longValue(),
                                articles.size()>2 ? ((Number)articles.get(2).get("id")).longValue() : ((Number)articles.get(0).get("id")).longValue() };
                        JsonNode comments = loadJson("dev/seed-comments.json");
                        LocalDateTime now = LocalDateTime.now();
                        List<Long> ids = new ArrayList<>();
                        return Flux.fromIterable(comments).concatMap(cm -> {
                            long cid = idService.nextId(); ids.add(cid);
                            long articleId = aids[Math.min(cm.path("articleOrder").asInt(), aids.length-1)];
                            JsonNode pn = cm.path("parentIndex");
                            Long parentId = pn.isNull() || pn.isMissingNode() ? null : ids.get(pn.asInt());
                            int daysAgo = cm.path("createdDaysAgo").asInt(-1);
                            LocalDateTime createdAt = daysAgo >= 0 ? now.minusDays(daysAgo) : now.minusHours(cm.path("createdHoursAgo").asInt());
                            var sql = databaseClient.sql("INSERT INTO comments (id,article_id,author_name,author_email,content,status,parent_id,created_at) VALUES (:id,:aid,:name,:email,:content,:status,:pid,:ca)")
                                    .bind("id",cid).bind("aid",articleId).bind("name",cm.path("name").asText())
                                    .bind("email",cm.path("email").asText()).bind("content",cm.path("content").asText())
                                    .bind("status",cm.path("status").asText());
                            sql = parentId != null ? sql.bind("pid",parentId) : sql.bindNull("pid",Long.class);
                            return sql.bind("ca",createdAt).fetch().rowsUpdated();
                        }).then().doOnSuccess(v -> log.info("Created {} dev comments", comments.size()));
                    });
        });
    }
}
