package org.booklore.service.browse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.booklore.model.entity.BookEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns a search query into the specification every client applies. The phonetic fallback is
 * chosen only when no book in the caller's own scope matches exactly, so books the caller cannot
 * see neither suppress it nor reveal their existence.
 */
@Component
public class BookSearchResolver {

    @PersistenceContext
    private EntityManager entityManager;

    /** Null for a blank query; {@code scope} is every other filter of the caller, search excluded. */
    public Specification<BookEntity> resolve(String query, Specification<BookEntity> scope) {
        List<String> words = BookSearchSpecification.words(query);
        if (words.isEmpty()) {
            return null;
        }
        Specification<BookEntity> exact = BookSearchSpecification.exact(words);
        if (!BookSearchSpecification.hasPhoneticCode(words) || anyMatch(scope.and(exact))) {
            return exact;
        }
        return BookSearchSpecification.tolerant(words);
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
