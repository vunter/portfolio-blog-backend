package dev.catananti.service;

import dev.catananti.entity.MediaAsset;
import dev.catananti.repository.MediaAssetRepository;
import dev.catananti.service.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock
    private StorageProvider storageProvider;

    @Mock
    private MediaAssetRepository mediaAssetRepository;

    @Mock
    private IdService idService;

    @Mock
    private ImageProcessingService imageProcessingService;

    @InjectMocks
    private MediaService mediaService;

    private MediaAsset testAsset;

    @BeforeEach
    void setUp() {
        testAsset = MediaAsset.builder()
                .id(1L)
                .originalFilename("test.jpg")
                .storedFilename("uuid.jpg")
                .storageKey("2026/03/uuid.jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .purpose("GENERAL")
                .altText("Test image")
                .url("https://cdn.example.com/images/2026/03/uuid.jpg")
                .thumbnailUrl("https://cdn.example.com/images/2026/03/uuid-thumb.jpg")
                .uploaderId(100L)
                .createdAt(LocalDateTime.now())
                .newRecord(false)
                .build();
    }

    /**
     * Helper to set the maxFileSize field via reflection.
     */
    private void setMaxFileSize(long maxSize) throws Exception {
        Field field = MediaService.class.getDeclaredField("maxFileSize");
        field.setAccessible(true);
        field.set(mediaService, maxSize);
    }

    /**
     * Helper to create a mock FilePart with given filename, content type, and byte data.
     */
    private FilePart createMockFilePart(String filename, MediaType contentType, byte[] data) {
        FilePart filePart = mock(FilePart.class);
        when(filePart.filename()).thenReturn(filename);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        when(filePart.headers()).thenReturn(headers);

        DataBuffer dataBuffer = new DefaultDataBufferFactory().wrap(data);
        when(filePart.content()).thenReturn(Flux.just(dataBuffer));

        return filePart;
    }

    /**
     * Generate valid JPEG magic bytes followed by filler to get the desired total size.
     */
    private byte[] createJpegBytes(int totalSize) {
        byte[] data = new byte[Math.max(totalSize, 12)];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xD8;
        data[2] = (byte) 0xFF;
        return data;
    }

    /**
     * Generate valid PNG magic bytes.
     */
    private byte[] createPngBytes(int totalSize) {
        byte[] data = new byte[Math.max(totalSize, 12)];
        data[0] = (byte) 0x89;
        data[1] = 0x50;
        data[2] = 0x4E;
        data[3] = 0x47;
        data[4] = 0x0D;
        data[5] = 0x0A;
        data[6] = 0x1A;
        data[7] = 0x0A;
        return data;
    }

    /**
     * Generate valid GIF magic bytes.
     */
    private byte[] createGifBytes(int totalSize) {
        byte[] data = new byte[Math.max(totalSize, 12)];
        data[0] = 0x47;  // G
        data[1] = 0x49;  // I
        data[2] = 0x46;  // F
        data[3] = 0x38;  // 8
        return data;
    }

    /**
     * Generate valid WebP magic bytes.
     */
    private byte[] createWebpBytes(int totalSize) {
        byte[] data = new byte[Math.max(totalSize, 12)];
        data[0] = 0x52;  // R
        data[1] = 0x49;  // I
        data[2] = 0x46;  // F
        data[3] = 0x46;  // F
        data[8] = 0x57;  // W
        data[9] = 0x45;  // E
        data[10] = 0x42; // B
        data[11] = 0x50; // P
        return data;
    }

    // ==================== findById ====================

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("Should return media asset by ID")
        void findById_ShouldReturnAsset() {
            when(mediaAssetRepository.findById(1L)).thenReturn(Mono.just(testAsset));

            StepVerifier.create(mediaService.findById(1L))
                    .assertNext(asset -> {
                        assertThat(asset.getId()).isEqualTo(1L);
                        assertThat(asset.getOriginalFilename()).isEqualTo("test.jpg");
                        assertThat(asset.isNewRecord()).isFalse();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty Mono when asset not found")
        void findById_ShouldReturnEmpty_WhenNotFound() {
            when(mediaAssetRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(mediaService.findById(999L))
                    .verifyComplete();
        }
    }

    // ==================== findAll ====================

    @Nested
    @DisplayName("findAll")
    class FindAll {

        @Test
        @DisplayName("Should return paginated media assets")
        void findAll_ShouldReturnPaginatedAssets() {
            when(mediaAssetRepository.findAllPaginated(10, 0))
                    .thenReturn(Flux.just(testAsset));

            StepVerifier.create(mediaService.findAll(0, 10).collectList())
                    .assertNext(list -> {
                        assertThat(list).hasSize(1);
                        assertThat(list.getFirst().getId()).isEqualTo(1L);
                        assertThat(list.getFirst().isNewRecord()).isFalse();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should calculate correct offset for page 2")
        void findAll_ShouldCalculateCorrectOffset() {
            when(mediaAssetRepository.findAllPaginated(10, 20))
                    .thenReturn(Flux.empty());

            StepVerifier.create(mediaService.findAll(2, 10).collectList())
                    .assertNext(list -> assertThat(list).isEmpty())
                    .verifyComplete();

            verify(mediaAssetRepository).findAllPaginated(10, 20);
        }

        @Test
        @DisplayName("Should return empty flux when no assets exist")
        void findAll_ShouldReturnEmpty_WhenNoAssets() {
            when(mediaAssetRepository.findAllPaginated(10, 0))
                    .thenReturn(Flux.empty());

            StepVerifier.create(mediaService.findAll(0, 10).collectList())
                    .assertNext(list -> assertThat(list).isEmpty())
                    .verifyComplete();
        }
    }

    // ==================== findByPurpose ====================

    @Nested
    @DisplayName("findByPurpose")
    class FindByPurpose {

        @Test
        @DisplayName("Should return assets filtered by purpose")
        void findByPurpose_ShouldReturnFilteredAssets() {
            when(mediaAssetRepository.findByPurposePaginated("AVATAR", 10, 0))
                    .thenReturn(Flux.just(testAsset));

            StepVerifier.create(mediaService.findByPurpose("avatar", 0, 10).collectList())
                    .assertNext(list -> {
                        assertThat(list).hasSize(1);
                        assertThat(list.getFirst().isNewRecord()).isFalse();
                    })
                    .verifyComplete();

            verify(mediaAssetRepository).findByPurposePaginated("AVATAR", 10, 0);
        }

        @Test
        @DisplayName("Should uppercase the purpose parameter")
        void findByPurpose_ShouldUppercasePurpose() {
            when(mediaAssetRepository.findByPurposePaginated("BLOG_COVER", 5, 10))
                    .thenReturn(Flux.empty());

            StepVerifier.create(mediaService.findByPurpose("blog_cover", 2, 5).collectList())
                    .assertNext(list -> assertThat(list).isEmpty())
                    .verifyComplete();

            verify(mediaAssetRepository).findByPurposePaginated("BLOG_COVER", 5, 10);
        }
    }

    // ==================== findByUploader ====================

    @Nested
    @DisplayName("findByUploader")
    class FindByUploader {

        @Test
        @DisplayName("Should return assets by uploader ID with pagination")
        void findByUploader_ShouldReturnAssets() {
            when(mediaAssetRepository.findByUploaderIdPaginated(100L, 10, 0))
                    .thenReturn(Flux.just(testAsset));

            StepVerifier.create(mediaService.findByUploader(100L, 0, 10).collectList())
                    .assertNext(list -> {
                        assertThat(list).hasSize(1);
                        assertThat(list.getFirst().getUploaderId()).isEqualTo(100L);
                    })
                    .verifyComplete();
        }
    }

    // ==================== countAll ====================

    @Nested
    @DisplayName("countAll")
    class CountAll {

        @Test
        @DisplayName("Should return total count of media assets")
        void countAll_ShouldReturnCount() {
            when(mediaAssetRepository.countAll()).thenReturn(Mono.just(42L));

            StepVerifier.create(mediaService.countAll())
                    .assertNext(count -> assertThat(count).isEqualTo(42L))
                    .verifyComplete();
        }
    }

    // ==================== countByPurpose ====================

    @Nested
    @DisplayName("countByPurpose")
    class CountByPurpose {

        @Test
        @DisplayName("Should return count by purpose uppercased")
        void countByPurpose_ShouldReturnCount() {
            when(mediaAssetRepository.countByPurpose("AVATAR")).thenReturn(Mono.just(5L));

            StepVerifier.create(mediaService.countByPurpose("avatar"))
                    .assertNext(count -> assertThat(count).isEqualTo(5L))
                    .verifyComplete();

            verify(mediaAssetRepository).countByPurpose("AVATAR");
        }
    }

    // ==================== delete ====================

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("Should delete asset and its storage files")
        void delete_ShouldDeleteAssetAndFiles() {
            when(mediaAssetRepository.findById(1L)).thenReturn(Mono.just(testAsset));
            when(storageProvider.delete("2026/03/uuid.jpg")).thenReturn(Mono.empty());
            when(storageProvider.delete("2026/03/uuid-thumb.jpg")).thenReturn(Mono.empty());
            when(mediaAssetRepository.delete(testAsset)).thenReturn(Mono.empty());

            StepVerifier.create(mediaService.delete(1L))
                    .verifyComplete();

            verify(storageProvider).delete("2026/03/uuid.jpg");
            verify(storageProvider).delete("2026/03/uuid-thumb.jpg");
            verify(mediaAssetRepository).delete(testAsset);
        }

        @Test
        @DisplayName("Should delete asset without thumbnail when thumbnailUrl is null")
        void delete_ShouldDeleteAsset_WithoutThumbnail() {
            testAsset.setThumbnailUrl(null);
            when(mediaAssetRepository.findById(1L)).thenReturn(Mono.just(testAsset));
            when(storageProvider.delete("2026/03/uuid.jpg")).thenReturn(Mono.empty());
            when(mediaAssetRepository.delete(testAsset)).thenReturn(Mono.empty());

            StepVerifier.create(mediaService.delete(1L))
                    .verifyComplete();

            verify(storageProvider).delete("2026/03/uuid.jpg");
            verify(storageProvider, never()).delete("2026/03/uuid-thumb.jpg");
            verify(mediaAssetRepository).delete(testAsset);
        }

        @Test
        @DisplayName("Should delete asset without thumbnail when thumbnailUrl is empty")
        void delete_ShouldDeleteAsset_WhenThumbnailUrlIsEmpty() {
            testAsset.setThumbnailUrl("");
            when(mediaAssetRepository.findById(1L)).thenReturn(Mono.just(testAsset));
            when(storageProvider.delete("2026/03/uuid.jpg")).thenReturn(Mono.empty());
            when(mediaAssetRepository.delete(testAsset)).thenReturn(Mono.empty());

            StepVerifier.create(mediaService.delete(1L))
                    .verifyComplete();

            verify(storageProvider, times(1)).delete(anyString());
        }

        @Test
        @DisplayName("Should throw ResponseStatusException when asset not found for delete")
        void delete_ShouldThrow_WhenAssetNotFound() {
            when(mediaAssetRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(mediaService.delete(999L))
                    .expectErrorMatches(ex ->
                            ex instanceof ResponseStatusException rse
                                    && rse.getStatusCode().value() == 404)
                    .verify();
        }

        @Test
        @DisplayName("Should still delete DB record even if thumbnail storage delete fails")
        void delete_ShouldContinue_WhenThumbnailDeleteFails() {
            when(mediaAssetRepository.findById(1L)).thenReturn(Mono.just(testAsset));
            when(storageProvider.delete("2026/03/uuid.jpg")).thenReturn(Mono.empty());
            when(storageProvider.delete("2026/03/uuid-thumb.jpg"))
                    .thenReturn(Mono.error(new RuntimeException("S3 error")));
            when(mediaAssetRepository.delete(testAsset)).thenReturn(Mono.empty());

            // The thumb delete has onErrorResume so it should still complete
            StepVerifier.create(mediaService.delete(1L))
                    .verifyComplete();

            verify(mediaAssetRepository).delete(testAsset);
        }
    }

    // ==================== deleteByUrl ====================

    @Nested
    @DisplayName("deleteByUrl")
    class DeleteByUrl {

        @Test
        @DisplayName("Should delete tracked asset by URL")
        void deleteByUrl_ShouldDeleteTrackedAsset() {
            String url = "https://cdn.example.com/images/2026/03/uuid.jpg";
            when(mediaAssetRepository.findByUrl(url)).thenReturn(Mono.just(testAsset));
            when(mediaAssetRepository.findById(1L)).thenReturn(Mono.just(testAsset));
            when(storageProvider.delete("2026/03/uuid.jpg")).thenReturn(Mono.empty());
            when(storageProvider.delete("2026/03/uuid-thumb.jpg")).thenReturn(Mono.empty());
            when(mediaAssetRepository.delete(testAsset)).thenReturn(Mono.empty());

            StepVerifier.create(mediaService.deleteByUrl(url))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should fall back to untracked delete when asset not in DB")
        void deleteByUrl_ShouldFallbackToUntrackedDelete() {
            String url = "https://cdn.example.com/images/2025/06/old-file.jpg";
            when(mediaAssetRepository.findByUrl(url)).thenReturn(Mono.empty());
            when(storageProvider.delete("2025/06/old-file.jpg")).thenReturn(Mono.empty());

            StepVerifier.create(mediaService.deleteByUrl(url))
                    .verifyComplete();

            verify(storageProvider).delete("2025/06/old-file.jpg");
        }

        @Test
        @DisplayName("Should return empty when URL has no /images/ segment and not in DB")
        void deleteByUrl_ShouldReturnEmpty_WhenUrlHasNoImagesSegment() {
            String url = "https://cdn.example.com/other/path/file.jpg";
            when(mediaAssetRepository.findByUrl(url)).thenReturn(Mono.empty());

            StepVerifier.create(mediaService.deleteByUrl(url))
                    .verifyComplete();

            verifyNoInteractions(storageProvider);
        }

        @Test
        @DisplayName("Should return empty when URL is null and not in DB")
        void deleteByUrl_ShouldReturnEmpty_WhenUrlIsNull() {
            when(mediaAssetRepository.findByUrl(null)).thenReturn(Mono.empty());

            StepVerifier.create(mediaService.deleteByUrl(null))
                    .verifyComplete();
        }
    }

    // ==================== upload ====================

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("Should reject file with invalid extension")
        void upload_ShouldReject_InvalidExtension() {
            FilePart filePart = mock(FilePart.class);
            when(filePart.filename()).thenReturn("malware.exe");

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .expectErrorMatches(ex ->
                            ex instanceof ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400
                                    && rse.getReason().contains("extension"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject file with invalid content type")
        void upload_ShouldReject_InvalidContentType() {
            FilePart filePart = mock(FilePart.class);
            when(filePart.filename()).thenReturn("doc.jpg");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            when(filePart.headers()).thenReturn(headers);

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .expectErrorMatches(ex ->
                            ex instanceof ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400
                                    && rse.getReason().contains("content type"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject file with path traversal in filename")
        void upload_ShouldReject_PathTraversalFilename() {
            FilePart filePart = mock(FilePart.class);
            when(filePart.filename()).thenReturn("../../../etc/passwd");

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .expectErrorMatches(ex ->
                            ex instanceof ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400)
                    .verify();
        }

        @Test
        @DisplayName("Should reject file with backslash in filename")
        void upload_ShouldReject_BackslashFilename() {
            FilePart filePart = mock(FilePart.class);
            when(filePart.filename()).thenReturn("path\\file.jpg");

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .expectErrorMatches(ex ->
                            ex instanceof ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400)
                    .verify();
        }

        @Test
        @DisplayName("Should reject file with null byte in filename")
        void upload_ShouldReject_NullByteFilename() {
            FilePart filePart = mock(FilePart.class);
            when(filePart.filename()).thenReturn("file\0.jpg");

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .expectErrorMatches(ex ->
                            ex instanceof ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400)
                    .verify();
        }

        @Test
        @DisplayName("Should reject file with blank filename")
        void upload_ShouldReject_BlankFilename() {
            FilePart filePart = mock(FilePart.class);
            when(filePart.filename()).thenReturn("   ");

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .expectErrorMatches(ex ->
                            ex instanceof ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400)
                    .verify();
        }

        @Test
        @DisplayName("Should reject file exceeding max size")
        void upload_ShouldReject_FileExceedingMaxSize() throws Exception {
            setMaxFileSize(100); // 100 bytes max

            byte[] jpegBytes = createJpegBytes(200); // 200 bytes, exceeds limit
            FilePart filePart = createMockFilePart("photo.jpg", MediaType.IMAGE_JPEG, jpegBytes);

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .expectErrorMatches(ex ->
                            ex instanceof ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400
                                    && rse.getReason().contains("size"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject file with mismatched magic bytes")
        void upload_ShouldReject_MismatchedMagicBytes() throws Exception {
            setMaxFileSize(10_485_760L);

            // PNG header but declared as JPEG
            byte[] pngBytes = createPngBytes(100);
            FilePart filePart = createMockFilePart("photo.jpg", MediaType.IMAGE_JPEG, pngBytes);

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .expectErrorMatches(ex ->
                            ex instanceof ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400
                                    && rse.getReason().contains("content does not match"))
                    .verify();
        }

        @Test
        @DisplayName("Should successfully upload a valid JPEG file")
        void upload_ShouldSucceed_ForValidJpeg() throws Exception {
            setMaxFileSize(10_485_760L);

            byte[] jpegBytes = createJpegBytes(1024);
            FilePart filePart = createMockFilePart("photo.jpg", MediaType.IMAGE_JPEG, jpegBytes);

            when(idService.nextId()).thenReturn(12345L);

            // GIF content type triggers the non-image path, JPEG triggers image processing
            ImageProcessingService.ImageVariant originalVariant =
                    new ImageProcessingService.ImageVariant("", jpegBytes, 800, 600);
            when(imageProcessingService.processImage(any(byte[].class), eq("image/jpeg")))
                    .thenReturn(Mono.just(Map.of("", originalVariant)));

            when(storageProvider.store(anyString(), any(byte[].class), eq("image/jpeg")))
                    .thenReturn(Mono.just("https://cdn.example.com/images/2026/03/uuid.jpg"));

            when(mediaAssetRepository.save(any(MediaAsset.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(mediaService.upload(filePart, "BLOG_COVER", "A nice photo", 100L))
                    .assertNext(asset -> {
                        assertThat(asset.getOriginalFilename()).isEqualTo("photo.jpg");
                        assertThat(asset.getContentType()).isEqualTo("image/jpeg");
                        assertThat(asset.getPurpose()).isEqualTo("BLOG_COVER");
                        assertThat(asset.getAltText()).isEqualTo("A nice photo");
                        assertThat(asset.getUploaderId()).isEqualTo(100L);
                        assertThat(asset.getUrl()).isEqualTo("https://cdn.example.com/images/2026/03/uuid.jpg");
                        assertThat(asset.isNewRecord()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should upload GIF without image processing")
        void upload_ShouldSkipProcessing_ForGif() throws Exception {
            setMaxFileSize(10_485_760L);

            byte[] gifBytes = createGifBytes(100);
            FilePart filePart = createMockFilePart("anim.gif", MediaType.IMAGE_GIF, gifBytes);

            when(idService.nextId()).thenReturn(12345L);
            when(storageProvider.store(anyString(), any(byte[].class), eq("image/gif")))
                    .thenReturn(Mono.just("https://cdn.example.com/images/2026/03/uuid.gif"));
            when(mediaAssetRepository.save(any(MediaAsset.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .assertNext(asset -> {
                        assertThat(asset.getContentType()).isEqualTo("image/gif");
                        assertThat(asset.getUrl()).isNotNull();
                    })
                    .verifyComplete();

            // GIF should not trigger image processing
            verifyNoInteractions(imageProcessingService);
        }

        @Test
        @DisplayName("Should default purpose to GENERAL when purpose is null")
        void upload_ShouldDefaultPurpose_WhenNull() throws Exception {
            setMaxFileSize(10_485_760L);

            byte[] jpegBytes = createJpegBytes(100);
            FilePart filePart = createMockFilePart("pic.jpg", MediaType.IMAGE_JPEG, jpegBytes);

            when(idService.nextId()).thenReturn(99L);
            when(imageProcessingService.processImage(any(byte[].class), eq("image/jpeg")))
                    .thenReturn(Mono.just(Map.of("",
                            new ImageProcessingService.ImageVariant("", jpegBytes, 100, 100))));
            when(storageProvider.store(anyString(), any(byte[].class), eq("image/jpeg")))
                    .thenReturn(Mono.just("https://cdn.example.com/pic.jpg"));
            when(mediaAssetRepository.save(any(MediaAsset.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(mediaService.upload(filePart, null, null, 1L))
                    .assertNext(asset -> assertThat(asset.getPurpose()).isEqualTo("GENERAL"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should store thumbnail variant when processing produces one")
        void upload_ShouldStoreThumbnail_WhenGenerated() throws Exception {
            setMaxFileSize(10_485_760L);

            byte[] jpegBytes = createJpegBytes(1024);
            FilePart filePart = createMockFilePart("large.jpg", MediaType.IMAGE_JPEG, jpegBytes);
            byte[] thumbBytes = createJpegBytes(200);

            when(idService.nextId()).thenReturn(42L);
            when(imageProcessingService.processImage(any(byte[].class), eq("image/jpeg")))
                    .thenReturn(Mono.just(Map.of(
                            "", new ImageProcessingService.ImageVariant("", jpegBytes, 1600, 1200),
                            "-thumb", new ImageProcessingService.ImageVariant("-thumb", thumbBytes, 150, 112)
                    )));
            when(storageProvider.store(matches(".*\\.jpg"), any(byte[].class), eq("image/jpeg")))
                    .thenReturn(Mono.just("https://cdn.example.com/images/stored.jpg"));
            when(mediaAssetRepository.save(any(MediaAsset.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(mediaService.upload(filePart, "BLOG_CONTENT", null, 1L))
                    .assertNext(asset -> {
                        assertThat(asset.getUrl()).isNotNull();
                        assertThat(asset.getThumbnailUrl()).isNotNull();
                    })
                    .verifyComplete();

            // Should have stored both original and thumbnail
            verify(storageProvider, times(2)).store(anyString(), any(byte[].class), eq("image/jpeg"));
        }

        @Test
        @DisplayName("Should accept valid PNG file")
        void upload_ShouldSucceed_ForValidPng() throws Exception {
            setMaxFileSize(10_485_760L);

            byte[] pngBytes = createPngBytes(512);
            FilePart filePart = createMockFilePart("screenshot.png", MediaType.IMAGE_PNG, pngBytes);

            when(idService.nextId()).thenReturn(55L);
            when(imageProcessingService.processImage(any(byte[].class), eq("image/png")))
                    .thenReturn(Mono.just(Map.of("",
                            new ImageProcessingService.ImageVariant("", pngBytes, 400, 300))));
            when(storageProvider.store(anyString(), any(byte[].class), eq("image/png")))
                    .thenReturn(Mono.just("https://cdn.example.com/screenshot.png"));
            when(mediaAssetRepository.save(any(MediaAsset.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", "A screenshot", 1L))
                    .assertNext(asset -> {
                        assertThat(asset.getContentType()).isEqualTo("image/png");
                        assertThat(asset.getStoredFilename()).endsWith(".png");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should accept valid WebP file")
        void upload_ShouldSucceed_ForValidWebp() throws Exception {
            setMaxFileSize(10_485_760L);

            byte[] webpBytes = createWebpBytes(256);
            FilePart filePart = createMockFilePart("image.webp", MediaType.valueOf("image/webp"), webpBytes);

            when(idService.nextId()).thenReturn(77L);
            when(imageProcessingService.processImage(any(byte[].class), eq("image/webp")))
                    .thenReturn(Mono.just(Map.of("",
                            new ImageProcessingService.ImageVariant("", webpBytes, 200, 200))));
            when(storageProvider.store(anyString(), any(byte[].class), eq("image/webp")))
                    .thenReturn(Mono.just("https://cdn.example.com/image.webp"));
            when(mediaAssetRepository.save(any(MediaAsset.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .assertNext(asset -> assertThat(asset.getContentType()).isEqualTo("image/webp"))
                    .verifyComplete();
        }
    }

    // ==================== uploadAndReturnUrl ====================

    @Nested
    @DisplayName("uploadAndReturnUrl")
    class UploadAndReturnUrl {

        @Test
        @DisplayName("Should return URL from uploaded asset")
        void uploadAndReturnUrl_ShouldReturnUrl() throws Exception {
            setMaxFileSize(10_485_760L);

            byte[] jpegBytes = createJpegBytes(100);
            FilePart filePart = createMockFilePart("test.jpg", MediaType.IMAGE_JPEG, jpegBytes);

            when(idService.nextId()).thenReturn(1L);
            when(imageProcessingService.processImage(any(byte[].class), eq("image/jpeg")))
                    .thenReturn(Mono.just(Map.of("",
                            new ImageProcessingService.ImageVariant("", jpegBytes, 100, 100))));
            when(storageProvider.store(anyString(), any(byte[].class), eq("image/jpeg")))
                    .thenReturn(Mono.just("https://cdn.example.com/uploaded.jpg"));
            when(mediaAssetRepository.save(any(MediaAsset.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(mediaService.uploadAndReturnUrl(filePart, 1L))
                    .assertNext(url -> assertThat(url).isEqualTo("https://cdn.example.com/uploaded.jpg"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should propagate validation errors")
        void uploadAndReturnUrl_ShouldPropagateValidationErrors() {
            FilePart filePart = mock(FilePart.class);
            when(filePart.filename()).thenReturn("script.js");

            StepVerifier.create(mediaService.uploadAndReturnUrl(filePart, 1L))
                    .expectError(ResponseStatusException.class)
                    .verify();
        }
    }

    // ==================== File validation edge cases ====================

    @Nested
    @DisplayName("File validation edge cases")
    class FileValidationEdgeCases {

        @Test
        @DisplayName("Should reject file with forward slash in filename")
        void upload_ShouldReject_ForwardSlashFilename() {
            FilePart filePart = mock(FilePart.class);
            when(filePart.filename()).thenReturn("path/file.jpg");

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .expectErrorMatches(ex ->
                            ex instanceof ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400)
                    .verify();
        }

        @Test
        @DisplayName("Should reject file with no content type header")
        void upload_ShouldReject_NullContentType() {
            FilePart filePart = mock(FilePart.class);
            when(filePart.filename()).thenReturn("photo.jpg");
            HttpHeaders headers = new HttpHeaders();
            // No content type set
            when(filePart.headers()).thenReturn(headers);

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .expectErrorMatches(ex ->
                            ex instanceof ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400
                                    && rse.getReason().contains("content type"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject file with too-small magic bytes (less than 12 bytes)")
        void upload_ShouldReject_TooSmallFile() throws Exception {
            setMaxFileSize(10_485_760L);

            byte[] tinyData = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}; // Only 3 bytes
            FilePart filePart = createMockFilePart("tiny.jpg", MediaType.IMAGE_JPEG, tinyData);

            StepVerifier.create(mediaService.upload(filePart, "GENERAL", null, 1L))
                    .expectErrorMatches(ex ->
                            ex instanceof ResponseStatusException rse
                                    && rse.getStatusCode().value() == 400
                                    && rse.getReason().contains("content does not match"))
                    .verify();
        }
    }
}
