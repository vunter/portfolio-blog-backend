package dev.catananti.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * F-172: Thymeleaf-based email template renderer.
 * <p>
 * Standalone configuration — does NOT interfere with WebFlux (no auto-configured
 * view resolver). Templates live under {@code classpath:/templates/email/}.
 * Supports database overrides via {@code email_template_overrides} table.
 * Supports custom variables via {@code email_custom_variables} table.
 * </p>
 */
@Service
@Slf4j
public class EmailTemplateService {

    private final TemplateEngine fileTemplateEngine;
    private final TemplateEngine stringTemplateEngine;
    private final DatabaseClient db;
    private final IdService idService;

    /** Cached custom variables: template_id -> list of CustomVar. Invalidated on writes. */
    private volatile Map<String, List<CustomVar>> customVarsCache = new ConcurrentHashMap<>();
    private volatile boolean cacheLoaded = false;

    public EmailTemplateService(DatabaseClient databaseClient, IdService idService) {
        this.db = databaseClient;
        this.idService = idService;

        // File-based templates from classpath
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);

        this.fileTemplateEngine = new TemplateEngine();
        this.fileTemplateEngine.setTemplateResolver(resolver);

        // String-based engine for DB overrides — also needs classpath access for layout fragments
        var classpathForFragments = new ClassLoaderTemplateResolver();
        classpathForFragments.setPrefix("templates/email/");
        classpathForFragments.setSuffix(".html");
        classpathForFragments.setTemplateMode(TemplateMode.HTML);
        classpathForFragments.setCharacterEncoding("UTF-8");
        classpathForFragments.setCacheable(true);
        classpathForFragments.setCheckExistence(true);
        classpathForFragments.setOrder(1);

        var stringResolver = new StringTemplateResolver();
        stringResolver.setTemplateMode(TemplateMode.HTML);
        stringResolver.setOrder(2);

        this.stringTemplateEngine = new TemplateEngine();
        this.stringTemplateEngine.addTemplateResolver(classpathForFragments);
        this.stringTemplateEngine.addTemplateResolver(stringResolver);

