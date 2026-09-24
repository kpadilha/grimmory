package org.booklore.service.browse;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.booklore.config.BookSearchFunctionContributor;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
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
                        root.get("id"), cb.literal(term))));
            }
            return cb.or(cb.and(words.toArray(Predicate[]::new)), identifier);
        };
    }

    /** One required prefix term ({@code +word*}) per distinct word, operator-free by construction. */
    static List<String> fulltextTerms(String query) {
        Set<String> all = new LinkedHashSet<>();
        Set<String> indexable = new LinkedHashSet<>();
        for (String word : NON_WORD.split(query)) {
            if (word.isEmpty()) {
                continue;
            }
            all.add(word);
            if (word.codePointCount(0, word.length()) >= MIN_TOKEN_LENGTH) {
                indexable.add(word);
            }
        }
        return (indexable.isEmpty() ? all : indexable).stream().map(word -> "+" + word + "*").toList();
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
