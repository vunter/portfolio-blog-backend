package dev.catananti.repository;

import dev.catananti.entity.Contact;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// TODO F-290: Add pagination default — findAllByOrderByCreatedAtDesc returns unbounded results
public interface ContactRepository extends ReactiveCrudRepository<Contact, Long> {

    Mono<Contact> findByPublicId(String publicId);

    Flux<Contact> findAllByOrderByCreatedAtDesc();

    Flux<Contact> findByReadFalseOrderByCreatedAtDesc();

    @Query("SELECT * FROM contacts ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Contact> findAllPaginated(int limit, int offset);

    @Query("SELECT COUNT(*) FROM contacts")
    Mono<Long> countAll();

    @Query("SELECT COUNT(*) FROM contacts WHERE is_read = false")
    Mono<Long> countUnread();
}
