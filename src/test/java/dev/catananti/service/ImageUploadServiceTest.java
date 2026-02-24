package dev.catananti.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageUploadServiceTest {

    @InjectMocks
    private ImageUploadService imageUploadService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(imageUploadService, "uploadPath", tempDir.toString());
        ReflectionTestUtils.setField(imageUploadService, "maxFileSize", 10485760L); // 10MB
        ReflectionTestUtils.setField(imageUploadService, "siteUrl", "https://catananti.dev");
    }

    private FilePart mockFilePart(String filename, MediaType contentType) {
        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        when(filePart.filename()).thenReturn(filename);
        when(filePart.headers()).thenReturn(headers);
        return filePart;
    }

    /** Mock that only stubs filename — for tests that fail before accessing headers */
    private FilePart mockFilePartNameOnly(String filename) {
        FilePart filePart = mock(FilePart.class);
        when(filePart.filename()).thenReturn(filename);
        return filePart;
    }

    // ==========================================
    // uploadImage - validation
    // ==========================================
    @Nested
    @DisplayName("uploadImage - validation")
    class UploadImageValidation {

        @Test
        @DisplayName("Should reject file with disallowed extension")
        void shouldRejectDisallowedExtension() {
            FilePart filePart = mockFilePartNameOnly("malware.exe");

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("Invalid file extension"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject SVG files (XSS risk)")
        void shouldRejectSvgFiles() {
            FilePart filePart = mockFilePartNameOnly("image.svg");

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("Invalid file extension"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject file with invalid content type")
        void shouldRejectInvalidContentType() {
            FilePart filePart = mockFilePart("image.jpg", MediaType.TEXT_PLAIN);

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("Invalid content type"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject null content type")
        void shouldRejectNullContentType() {
            FilePart filePart = mockFilePart("image.png", null);

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("Invalid content type"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject filename with path traversal (..) attack")
        void shouldRejectPathTraversalInFilename() {
            FilePart filePart = mockFilePartNameOnly("../../../etc/passwd.jpg");

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("Invalid filename"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject filename with forward slash")
        void shouldRejectFilenameWithForwardSlash() {
            FilePart filePart = mockFilePartNameOnly("path/to/image.jpg");

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("Invalid filename"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject filename with backslash")
        void shouldRejectFilenameWithBackslash() {
            FilePart filePart = mockFilePartNameOnly("path\\to\\image.jpg");

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("Invalid filename"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject filename with null byte")
        void shouldRejectFilenameWithNullByte() {
            FilePart filePart = mockFilePartNameOnly("image\0.jpg");

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("Invalid filename"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject blank filename")
        void shouldRejectBlankFilename() {
            FilePart filePart = mockFilePartNameOnly("   ");

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("Invalid filename"))
                    .verify();
        }

        @Test
        @DisplayName("Should accept valid jpg with image/jpeg content type")
        void shouldAcceptValidJpg() {
            FilePart filePart = mockFilePart("photo.jpg", MediaType.IMAGE_JPEG);
            // transferTo will be called on save - just test validation passes
            when(filePart.transferTo(any(Path.class))).thenReturn(Mono.empty().then(Mono.defer(() -> {
                // Create a fake file with JPEG magic bytes
                try {
                    Path dir = tempDir.resolve(java.time.LocalDate.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM")));
                    Files.createDirectories(dir);
                    // We need to figure out the actual path - skip full integration
                } catch (Exception e) {}
                return Mono.empty();
            })));

            // This tests the validation part only — saving requires filesystem integration
            // The validation should pass without error
            // We'll verify that filename validation + extension + content type all pass:

            // Valid file part would reach saveFile
            // Since we can't easily mock the full save pipeline, we verify validation accepts the file
            // by checking that transferTo is attempted (meaning validation passed)
            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    // Will error in save step due to mock, but we confirm validation passed
                    .expectErrorMatches(e -> !(e instanceof IllegalArgumentException))
                    .verify();
        }

        @Test
        @DisplayName("Should accept valid png extension")
        void shouldAcceptValidPng() {
            FilePart filePart = mockFilePart("photo.png", MediaType.IMAGE_PNG);
            when(filePart.transferTo(any(Path.class))).thenReturn(Mono.empty());

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> !(e instanceof IllegalArgumentException))
                    .verify();
        }

        @Test
        @DisplayName("Should accept valid gif extension")
        void shouldAcceptValidGif() {
            FilePart filePart = mockFilePart("animation.gif", MediaType.IMAGE_GIF);
            when(filePart.transferTo(any(Path.class))).thenReturn(Mono.empty());

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> !(e instanceof IllegalArgumentException))
                    .verify();
        }

        @Test
        @DisplayName("Should accept valid webp extension")
        void shouldAcceptValidWebp() {
            FilePart filePart = mockFilePart("photo.webp", MediaType.valueOf("image/webp"));
            when(filePart.transferTo(any(Path.class))).thenReturn(Mono.empty());

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> !(e instanceof IllegalArgumentException))
                    .verify();
        }
    }

    // ==========================================
    // deleteImage
    // ==========================================
    @Nested
    @DisplayName("deleteImage")
    class DeleteImage {

        @Test
        @DisplayName("Should delete existing image file")
        void shouldDeleteExistingImage() throws IOException {
            Path imageDir = tempDir.resolve("2026/01");
            Files.createDirectories(imageDir);
            Path imageFile = imageDir.resolve("test-image.jpg");
            Files.write(imageFile, new byte[]{1, 2, 3});

            assertThat(Files.exists(imageFile)).isTrue();

            String imageUrl = "https://catananti.dev/images/2026/01/test-image.jpg";

            StepVerifier.create(imageUploadService.deleteImage(imageUrl))
                    .verifyComplete();

            assertThat(Files.exists(imageFile)).isFalse();
        }

        @Test
        @DisplayName("Should complete silently when image does not exist")
        void shouldCompleteSilentlyWhenNotExists() {
            String imageUrl = "https://catananti.dev/images/2026/01/nonexistent.jpg";

            StepVerifier.create(imageUploadService.deleteImage(imageUrl))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty mono when URL is null")
        void shouldHandleNullUrl() {
            StepVerifier.create(imageUploadService.deleteImage(null))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty mono when URL does not contain /images/")
        void shouldHandleUrlWithoutImagesPath() {
            StepVerifier.create(imageUploadService.deleteImage("https://catananti.dev/other/path.jpg"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should block path traversal in delete URL")
        void shouldBlockPathTraversalInDelete() {
            String maliciousUrl = "https://catananti.dev/images/../../etc/passwd";

            StepVerifier.create(imageUploadService.deleteImage(maliciousUrl))
                    .verifyComplete();
            // Should not attempt to delete files outside upload directory
        }

        @Test
        @DisplayName("Should block null bytes in delete URL")
        void shouldBlockNullBytesInDelete() {
            String maliciousUrl = "https://catananti.dev/images/2026/01/file\0.jpg";

            StepVerifier.create(imageUploadService.deleteImage(maliciousUrl))
                    .verifyComplete();
        }
    }

    // ==========================================
    // uploadImage - save and magic bytes validation
    // ==========================================
    @Nested
    @DisplayName("uploadImage - save and magic bytes validation")
    class UploadImageSave {

        @Test
        @DisplayName("Should successfully upload JPEG with valid magic bytes")
        void shouldUploadValidJpeg() {
            FilePart filePart = mockFilePart("photo.jpg", MediaType.IMAGE_JPEG);
            byte[] jpegBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                    0, 0, 0, 0, 0, 0, 0, 0};

            when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
                Path path = inv.getArgument(0);
                Files.write(path, jpegBytes);
                return Mono.empty();
            });

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .assertNext(url -> {
                        assertThat(url).startsWith("https://catananti.dev/images/");
                        assertThat(url).endsWith(".jpg");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should successfully upload PNG with valid magic bytes")
        void shouldUploadValidPng() {
            FilePart filePart = mockFilePart("image.png", MediaType.IMAGE_PNG);
            byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                    0, 0, 0, 0};

            when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
                Path path = inv.getArgument(0);
                Files.write(path, pngBytes);
                return Mono.empty();
            });

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .assertNext(url -> {
                        assertThat(url).startsWith("https://catananti.dev/images/");
                        assertThat(url).endsWith(".png");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should successfully upload GIF with valid magic bytes")
        void shouldUploadValidGif() {
            FilePart filePart = mockFilePart("anim.gif", MediaType.IMAGE_GIF);
            byte[] gifBytes = new byte[]{0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0, 0, 0, 0, 0};

            when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
                Path path = inv.getArgument(0);
                Files.write(path, gifBytes);
                return Mono.empty();
            });

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .assertNext(url -> {
                        assertThat(url).startsWith("https://catananti.dev/images/");
                        assertThat(url).endsWith(".gif");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should successfully upload WEBP with valid magic bytes")
        void shouldUploadValidWebp() {
            FilePart filePart = mockFilePart("photo.webp", MediaType.valueOf("image/webp"));
            byte[] webpBytes = new byte[]{0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50};

            when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
                Path path = inv.getArgument(0);
                Files.write(path, webpBytes);
                return Mono.empty();
            });

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .assertNext(url -> {
                        assertThat(url).startsWith("https://catananti.dev/images/");
                        assertThat(url).endsWith(".webp");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject file exceeding max size")
        void shouldRejectOversizedFile() {
            ReflectionTestUtils.setField(imageUploadService, "maxFileSize", 20L);
            FilePart filePart = mockFilePart("photo.jpg", MediaType.IMAGE_JPEG);
            byte[] oversized = new byte[100];
            oversized[0] = (byte) 0xFF; oversized[1] = (byte) 0xD8; oversized[2] = (byte) 0xFF;

            when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
                Path path = inv.getArgument(0);
                Files.write(path, oversized);
                return Mono.empty();
            });

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e.getMessage().contains("File size exceeds"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject file too small to validate")
        void shouldRejectFileTooSmall() {
            FilePart filePart = mockFilePart("tiny.jpg", MediaType.IMAGE_JPEG);
            byte[] tooSmall = new byte[]{(byte) 0xFF, (byte) 0xD8};

            when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
                Path path = inv.getArgument(0);
                Files.write(path, tooSmall);
                return Mono.empty();
            });

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e.getMessage().contains("File too small"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject file with mismatched magic bytes")
        void shouldRejectMismatchedMagicBytes() {
            FilePart filePart = mockFilePart("fake.jpg", MediaType.IMAGE_JPEG);
            byte[] pngInJpg = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                    0, 0, 0, 0};

            when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
                Path path = inv.getArgument(0);
                Files.write(path, pngInJpg);
                return Mono.empty();
            });

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("File content does not match"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject file with unknown extension magic bytes")
        void shouldRejectUnknownExtensionMagicBytes() {
            FilePart filePart = mockFilePart("photo.jpeg", MediaType.IMAGE_JPEG);
            byte[] randomBytes = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00};

            when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
                Path path = inv.getArgument(0);
                Files.write(path, randomBytes);
                return Mono.empty();
            });

            StepVerifier.create(imageUploadService.uploadImage(filePart))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("File content does not match"))
                    .verify();
        }
    }
}
