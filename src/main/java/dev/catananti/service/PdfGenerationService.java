package dev.catananti.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Margin;
import dev.catananti.exception.PdfGenerationException;
import dev.catananti.metrics.BlogMetrics;
import dev.catananti.util.HtmlUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Service for converting HTML content to PDF documents.
 * Uses Playwright with Chromium for perfect CSS3 support (flexbox, grid, etc).
 */
@Service
@Slf4j
public class PdfGenerationService {

    private final BlogMetrics blogMetrics;

    private volatile Playwright playwright;
    private volatile Browser browser;
    private volatile Disposable initSubscription;

    public PdfGenerationService(BlogMetrics blogMetrics) {
        this.blogMetrics = blogMetrics;
    }

    @org.springframework.beans.factory.annotation.Value("${app.pdf.timeout-seconds:30}")
    private int timeoutSeconds = 30;

    @org.springframework.beans.factory.annotation.Value("${app.pdf.max-pages:50}")
    private int maxPages = 50;

    @org.springframework.beans.factory.annotation.Value("${app.pdf.max-concurrent:3}")
    private int maxConcurrentPdf = 3;
    // Eager init covers the (rare) case where a method is called before
    // @PostConstruct runs — typically only matters in unit tests that
    // construct the bean directly and never invoke init().
    private volatile Semaphore pdfSemaphore = new Semaphore(3);

    // Q5.15: Optional remote browser endpoint for Playwright sidecar.
    // When set, Chromium runs in a separate container — main image drops ~500MB.
    @org.springframework.beans.factory.annotation.Value("${app.pdf.browser-ws-endpoint:}")
    private String browserWsEndpoint;

    /**
     * Paper size dimensions.
     */
    public enum PaperSize {
        A4("A4"),
        LETTER("Letter"),
        LEGAL("Legal");

        private final String format;

        PaperSize(String format) {
            this.format = format;
        }

        public String getFormat() {
            return format;
        }
    }

    /**
     * Reactive lock for non-blocking lazy initialization.
     * Uses Mono.defer + cache to ensure single initialization without blocking Netty threads.
     *
     * Q5.15: Supports two modes:
     * - Local: launches Chromium in the same container (default, requires Chromium installed)
     * - Remote: connects to a Playwright sidecar via WebSocket (set app.pdf.browser-ws-endpoint)
     */
    private volatile Mono<Browser> browserMono;

    private Mono<Browser> createBrowserMono() {
        return Mono.defer(() ->
                Mono.fromCallable(() -> {
                    log.info("Initializing Playwright for PDF generation...");
                    playwright = Playwright.create();

                    if (browserWsEndpoint != null && !browserWsEndpoint.isBlank()) {
                        // Q5.15: Remote sidecar — Chromium runs in a separate container
                        log.info("Connecting to remote Playwright browser at {}", browserWsEndpoint);
                        browser = playwright.chromium().connect(browserWsEndpoint);
                        log.info("Connected to remote Playwright browser");
                    } else {
                        // Local mode — launch Chromium in this container
                        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                                .setHeadless(true);
                        launchOptions.setArgs(java.util.List.of(
                                "--no-sandbox",
                                "--disable-setuid-sandbox",
                                "--disable-dev-shm-usage",
                                "--disable-gpu"
                        ));
                        browser = playwright.chromium().launch(launchOptions);
                        log.info("Playwright initialized with local Chromium");
                    }
                    return browser;
                }).subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Playwright init failed: {}", e.getMessage()))
                .retry(2)
        ).cache();
    }

