package dev.catananti.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que a política de vínculo viva em SQL condicional, não em if de Java.
 *
 * <p>Verificação de e-mail e confirmação de inscrição podem correr ao mesmo tempo;
 * um check-then-act em Java passaria nos testes e perderia a corrida em produção.
 * O UPDATE condicional devolve contagem de linhas: quem obtém 1 venceu.
 */
class SubscriberRepositoryQueryTest {

    private Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return SubscriberRepository.class.getMethod(name, params);
    }

    private String sqlOf(Method m) {
        assertThat(m.isAnnotationPresent(Modifying.class))
                .as("DML sem @Modifying nao é executado como escrita pelo R2DBC")
                .isTrue();
        assertThat(m.getGenericReturnType().getTypeName())
                .as("o chamador decide pelo row count")
                .isEqualTo("reactor.core.publisher.Mono<java.lang.Long>");
        return m.getAnnotation(Query.class).value();
    }

    @Test
    @DisplayName("autoLink só vincula se ninguém vinculou antes e o titular não recusou")
    void autoLinkGuardsAgainstRaceAndUserRefusal() throws NoSuchMethodException {
        String sql = sqlOf(method("autoLink", Long.class, Long.class, String.class, LocalDateTime.class));

        assertThat(sql).contains("user_id IS NULL");
        assertThat(sql)
                .as("apenas a recusa do próprio titular bloqueia o re-vínculo automático")
                .contains("unlinked_by IS NULL OR unlinked_by <> 'USER'");
        assertThat(sql)
                .as("re-vincular limpa o registro de desvinculação anterior")
                .contains("unlinked_at = NULL")
                .contains("unlinked_by = NULL");
    }

    @Test
    @DisplayName("linkIgnoringRefusal mantém a proteção contra corrida mas não a guarda de recusa")
    void linkIgnoringRefusalKeepsOnlyTheRaceGuard() throws NoSuchMethodException {
        String sql = sqlOf(method("linkIgnoringRefusal", Long.class, Long.class, String.class, LocalDateTime.class));

        assertThat(sql).contains("user_id IS NULL");
        // when the holder asks for the link back, the earlier refusal is exactly
        // what is being revoked — the guard must not exist here
        assertThat(sql).doesNotContain("unlinked_by <>");
        assertThat(sql).doesNotContain("unlinked_by IS NULL OR");
    }

    @Test
    @DisplayName("unlink limpa o vínculo e registra quem desfez")
    void unlinkClearsLinkAndRecordsWho() throws NoSuchMethodException {
        String sql = sqlOf(method("unlink", Long.class, String.class, LocalDateTime.class));

        assertThat(sql).contains("user_id = NULL");
        assertThat(sql).contains("unlinked_at = :now");
        assertThat(sql).contains("unlinked_by = :by");
        assertThat(sql).contains("WHERE user_id = :userId");
        assertThat(sql)
                .as("linked_at responde quando o vínculo existiu; desfazer não apaga a história")
                .doesNotContain("linked_at = NULL");
    }

    @Test
    @DisplayName("updateAnalyticsConsent toca apenas o consentimento")
    void updateAnalyticsConsentTouchesOnlyTheFlag() throws NoSuchMethodException {
        String sql = sqlOf(method("updateAnalyticsConsent", Long.class, Boolean.class));

        assertThat(sql).contains("analytics_consent = :consent");
        assertThat(sql).contains("WHERE id = :id");
        assertThat(sql)
                .doesNotContain("status")
                .doesNotContain("user_id");
    }

    @Test
    @DisplayName("unsubscribeByUserId cancela os envios sem tocar o vínculo")
    void unsubscribeByUserIdCancelsEmailsButKeepsTheLink() throws NoSuchMethodException {
        String sql = sqlOf(method("unsubscribeByUserId", Long.class, LocalDateTime.class));

        assertThat(sql).contains("status = 'UNSUBSCRIBED'");
        assertThat(sql).contains("unsubscribed_at = :at");
        assertThat(sql).contains("WHERE user_id = :userId");
        assertThat(sql)
                .as("idempotente: re-executar sobre quem já cancelou devolve 0 linhas")
                .contains("status <> 'UNSUBSCRIBED'");
        assertThat(sql)
                .as("cancelar inscrição não é desvincular — unlink é outra operação")
                .doesNotContain("user_id = NULL")
                .doesNotContain("unlinked");
    }

    @Test
    @DisplayName("findByUserId existe para a área da conta resolver a inscrição vinculada")
    void findByUserIdReturnsSingleSubscriber() throws NoSuchMethodException {
        Method m = method("findByUserId", Long.class);
        assertThat(m.getGenericReturnType().getTypeName())
                .as("o índice único parcial garante no máximo uma linha por usuário")
                .isEqualTo("reactor.core.publisher.Mono<dev.catananti.entity.Subscriber>");
    }
}
