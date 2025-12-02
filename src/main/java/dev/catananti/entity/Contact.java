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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("contacts")
public class Contact implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @Column("public_id")
    private String publicId;

    @Column("name")
    private String name;

    @Column("email")
    private String email;

    @Column("subject")
    private String subject;

    @Column("message")
    private String message;

    @Column("is_read")
    @Builder.Default
    private boolean read = false;

    @Column("created_at")
    private LocalDateTime createdAt;
}
