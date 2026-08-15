package dev.catananti.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante o ponto central da Fase 3 em comments: a eliminação remove a PII
 * (author_name, author_email) mas MANTÉM user_id — rastreabilidade estrutural
 * apontando para um registro que não reidentifica ninguém (LGPD art. 16, IV).
 */
class CommentRepositoryQueryTest {

    private Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return CommentRepository.class.getMethod(name, params);
    }

    @Test
    @DisplayName("anonymizeByOwner remove nome e e-mail mas nunca toca user_id")
    void anonymizeRemovesPiiButKeepsStructuralAuthorship() throws NoSuchMethodException {
        Method m = method("anonymizeByOwner", Long.class, String.class, String.class);

        assertThat(m.isAnnotationPresent(Modifying.class))
                .as("DML sem @Modifying nao é executado como escrita pelo R2DBC")
                .isTrue();
        assertThat(m.getGenericReturnType().getTypeName())
                .isEqualTo("reactor.core.publisher.Mono<java.lang.Long>");

        String sql = m.getAnnotation(Query.class).value();
        assertThat(sql).contains("author_name = :anonName");
        assertThat(sql).contains("author_email = NULL");
        // Belt and suspenders: rows missed by the user_id backfill (or created
        // before user_id propagation existed) still carry the address in
        // author_email — erasure must catch them by email as well.
        assertThat(sql).contains("WHERE user_id = :userId OR LOWER(author_email) = LOWER(:email)");
        assertThat(sql)
                .as("user_id permanece: é o que preserva integridade referencial sem PII")
                .doesNotContain("user_id = NULL")
                .doesNotContain("SET user_id");
        assertThat(sql)
                .as("o conteúdo público do comentário permanece")
                .doesNotContain("content");
    }

    @Test
    @DisplayName("countByUserId alimenta o preview de exclusão")
    void countByUserIdCountsStructuralAuthorship() throws NoSuchMethodException {
        Method m = method("countByUserId", Long.class);

        assertThat(m.getGenericReturnType().getTypeName())
                .isEqualTo("reactor.core.publisher.Mono<java.lang.Long>");

        String sql = m.getAnnotation(Query.class).value();
        assertThat(sql).contains("COUNT(*)");
        assertThat(sql).contains("WHERE user_id = :userId");
    }
}
