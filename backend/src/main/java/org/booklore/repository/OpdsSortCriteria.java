package org.booklore.repository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.ListJoin;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.OpdsSortOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds JPA Criteria {@link Order} lists for every {@link OpdsSortOrder}, replacing the
 * {@code JpaSort.unsafe} JPQL-text sorts that only worked against the id-queries' own aliases.
 * Every order ends with a stable id tiebreaker ({@code b.id ASC}, or metadata's book id for title sorts).
 */
final class OpdsSortCriteria {

    private OpdsSortCriteria() {
    }

    /**
     * Inner-joins metadata (and, for author sorts, left-joins the first author) onto the given root.
     * Every book gets its metadata row in the same save; the inner join lets title sorts drive from the index.
     */
    static Join<BookEntity, BookMetadataEntity> joinMetadata(Root<BookEntity> root) {
        return root.join("metadata", JoinType.INNER);
    }

    static ListJoin<BookMetadataEntity, AuthorEntity> joinFirstAuthor(CriteriaBuilder cb, Join<BookEntity, BookMetadataEntity> metadata) {
        ListJoin<BookMetadataEntity, AuthorEntity> firstAuthor = metadata.joinList("authors", JoinType.LEFT);
        firstAuthor.on(cb.equal(firstAuthor.index(), 0));
        return firstAuthor;
    }

    static boolean needsFirstAuthor(OpdsSortOrder sortOrder) {
        return sortOrder == OpdsSortOrder.AUTHOR_ASC || sortOrder == OpdsSortOrder.AUTHOR_DESC;
    }

    static List<Order> orders(CriteriaBuilder cb, Root<BookEntity> root,
                               Join<BookEntity, BookMetadataEntity> m,
                               ListJoin<BookMetadataEntity, AuthorEntity> sa,
                               OpdsSortOrder sortOrder) {
        List<Order> orders = new ArrayList<>();
        switch (sortOrder == null ? OpdsSortOrder.RECENT : sortOrder) {
            case RECENT -> orders.add(cb.desc(root.get("addedOn")));
            // Tiebreak on metadata's book_id in the same direction so (title_sort, book_id) serves it.
            case TITLE_ASC -> {
                return List.of(cb.asc(m.get("titleSort")), cb.asc(m.get("bookId")));
            }
            case TITLE_DESC -> {
                return List.of(cb.desc(m.get("titleSort")), cb.desc(m.get("bookId")));
            }
            case AUTHOR_ASC -> orders.add(cb.asc(cb.coalesce(sa.get("sortName"), "")));
            case AUTHOR_DESC -> orders.add(cb.desc(cb.coalesce(sa.get("sortName"), "")));
            case SERIES_ASC -> orders.addAll(seriesOrders(cb, m, true));
            case SERIES_DESC -> orders.addAll(seriesOrders(cb, m, false));
            case RATING_ASC -> orders.addAll(ratingOrders(cb, m, true));
            case RATING_DESC -> orders.addAll(ratingOrders(cb, m, false));
        }
        orders.add(cb.asc(root.get("id")));
        return orders;
    }

    // Browsing a single series pre-existing default is series order, not addedOn; missing series
    // numbers sort last. Used only when the caller asked for RECENT (or no order) while browsing
    // a single series - every other order behaves like the general switch above.
    static List<Order> seriesNaturalOrders(CriteriaBuilder cb, Root<BookEntity> root, Join<BookEntity, BookMetadataEntity> m) {
        Expression<Float> seriesNumber = cb.coalesce(m.<Float>get("seriesNumber"), 999999f);
        return List.of(cb.asc(seriesNumber), cb.desc(root.get("addedOn")), cb.asc(root.get("id")));
    }

    // Books without a series always sort after ones with a series, in both directions; a missing
    // series number sorts last within its series for the same reason.
    private static List<Order> seriesOrders(CriteriaBuilder cb, Join<BookEntity, BookMetadataEntity> m, boolean asc) {
        Expression<String> seriesName = cb.coalesce(m.<String>get("seriesName"), "");
        Expression<Integer> noSeries = cb.<Integer>selectCase()
                .when(cb.equal(seriesName, ""), 1).otherwise(0);
        Expression<Integer> noSeriesNumber = cb.<Integer>selectCase()
                .when(cb.isNull(m.get("seriesNumber")), 1).otherwise(0);
        return List.of(
                cb.asc(noSeries),
                asc ? cb.asc(m.get("seriesName")) : cb.desc(m.get("seriesName")),
                cb.asc(noSeriesNumber),
                asc ? cb.asc(m.<Float>get("seriesNumber")) : cb.desc(m.<Float>get("seriesNumber")));
    }

    // Rating is the average of the positive amazon/goodreads/hardcover ratings; books without a
    // rating sort last in BOTH directions. COALESCE(NULLIF(x,0),0) == COALESCE(x,0) for x >= 0,
    // which every rating here is, so the numerator skips the redundant NULLIF.
    private static List<Order> ratingOrders(CriteriaBuilder cb, Join<BookEntity, BookMetadataEntity> m, boolean asc) {
        Expression<Double> amazon = cb.coalesce(m.<Double>get("amazonRating"), 0.0);
        Expression<Double> goodreads = cb.coalesce(m.<Double>get("goodreadsRating"), 0.0);
        Expression<Double> hardcover = cb.coalesce(m.<Double>get("hardcoverRating"), 0.0);

        Expression<Integer> hasAmazon = cb.<Integer>selectCase().when(cb.greaterThan(amazon, 0.0), 1).otherwise(0);
        Expression<Integer> hasGoodreads = cb.<Integer>selectCase().when(cb.greaterThan(goodreads, 0.0), 1).otherwise(0);
        Expression<Integer> hasHardcover = cb.<Integer>selectCase().when(cb.greaterThan(hardcover, 0.0), 1).otherwise(0);
        Expression<Integer> count = cb.sum(cb.sum(hasAmazon, hasGoodreads), hasHardcover);

        Expression<Integer> hasAnyValue = cb.<Integer>selectCase().when(cb.greaterThan(count, 0), 0).otherwise(1);
        Expression<Double> sum = cb.sum(cb.sum(amazon, goodreads), hardcover);
        Expression<Integer> safeCount = cb.<Integer>selectCase()
                .when(cb.equal(count, 0), cb.nullLiteral(Integer.class)).otherwise(count);
        Expression<Double> average = cb.quot(sum, safeCount).as(Double.class);

        return List.of(
                cb.asc(hasAnyValue),
                asc ? cb.asc(average) : cb.desc(average));
    }
}
