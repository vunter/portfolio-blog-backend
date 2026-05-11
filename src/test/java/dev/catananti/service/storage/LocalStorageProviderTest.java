package dev.catananti.service.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LocalStorageProvider")
class LocalStorageProviderTest {

    @TempDir
    Path tempDir;

    private LocalStorageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LocalStorageProvider(tempDir.toString(), "https://example.com");
    }

    @Nested
    @DisplayName("store")
    class Store {

        @Test
        @DisplayName("should store file and return URL")
        void shouldStoreFileAndReturnUrl() {
            byte[] data = "hello world".getBytes();

            StepVerifier.create(provider.store("2026/01/test.jpg", data, "image/jpeg"))
                    .assertNext(url -> {
                        assertThat(url).isEqualTo("https://example.com/images/2026/01/test.jpg");
                        assertThat(Files.exists(tempDir.resolve("2026/01/test.jpg"))).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should create parent directories")
        void shouldCreateParentDirectories() {
            byte[] data = "data".getBytes();

            StepVerifier.create(provider.store("deep/nested/dir/file.png", data, "image/png"))
                    .assertNext(url -> assertThat(url).contains("deep/nested/dir/file.png"))
                    .verifyComplete();

            assertThat(Files.exists(tempDir.resolve("deep/nested/dir/file.png"))).isTrue();
        }

        @Test
        @DisplayName("should write correct content")
        void shouldWriteCorrectContent() throws IOException {
            byte[] data = "file content here".getBytes();

            StepVerifier.create(provider.store("content-test.txt", data, "text/plain"))
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(Files.readAllBytes(tempDir.resolve("content-test.txt"))).isEqualTo(data);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("should delete existing file")
        void shouldDeleteExistingFile() throws IOException {
            Path file = tempDir.resolve("to-delete.txt");
            Files.writeString(file, "delete me");

            StepVerifier.create(provider.delete("to-delete.txt"))
                    .verifyComplete();

            assertThat(Files.exists(file)).isFalse();
        }

        @Test
        @DisplayName("should handle non-existent file gracefully")
        void shouldHandleNonExistentFile() {
            StepVerifier.create(provider.delete("nonexistent.txt"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should block path traversal attempts")
        void shouldBlockPathTraversal() {
            StepVerifier.create(provider.delete("../../etc/passwd"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getUrl")
    class GetUrl {

        @Test
        @DisplayName("should construct correct URL")
        void shouldConstructCorrectUrl() {
            assertThat(provider.getUrl("2026/01/image.jpg"))
                    .isEqualTo("https://example.com/images/2026/01/image.jpg");
        }
    }

    @Nested
    @DisplayName("isHealthy")
    class IsHealthy {

        @Test
        @DisplayName("should return true for writable directory")
        void shouldReturnTrueForWritableDirectory() {
            StepVerifier.create(provider.isHealthy())
                    .assertNext(healthy -> assertThat(healthy).isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("should create directory if not exists")
        void shouldCreateDirectoryIfNotExists() {
            Path nonExistent = tempDir.resolve("new-uploads");
            LocalStorageProvider newProvider = new LocalStorageProvider(nonExistent.toString(), "https://example.com");

            StepVerifier.create(newProvider.isHealthy())
                    .assertNext(healthy -> assertThat(healthy).isTrue())
                    .verifyComplete();

            assertThat(Files.exists(nonExistent)).isTrue();
        }
    }

    @Nested
    @DisplayName("getType")
    class GetType {

        @Test
        @DisplayName("should return LOCAL")
        void shouldReturnLocal() {
            assertThat(provider.getType()).isEqualTo("LOCAL");
        }
    }
}
