package dev.catananti.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("reading_history")
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadingHistory implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("article_id")
    private Long articleId;

    @Column("last_read_at")
    private LocalDateTime lastReadAt;

    @Column("read_count")
    private int readCount;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }
}
