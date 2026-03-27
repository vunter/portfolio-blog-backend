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
        @DisplayName("should handle invalid image data gracefully")
        void shouldHandleInvalidImageData() {
            byte[] invalidData = new byte[]{0, 0, 0, 0};

            StepVerifier.create(service.processImage(invalidData, "image/jpeg"))
                    .assertNext(variants -> {
                        assertThat(variants).containsOnlyKeys("");
                        // Should return raw bytes as fallback
                        assertThat(variants.get("").data()).isEqualTo(invalidData);
                    })
                    .verifyComplete();
        }
    }
}
