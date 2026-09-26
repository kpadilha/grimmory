package org.booklore.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of the narrow search table (written through {@link BookMetadataEntity}), so the
 * search can scan it alone to measure how many books match before the page query is planned.
 */
@Entity
@Immutable
@Getter
@Table(name = BookMetadataEntity.SEARCH_TABLE)
public class BookSearchTextEntity {

    @Id
    @Column(name = "book_id")
    private Long bookId;

    @Column(name = "search_text", columnDefinition = "TEXT")
    private String searchText;

    @Column(name = "search_phonetic", columnDefinition = "TEXT")
    private String searchPhonetic;
}
