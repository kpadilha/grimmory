package org.booklore.service.browse;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
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
 * of the book's search text, in any order. When no book at all matches that way, a word may instead
 * match an author-name word by Soundex, so "Glynn Stuart" still finds "Glynn Stewart".
 */
public final class BookSearchSpecification {

    // Each word adds one LIKE per row to the page, count and facet queries; caps bound the cost.
    static final int MAX_QUERY_LENGTH = 256;
    static final int MAX_TERMS = 16;

    private static final char ESCAPE = '\\';
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private BookSearchSpecification() {
    }

    public static Specification<BookEntity> matching(String query) {
        return (root, criteriaQuery, cb) -> {
            List<String> words = words(query);
            if (words.isEmpty()) {
                return cb.conjunction();
            }
            HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) cb;
            Join<?, ?> metadata = innerMetadataJoin(root);
            Predicate exact = allWordsInText(hcb, metadata, words);
            if (words.stream().allMatch(word -> BookUtils.soundex(word) == null)) {
                return exact;
            }

            Subquery<Long> anyExact = criteriaQuery.subquery(Long.class);
            Root<BookMetadataEntity> other = anyExact.from(BookMetadataEntity.class);
            anyExact.select(other.get("bookId")).where(allWordsInText(hcb, other, words));

            List<Predicate> tolerant = new ArrayList<>(words.size());
            for (String word : words) {
                String code = BookUtils.soundex(word);
                Predicate inText = contains(hcb, metadata.get("searchText"), word);
                tolerant.add(code == null ? inText
                        : cb.or(inText, cb.like(metadata.get("searchPhonetic"), hcb.value("% " + code + " %"), ESCAPE)));
            }
            return cb.or(exact, cb.and(cb.not(cb.exists(anyExact)), cb.and(tolerant.toArray(Predicate[]::new))));
        };
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

    private static Predicate allWordsInText(HibernateCriteriaBuilder cb, From<?, ?> metadata, List<String> words) {
        return cb.and(words.stream().map(word -> contains(cb, metadata.get("searchText"), word)).toArray(Predicate[]::new));
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
