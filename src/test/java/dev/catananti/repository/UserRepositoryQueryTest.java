package dev.catananti.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que as escritas em {@code users} sejam UPDATEs parciais.
 *
 * <p>Um {@code save()} da entidade inteira reescreve todas as colunas, então uma
 * requisição segurando uma cópia velha do usuário reverteria silenciosamente o que
 * outra acabou de gravar. Foi essa a classe de bug (lost update) que a auditoria de
 * persistência encontrou nos fluxos de MFA e senha.
 */
class UserRepositoryQueryTest {

    private Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return UserRepository.class.getMethod(name, params);
    }

    @Test
    @DisplayName("markEmailVerified toca apenas o flag e o updated_at")
    void markEmailVerifiedUpdatesOnlyTheFlag() throws NoSuchMethodException {
        Method m = method("markEmailVerified", Long.class, LocalDateTime.class);

        assertThat(m.isAnnotationPresent(Modifying.class))
                .as("UPDATE sem @Modifying nao é executado como escrita pelo R2DBC")
                .isTrue();

        String sql = m.getAnnotation(Query.class).value();
        assertThat(sql).contains("UPDATE users SET email_verified = true");
        assertThat(sql).contains("WHERE id = :id");
        assertThat(sql)
                .as("nao pode reescrever colunas que este fluxo nao possui")
                .doesNotContain("password_hash")
                .doesNotContain("mfa_enabled")
                .doesNotContain("role");
    }

    @Test
    @DisplayName("markEmailVerified devolve contagem de linhas para o chamador decidir")
    void markEmailVerifiedReturnsRowCount() throws NoSuchMethodException {
        Method m = method("markEmailVerified", Long.class, LocalDateTime.class);
        assertThat(m.getGenericReturnType().getTypeName())
                .isEqualTo("reactor.core.publisher.Mono<java.lang.Long>");
    }

    @Test
    @DisplayName("deactivateAccount é condicional: rows=0 quando a conta já saiu de ACTIVE")
    void deactivateAccountIsConditionalOnActiveStatus() throws NoSuchMethodException {
        Method m = method("deactivateAccount", Long.class, LocalDateTime.class);

        assertThat(m.isAnnotationPresent(Modifying.class)).isTrue();
        assertThat(m.getGenericReturnType().getTypeName())
                .as("o chamador transforma rows=0 em 409 (já desativada)")
                .isEqualTo("reactor.core.publisher.Mono<java.lang.Long>");

        String sql = m.getAnnotation(Query.class).value();
        assertThat(sql).contains("active = false");
        assertThat(sql).contains("status = 'DEACTIVATED'");
        assertThat(sql).contains("deleted_at = :at");
        assertThat(sql).contains("updated_at = :at");
        assertThat(sql).contains("WHERE id = :id AND status = 'ACTIVE'");
        assertThat(sql)
                .as("desativação preserva a PII — só a eliminação anonimiza")
                .doesNotContain("email =")
                .doesNotContain("name =")
                .doesNotContain("password_hash");
    }

    @Test
    @DisplayName("eraseAccount anonimiza toda a PII da linha e é irrepetível")
    void eraseAccountAnonymizesEveryPiiColumn() throws NoSuchMethodException {
        Method m = method("eraseAccount",
                Long.class, String.class, String.class, String.class, LocalDateTime.class);

        assertThat(m.isAnnotationPresent(Modifying.class)).isTrue();
        assertThat(m.getGenericReturnType().getTypeName())
                .isEqualTo("reactor.core.publisher.Mono<java.lang.Long>");

        String sql = m.getAnnotation(Query.class).value();
        assertThat(sql).contains("active = false");
        assertThat(sql).contains("status = 'ERASED'");
        assertThat(sql).contains("deleted_at = :at");
        assertThat(sql).contains("email = :anonEmail");
        assertThat(sql).contains("name = :anonName");
        assertThat(sql).contains("username = NULL");
        assertThat(sql).contains("avatar_url = NULL");
        assertThat(sql).contains("bio = NULL");
        assertThat(sql).contains("password_hash = :scrambledHash");
        assertThat(sql).contains("mfa_enabled = false");
        assertThat(sql).contains("mfa_preferred_method = NULL");
        assertThat(sql).contains("email_verified = false");
        assertThat(sql)
                .as("uma conta DEACTIVATED ainda pode ser eliminada; só ERASED é terminal")
                .contains("WHERE id = :id AND status <> 'ERASED'");
    }

    @Test
    @DisplayName("updateAnalyticsConsent toca apenas o consentimento e seus timestamps")
    void updateAnalyticsConsentUpdatesOnlyConsentColumns() throws NoSuchMethodException {
        Method m = method("updateAnalyticsConsent", Long.class, Boolean.class, LocalDateTime.class);

        assertThat(m.isAnnotationPresent(Modifying.class)).isTrue();
        assertThat(m.getGenericReturnType().getTypeName())
                .isEqualTo("reactor.core.publisher.Mono<java.lang.Long>");

        String sql = m.getAnnotation(Query.class).value();
        assertThat(sql).contains("analytics_consent = :consent");
        assertThat(sql).contains("analytics_consent_at = :at");
        assertThat(sql).contains("WHERE id = :id");
        assertThat(sql)
                .doesNotContain("password_hash")
                .doesNotContain("email_verified")
                .doesNotContain("role");
    }
}
