package dev.catananti.service;

import dev.catananti.dto.ContactRequest;
import dev.catananti.dto.ContactResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Contact;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ContactRepository;
import dev.catananti.service.HtmlSanitizerService;
import dev.catananti.service.IdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    @Mock private ContactRepository contactRepository;
    @Mock private EmailService emailService;
    @Mock private HtmlSanitizerService htmlSanitizerService;
    @Mock private IdService idService;
    @Mock private NotificationEventService notificationEventService;

    private ContactService contactService;

    private Contact testContact;
    private String publicId;

    @BeforeEach
    void setUp() {
        lenient().when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));

        contactService = new ContactService(contactRepository, emailService, htmlSanitizerService, idService, notificationEventService, "test@example.com");
        publicId = UUID.randomUUID().toString();
        testContact = Contact.builder()
                .id(3001L)
                .publicId(publicId)
                .name("Leonardo Catananti")
                .email("leonardo@example.com")
                .subject("Proposta de Projeto")
                .message("Gostaria de discutir um projeto de consultoria em Java e Spring Boot para modernização de sistema legado.")
                .read(false)
                .createdAt(LocalDateTime.now().minusHours(3))
                .build();
    }

    @Nested
    @DisplayName("saveContact")
    class SaveContact {

        @Test
        @DisplayName("Should save contact message with realistic data")
        void shouldSaveContact() {
            ContactRequest request = ContactRequest.builder()
                    .name("Maria Santos")
                    .email("maria.santos@empresa.com.br")
                    .subject("Orçamento para Site")
                    .message("Preciso de um orçamento para desenvolvimento de um site corporativo com blog integrado e sistema de newsletter.")
                    .build();

            when(idService.nextId()).thenReturn(3002L);
            when(contactRepository.save(any(Contact.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(emailService.sendContactNotification(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());
            when(emailService.sendContactAutoReply(anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(contactService.saveContact(request))
                    .assertNext(response -> {
                        assertThat(response.getName()).isEqualTo("Maria Santos");
                        assertThat(response.getEmail()).isEqualTo("maria.santos@empresa.com.br");
                        assertThat(response.getSubject()).isEqualTo("Orçamento para Site");
                        assertThat(response.isRead()).isFalse();
                        assertThat(response.getId()).isNotNull();
                    })
                    .verifyComplete();

            verify(contactRepository).save(any(Contact.class));
        }
    }

    @Nested
    @DisplayName("getAllContacts")
    class GetAllContacts {

        @Test
        @DisplayName("Should return all contacts ordered by date desc")
        void shouldReturnAllContacts() {
            Contact older = Contact.builder()
                    .id(3000L).publicId("old-id").name("Carlos")
                    .email("carlos@test.com").subject("Pergunta")
                    .message("Uma pergunta sobre o blog que precisa de resposta detalhada.")
                    .read(true).createdAt(LocalDateTime.now().minusDays(5))
                    .build();

            when(contactRepository.findAllByOrderByCreatedAtDesc())
                    .thenReturn(Flux.just(testContact, older));

            StepVerifier.create(contactService.getAllContacts().collectList())
                    .assertNext(contacts -> {
                        assertThat(contacts).hasSize(2);
                        assertThat(contacts.get(0).getName()).isEqualTo("Leonardo Catananti");
                        assertThat(contacts.get(1).getName()).isEqualTo("Carlos");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getContactById")
    class GetContactById {

        @Test
        @DisplayName("Should return contact by public ID")
        void shouldReturnContact() {
            when(contactRepository.findByPublicId(publicId))
                    .thenReturn(Mono.just(testContact));

            StepVerifier.create(contactService.getContactById(publicId))
                    .assertNext(response -> {
                        assertThat(response.getName()).isEqualTo("Leonardo Catananti");
                        assertThat(response.getSubject()).isEqualTo("Proposta de Projeto");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            when(contactRepository.findByPublicId("nonexistent"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(contactService.getContactById("nonexistent"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("Should mark contact as read")
        void shouldMarkAsRead() {
            when(contactRepository.findByPublicId(publicId))
                    .thenReturn(Mono.just(testContact));
            when(contactRepository.save(any(Contact.class)))
                    .thenAnswer(inv -> {
                        Contact c = inv.getArgument(0);
                        return Mono.just(c);
                    });

            StepVerifier.create(contactService.markAsRead(publicId))
                    .assertNext(response -> {
                        assertThat(response.isRead()).isTrue();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("deleteContact")
    class DeleteContact {

        @Test
        @DisplayName("Should delete contact by public ID")
        void shouldDeleteContact() {
            when(contactRepository.findByPublicId(publicId))
                    .thenReturn(Mono.just(testContact));
            when(contactRepository.deleteById(testContact.getId())).thenReturn(Mono.empty());

            StepVerifier.create(contactService.deleteContact(publicId))
                    .verifyComplete();

            verify(contactRepository).deleteById(testContact.getId());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException for unknown contact")
        void shouldThrowWhenNotFound() {
            when(contactRepository.findByPublicId("nonexistent"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(contactService.deleteContact("nonexistent"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("getAllContactsPaginated")
    class GetAllContactsPaginated {

        @Test
        @DisplayName("Should return paginated contacts with correct metadata")
        void shouldReturnPaginatedContacts() {
            Contact contact2 = Contact.builder()
                    .id(3002L).publicId("pub-2").name("Maria Santos")
                    .email("maria@test.com").subject("Pergunta")
                    .message("Uma pergunta sobre o blog.")
                    .read(true).createdAt(LocalDateTime.now().minusDays(1))
                    .build();

            when(contactRepository.findAllPaginated(10, 0))
                    .thenReturn(Flux.just(testContact, contact2));
            when(contactRepository.countAll())
                    .thenReturn(Mono.just(2L));

            StepVerifier.create(contactService.getAllContactsPaginated(0, 10))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(2);
                        assertThat(page.getPage()).isZero();
                        assertThat(page.getSize()).isEqualTo(10);
                        assertThat(page.getTotalElements()).isEqualTo(2L);
                        assertThat(page.getTotalPages()).isEqualTo(1);
                        assertThat(page.isFirst()).isTrue();
                        assertThat(page.isLast()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle second page correctly")
        void shouldHandleSecondPage() {
            Contact contact3 = Contact.builder()
                    .id(3003L).publicId("pub-3").name("Carlos")
                    .email("carlos@test.com").subject("Follow up")
                    .message("Follow up question.")
                    .read(false).createdAt(LocalDateTime.now())
                    .build();

            when(contactRepository.findAllPaginated(2, 2))
                    .thenReturn(Flux.just(contact3));
            when(contactRepository.countAll())
                    .thenReturn(Mono.just(5L));

            StepVerifier.create(contactService.getAllContactsPaginated(1, 2))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                        assertThat(page.getPage()).isEqualTo(1);
                        assertThat(page.getSize()).isEqualTo(2);
                        assertThat(page.getTotalElements()).isEqualTo(5L);
                        assertThat(page.getTotalPages()).isEqualTo(3);
                        assertThat(page.isFirst()).isFalse();
                        assertThat(page.isLast()).isFalse();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty page when no contacts")
        void shouldReturnEmptyPage() {
            when(contactRepository.findAllPaginated(10, 0))
                    .thenReturn(Flux.empty());
            when(contactRepository.countAll())
                    .thenReturn(Mono.just(0L));

            StepVerifier.create(contactService.getAllContactsPaginated(0, 10))
                    .assertNext(page -> {
                        assertThat(page.getContent()).isEmpty();
                        assertThat(page.getTotalElements()).isZero();
                    })
                    .verifyComplete();
        }
    }
}
