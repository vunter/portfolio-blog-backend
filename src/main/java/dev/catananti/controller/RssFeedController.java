package dev.catananti.controller;

import dev.catananti.entity.Article;
import dev.catananti.service.ArticleService;
import dev.catananti.service.CacheService;
import dev.catananti.util.XmlUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@RestController
@RequiredArgsConstructor
@Slf4j
public class RssFeedController {

    private final ArticleService articleService;
    private final CacheService cacheService;
    
    private static final String RSS_CACHE_KEY = "rss:feed";
    private static final Duration RSS_CACHE_TTL = Duration.ofMinutes(15);

    @Value("${app.site-url:https://catananti.dev}")
    private String siteUrl;

    @Value("${app.name:Portfolio Blog}")
    private String siteName;

    @Value("${app.description:Developer blog with articles about Java, Cloud, and Software Architecture}")
    private String siteDescription;

    private static final DateTimeFormatter RSS_DATE_FORMAT = 
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

    // TODO F-102: Add Cache-Control/ETag headers to RSS feed endpoint (currently relies on CacheService TTL only)
    @GetMapping(value = {"/rss.xml", "/feed.xml"}, produces = MediaType.APPLICATION_XML_VALUE)
    public Mono<String> getRssFeed() {
        log.debug("Generating RSS feed");
        return cacheService.get(RSS_CACHE_KEY, String.class)
                .switchIfEmpty(
                        articleService.findAllPublishedForFeed()
                                .take(20)
                                .collectList()
                                .map(this::buildRssFeed)
                                .flatMap(rss -> cacheService.set(RSS_CACHE_KEY, rss, RSS_CACHE_TTL)
                                        .thenReturn(rss))
                );
    }

    private String buildRssFeed(java.util.List<Article> articles) {
        // TODO F-103: Map Article entities to a lightweight DTO (e.g., RssFeedItem) to avoid entity leakage
        StringBuilder xml = new StringBuilder();
        
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n");
        xml.append("  <channel>\n");
        xml.append("    <title>").append(XmlUtil.escapeXml(siteName)).append("</title>\n");
        xml.append("    <link>").append(XmlUtil.escapeXml(siteUrl)).append("</link>\n");
        xml.append("    <description>").append(XmlUtil.escapeXml(siteDescription)).append("</description>\n");
        xml.append("    <language>en-us</language>\n");
        xml.append("    <atom:link href=\"").append(siteUrl).append("/rss.xml\" rel=\"self\" type=\"application/rss+xml\"/>\n");
        
        if (!articles.isEmpty() && articles.getFirst().getPublishedAt() != null) {
            xml.append("    <lastBuildDate>")
               .append(articles.getFirst().getPublishedAt().atOffset(ZoneOffset.UTC).format(RSS_DATE_FORMAT))
               .append("</lastBuildDate>\n");
        }

        for (Article article : articles) {
            xml.append("    <item>\n");
            xml.append("      <title>").append(XmlUtil.escapeXml(article.getTitle())).append("</title>\n");
            xml.append("      <link>").append(siteUrl).append("/blog/").append(XmlUtil.escapeXml(article.getSlug())).append("</link>\n");
            xml.append("      <guid isPermaLink=\"true\">").append(siteUrl).append("/blog/").append(XmlUtil.escapeXml(article.getSlug())).append("</guid>\n");
            
            if (article.getExcerpt() != null) {
                xml.append("      <description>").append(XmlUtil.escapeXml(article.getExcerpt())).append("</description>\n");
            } else if (article.getSeoDescription() != null) {
                xml.append("      <description>").append(XmlUtil.escapeXml(article.getSeoDescription())).append("</description>\n");
            }
            
            if (article.getPublishedAt() != null) {
                xml.append("      <pubDate>")
                   .append(article.getPublishedAt().atOffset(ZoneOffset.UTC).format(RSS_DATE_FORMAT))
                   .append("</pubDate>\n");
            }
            
            xml.append("    </item>\n");
        }
        
        xml.append("  </channel>\n");
        xml.append("</rss>");
        
        return xml.toString();
    }
}
