package org.booklore.service.browse;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.booklore.browse.SortContext;
import org.booklore.browse.SortOrderBuilder;
import org.booklore.browse.SortRegistry;
import org.booklore.model.entity.BookEntity;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BookSortRegistry {

    private static final List<String> PROGRESS_PERCENT_FIELDS = List.of(
            "pdfProgressPercent", "epubProgressPercent", "cbxProgressPercent",
            "koreaderProgressPercent", "koboProgressPercent");

    private final SortRegistry<BookEntity> registry = build();

    public SortRegistry<BookEntity> registry() {
        return registry;
    }

    private static SortRegistry<BookEntity> build() {
        SortRegistry<BookEntity> registry = new SortRegistry<>();

        registry.register("id", idTiebreaker());
        registry.register("addedOn", rootField("addedOn"));
        registry.register("title", metadataField("titleSort"));

        for (String field : List.of(
                "seriesName", "seriesNumber", "publisher", "publishedDate",
                "amazonRating", "amazonReviewCount", "goodreadsRating", "goodreadsReviewCount",
                "hardcoverRating", "hardcoverReviewCount", "ranobedbRating",
                "lubimyczytacRating",
                "audibleRating", "audibleReviewCount",
                "applebooksRating", "applebooksReviewCount",
                "narrator", "pageCount", "language")) {
            registry.register(field, metadataField(field));
        }

        for (String field : List.of("personalRating", "lastReadTime", "readStatus", "dateFinished")) {
            registry.register(field, progressField(field));
        }

        registry.register("authorName", authorField("name"));
        registry.register("authorSortName", authorField("sortName"));

        registry.register("readingProgress", readingProgress());
        registry.register("random", random());

        return registry;
    }

    private static SortOrderBuilder<BookEntity> rootField(String field) {
        return ctx -> List.of(order(ctx, ctx.root().get(field)));
    }

    // Once a sort joined metadata, break ties on its book_id (equal to book.id) so the ORDER BY
    // stays within one table and an index such as (title_sort, book_id) can deliver it.
    private static SortOrderBuilder<BookEntity> idTiebreaker() {
        return ctx -> {
            Join<?, ?> metadata = findSortJoin(ctx.root(), "metadata", JoinType.INNER);
            return List.of(order(ctx, metadata != null ? metadata.get("bookId") : ctx.root().get("id")));
        };
    }

    private static SortOrderBuilder<BookEntity> metadataField(String field) {
        return ctx -> List.of(order(ctx, metadataJoin(ctx).get(field)));
    }

    private static SortOrderBuilder<BookEntity> progressField(String field) {
        return ctx -> List.of(order(ctx, progressJoin(ctx).get(field)));
    }

    private static SortOrderBuilder<BookEntity> authorField(String field) {
        return ctx -> List.of(order(ctx, authorJoin(ctx).get(field)));
    }

    private static SortOrderBuilder<BookEntity> readingProgress() {
        return ctx -> {
            CriteriaBuilder cb = ctx.cb();
            var progress = progressJoin(ctx);
            Expression<Float> greatest = null;
            for (String field : PROGRESS_PERCENT_FIELDS) {
                Expression<Float> value = cb.coalesce(progress.get(field), cb.literal(0f));
                greatest = greatest == null ? value : greatestOf(cb, greatest, value);
            }
            return List.of(order(ctx, greatest));
        };
    }

    private static SortOrderBuilder<BookEntity> random() {
        return ctx -> {
            CriteriaBuilder cb = ctx.cb();

            // If the random seed isn't set (null) we hard code it to 0.  This means
            // that the "randomness" will never change until a new book is added.
            // This is a decent enough fallback because:
            // * We don't care if this isn't _that_ random, just that it's good enough
            // * It's a reasonable approach for most folks use cases.
            int randomSeed = ctx.randomSeed() == null ? 0 : ctx.randomSeed();

            // This is hard coded to the mariadb "RAND" function.  This has poor performance
            // on larger tables but gives a consistent sort every time.  This is done by
            // seeding the random number generator which we use for ordering.
            //
            // While just the seed for the pagination "session" is sufficient for MariaDB,
            // H2 will emit the same number every row - so we have to include the current ID.
            Expression<Double> random = cb.function(
                    "RAND",
                    Double.class,
                    cb.sum(cb.literal(randomSeed), ctx.root().get("id"))
            );
            return List.of(order(ctx, random));
        };
    }

    private static Expression<Float> greatestOf(CriteriaBuilder cb, Expression<Float> a, Expression<Float> b) {
        return cb.<Float>selectCase()
                .when(cb.greaterThanOrEqualTo(a, b), a)
                .otherwise(b);
    }

    private static Order order(SortContext<BookEntity> ctx, Expression<?> expression) {
        return ctx.descending() ? ctx.cb().desc(expression) : ctx.cb().asc(expression);
    }

    private static Join<?, ?> getSortJoin(From<?, ?> root, String name) {
        return getSortJoin(root, name, JoinType.LEFT);
    }

    private static Join<?, ?> getSortJoin(From<?, ?> root, String name, JoinType joinType) {
        Join<?, ?> existing = findSortJoin(root, name, joinType);
        if (existing != null) {
            return existing;
        }
        Join<?, ?> join = root.join(name, joinType);
        join.alias("sort_" + name);
        return join;
    }

    private static Join<?, ?> findSortJoin(From<?, ?> root, String name, JoinType joinType) {
        String alias = "sort_" + name;
        for (var join : root.getJoins()) {
            if (!alias.equals(join.getAlias())) {
                continue;
            }

            if (!name.equals(join.getAttribute().getName())) {
                continue;
            }

            if (!joinType.equals(join.getJoinType())) {
                continue;
            }

            return join;
        }
        return null;
    }

    // Inner: every book gets its metadata row in the same save, and an inner join lets the
    // optimiser drive from a book_metadata index instead of scanning book and filesorting.
    private static Join<?, ?> metadataJoin(SortContext<BookEntity> ctx) {
        Root<BookEntity> root = ctx.root();
        return getSortJoin(root, "metadata", JoinType.INNER);
    }

    private static Join<?, ?> progressJoin(SortContext<BookEntity> ctx) {
        Root<BookEntity> root = ctx.root();

        var join = getSortJoin(root, "userBookProgress");

        CriteriaBuilder cb = ctx.cb();
        Path<?> joinUserId = join.get("user").get("id");
        join.on(ctx.userId() != null ? cb.equal(joinUserId, ctx.userId()) : cb.disjunction());

        return join;
    }

    private static Join<?, ?> authorJoin(SortContext<BookEntity> ctx) {
        var parent = metadataJoin(ctx);

        return getSortJoin(parent, "authors");
    }
}
