package dev.catananti.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Margin;
import dev.catananti.exception.PdfGenerationException;
import dev.catananti.util.HtmlUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
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

    private volatile Playwright playwright;
    private volatile Browser browser;

    @org.springframework.beans.factory.annotation.Value("${app.pdf.timeout-seconds:30}")
    private int timeoutSeconds = 30;

    @org.springframework.beans.factory.annotation.Value("${app.pdf.max-pages:50}")
    private int maxPages = 50;

    /**
     * IMP-08: Semaphore to limit concurrent PDF generation.
     * Playwright serializes page operations through a single browser process,
     * so unbounded concurrency starves the bounded-elastic thread pool.
     */
    private static final int MAX_CONCURRENT_PDF = 3;
    private final Semaphore pdfSemaphore = new Semaphore(MAX_CONCURRENT_PDF);

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
     */
    private final Mono<Browser> browserMono = Mono.defer(() ->
            Mono.fromCallable(() -> {
                log.info("Initializing Playwright for PDF generation...");
                playwright = Playwright.create();

                BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                        .setHeadless(true);

                // Use system Chromium if PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH is set
                String chromiumPath = System.getenv("PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH");
                if (chromiumPath != null && !chromiumPath.isBlank()) {
                    log.info("Using system Chromium at: {}", chromiumPath);
                    launchOptions.setExecutablePath(java.nio.file.Paths.get(chromiumPath));
                }

                // Container-safe flags
                launchOptions.setArgs(java.util.List.of(
                        "--no-sandbox",
                        "--disable-setuid-sandbox",
                        "--disable-dev-shm-usage",
                        "--disable-gpu"
                ));

                browser = playwright.chromium().launch(launchOptions);
                log.info("Playwright initialized successfully with Chromium");
                return browser;
            }).subscribeOn(Schedulers.boundedElastic())
            .doOnError(e -> log.error("Playwright init failed: {}", e.getMessage()))
            .retry(2)
    ).cache(); // cache() ensures single initialization

    @PostConstruct
    public void init() {
        // Eagerly trigger initialization on boundedElastic scheduler (non-blocking)
        browserMono.subscribe(
                b -> log.info("Playwright pre-initialized successfully"),
                e -> log.warn("Failed to pre-initialize Playwright: {}. Will retry on first use.", e.getMessage())
        );
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down Playwright...");
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception e) {
                log.warn("Error closing browser: {}", e.getMessage());
            }
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception e) {
                log.warn("Error closing playwright: {}", e.getMessage());
            }
        }
        log.info("Playwright shutdown complete");
    }

    /**
     * Ensure browser is initialized reactively (non-blocking).
     * Uses cached Mono to guarantee single initialization without synchronized blocks.
     */
    private Mono<Browser> ensureBrowserReactive() {
        return browserMono;
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
                .doOnSuccess(bytes -> log.info("PDF generated successfully: {} bytes", bytes.length))
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
    private byte[] convertToPdf(Browser browserInstance, String htmlContent, String paperSizeStr, boolean landscape) {
        Path tempFile = null;
        BrowserContext context = null;
        
        try {
            // Create a temporary HTML file (Playwright works best with file URLs)
            tempFile = Files.createTempFile("resume_", ".html");
            Files.writeString(tempFile, htmlContent, StandardCharsets.UTF_8);
            
            log.debug("Created temp HTML file: {}", tempFile);
            
            // Create browser context and page
            context = browserInstance.newContext();
            Page page = context.newPage();
            
            // SECURITY: Block all external network requests to prevent SSRF.
            // Only file:// URLs (local temp HTML) are permitted.
            page.route("**", route -> {
                String url = route.request().url();
                if (url.startsWith("file://")) {
                    route.resume();
                } else {
                    log.warn("Blocked external request during PDF generation: {}", url);
                    route.abort();
                }
            });
            
            // Navigate to the HTML file
            page.navigate("file:///" + tempFile.toAbsolutePath().toString().replace("\\", "/"));
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
            
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
                    log.warn("Error closing browser context: {}", e.getMessage());
                }
            }
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    log.warn("Failed to delete temp file: {}", e.getMessage());
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
            log.warn("Failed to extract text preview: {}", e.getMessage());
            return "";
        }
    }
}
