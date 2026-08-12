package dev.catananti.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Token de prova de posse do endereço de e-mail no cadastro.
 *
 * <p>Espelha {@link EmailChangeToken}: o valor em texto puro só existe no link
 * enviado por e-mail; o banco guarda apenas o SHA-256.
 *
 * <p>{@link Persistable} é obrigatório aqui: os ids vêm prontos do IdService, e
 * sem o isNew() o Spring Data R2DBC trataria o save() como UPDATE de uma linha
 * inexistente — que afeta 0 linhas SEM erro, "salvando" um token que nunca
 * chega ao banco enquanto o e-mail com o link é enviado normalmente.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("email_verification_tokens")
public class EmailVerificationToken implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @Column("user_id")
    private Long userId;

    @Column("email")
    private String email;

    /** SHA-256 do token; o valor em texto só existe no e-mail enviado. */
    @Column("token")
    private String token;

    @Column("expires_at")
    private LocalDateTime expiresAt;

    @Column("used")
    @Builder.Default
    private boolean used = false;

    @Column("used_at")
    private LocalDateTime usedAt;

    @Column("created_at")
    private LocalDateTime createdAt;

    public boolean isValid() {
        return !used && expiresAt != null && expiresAt.isAfter(LocalDateTime.now());
    }
}
