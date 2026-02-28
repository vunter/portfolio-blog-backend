package dev.catananti.controller;

import dev.catananti.dto.RssFeedItem;
import dev.catananti.entity.Article;
import dev.catananti.service.ArticleService;
import dev.catananti.service.CacheService;
import dev.catananti.util.XmlUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @GetMapping({"/rss.xml", "/feed.xml"})
    public Mono<ResponseEntity<String>> getRssFeed() {
        log.debug("Generating RSS feed");
        return cacheService.get(RSS_CACHE_KEY, String.class)
                .switchIfEmpty(
                        articleService.findAllPublishedForFeed()
                                .take(20)
                                .map(article -> new RssFeedItem(
                                        article.getTitle(), article.getSlug(), article.getExcerpt(),
                                        article.getSeoDescription(), article.getPublishedAt()))
                                .collectList()
                                .map(this::buildRssFeed)
                                .flatMap(rss -> cacheService.set(RSS_CACHE_KEY, rss, RSS_CACHE_TTL)
                                        .thenReturn(rss))
                )
                .map(rss -> {
                    String etag = "\"" + Integer.toHexString(rss.hashCode()) + "\"";
                    return ResponseEntity.ok()
                            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                            .eTag(etag)
                            .contentType(MediaType.APPLICATION_XML)
                            .body(rss);
                });
    }

    private String buildRssFeed(java.util.List<RssFeedItem> items) {
        StringBuilder xml = new StringBuilder();
        
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n");
        xml.append("  <channel>\n");
        xml.append("    <title>").append(XmlUtil.escapeXml(siteName)).append("</title>\n");
        xml.append("    <link>").append(XmlUtil.escapeXml(siteUrl)).append("</link>\n");
        xml.append("    <description>").append(XmlUtil.escapeXml(siteDescription)).append("</description>\n");
        xml.append("    <language>en-us</language>\n");
        xml.append("    <atom:link href=\"").append(siteUrl).append("/rss.xml\" rel=\"self\" type=\"application/rss+xml\"/>\n");
        
        if (!items.isEmpty() && items.getFirst().publishedAt() != null) {
            xml.append("    <lastBuildDate>")
               .append(items.getFirst().publishedAt().atOffset(ZoneOffset.UTC).format(RSS_DATE_FORMAT))
               .append("</lastBuildDate>\n");
        }

        for (RssFeedItem item : items) {
            xml.append("    <item>\n");
            xml.append("      <title>").append(XmlUtil.escapeXml(item.title())).append("</title>\n");
            xml.append("      <link>").append(siteUrl).append("/blog/").append(XmlUtil.escapeXml(item.slug())).append("</link>\n");
            xml.append("      <guid isPermaLink=\"true\">").append(siteUrl).append("/blog/").append(XmlUtil.escapeXml(item.slug())).append("</guid>\n");
            
            if (item.excerpt() != null) {
                xml.append("      <description>").append(XmlUtil.escapeXml(item.excerpt())).append("</description>\n");
            } else if (item.seoDescription() != null) {
                xml.append("      <description>").append(XmlUtil.escapeXml(item.seoDescription())).append("</description>\n");
            }
            
            if (item.publishedAt() != null) {
                xml.append("      <pubDate>")
                   .append(item.publishedAt().atOffset(ZoneOffset.UTC).format(RSS_DATE_FORMAT))
                   .append("</pubDate>\n");
            }
            
            xml.append("    </item>\n");
        }
        
        xml.append("  </channel>\n");
        xml.append("</rss>");
        
        return xml.toString();
    }
}
