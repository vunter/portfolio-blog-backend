package dev.catananti.controller;

import dev.catananti.dto.ContactResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.ContactService;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminContactControllerTest {

    @Mock
    private ContactService contactService;

    @InjectMocks
    private AdminContactController controller;

    private ContactResponse unreadMessage;
    private ContactResponse readMessage;

    @BeforeEach
    void setUp() {
        unreadMessage = ContactResponse.builder()
                .id("c001")
                .name("John Doe")
                .email("john@example.com")
                .subject("Question about portfolio")
                .message("I'd like to know more about your work.")
                .read(false)
                .createdAt(LocalDateTime.now().minusHours(3))
                .build();

        readMessage = ContactResponse.builder()
                .id("c002")
                .name("Jane Smith")
                .email("jane@example.com")
                .subject("Collaboration proposal")
                .message("Let's work together!")
                .read(true)
                .createdAt(LocalDateTime.now().minusDays(1))
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/admin/contact/messages")
    class GetAllMessages {

        @Test
        @DisplayName("Should return paginated messages")
        void shouldReturnPaginatedMessages() {
            PageResponse<ContactResponse> page = PageResponse.<ContactResponse>builder()
                    .content(List.of(unreadMessage, readMessage))
                    .page(0)
                    .size(20)
                    .totalElements(2)
                    .totalPages(1)
                    .first(true)
                    .last(true)
                    .build();

            when(contactService.getAllContactsPaginated(0, 20))
                    .thenReturn(Mono.just(page));

            StepVerifier.create(controller.getAllMessages(0, 20))
                    .assertNext(result -> {
                        assertThat(result.getContent()).hasSize(2);
                        assertThat(result.getTotalElements()).isEqualTo(2);
                        assertThat(result.getContent().getFirst().getName()).isEqualTo("John Doe");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/contact/messages/{id}")
    class GetMessage {

        @Test
        @DisplayName("Should return message by id")
        void shouldReturnMessageById() {
            when(contactService.getContactById("c001"))
                    .thenReturn(Mono.just(unreadMessage));

            StepVerifier.create(controller.getMessage("c001"))
                    .assertNext(result -> {
                        assertThat(result.getId()).isEqualTo("c001");
                        assertThat(result.getSubject()).isEqualTo("Question about portfolio");
                        assertThat(result.isRead()).isFalse();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/contact/messages/{id}/read")
    class MarkAsRead {

        @Test
        @DisplayName("Should mark message as read")
        void shouldMarkAsRead() {
            ContactResponse marked = ContactResponse.builder()
                    .id("c001")
                    .name("John Doe")
                    .email("john@example.com")
                    .subject("Question about portfolio")
                    .message("I'd like to know more about your work.")
                    .read(true)
                    .createdAt(unreadMessage.getCreatedAt())
                    .build();

            when(contactService.markAsRead("c001"))
                    .thenReturn(Mono.just(marked));

            StepVerifier.create(controller.markAsRead("c001"))
                    .assertNext(result -> {
                        assertThat(result.getId()).isEqualTo("c001");
                        assertThat(result.isRead()).isTrue();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/contact/messages/{id}")
    class DeleteMessage {

        @Test
        @DisplayName("Should delete message")
        void shouldDeleteMessage() {
            when(contactService.deleteContact("c001"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.deleteMessage("c001"))
                    .verifyComplete();

            verify(contactService).deleteContact("c001");
        }
    }
}