    @PostConstruct
    public void init() {
        this.pdfSemaphore = new Semaphore(maxConcurrentPdf);
        this.browserMono = createBrowserMono();
        initSubscription = browserMono.subscribe(
                b -> log.info("Playwright pre-initialized successfully"),
                e -> log.warn("Failed to pre-initialize Playwright: {}. Will retry on first use.", e.getMessage(), e)
        );
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down Playwright...");
        // Invalidate the cached Mono FIRST so any in-flight request doesn't get a closed
        // Browser handed back from the cache. New ensureBrowserReactive() callers will
        // see a failing Mono and short-circuit.
        browserMono = Mono.error(new IllegalStateException("PdfGenerationService is shutting down"));
        if (initSubscription != null && !initSubscription.isDisposed()) {
            initSubscription.dispose();
        }
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception e) {
                log.warn("Error closing browser: {}", e.getMessage(), e);
            } finally {
                browser = null;
            }
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception e) {
                log.warn("Error closing playwright: {}", e.getMessage(), e);
            } finally {
                playwright = null;
            }
        }
        log.info("Playwright shutdown complete");
    }

    /**
     * Ensure browser is initialized reactively (non-blocking).
     * Uses cached Mono to guarantee single initialization without synchronized blocks.
     */
    private Mono<Browser> ensureBrowserReactive() {
        Mono<Browser> current = browserMono;
        if (current == null) {
            synchronized (this) {
                current = browserMono;
                if (current == null) {
                    current = createBrowserMono();
                    browserMono = current;
                }
            }
        }
        return current;
    }

    /**
     * Convert HTML content to PDF bytes.
     *
     * @param htmlContent The HTML content to convert
     * @param paperSize   Paper size (A4, LETTER, LEGAL)
     * @param landscape   Whether to use landscape orientation
     * @return Mono containing the PDF bytes
     */
    public Mono<byte[]> generatePdf(String htmlContent, String paperSize, boolean landscape) {
        // Q12.3: Record PDF generation latency
        io.micrometer.core.instrument.Timer.Sample sample = io.micrometer.core.instrument.Timer.start();
        return ensureBrowserReactive()
                .flatMap(browserInstance -> Mono.fromCallable(() -> {
                            // F-197: Blocking semaphore.acquire() is intentionally wrapped in
                            // Mono.fromCallable + subscribeOn(boundedElastic) to avoid blocking
                            // the Netty event loop. This is the correct reactive pattern.
                            pdfSemaphore.acquire();
                            try {
                                return convertToPdf(browserInstance, htmlContent, paperSize, landscape);
                            } finally {
                                pdfSemaphore.release();
                            }
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .doOnSuccess(bytes -> {
                    sample.stop(blogMetrics.getPdfGenerationTimer());
                    blogMetrics.incrementPdfGenerated();
                    log.info("PDF generated successfully: {} bytes", bytes.length);
                })
                .doOnError(e -> log.error("PDF generation failed", e));
    }

    /**
     * Convert HTML content to PDF with variable substitution.
     *
     * @param htmlContent The HTML template content
     * @param variables   Variables to substitute in the template
     * @param paperSize   Paper size
     * @param landscape   Whether to use landscape orientation
     * @return Mono containing the PDF bytes
     */
    public Mono<byte[]> generatePdfWithVariables(
            String htmlContent,
            Map<String, String> variables,
            String paperSize,
            boolean landscape) {

        String processedHtml = substituteVariables(htmlContent, variables);
        return generatePdf(processedHtml, paperSize, landscape);
    }

    /**
     * Internal method to perform the actual PDF conversion using Playwright.
     * This is a blocking operation and should be called on boundedElastic scheduler.
     */
    /**
     * Sanitize HTML content for PDF generation.
     * Strips script tags and dangerous elements while preserving layout/style tags
     * needed for PDF rendering.
     */
    private String sanitizeHtmlForPdf(String htmlContent) {
        Document doc = Jsoup.parse(htmlContent);
        // Remove script tags and event handler attributes
        doc.select("script, iframe, object, embed, applet, form, input, textarea, button").remove();
        // Remove event handler attributes from all elements
        doc.getAllElements().forEach(element -> {
            element.attributes().asList().stream()
                    .filter(attr -> attr.getKey().toLowerCase().startsWith("on"))
                    .forEach(attr -> element.removeAttr(attr.getKey()));
            // Remove javascript: protocol in href/src
            for (String urlAttr : new String[]{"href", "src", "action"}) {
                String val = element.attr(urlAttr);
                if (val != null && val.trim().toLowerCase().startsWith("javascript:")) {
                    element.removeAttr(urlAttr);
                }
            }
        });
        // Preserve full document structure for PDF rendering
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.html);
        return doc.html();
    }

    private boolean isRemoteBrowser() {
        return browserWsEndpoint != null && !browserWsEndpoint.isBlank();
    }

    private byte[] convertToPdf(Browser browserInstance, String htmlContent, String paperSizeStr, boolean landscape) {
        Path tempFile = null;
        BrowserContext context = null;

        try {
            // Sanitize HTML before rendering to prevent script injection
            String sanitizedHtml = sanitizeHtmlForPdf(htmlContent);

            // Create browser context and page
            context = browserInstance.newContext();
            Page page = context.newPage();

            // SECURITY: Block all external network requests to prevent SSRF.
            page.route("**", route -> {
                String url = route.request().url();
                if (url.startsWith("file://") || url.startsWith("data:") || url.startsWith("about:")) {
                    route.resume();
                } else {
                    log.warn("Blocked external request during PDF generation: {}", url);
                    route.abort();
                }
            });

            if (isRemoteBrowser()) {
                // Q5.15: Remote browser — use setContent() since temp files aren't shared
                page.setContent(sanitizedHtml);
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
            } else {
                // Local browser — use temp file (Playwright works best with file URLs)
                tempFile = Files.createTempFile("resume_", ".html");
                tempFile.toFile().deleteOnExit();
                Files.writeString(tempFile, sanitizedHtml, StandardCharsets.UTF_8);
                log.debug("Created temp HTML file: {}", tempFile);
                page.navigate("file:///" + tempFile.toAbsolutePath().toString().replace("\\", "/"));
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
            }
            
            // Inject CSS for proper PDF output (EXACTLY like Python script)
            page.addStyleTag(new Page.AddStyleTagOptions().setContent("""
                @page {
                    size: A4;
                    margin: 0;
                }
                html, body {
                    margin: 0 !important;
                    padding: 0 !important;
                    background: white !important;
                    background-color: white !important;
                }
                .page {
                    width: 210mm;
                    min-height: auto !important;
                    height: auto !important;
                    margin: 0 !important;
                    padding: 8mm 8mm !important;
                    box-shadow: none !important;
                    page-break-after: auto !important;
                    page-break-inside: avoid;
                    break-after: page;
                }
                .page:last-child {
                    break-after: avoid;
                }
            """));
            
            // Configure PDF options
            // Margins are set to 0 here because the injected CSS above handles
            // page layout via @page { margin: 0 } and .page { padding: 8mm 8mm }.
            // Setting margins in BOTH places causes double margins and extra pages.
            Page.PdfOptions pdfOptions = new Page.PdfOptions()
                    .setFormat("A4")
                    .setLandscape(landscape)
                    .setPrintBackground(true)
                    .setPreferCSSPageSize(true)
                    .setMargin(new Margin()
                            .setTop("0")
                            .setBottom("0")
                            .setLeft("0")
                            .setRight("0"));
            
            // Generate PDF
            byte[] pdfBytes = page.pdf(pdfOptions);

            // F-210: Enforce max pages limit
            // Rough estimation: typical A4 PDF page ~3KB-50KB; reject extremely large outputs
            // A more precise check would parse the PDF page count, but this is a safe guard
            if (pdfBytes.length > maxPages * 200_000L) {
                throw new PdfGenerationException("PDF exceeds maximum allowed size (estimated >" + maxPages + " pages)");
            }
            
            log.debug("PDF generated: {} bytes", pdfBytes.length);
            return pdfBytes;
            
        } catch (Exception e) {
            log.error("Failed to generate PDF: {}", e.getMessage(), e);
            throw new PdfGenerationException("Failed to generate PDF: " + e.getMessage(), e);
        } finally {
            // Cleanup
            if (context != null) {
                try {
                    context.close();
                } catch (Exception e) {
                    log.warn("Error closing browser context", e);
                }
            }
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    log.debug("Failed to delete temp file", e);
                }
            }
        }
    }

    /**
     * Substitute variables in HTML template.
     * Variables are in format {{variableName}}.
     * All values are HTML-escaped to prevent template injection.
     */
    private String substituteVariables(String html, Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return html;
        }

        String result = html;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? escapeHtml(entry.getValue()) : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    /**
     * Escape HTML special characters to prevent injection.
     */
    private String escapeHtml(String input) {
        return HtmlUtils.escapeHtml(input);
    }

    /**
     * Validate HTML content for PDF generation.
     * Checks if HTML is well-formed and can be rendered.
     *
     * @param htmlContent The HTML content to validate
     * @return Mono with true if valid, false otherwise
     */
    public Mono<Boolean> validateHtml(String htmlContent) {
        return Mono.fromCallable(() -> {
            if (htmlContent == null || htmlContent.isBlank()) {
                return false;
            }
            String trimmed = htmlContent.trim().toLowerCase();
            // Require at least one HTML tag and basic structure
            return trimmed.contains("<html") || trimmed.contains("<!doctype")
                    || (trimmed.contains("<") && trimmed.contains(">") && trimmed.contains("</"));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Extract plain text preview from HTML content.
     *
     * @param htmlContent HTML content to extract text from
     * @param maxLength   Maximum length of extracted text
     * @return Extracted text content
     */
    public String extractTextPreview(String htmlContent, int maxLength) {
        try {
            Document doc = Jsoup.parse(htmlContent);
            String text = doc.body().text();
            if (text.length() > maxLength) {
                return text.substring(0, maxLength) + "...";
            }
            return text;
        } catch (Exception e) {
            log.warn("Failed to extract text preview: {}", e.getMessage(), e);
            return "";
        }
    }
}
