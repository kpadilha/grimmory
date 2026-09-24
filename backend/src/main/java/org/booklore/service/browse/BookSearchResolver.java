package org.booklore.service.browse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookSearchTextEntity;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns a search query into the specification every client applies. The phonetic fallback is
 * chosen only when no book in the caller's own scope matches exactly, so books the caller cannot
 * see neither suppress it nor reveal their existence.
 * <p>
 * MariaDB cannot estimate how many rows a {@code LIKE '%x%'} matches, so under ORDER BY ... LIMIT it
 * walks the sort index and probes every book. The matches are therefore counted first on the narrow
 * search table; when few, the caller's query is driven from their ids instead.
 */
@Component
public class BookSearchResolver {

    // Measured on 132k books: id-driven page+count beat the LIKE plan from 89 to ~9.9k matches, and
    // 2000 keeps the padded IN list at 2048 bound parameters.
    static final int SELECTIVE_MATCH_LIMIT = 2000;

    @PersistenceContext
    private EntityManager entityManager;

    /** Null for a blank query; {@code scope} is every other filter of the caller, search excluded. */
    public Specification<BookEntity> resolve(String query, Specification<BookEntity> scope) {
        List<String> words = BookSearchSpecification.words(query);
        if (words.isEmpty()) {
            return null;
        }
        Specification<BookEntity> exact = narrowed(words, false);
        if (!BookSearchSpecification.hasPhoneticCode(words) || anyMatch(scope.and(exact))) {
            return exact;
        }
        return narrowed(words, true);
    }

    // Selective: the ids themselves; otherwise the LIKE predicate, where walking the sort index
    // finds a page of matches quickly.
    private Specification<BookEntity> narrowed(List<String> words, boolean tolerant) {
        List<Long> ids = matchingIds(words, tolerant);
        if (ids.size() > SELECTIVE_MATCH_LIMIT) {
            return tolerant ? BookSearchSpecification.tolerant(words) : BookSearchSpecification.exact(words);
        }
        return (root, query, cb) -> ids.isEmpty() ? cb.disjunction() : root.get("id").in(ids);
    }

    /** Ids of all books matching on the narrow search table, capped one past the selectivity limit. */
    List<Long> matchingIds(List<String> words, boolean tolerant) {
        HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookSearchTextEntity> row = query.from(BookSearchTextEntity.class);
        query.select(row.get("bookId")).where(tolerant
                ? BookSearchSpecification.tolerantPredicate(cb, row.get("searchText"), row.get("searchPhonetic"), words)
                : BookSearchSpecification.exactPredicate(cb, row.get("searchText"), words));
        return entityManager.createQuery(query).setMaxResults(SELECTIVE_MATCH_LIMIT + 1).getResultList();
    }

    private boolean anyMatch(Specification<BookEntity> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> root = query.from(BookEntity.class);
        query.select(root.get("id"));
        Predicate predicate = spec.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
        return !entityManager.createQuery(query).setMaxResults(1).getResultList().isEmpty();
    }
}
