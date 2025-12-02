package dev.catananti.service;

import dev.catananti.dto.ContactRequest;
import dev.catananti.dto.ContactResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Contact;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ContactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
// TODO F-170: Add spam scoring (e.g., Akismet, keyword-based) for contact form submissions
public class ContactService {

    private final ContactRepository contactRepository;
    private final EmailService emailService;
    private final HtmlSanitizerService htmlSanitizerService;
    private final IdService idService;
    private final NotificationEventService notificationEventService;
    private final String notificationEmail;

    public ContactService(ContactRepository contactRepository,
                          EmailService emailService,
                          HtmlSanitizerService htmlSanitizerService,
                          IdService idService,
                          NotificationEventService notificationEventService,
                          @Value("${app.contact.notification-email}") String notificationEmail) {
        this.contactRepository = contactRepository;
        this.emailService = emailService;
        this.htmlSanitizerService = htmlSanitizerService;
        this.idService = idService;
        this.notificationEventService = notificationEventService;
        this.notificationEmail = notificationEmail;
    }

    public Mono<ContactResponse> saveContact(ContactRequest request) {
        // F-169: Enforce field length limits to prevent abuse
        String name = htmlSanitizerService.stripHtml(request.getName());
        if (name.length() > 200) name = name.substring(0, 200);
        String subject = htmlSanitizerService.stripHtml(request.getSubject());
        if (subject.length() > 500) subject = subject.substring(0, 500);
        String message = htmlSanitizerService.stripHtml(request.getMessage());
        if (message.length() > 5000) message = message.substring(0, 5000);
        String email = request.getEmail();
        if (email != null && email.length() > 255) email = email.substring(0, 255);

        Contact contact = Contact.builder()
                .id(idService.nextId())
                .publicId(UUID.randomUUID().toString())
                .name(name)
                .email(email)
                .subject(subject)
                .message(message)
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();

        return contactRepository.save(contact)
                .flatMap(saved -> {
                    log.info("Contact message saved with id: {}", saved.getPublicId());
                    notificationEventService.contactReceived(saved.getName());
                    return sendNotificationEmail(saved)
                            .thenReturn(saved);
                })
                .map(this::mapToResponse);
    }

    public Flux<ContactResponse> getAllContacts() {
        return contactRepository.findAllByOrderByCreatedAtDesc()
                .map(this::mapToResponse);
    }

    public Mono<PageResponse<ContactResponse>> getAllContactsPaginated(int page, int size) {
        int offset = page * size;
        return contactRepository.findAllPaginated(size, offset)
                .map(this::mapToResponse)
                .collectList()
                .zipWith(contactRepository.countAll())
                .map(tuple -> {
                    var content = tuple.getT1();
                    var total = tuple.getT2();
                    return PageResponse.of(content, page, size, total);
                });
    }

    public Mono<ContactResponse> getContactById(String publicId) {
        return contactRepository.findByPublicId(publicId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Contact not found: " + publicId)))
                .map(this::mapToResponse);
    }

    public Mono<ContactResponse> markAsRead(String publicId) {
        return contactRepository.findByPublicId(publicId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Contact not found: " + publicId)))
                .flatMap(contact -> {
                    contact.setRead(true);
                    return contactRepository.save(contact);
                })
                .map(this::mapToResponse);
    }

    public Mono<Void> deleteContact(String publicId) {
        return contactRepository.findByPublicId(publicId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Contact not found: " + publicId)))
                .flatMap(contact -> contactRepository.deleteById(contact.getId()));
    }

    public Flux<ContactResponse> getUnreadContacts() {
        return contactRepository.findByReadFalseOrderByCreatedAtDesc()
                .map(this::mapToResponse);
    }

    private Mono<Void> sendNotificationEmail(Contact contact) {
        // Send HTML admin notification + auto-reply to submitter
        Mono<Void> adminNotification = emailService.sendContactNotification(
                notificationEmail,
                contact.getName(),
                contact.getEmail(),
                contact.getSubject(),
                contact.getMessage()
        ).doOnError(e -> log.error("Failed to send contact notification email", e))
         .onErrorResume(e -> Mono.empty());

        Mono<Void> autoReply = Mono.empty();
        if (contact.getEmail() != null && !contact.getEmail().isBlank()) {
            autoReply = emailService.sendContactAutoReply(
                    contact.getEmail(),
                    contact.getName(),
                    contact.getSubject()
            ).doOnError(e -> log.error("Failed to send contact auto-reply to {}", contact.getEmail(), e))
             .onErrorResume(e -> Mono.empty());
        }

        return adminNotification.then(autoReply);
    }

    private ContactResponse mapToResponse(Contact contact) {
        return ContactResponse.builder()
                .id(contact.getPublicId())
                .name(contact.getName())
                .email(contact.getEmail())
                .subject(contact.getSubject())
                .message(contact.getMessage())
                .read(contact.isRead())
                .createdAt(contact.getCreatedAt())
                .build();
    }
}
