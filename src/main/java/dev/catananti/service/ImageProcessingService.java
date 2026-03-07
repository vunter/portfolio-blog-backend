package dev.catananti.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Image processing service that generates size variants and strips EXIF metadata.
 * Uses Java's built-in java.awt APIs — no external dependencies needed.
 */
@Service
@Slf4j
public class ImageProcessingService {

    public record ImageVariant(String suffix, byte[] data, int width, int height) {}

    private static final int THUMBNAIL_WIDTH = 150;
    private static final int MEDIUM_WIDTH = 600;
    private static final int LARGE_WIDTH = 1200;

    /**
     * Process an image and generate size variants.
     * Returns a map of suffix → ImageVariant (e.g., "-thumb", "-medium", "-large", "" for original).
     * EXIF is stripped from all variants by re-encoding.
     */
    public Mono<Map<String, ImageVariant>> processImage(byte[] imageBytes, String contentType) {
        return Mono.fromCallable(() -> doProcessImage(imageBytes, contentType))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, ImageVariant> doProcessImage(byte[] imageBytes, String contentType) {
        Map<String, ImageVariant> variants = new LinkedHashMap<>();
        String format = getFormatName(contentType);

        // WebP: can't process with standard Java, just return original
        if ("webp".equals(format)) {
            variants.put("", new ImageVariant("", imageBytes, 0, 0));
            return variants;
        }

        // Check image dimensions before full decompression to prevent OOM
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis);
                    int w = reader.getWidth(0);
                    int h = reader.getHeight(0);
                    long pixels = (long) w * h;
                    if (pixels > 50_000_000) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Image dimensions too large: " + w + "x" + h);
                    }
                } finally {
                    reader.dispose();
                }
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            log.warn("Failed to check image dimensions: {}", e.getMessage(), e);
        }

        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (original == null) {
                log.warn("Failed to decode image, returning raw bytes");
                variants.put("", new ImageVariant("", imageBytes, 0, 0));
                return variants;
            }

            int origWidth = original.getWidth();
            int origHeight = original.getHeight();

            // Re-encode original (strips EXIF)
            byte[] cleanOriginal = encode(original, format);
            variants.put("", new ImageVariant("", cleanOriginal, origWidth, origHeight));

            // Generate thumbnail if original is wider than threshold
            if (origWidth > THUMBNAIL_WIDTH) {
                BufferedImage thumb = resize(original, THUMBNAIL_WIDTH);
                variants.put("-thumb", new ImageVariant("-thumb",
                        encode(thumb, format), thumb.getWidth(), thumb.getHeight()));
            }

            // Generate medium if original is wider
            if (origWidth > MEDIUM_WIDTH) {
                BufferedImage medium = resize(original, MEDIUM_WIDTH);
                variants.put("-medium", new ImageVariant("-medium",
                        encode(medium, format), medium.getWidth(), medium.getHeight()));
            }

            // Generate large if original is wider
            if (origWidth > LARGE_WIDTH) {
                BufferedImage large = resize(original, LARGE_WIDTH);
                variants.put("-large", new ImageVariant("-large",
                        encode(large, format), large.getWidth(), large.getHeight()));
            }

            log.debug("Generated {} variants for image ({}x{})", variants.size(), origWidth, origHeight);

        } catch (IOException e) {
            log.error("Image processing failed", e);
            variants.put("", new ImageVariant("", imageBytes, 0, 0));
        }

        return variants;
    }

    private BufferedImage resize(BufferedImage source, int targetWidth) {
        double ratio = (double) targetWidth / source.getWidth();
        int targetHeight = (int) (source.getHeight() * ratio);

        int imageType = source.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : source.getType();
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, imageType);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return resized;
    }

    private byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if ("png".equals(format) && image.getColorModel().hasAlpha()) {
            ImageIO.write(image, "png", baos);
        } else {
            // Convert ARGB to RGB for JPEG (JPEG doesn't support alpha)
            if ("jpg".equals(format) && image.getType() == BufferedImage.TYPE_INT_ARGB) {
                BufferedImage rgb = new BufferedImage(
                        image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.drawImage(image, 0, 0, null);
                g.dispose();
                image = rgb;
            }
            ImageIO.write(image, format, baos);
        }
        return baos.toByteArray();
    }

    private String getFormatName(String contentType) {
        if (contentType == null) return "jpg";
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }
}
