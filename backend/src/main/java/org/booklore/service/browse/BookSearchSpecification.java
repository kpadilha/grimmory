package org.booklore.service.browse;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.booklore.model.entity.BookEntity;
import org.booklore.util.BookUtils;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one library search rule (web, facets, OPDS, app): every query word must occur as a substring
 * of the book's search text, in any order. {@link BookSearchResolver} decides, within the caller's
 * visible books, when a word may instead match an author-name word by Soundex ("Glynn Stuart").
 */
public final class BookSearchSpecification {

    // Each word adds one LIKE per row to the page, count and facet queries; caps bound the cost.
    static final int MAX_QUERY_LENGTH = 256;
    static final int MAX_TERMS = 16;

    private static final char ESCAPE = '\\';
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private BookSearchSpecification() {
    }

    /** Every word is a substring of the search text. */
    static Specification<BookEntity> exact(List<String> words) {
        return (root, query, cb) -> exactPredicate((HibernateCriteriaBuilder) cb, innerMetadataJoin(root).get("searchText"), words);
    }

    /** Every word is a substring of the search text or shares a Soundex code with an author-name word. */
    static Specification<BookEntity> tolerant(List<String> words) {
        return (root, query, cb) -> {
            Join<?, ?> metadata = innerMetadataJoin(root);
            return tolerantPredicate((HibernateCriteriaBuilder) cb, metadata.get("searchText"), metadata.get("searchPhonetic"), words);
        };
    }

    static Predicate exactPredicate(HibernateCriteriaBuilder cb, Expression<String> text, List<String> words) {
        return cb.and(words.stream().map(word -> contains(cb, text, word)).toArray(Predicate[]::new));
    }

    static Predicate tolerantPredicate(HibernateCriteriaBuilder cb, Expression<String> text, Expression<String> phonetic, List<String> words) {
        List<Predicate> predicates = new ArrayList<>(words.size());
        for (String word : words) {
            Predicate inText = contains(cb, text, word);
            String code = BookUtils.soundex(word);
            predicates.add(code == null ? inText : cb.or(inText, cb.like(phonetic, cb.value("% " + code + " %"), ESCAPE)));
        }
        return cb.and(predicates.toArray(Predicate[]::new));
    }

    static boolean hasPhoneticCode(List<String> words) {
        return words.stream().anyMatch(word -> BookUtils.soundex(word) != null);
    }

    /** Normalised like search_text, split on whitespace, deduplicated; at most 16 words from 256 characters. */
    static List<String> words(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = BookUtils.normalizeForSearch(truncate(query.trim()));
        Set<String> words = new LinkedHashSet<>();
        for (String word : WHITESPACE.split(normalized)) {
            if (!word.isEmpty()) {
                words.add(word);
            }
            if (words.size() == MAX_TERMS) {
                break;
            }
        }
        return List.copyOf(words);
    }

    private static Predicate contains(HibernateCriteriaBuilder cb, Expression<String> text, String word) {
        String escaped = word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return cb.like(text, cb.value("%" + escaped + "%"), ESCAPE);
    }

    // Reuses the sort's inner join when present, so the page query reads one book_metadata row.
    private static Join<?, ?> innerMetadataJoin(Root<BookEntity> root) {
        for (Join<BookEntity, ?> join : root.getJoins()) {
            if ("metadata".equals(join.getAttribute().getName()) && join.getJoinType() == JoinType.INNER && join.getOn() == null) {
                return join;
            }
        }
        return root.join("metadata", JoinType.INNER);
    }

    private static String truncate(String query) {
        if (query.codePointCount(0, query.length()) <= MAX_QUERY_LENGTH) {
            return query;
        }
        return query.substring(0, query.offsetByCodePoints(0, MAX_QUERY_LENGTH));
    }
}
