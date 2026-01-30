package dev.catananti.controller;

import dev.catananti.entity.MediaAsset;
import dev.catananti.entity.User;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.MediaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.userdetails.UserDetails;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminImageControllerTest {

    @Mock
    private MediaService mediaService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminImageController controller;

    @Nested
    @DisplayName("POST /api/v1/admin/images/upload")
    class UploadImage {

        @Test
        @DisplayName("Should upload image and return URL")
        void shouldUploadImage() {
            FilePart filePart = mock(FilePart.class);
            when(filePart.filename()).thenReturn("hero-image.png");

            var userDetails = mock(UserDetails.class);
            when(userDetails.getUsername()).thenReturn("admin@test.com");

            var user = new User();
            user.setId(1L);
            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(user));

            var asset = new MediaAsset();
            asset.setUrl("https://cdn.example.com/images/hero-image.png");
            when(mediaService.upload(eq(filePart), eq("GENERAL"), isNull(), eq(1L)))
                    .thenReturn(Mono.just(asset));

            StepVerifier.create(controller.uploadImage(filePart, userDetails))
                    .assertNext(result -> {
                        assertThat(result).containsEntry("url", "https://cdn.example.com/images/hero-image.png");
                        assertThat(result).containsEntry("filename", "hero-image.png");
                        assertThat(result).containsEntry("message", "Image uploaded successfully");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/images")
    class DeleteImage {

        @Test
        @DisplayName("Should delete image by URL")
        void shouldDeleteImage() {
            String imageUrl = "https://cdn.example.com/images/hero-image.png";

            when(mediaService.deleteByUrl(imageUrl))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.deleteImage(imageUrl))
                    .verifyComplete();

            verify(mediaService).deleteByUrl(imageUrl);
        }
    }
}
