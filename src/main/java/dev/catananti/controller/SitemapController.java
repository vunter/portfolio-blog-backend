package dev.catananti.controller;

import dev.catananti.entity.Article;
import dev.catananti.entity.Tag;
import dev.catananti.service.ArticleService;
import dev.catananti.service.CacheService;
import dev.catananti.service.TagService;
import dev.catananti.util.XmlUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
public class SitemapController {

    private final ArticleService articleService;
    private final TagService tagService;
    private final CacheService cacheService;
    
    private static final String SITEMAP_CACHE_KEY = "sitemap:xml";
    private static final Duration SITEMAP_CACHE_TTL = Duration.ofMinutes(30);

    @Value("${app.site-url:https://catananti.dev}")
    private String siteUrl;

    private static final DateTimeFormatter SITEMAP_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @GetMapping("/sitemap.xml")
    public Mono<String> getSitemap() {
        log.debug("Generating sitemap");
        return cacheService.get(SITEMAP_CACHE_KEY, String.class)
                .switchIfEmpty(
                        Mono.zip(
                                articleService.findAllPublishedForFeed().collectList(),
                                tagService.findAllTagEntities().collectList()
                        )
                        .map(tuple -> buildSitemap(tuple.getT1(), tuple.getT2()))
                        .flatMap(sitemap -> cacheService.set(SITEMAP_CACHE_KEY, sitemap, SITEMAP_CACHE_TTL)
                                .thenReturn(sitemap))
                );
    }

    private String buildSitemap(List<Article> articles, List<Tag> tags) {
        StringBuilder xml = new StringBuilder();
        
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        
        // Homepage
        xml.append("  <url>\n");
        xml.append("    <loc>").append(siteUrl).append("/</loc>\n");
        xml.append("    <changefreq>daily</changefreq>\n");
        xml.append("    <priority>1.0</priority>\n");
        xml.append("  </url>\n");
        
        // Blog listing page
        xml.append("  <url>\n");
        xml.append("    <loc>").append(siteUrl).append("/blog</loc>\n");
        xml.append("    <changefreq>daily</changefreq>\n");
        xml.append("    <priority>0.9</priority>\n");
        xml.append("  </url>\n");
        
        // About page
        xml.append("  <url>\n");
        xml.append("    <loc>").append(siteUrl).append("/about</loc>\n");
        xml.append("    <changefreq>monthly</changefreq>\n");
        xml.append("    <priority>0.7</priority>\n");
        xml.append("  </url>\n");
        
        // All published articles
        for (Article article : articles) {
            xml.append("  <url>\n");
            xml.append("    <loc>").append(siteUrl).append("/blog/").append(XmlUtil.escapeXml(article.getSlug())).append("</loc>\n");
            
            LocalDateTime lastMod = article.getUpdatedAt() != null ? article.getUpdatedAt() : article.getPublishedAt();
            if (lastMod != null) {
                xml.append("    <lastmod>").append(lastMod.format(SITEMAP_DATE_FORMAT)).append("</lastmod>\n");
            }
            
            xml.append("    <changefreq>weekly</changefreq>\n");
            xml.append("    <priority>0.8</priority>\n");
            xml.append("  </url>\n");
        }
        
        // Tag pages
        for (Tag tag : tags) {
            xml.append("  <url>\n");
            xml.append("    <loc>").append(siteUrl).append("/tags/").append(XmlUtil.escapeXml(tag.getSlug())).append("</loc>\n");
            xml.append("    <changefreq>weekly</changefreq>\n");
            xml.append("    <priority>0.6</priority>\n");
            xml.append("  </url>\n");
        }
        
        xml.append("</urlset>");
        
        return xml.toString();
    }
}