        log.info("EmailTemplateService initialized with Thymeleaf (templates/email/ + DB overrides + custom vars)");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refreshCustomVarsCache()
                .subscribe(
                        v -> log.info("Custom email variables cache loaded"),
                        e -> log.warn("Failed to load custom email variables: {}", e.getMessage())
                );
    }

    // ==================== Template Rendering ====================

    /**
     * Render an email template with the given variables.
     * Custom variables (global + per-template) are merged first, then code-injected vars override.
     * Uses default locale '*' (fallback).
     */
    public String render(String templateName, Map<String, Object> variables) {
        return render(templateName, variables, null);
    }

    /**
     * Render an email template with locale-aware custom variable injection.
     * Resolution order: locale-specific custom var > fallback '*' custom var > code-injected var.
     */
    public String render(String templateName, Map<String, Object> variables, String locale) {
        var context = new Context();
        context.setVariables(getCustomVariablesForRender(templateName, locale));
        context.setVariables(variables);
        return fileTemplateEngine.process(templateName, context);
    }

    /**
     * Render a raw HTML string as a Thymeleaf template.
     */
    public String renderFromString(String htmlContent, Map<String, Object> variables) {
        return renderFromString(htmlContent, variables, null);
    }

    /**
     * Render a raw HTML string with custom variable injection for a specific template.
     */
    public String renderFromString(String htmlContent, Map<String, Object> variables, String templateId) {
        return renderFromString(htmlContent, variables, templateId, null);
    }

    /**
     * Render a raw HTML string with locale-aware custom variable injection.
     */
    public String renderFromString(String htmlContent, Map<String, Object> variables, String templateId, String locale) {
        var context = new Context();
        if (templateId != null) {
            context.setVariables(getCustomVariablesForRender(templateId, locale));
        }
        context.setVariables(variables);
        return stringTemplateEngine.process(htmlContent, context);
    }

    /**
     * Get merged custom variables for rendering: global + per-template, with locale resolution.
     * For each var key: locale-specific value wins over fallback '*'.
     */
    private Map<String, Object> getCustomVariablesForRender(String templateName, String locale) {
        var result = new HashMap<String, Object>();
        var cache = customVarsCache;
        String effectiveLocale = (locale == null || locale.isBlank()) ? "*" : locale;

        // Global vars first — fallback then locale-specific override
        applyVarsWithLocale(cache.getOrDefault("__global__", List.of()), effectiveLocale, result);
        // Per-template vars override globals
        applyVarsWithLocale(cache.getOrDefault(templateName, List.of()), effectiveLocale, result);

        return result;
    }

    /** Apply vars with locale resolution: fallback '*' first, then locale-specific overrides. */
    private void applyVarsWithLocale(List<CustomVar> vars, String locale, Map<String, Object> result) {
        // First pass: apply fallback '*' vars
        for (var v : vars) {
            if ("*".equals(v.locale())) {
                result.put(v.key(), v.value());
            }
        }
        // Second pass: locale-specific vars override fallback
        if (!"*".equals(locale)) {
            for (var v : vars) {
                if (locale.equals(v.locale())) {
                    result.put(v.key(), v.value());
                }
            }
        }
    }

    // ==================== Custom Variables CRUD ====================

    public record CustomVar(long id, String key, String value, String description, String templateId, String locale) {}

    /** Load all custom variables into cache. Called at startup and after mutations. */
    public Mono<Void> refreshCustomVarsCache() {
        return db.sql("SELECT id, var_key, var_value, description, template_id, locale FROM email_custom_variables ORDER BY id")
                .map(row -> new CustomVar(
                        row.get("id", Long.class),
                        row.get("var_key", String.class),
                        row.get("var_value", String.class),
                        row.get("description", String.class),
                        row.get("template_id", String.class),
                        row.get("locale", String.class)))
                .all()
                .collectList()
                .doOnNext(all -> {
                    var newCache = new ConcurrentHashMap<String, List<CustomVar>>();
                    for (var v : all) {
                        newCache.computeIfAbsent(v.templateId(), k -> new java.util.ArrayList<>()).add(v);
                    }
                    this.customVarsCache = newCache;
                    this.cacheLoaded = true;
                    log.debug("Refreshed custom variables cache: {} vars", all.size());
                })
                .then();
    }

    /** Ensure cache is loaded (lazy init on first render). */
    public Mono<Void> ensureCacheLoaded() {
        if (cacheLoaded) return Mono.empty();
        return refreshCustomVarsCache();
    }

    /** List custom variables by scope. If templateId is null, returns all. */
    public Flux<CustomVar> listCustomVariables(String templateId) {
        if (templateId == null) {
            return db.sql("SELECT id, var_key, var_value, description, template_id, locale FROM email_custom_variables ORDER BY template_id, var_key, locale")
                    .map(this::mapCustomVar).all();
        }
        return db.sql("SELECT id, var_key, var_value, description, template_id, locale FROM email_custom_variables WHERE template_id = :tid ORDER BY var_key, locale")
                .bind("tid", templateId)
                .map(this::mapCustomVar).all();
    }

    /** Save (upsert) a custom variable with locale support. */
    public Mono<CustomVar> saveCustomVariable(String key, String value, String description, String templateId, String locale) {
        String tid = (templateId == null || templateId.isBlank()) ? "__global__" : templateId;
        String loc = (locale == null || locale.isBlank()) ? "*" : locale;
        return db.sql("""
                INSERT INTO email_custom_variables (id, var_key, var_value, description, template_id, locale, updated_at)
                VALUES (:id, :key, :val, :desc, :tid, :locale, NOW())
                ON CONFLICT (var_key, template_id, locale)
                DO UPDATE SET var_value = :val, description = :desc, updated_at = NOW()
                RETURNING id, var_key, var_value, description, template_id, locale
                """)
                .bind("id", idService.nextId())
                .bind("key", key)
                .bind("val", value)
                .bind("desc", description != null ? description : "")
                .bind("tid", tid)
                .bind("locale", loc)
                .map(this::mapCustomVar)
                .one()
                .flatMap(v -> refreshCustomVarsCache().thenReturn(v))
                .doOnSuccess(v -> log.info("Saved custom variable: {}={} (scope: {}, locale: {})", key, value, tid, loc));
    }

    /** Update a custom variable by ID. */
    public Mono<CustomVar> updateCustomVariable(long id, String value, String description) {
        return db.sql("""
                UPDATE email_custom_variables SET var_value = :val, description = :desc, updated_at = NOW()
                WHERE id = :id
                RETURNING id, var_key, var_value, description, template_id, locale
                """)
                .bind("id", id)
                .bind("val", value)
                .bind("desc", description != null ? description : "")
                .map(this::mapCustomVar)
                .one()
                .flatMap(v -> refreshCustomVarsCache().thenReturn(v))
                .doOnSuccess(v -> { if (v != null) log.info("Updated custom variable id={}: {}={}", id, v.key(), v.value()); });
    }

    /** Delete a custom variable by ID. */
    public Mono<Boolean> deleteCustomVariable(long id) {
        return db.sql("DELETE FROM email_custom_variables WHERE id = :id")
                .bind("id", id)
                .fetch().rowsUpdated()
                .map(rows -> rows > 0)
                .flatMap(deleted -> refreshCustomVarsCache().thenReturn(deleted))
                .doOnSuccess(deleted -> { if (deleted) log.info("Deleted custom variable id={}", id); });
    }

    private CustomVar mapCustomVar(io.r2dbc.spi.Readable row) {
        return new CustomVar(
                row.get("id", Long.class),
                row.get("var_key", String.class),
                row.get("var_value", String.class),
                row.get("description", String.class),
                row.get("template_id", String.class),
                row.get("locale", String.class));
    }

    // ==================== Template Overrides ====================

    public String readClasspathTemplate(String templateName) {
        String path = "templates/email/" + templateName + ".html";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("Template not found: " + templateName);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read template: " + templateName, e);
        }
    }

    public Mono<TemplateSource> getTemplateSource(String templateId) {
        return db.sql("SELECT html_content FROM email_template_overrides WHERE template_id = :id")
                .bind("id", templateId)
                .map(row -> row.get("html_content", String.class))
                .one()
                .map(html -> new TemplateSource(html, true))
                .switchIfEmpty(Mono.fromCallable(() ->
                    new TemplateSource(readClasspathTemplate(templateId), false))
                    .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<Void> saveOverride(String templateId, String htmlContent, String updatedBy) {
        return db.sql("""
                INSERT INTO email_template_overrides (template_id, html_content, updated_at, updated_by)
                VALUES (:id, :html, NOW(), :by)
                ON CONFLICT (template_id)
                DO UPDATE SET html_content = :html, updated_at = NOW(), updated_by = :by
                """)
                .bind("id", templateId)
                .bind("html", htmlContent)
                .bind("by", updatedBy)
                .fetch().rowsUpdated()
                .doOnSuccess(rows -> {
                    log.info("Saved email template override: {} by {}", templateId, updatedBy);
                    fileTemplateEngine.clearTemplateCache();
                })
                .then();
    }

    public Mono<Boolean> deleteOverride(String templateId) {
        return db.sql("DELETE FROM email_template_overrides WHERE template_id = :id")
                .bind("id", templateId)
                .fetch().rowsUpdated()
                .map(rows -> rows > 0)
                .doOnSuccess(deleted -> {
                    if (deleted) log.info("Deleted email template override: {}", templateId);
                });
    }

    public Flux<String> getOverriddenTemplateIds() {
        return db.sql("SELECT template_id FROM email_template_overrides")
                .map(row -> row.get("template_id", String.class))
                .all();
    }

    public record TemplateSource(String html, boolean isOverride) {}
}
