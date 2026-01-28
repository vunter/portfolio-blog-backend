package dev.catananti.controller;

import dev.catananti.dto.ContactRequest;
import dev.catananti.dto.ContactResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.ContactService;
import dev.catananti.service.RecaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ContactController using Mockito.
 */
@ExtendWith(MockitoExtension.class)
class ContactControllerTest {

    @Mock
    private ContactService contactService;

    @Mock
    private RecaptchaService recaptchaService;

    @InjectMocks
    private ContactController contactController;

    @InjectMocks
    private AdminContactController adminContactController;

    private ContactResponse testContact;

    @BeforeEach
    void setUp() {
        lenient().when(recaptchaService.verify(any(), any())).thenReturn(Mono.empty());
        testContact = ContactResponse.builder()
                .id("1")
                .name("John Doe")
                .email("john@example.com")
                .message("Hello, I'd like to discuss a project")
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("POST /api/v1/contact")
    class SendMessage {

        @Test
        @DisplayName("Should return contact response when sending valid message")
        void shouldReturnContactResponse_WhenMessageValid() {
            // Given
            ContactRequest request = ContactRequest.builder()
                    .name("John Doe")
                    .email("john@example.com")
                    .subject("Project Inquiry")
                    .message("Hello, project inquiry")
                    .build();

            when(contactService.saveContact(any(ContactRequest.class)))
                    .thenReturn(Mono.just(testContact));

            // When & Then
            StepVerifier.create(contactController.sendMessage(request))
                    .assertNext(response -> {
                        assertThat(response.getName()).isEqualTo("John Doe");
                        assertThat(response.getEmail()).isEqualTo("john@example.com");
                        assertThat(response.getMessage()).isEqualTo("Hello, I'd like to discuss a project");
                    })
                    .verifyComplete();

            verify(contactService).saveContact(any(ContactRequest.class));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/contact/admin/messages")
    class GetAllMessages {

        @Test
        @DisplayName("Should return paginated messages")
        void shouldReturnPaginatedMessages() {
            // Given
            PageResponse<ContactResponse> page = PageResponse.<ContactResponse>builder()
                    .content(List.of(testContact))
                    .page(0)
                    .size(20)
                    .totalElements(1)
                    .totalPages(1)
                    .first(true)
                    .last(true)
                    .build();

            when(contactService.getAllContactsPaginated(0, 20))
                    .thenReturn(Mono.just(page));

            // When & Then
            StepVerifier.create(adminContactController.getAllMessages(0, 20))
                    .assertNext(response -> {
                        assertThat(response.getContent()).hasSize(1);
                        assertThat(response.getTotalElements()).isEqualTo(1);
                        assertThat(response.getPage()).isEqualTo(0);
                    })
                    .verifyComplete();

            verify(contactService).getAllContactsPaginated(0, 20);
        }

        @Test
        @DisplayName("Should accept pagination parameters")
        void shouldAcceptPaginationParams() {
            // Given
            PageResponse<ContactResponse> page = PageResponse.<ContactResponse>builder()
                    .content(List.of())
                    .page(2)
                    .size(10)
                    .totalElements(0)
                    .totalPages(0)
                    .first(false)
                    .last(true)
                    .build();

            when(contactService.getAllContactsPaginated(2, 10))
                    .thenReturn(Mono.just(page));

            // When & Then
            StepVerifier.create(adminContactController.getAllMessages(2, 10))
                    .assertNext(response -> {
                        assertThat(response.getPage()).isEqualTo(2);
                        assertThat(response.getSize()).isEqualTo(10);
                        assertThat(response.getContent()).isEmpty();
                    })
                    .verifyComplete();

            verify(contactService).getAllContactsPaginated(2, 10);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/contact/admin/messages/{id}")
    class GetMessage {

        @Test
        @DisplayName("Should return message by id")
        void shouldReturnMessageById() {
            // Given
            when(contactService.getContactById("1"))
                    .thenReturn(Mono.just(testContact));

            // When & Then
            StepVerifier.create(adminContactController.getMessage("1"))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo("1");
                        assertThat(response.getName()).isEqualTo("John Doe");
                    })
                    .verifyComplete();

            verify(contactService).getContactById("1");
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/contact/admin/messages/{id}/read")
    class MarkAsRead {

        @Test
        @DisplayName("Should mark message as read")
        void shouldMarkAsRead() {
            // Given
            ContactResponse readContact = ContactResponse.builder()
                    .id("1")
                    .name("John Doe")
                    .email("john@example.com")
                    .message("Hello")
                    .read(true)
                    .createdAt(LocalDateTime.now())
                    .build();

            when(contactService.markAsRead("1")).thenReturn(Mono.just(readContact));

            // When & Then
            StepVerifier.create(adminContactController.markAsRead("1"))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo("1");
                        assertThat(response.isRead()).isTrue();
                    })
                    .verifyComplete();

            verify(contactService).markAsRead("1");
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/contact/admin/messages/{id}")
    class DeleteMessage {

        @Test
        @DisplayName("Should complete on delete")
        void shouldComplete_OnDelete() {
            // Given
            when(contactService.deleteContact("1")).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(adminContactController.deleteMessage("1"))
                    .verifyComplete();

            verify(contactService).deleteContact("1");
        }
    }
}
