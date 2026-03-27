package dev.catananti.controller;

import dev.catananti.entity.MediaAsset;
import dev.catananti.entity.User;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.MediaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaControllerTest {

    @Mock
    private MediaService mediaService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MediaController controller;

    private MediaAsset sampleAsset;

    @BeforeEach
    void setUp() {
        sampleAsset = MediaAsset.builder()
                .id(1L)
                .originalFilename("photo.jpg")
                .storedFilename("uuid.jpg")
                .storageKey("2026/03/uuid.jpg")
                .contentType("image/jpeg")
                .fileSize(1024L)
                .purpose("GENERAL")
                .altText("A photo")
                .url("https://cdn.example.com/images/2026/03/uuid.jpg")
                .thumbnailUrl("https://cdn.example.com/images/2026/03/uuid-thumb.jpg")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("POST /api/v1/admin/media/upload")
    class UploadMedia {

        @Test
        @DisplayName("Should upload media and return response")
        void shouldUploadMedia() {
            FilePart filePart = mock(FilePart.class);
            when(filePart.filename()).thenReturn("photo.jpg");

            UserDetails userDetails = mock(UserDetails.class);
            when(userDetails.getUsername()).thenReturn("admin@test.com");

            User user = new User();
            user.setId(42L);
            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(user));
            when(mediaService.upload(eq(filePart), eq("GENERAL"), eq("A photo"), eq(42L)))
                    .thenReturn(Mono.just(sampleAsset));

            StepVerifier.create(controller.uploadMedia(filePart, "GENERAL", "A photo", userDetails))
                    .assertNext(response -> {
                        assertThat(response.id()).isEqualTo(1L);
                        assertThat(response.originalFilename()).isEqualTo("photo.jpg");
                        assertThat(response.contentType()).isEqualTo("image/jpeg");
                        assertThat(response.purpose()).isEqualTo("GENERAL");
                        assertThat(response.url()).isEqualTo("https://cdn.example.com/images/2026/03/uuid.jpg");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should upload media when user details is null")
        void shouldUploadMediaWithNullUserDetails() {
            FilePart filePart = mock(FilePart.class);
            when(filePart.filename()).thenReturn("photo.jpg");

            when(mediaService.upload(eq(filePart), eq("GENERAL"), isNull(), isNull()))
                    .thenReturn(Mono.just(sampleAsset));

            StepVerifier.create(controller.uploadMedia(filePart, "GENERAL", null, null))
                    .assertNext(response -> {
                        assertThat(response.id()).isEqualTo(1L);
                        assertThat(response.originalFilename()).isEqualTo("photo.jpg");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/media")
    class ListMedia {

        @Test
        @DisplayName("Should list media assets without purpose filter")
        void shouldListMediaWithoutFilter() {
            when(mediaService.findAll(0, 20))
                    .thenReturn(Flux.just(sampleAsset));
            when(mediaService.countAll())
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(controller.listMedia(0, 20, null))
                    .assertNext(response -> {
                        assertThat(response.items()).hasSize(1);
                        assertThat(response.totalItems()).isEqualTo(1L);
                        assertThat(response.page()).isEqualTo(0);
                        assertThat(response.size()).isEqualTo(20);
                        assertThat(response.items().getFirst().originalFilename()).isEqualTo("photo.jpg");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should list media assets filtered by purpose")
        void shouldListMediaFilteredByPurpose() {
            when(mediaService.findByPurpose("AVATAR", 0, 20))
                    .thenReturn(Flux.just(sampleAsset));
            when(mediaService.countByPurpose("AVATAR"))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(controller.listMedia(0, 20, "AVATAR"))
                    .assertNext(response -> {
                        assertThat(response.items()).hasSize(1);
                        assertThat(response.totalItems()).isEqualTo(1L);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/media/{id}")
    class GetMedia {

        @Test
        @DisplayName("Should return media asset when found")
        void shouldReturnMediaWhenFound() {
            when(mediaService.findById(1L)).thenReturn(Mono.just(sampleAsset));

            StepVerifier.create(controller.getMedia(1L))
                    .assertNext(response -> {
                        assertThat(response.id()).isEqualTo(1L);
                        assertThat(response.originalFilename()).isEqualTo("photo.jpg");
                        assertThat(response.altText()).isEqualTo("A photo");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return error when media not found")
        void shouldReturnErrorWhenNotFound() {
            when(mediaService.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(controller.getMedia(999L))
                    .expectErrorMatches(ex -> ex instanceof ResponseStatusException
                            && ((ResponseStatusException) ex).getStatusCode().value() == 404)
                    .verify();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/media/{id}")
    class DeleteMedia {

        @Test
        @DisplayName("Should delete media asset")
        void shouldDeleteMedia() {
            when(mediaService.delete(1L)).thenReturn(Mono.empty());

            StepVerifier.create(controller.deleteMedia(1L))
                    .verifyComplete();

            verify(mediaService).delete(1L);
        }
    }
}
