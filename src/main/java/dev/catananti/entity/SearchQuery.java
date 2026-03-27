package dev.catananti.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("search_queries")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchQuery implements Persistable<Long>, NewRecordAware {
    @Id
    private Long id;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @Override
    public void setNewRecord(boolean newRecord) {
        this.newRecord = newRecord;
    }

    @Column("query_text")
    private String queryText;

    @Column("results_count")
    private int resultsCount;

    @Column("user_id")
    private Long userId;

    @Column("user_ip")
    private String userIp;

    @Column("created_at")
    private LocalDateTime createdAt;
}
