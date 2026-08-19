package dev.catananti.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ImageProcessingService")
class ImageProcessingServiceTest {

    private ImageProcessingService service;

    @BeforeEach
    void setUp() {
        service = new ImageProcessingService();
    }

    private byte[] createTestImage(int width, int height, String format) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return baos.toByteArray();
    }

    @Nested
    @DisplayName("processImage")
    class ProcessImage {

        @Test
        @DisplayName("should generate all variants for large image")
        void shouldGenerateAllVariants() throws IOException {
            byte[] imageBytes = createTestImage(2000, 1000, "png");

            StepVerifier.create(service.processImage(imageBytes, "image/png"))
                    .assertNext(variants -> {
                        assertThat(variants).containsKeys("", "-thumb", "-medium", "-large");
                        assertThat(variants.get("-thumb").width()).isEqualTo(150);
                        assertThat(variants.get("-medium").width()).isEqualTo(600);
                        assertThat(variants.get("-large").width()).isEqualTo(1200);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should generate only original for small image")
        void shouldGenerateOnlyOriginalForSmallImage() throws IOException {
            byte[] imageBytes = createTestImage(100, 80, "jpg");

            StepVerifier.create(service.processImage(imageBytes, "image/jpeg"))
                    .assertNext(variants -> {
                        assertThat(variants).containsOnlyKeys("");
                        assertThat(variants.get("").width()).isEqualTo(100);
                        assertThat(variants.get("").height()).isEqualTo(80);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should handle WebP by returning raw bytes")
        void shouldHandleWebP() {
            byte[] fakeWebP = new byte[]{1, 2, 3, 4};

            StepVerifier.create(service.processImage(fakeWebP, "image/webp"))
                    .assertNext(variants -> {
                        assertThat(variants).containsOnlyKeys("");
                        assertThat(variants.get("").data()).isEqualTo(fakeWebP);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should generate thumb and medium but not large for mid-size image")
        void shouldGenerateThumbAndMediumOnly() throws IOException {
            byte[] imageBytes = createTestImage(800, 600, "jpg");

            StepVerifier.create(service.processImage(imageBytes, "image/jpeg"))
                    .assertNext(variants -> {
                        assertThat(variants).containsKeys("", "-thumb", "-medium");
                        assertThat(variants).doesNotContainKey("-large");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should maintain aspect ratio in resized variants")
        void shouldMaintainAspectRatio() throws IOException {
            byte[] imageBytes = createTestImage(2000, 1000, "png");

            StepVerifier.create(service.processImage(imageBytes, "image/png"))
                    .assertNext(variants -> {
                        var thumb = variants.get("-thumb");
                        assertThat(thumb.width()).isEqualTo(150);
                        assertThat(thumb.height()).isEqualTo(75); // 2:1 ratio
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should handle PNG with alpha channel")
        void shouldHandlePngWithAlpha() throws IOException {
            BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(255, 0, 0, 128));
            g.fillRect(0, 0, 200, 200);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);

            StepVerifier.create(service.processImage(baos.toByteArray(), "image/png"))
                    .assertNext(variants -> {
                        assertThat(variants).containsKeys("", "-thumb");
                        assertThat(variants.get("").data().length).isGreaterThan(0);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("AUD18-JB6: should REJECT undecodable image data with 400 instead of storing raw bytes")
        void shouldRejectInvalidImageData() {
            byte[] invalidData = new byte[]{0, 0, 0, 0};

            StepVerifier.create(service.processImage(invalidData, "image/jpeg"))
                    .expectErrorMatches(e ->
                            e instanceof org.springframework.web.server.ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400)
                    .verify();
        }

        @Test
        @DisplayName("AUD18-JB6: should REJECT garbage bytes with a valid JPEG magic prefix")
        void shouldRejectCorruptJpegBytes() {
            // Passes a magic-bytes check (FF D8 FF) but is not a decodable image —
            // exactly the payload the raw-bytes fallback used to store verbatim.
            byte[] corrupt = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x11, 0x22, 0x33};

            StepVerifier.create(service.processImage(corrupt, "image/jpeg"))
                    .expectErrorMatches(e ->
                            e instanceof org.springframework.web.server.ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400)
                    .verify();
        }
    }
}
