package dev.catananti.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * A user or visitor bookmark for an article.
 * Visitors are identified by a fingerprint hash (anonymous bookmarking).
 * Authenticated users are identified by userId.
 */
@Table("bookmarks")
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Bookmark implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    @Column("article_id")
    private Long articleId;

    @Column("user_id")
    private Long userId;

    @Column("visitor_hash")
    private String visitorHash;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }
}
