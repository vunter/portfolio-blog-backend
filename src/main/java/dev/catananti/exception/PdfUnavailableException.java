package dev.catananti.exception;

/**
 * The PDF rendering browser (local Chromium or the Playwright sidecar) could not
 * be reached. Unlike a generic {@link PdfGenerationException}, this is an
 * infrastructure outage, not a document problem — callers get a 503 so clients
 * and monitors can tell "try again later" apart from "this document is broken".
 */
public class PdfUnavailableException extends PdfGenerationException {

    public PdfUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
