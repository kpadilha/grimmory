package org.booklore.service.browse;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.booklore.config.BookSearchFunctionContributor;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Library search: every word must prefix-match a word of the title, series, an author, a category
 * or a tag (MariaDB FULLTEXT), or the whole query must equal the ISBN-13, ISBN-10 or ASIN.
 * Mid-word substrings no longer match, and words shorter than the index's minimum token size are
 * ignored unless the query has nothing longer. The FULLTEXT part is MariaDB-only.
 */
public final class BookSearchSpecification {

    // innodb_ft_min_token_size default (and the value measured in production): shorter words are
    // never indexed, so a required short term would reject every book.
    static final int MIN_TOKEN_LENGTH = 3;

    // Each term adds a UNION of four FULLTEXT lookups to the page, count and facet queries, so
    // query size is capped to keep one request's cost bounded.
    static final int MAX_QUERY_LENGTH = 256;
    static final int MAX_TERMS = 16;

    // InnoDB splits on anything but letters, digits and '_'; splitting the same way also strips
    // every boolean-mode operator (+ - < > ( ) ~ * " @) from user input.
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{M}\\p{N}_]+");

    private BookSearchSpecification() {
    }

    public static Specification<BookEntity> matching(String query) {
        return (root, criteriaQuery, cb) -> {
            if (query == null || query.isBlank()) {
                return cb.conjunction();
            }
            String trimmed = query.trim();
            Predicate identifier = identifierMatches(root, criteriaQuery.subquery(Long.class), cb, trimmed);
            List<String> terms = fulltextTerms(trimmed);
            if (terms.isEmpty()) {
                return identifier;
            }
            List<Predicate> words = new ArrayList<>(terms.size());
            for (String term : terms) {
                words.add(cb.isTrue(cb.function(BookSearchFunctionContributor.BOOK_SEARCH_TERM, Boolean.class,
                        root.get("id"), ((HibernateCriteriaBuilder) cb).value(term))));
            }
            return cb.or(cb.and(words.toArray(Predicate[]::new)), identifier);
        };
    }

    /**
     * One required prefix term ({@code +word*}) per distinct word, operator-free by construction.
     * Only the first {@link #MAX_QUERY_LENGTH} characters are read and the first {@link #MAX_TERMS} terms kept.
     */
    static List<String> fulltextTerms(String query) {
        Set<String> all = new LinkedHashSet<>();
        Set<String> indexable = new LinkedHashSet<>();
        for (String word : NON_WORD.split(truncate(query))) {
            if (word.isEmpty()) {
                continue;
            }
            all.add(word);
            if (word.codePointCount(0, word.length()) >= MIN_TOKEN_LENGTH) {
                indexable.add(word);
            }
        }
        return (indexable.isEmpty() ? all : indexable).stream().limit(MAX_TERMS).map(word -> "+" + word + "*").toList();
    }

    private static String truncate(String query) {
        if (query.codePointCount(0, query.length()) <= MAX_QUERY_LENGTH) {
            return query;
        }
        return query.substring(0, query.offsetByCodePoints(0, MAX_QUERY_LENGTH));
    }

    private static Predicate identifierMatches(Root<BookEntity> root, Subquery<Long> sub, CriteriaBuilder cb, String value) {
        Root<BookMetadataEntity> m = sub.from(BookMetadataEntity.class);
        sub.select(m.get("bookId")).where(cb.or(
                cb.equal(m.get("isbn13"), value),
                cb.equal(m.get("isbn10"), value),
                cb.equal(m.get("asin"), value)));
        return root.get("id").in(sub);
    }
}
