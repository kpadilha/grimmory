package org.booklore.repository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.ShelfEntity;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;

/** WHERE-clause building blocks for the OPDS id-query, composed with {@code AppBookSpecification} and
 * {@code ContentRestrictionSpecification} so restriction, library/shelf scope, and search all live
 * in one Criteria predicate instead of a parallel JPQL filter. */
final class BookOpdsSpecifications {

    private BookOpdsSpecifications() {
    }

    static Specification<BookEntity> inLibraries(Collection<Long> libraryIds) {
        return (root, query, cb) -> root.get("library").get("id").in(libraryIds);
    }

    static Specification<BookEntity> inShelves(Collection<Long> shelfIds) {
        return (root, query, cb) -> {
            Join<BookEntity, ShelfEntity> shelves = root.join("shelves", JoinType.INNER);
            return shelves.get("id").in(shelfIds);
        };
    }

    static Specification<BookEntity> hasAuthorName(String authorName) {
        return (root, query, cb) -> {
            Join<BookEntity, BookMetadataEntity> m = root.join("metadata", JoinType.INNER);
            Join<BookMetadataEntity, AuthorEntity> a = m.join("authors", JoinType.INNER);
            return cb.equal(a.get("name"), authorName);
        };
    }

    static Specification<BookEntity> hasSeriesName(String seriesName) {
        return (root, query, cb) -> {
            Join<BookEntity, BookMetadataEntity> m = root.join("metadata", JoinType.INNER);
            return cb.equal(m.get("seriesName"), seriesName);
        };
    }
}
