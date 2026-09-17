package org.booklore.service.stats;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.response.LibraryAggregateBucket;
import org.booklore.model.dto.response.LibraryAuthorStat;
import org.booklore.model.dto.response.LibraryCrosstabCell;
import org.booklore.model.dto.response.LibraryHistogramBucket;
import org.booklore.model.dto.response.LibraryRatedBook;
import org.booklore.model.dto.response.LibrarySeriesStat;
import org.booklore.model.dto.response.LibrarySummary;
import org.booklore.model.dto.response.LibraryTimelineResponse;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.ReadStatus;
import org.booklore.service.browse.BookFilterSpecifications;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Every query here goes through BookFilterSpecifications.base(...) - the same scope/content-restriction
// predicate BookFacetService uses - so a library-stats endpoint can never see a book its caller cannot.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LibraryStatsService {

    private static final int MAX_AGGREGATE_VALUES = 200;

    private final AuthenticationService authenticationService;
    private final BookFilterSpecifications filterSpecifications;

    @PersistenceContext
    private EntityManager entityManager;

    // Keyed by userId + every call argument, so one user's library-filter switch never serves
    // another user's cached rows.
    private final Cache<String, Object> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(500)
            .build();

    private interface FieldExpr {
        Expression<?> apply(CriteriaBuilder cb, Root<BookEntity> root, Long userId);
    }

    private static final Map<String, FieldExpr> AGGREGATE_FIELDS = Map.ofEntries(
            Map.entry("file_type", (cb, root, userId) -> {
                Join<BookEntity, BookFileEntity> files = root.join("bookFiles", JoinType.LEFT);
                files.on(cb.isTrue(files.get("isBookFormat")));
                return files.get("bookType");
            }),
            Map.entry("language", (cb, root, userId) -> root.join("metadata", JoinType.LEFT).get("language")),
            Map.entry("publisher", (cb, root, userId) -> root.join("metadata", JoinType.LEFT).get("publisher")),
            Map.entry("series", (cb, root, userId) -> root.join("metadata", JoinType.LEFT).get("seriesName")),
            Map.entry("authors", (cb, root, userId) -> root.join("metadata", JoinType.LEFT).join("authors", JoinType.LEFT).get("name")),
            Map.entry("categories", (cb, root, userId) -> root.join("metadata", JoinType.LEFT).join("categories", JoinType.LEFT).get("name")),
            Map.entry("tags", (cb, root, userId) -> root.join("metadata", JoinType.LEFT).join("tags", JoinType.LEFT).get("name")),
            Map.entry("moods", (cb, root, userId) -> root.join("metadata", JoinType.LEFT).join("moods", JoinType.LEFT).get("name")),
            Map.entry("read_status", LibraryStatsService::readStatusExpr),
            Map.entry("personal_rating", (cb, root, userId) -> progressJoin(cb, root, userId).get("personalRating")),
            Map.entry("progress_percent", LibraryStatsService::progressBucketExpr)
    );

    private static Expression<ReadStatus> readStatusExpr(CriteriaBuilder cb, Root<BookEntity> root, Long userId) {
        Join<BookEntity, UserBookProgressEntity> progress = progressJoin(cb, root, userId);
        return cb.<ReadStatus>selectCase()
                .when(progress.get("id").isNull(), ReadStatus.UNSET)
                .otherwise(progress.get("readStatus"));
    }

    private static Join<BookEntity, UserBookProgressEntity> progressJoin(CriteriaBuilder cb, Root<BookEntity> root, Long userId) {
        Join<BookEntity, UserBookProgressEntity> progress = root.join("userBookProgress", JoinType.LEFT);
        progress.on(cb.equal(progress.get("user").get("id"), userId));
        return progress;
    }

    // Greatest of the five per-format progress columns, coalesced to 0 so GREATEST never sees a
    // null, then bucketed at the exact survival-curve thresholds so a reverse-cumulative sum on
    // the client reconstructs "% of started books still at or past each threshold" exactly.
    private static Expression<String> progressBucketExpr(CriteriaBuilder cb, Root<BookEntity> root, Long userId) {
        Join<BookEntity, UserBookProgressEntity> progress = progressJoin(cb, root, userId);
        Expression<Float> greatest = cb.function("GREATEST", Float.class,
                cb.coalesce(progress.get("koreaderProgressPercent"), 0f),
                cb.coalesce(progress.get("koboProgressPercent"), 0f),
                cb.coalesce(progress.get("epubProgressPercent"), 0f),
                cb.coalesce(progress.get("pdfProgressPercent"), 0f),
                cb.coalesce(progress.get("cbxProgressPercent"), 0f));
        // Unstarted books (progress exactly 0) return NULL so the caller's blanket isNotNull(value)
        // predicate excludes them. Buckets are labelled by their LOWER bound - [0,10),[10,25),...,
        // {100} - so survival(T) = sum of buckets whose label is >= T reconstructs the exact
        // per-book "count(progress >= T)" curve, not an approximation.
        return cb.<String>selectCase()
                .when(cb.equal(greatest, 0f), cb.nullLiteral(String.class))
                .when(cb.lt(greatest, 10f), "0")
                .when(cb.lt(greatest, 25f), "10")
                .when(cb.lt(greatest, 50f), "25")
                .when(cb.lt(greatest, 75f), "50")
                .when(cb.lt(greatest, 90f), "75")
                .when(cb.lt(greatest, 100f), "90")
                .otherwise("100");
    }

    private Specification<BookEntity> scope(Long libraryId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        boolean isAdmin = user.getPermissions().isAdmin();
        Set<Long> libraryIds = BookFilterSpecifications.libraryIds(user);
        Specification<BookEntity> base = filterSpecifications.base(null, Map.of(), null, userId, isAdmin, libraryIds, null);
        if (libraryId != null) {
            base = base.and((root, q, cb) -> cb.equal(root.get("library").get("id"), libraryId));
        }
        return base;
    }

    private Long currentUserId() {
        return authenticationService.getAuthenticatedUser().getId();
    }

    private String cacheKey(String op, Object... parts) {
        StringBuilder sb = new StringBuilder(currentUserId().toString()).append(':').append(op);
        for (Object part : parts) {
            sb.append(':').append(part);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- aggregate

    @SuppressWarnings("unchecked")
    public List<LibraryAggregateBucket> aggregate(String field, String breakdownBy, Long libraryId) {
        FieldExpr fieldExpr = requireField(field);
        FieldExpr breakdownExpr = breakdownBy == null ? null : requireField(breakdownBy);
        String key = cacheKey("aggregate", field, breakdownBy, libraryId);
        return (List<LibraryAggregateBucket>) cache.get(key, k -> computeAggregate(fieldExpr, breakdownExpr, libraryId));
    }

    private List<LibraryAggregateBucket> computeAggregate(FieldExpr fieldExpr, FieldExpr breakdownExpr, Long libraryId) {
        Long userId = currentUserId();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        Expression<?> value = fieldExpr.apply(cb, root, userId);
        Expression<?> breakdown = breakdownExpr == null ? null : breakdownExpr.apply(cb, root, userId);
        Expression<Long> count = cb.countDistinct(root.get("id"));

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(value));

        if (breakdown != null) {
            cq.multiselect(value.alias("value"), breakdown.alias("breakdown"), count.alias("count"));
            cq.groupBy(value, breakdown);
        } else {
            cq.multiselect(value.alias("value"), count.alias("count"));
            cq.groupBy(value);
        }
        cq.where(predicates.toArray(Predicate[]::new));
        cq.orderBy(cb.desc(count));

        TypedQuery<Tuple> query = entityManager.createQuery(cq);
        query.setMaxResults(MAX_AGGREGATE_VALUES * (breakdown != null ? 20 : 1));
        List<Tuple> rows = query.getResultList();

        if (breakdown == null) {
            return rows.stream()
                    .map(t -> LibraryAggregateBucket.builder()
                            .value(String.valueOf(t.get("value")))
                            .count(((Number) t.get("count")).longValue())
                            .build())
                    .limit(MAX_AGGREGATE_VALUES)
                    .toList();
        }

        Map<String, List<LibraryAggregateBucket>> byValue = new LinkedHashMap<>();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Tuple t : rows) {
            String v = String.valueOf(t.get("value"));
            String b = String.valueOf(t.get("breakdown"));
            long c = ((Number) t.get("count")).longValue();
            byValue.computeIfAbsent(v, k -> new ArrayList<>())
                    .add(LibraryAggregateBucket.builder().value(b).count(c).build());
            totals.merge(v, c, Long::sum);
        }
        return totals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(MAX_AGGREGATE_VALUES)
                .map(e -> LibraryAggregateBucket.builder()
                        .value(e.getKey())
                        .count(e.getValue())
                        .breakdown(byValue.get(e.getKey()))
                        .build())
                .toList();
    }

    private FieldExpr requireField(String field) {
        FieldExpr expr = AGGREGATE_FIELDS.get(field);
        if (expr == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Unknown stats field: " + field);
        }
        return expr;
    }

    // ---------------------------------------------------------------- histogram

    private record RangeDef(String label, float min, float max) {
    }

    private static final List<RangeDef> PAGE_COUNT_RANGES = List.of(
            new RangeDef("0-100", 0, 100), new RangeDef("101-200", 101, 200),
            new RangeDef("201-300", 201, 300), new RangeDef("301-500", 301, 500),
            new RangeDef("501-750", 501, 750), new RangeDef("751-1000", 751, 1000),
            new RangeDef("1000+", 1001, Float.MAX_VALUE));

    private static final List<RangeDef> METADATA_SCORE_RANGES = List.of(
            new RangeDef("veryPoor", 0, 24), new RangeDef("poor", 25, 49),
            new RangeDef("fair", 50, 69), new RangeDef("good", 70, 89),
            new RangeDef("excellent", 90, 100));

    @SuppressWarnings("unchecked")
    public List<LibraryHistogramBucket> histogram(String field, Long libraryId) {
        String key = cacheKey("histogram", field, libraryId);
        return (List<LibraryHistogramBucket>) cache.get(key, k -> computeHistogram(field, libraryId));
    }

    private List<LibraryHistogramBucket> computeHistogram(String field, Long libraryId) {
        return switch (field) {
            case "page_count" -> computeHistogram(PAGE_COUNT_RANGES,
                    (cb, root) -> root.join("metadata", JoinType.LEFT).<Number>get("pageCount"), libraryId, 0f);
            case "metadata_score" -> computeHistogram(METADATA_SCORE_RANGES,
                    (cb, root) -> root.<Number>get("metadataMatchScore"), libraryId, 0f);
            default -> throw ApiError.GENERIC_BAD_REQUEST.createException("Unknown histogram field: " + field);
        };
    }

    private interface NumericExpr {
        Expression<Number> apply(CriteriaBuilder cb, Root<BookEntity> root);
    }

    private List<LibraryHistogramBucket> computeHistogram(List<RangeDef> ranges, NumericExpr numericExpr, Long libraryId, float minAllowed) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        Expression<Number> value = numericExpr.apply(cb, root);

        CriteriaBuilder.Case<String> caseExpr = cb.selectCase();
        for (RangeDef range : ranges) {
            caseExpr = caseExpr.when(cb.and(cb.ge(value, range.min()), cb.le(value, range.max())), range.label());
        }
        Expression<String> bucket = caseExpr.otherwise((String) null);

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(value));
        predicates.add(cb.ge(value, minAllowed));

        Expression<Long> count = cb.countDistinct(root.get("id"));
        cq.multiselect(bucket.alias("bucket"), count.alias("count"));
        cq.where(predicates.toArray(Predicate[]::new));
        cq.groupBy(bucket);

        Map<String, Long> counts = entityManager.createQuery(cq).getResultList().stream()
                .filter(t -> t.get("bucket") != null)
                .collect(Collectors.toMap(t -> (String) t.get("bucket"), t -> ((Number) t.get("count")).longValue()));

        return ranges.stream()
                .map(range -> LibraryHistogramBucket.builder()
                        .range(range.label())
                        .min((int) range.min())
                        .max(range.max() == Float.MAX_VALUE ? Integer.MAX_VALUE : (int) range.max())
                        .count(counts.getOrDefault(range.label(), 0L))
                        .build())
                .toList();
    }

    // ---------------------------------------------------------------- timeline

    @SuppressWarnings("unchecked")
    public LibraryTimelineResponse timeline(String field, String granularity, Long libraryId) {
        String key = cacheKey("timeline", field, granularity, libraryId);
        return (LibraryTimelineResponse) cache.get(key, k -> computeTimeline(field, granularity, libraryId));
    }

    private LibraryTimelineResponse computeTimeline(String field, String granularity, Long libraryId) {
        boolean byMonth = "month".equals(granularity);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        Long userId = currentUserId();

        Expression<?> dateExpr = switch (field) {
            case "published_date" -> root.join("metadata", JoinType.LEFT).get("publishedDate");
            case "added_on" -> root.get("addedOn");
            case "date_finished" -> progressJoin(cb, root, userId).get("dateFinished");
            default -> throw ApiError.GENERIC_BAD_REQUEST.createException("Unknown timeline field: " + field);
        };

        // Groups by YEAR()/MONTH() and formats "YYYY" or "YYYY-MM" in Java rather than a
        // DATE_FORMAT() SQL call, which MariaDB has but the H2 test dialect does not.
        Expression<Integer> year = cb.function("YEAR", Integer.class, dateExpr);
        Expression<Integer> month = byMonth ? cb.function("MONTH", Integer.class, dateExpr) : cb.literal(0);

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(dateExpr));

        Expression<Long> count = cb.countDistinct(root.get("id"));
        cq.multiselect(year.alias("year"), month.alias("month"), count.alias("count"));
        cq.where(predicates.toArray(Predicate[]::new));
        List<Expression<?>> grouping = new ArrayList<>();
        grouping.add(year);
        if (byMonth) {
            grouping.add(month);
        }
        cq.groupBy(grouping);
        cq.orderBy(cb.asc(year), cb.asc(month));

        List<LibraryTimelineResponse.Bucket> buckets = entityManager.createQuery(cq).getResultList().stream()
                .map(t -> LibraryTimelineResponse.Bucket.builder()
                        .period(formatPeriod((Integer) t.get("year"), byMonth ? (Integer) t.get("month") : null))
                        .count(((Number) t.get("count")).longValue())
                        .build())
                .toList();

        LibraryTimelineResponse.TitleYear oldest = null;
        LibraryTimelineResponse.TitleYear newest = null;
        Double avgDaysToFinish = null;
        if ("published_date".equals(field)) {
            oldest = titleYearEdge(libraryId, true);
            newest = titleYearEdge(libraryId, false);
        } else if ("date_finished".equals(field)) {
            avgDaysToFinish = computeAvgDaysToFinish(libraryId, userId);
        }

        return LibraryTimelineResponse.builder()
                .buckets(buckets)
                .oldest(oldest)
                .newest(newest)
                .avgDaysToFinish(avgDaysToFinish)
                .build();
    }

    private LibraryTimelineResponse.TitleYear titleYearEdge(Long libraryId, boolean oldest) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        var metadata = root.join("metadata", JoinType.LEFT);
        Expression<LocalDate> publishedDate = metadata.get("publishedDate");

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(publishedDate));

        cq.multiselect(metadata.get("title").alias("title"),
                cb.function("YEAR", Integer.class, publishedDate).alias("year"));
        cq.where(predicates.toArray(Predicate[]::new));
        cq.orderBy(oldest ? cb.asc(publishedDate) : cb.desc(publishedDate));

        List<Tuple> result = entityManager.createQuery(cq).setMaxResults(1).getResultList();
        if (result.isEmpty()) {
            return null;
        }
        Tuple row = result.get(0);
        return LibraryTimelineResponse.TitleYear.builder()
                .title((String) row.get("title"))
                .year((Integer) row.get("year"))
                .build();
    }

    // Java-side averaging (not DATEDIFF, whose argument order and support differ between
    // MariaDB and the H2 test dialect) over a lean two-column projection.
    private Double computeAvgDaysToFinish(Long libraryId, Long userId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        Join<BookEntity, UserBookProgressEntity> progress = progressJoin(cb, root, userId);
        Expression<Instant> addedOn = root.get("addedOn");
        Expression<Instant> dateFinished = progress.get("dateFinished");

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(addedOn));
        predicates.add(cb.isNotNull(dateFinished));
        predicates.add(cb.greaterThanOrEqualTo(dateFinished, addedOn));

        cq.multiselect(addedOn.alias("addedOn"), dateFinished.alias("dateFinished"));
        cq.where(predicates.toArray(Predicate[]::new));

        List<Tuple> rows = entityManager.createQuery(cq).getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        double totalDays = 0;
        for (Tuple row : rows) {
            Instant added = row.get("addedOn", Instant.class);
            Instant finished = row.get("dateFinished", Instant.class);
            totalDays += Duration.between(added, finished).toDays();
        }
        return totalDays / rows.size();
    }

    private static String formatPeriod(Integer year, Integer month) {
        if (month == null) {
            return String.valueOf(year);
        }
        return String.format("%d-%02d", year, month);
    }

    // ---------------------------------------------------------------- authors

    @SuppressWarnings("unchecked")
    public List<LibraryAuthorStat> authors(int limit, Long libraryId) {
        String key = cacheKey("authors", limit, libraryId);
        return (List<LibraryAuthorStat>) cache.get(key, k -> computeAuthors(limit, libraryId));
    }

    // Category count comes from a SEPARATE grouped query - joining categories (many-to-many)
    // alongside SUM(pageCount)/AVG(rating) in one query would fan each book out once per
    // category and inflate those sums, so the two are computed independently and merged here.
    private List<LibraryAuthorStat> computeAuthors(int limit, Long libraryId) {
        Long userId = currentUserId();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        var authorJoin = root.join("metadata", JoinType.LEFT).join("authors", JoinType.LEFT);
        Join<BookEntity, UserBookProgressEntity> progress = progressJoin(cb, root, userId);

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(authorJoin.get("name")));

        Expression<ReadStatus> statusCase = cb.<ReadStatus>selectCase()
                .when(progress.get("id").isNull(), ReadStatus.UNSET)
                .otherwise(progress.get("readStatus"));

        cq.multiselect(
                authorJoin.get("name").alias("author"),
                cb.countDistinct(root.get("id")).alias("bookCount"),
                cb.sumAsLong(cb.coalesce(root.join("metadata", JoinType.LEFT).<Integer>get("pageCount"), 0)).alias("totalPages"),
                cb.avg(progress.get("personalRating")).alias("avgRating"),
                cb.sum(cb.<Long>selectCase().when(cb.equal(statusCase, ReadStatus.READ), 1L).otherwise(0L)).alias("readCount"));
        cq.where(predicates.toArray(Predicate[]::new));
        cq.groupBy(authorJoin.get("name"));
        cq.orderBy(cb.desc(cb.countDistinct(root.get("id"))));

        List<Tuple> rows = entityManager.createQuery(cq).setMaxResults(limit).getResultList();
        Map<String, Long> categoryCounts = authorDistinctCategoryCounts(libraryId, rows.stream().map(t -> (String) t.get("author")).toList());

        return rows.stream()
                .map(t -> {
                    String author = (String) t.get("author");
                    return LibraryAuthorStat.builder()
                            .author(author)
                            .bookCount(((Number) t.get("bookCount")).longValue())
                            .totalPages(((Number) t.get("totalPages")).longValue())
                            .avgRating(t.get("avgRating") == null ? null : ((Number) t.get("avgRating")).doubleValue())
                            .readCount(((Number) t.get("readCount")).longValue())
                            .distinctCategories(categoryCounts.getOrDefault(author, 0L))
                            .build();
                })
                .toList();
    }

    private Map<String, Long> authorDistinctCategoryCounts(Long libraryId, List<String> authorNames) {
        if (authorNames.isEmpty()) {
            return Map.of();
        }
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        var authorJoin = root.join("metadata", JoinType.LEFT).join("authors", JoinType.LEFT);
        var categoryJoin = root.join("metadata", JoinType.LEFT).join("categories", JoinType.LEFT);

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(authorJoin.get("name").in(authorNames));
        predicates.add(cb.isNotNull(categoryJoin.get("id")));

        cq.multiselect(authorJoin.get("name").alias("author"), cb.countDistinct(categoryJoin.get("id")).alias("count"));
        cq.where(predicates.toArray(Predicate[]::new));
        cq.groupBy(authorJoin.get("name"));

        return entityManager.createQuery(cq).getResultList().stream()
                .collect(Collectors.toMap(t -> (String) t.get("author"), t -> ((Number) t.get("count")).longValue()));
    }

    // ---------------------------------------------------------------- series

    @SuppressWarnings("unchecked")
    public List<LibrarySeriesStat> series(int limit, Long libraryId) {
        String key = cacheKey("series", limit, libraryId);
        return (List<LibrarySeriesStat>) cache.get(key, k -> computeSeries(limit, libraryId));
    }

    private List<LibrarySeriesStat> computeSeries(int limit, Long libraryId) {
        Long userId = currentUserId();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        var metadata = root.join("metadata", JoinType.LEFT);
        Join<BookEntity, UserBookProgressEntity> progress = progressJoin(cb, root, userId);

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(metadata.get("seriesName")));

        Expression<ReadStatus> statusCase = cb.<ReadStatus>selectCase()
                .when(progress.get("id").isNull(), ReadStatus.UNSET)
                .otherwise(progress.get("readStatus"));

        cq.multiselect(
                metadata.get("seriesName").alias("seriesName"),
                cb.countDistinct(root.get("id")).alias("bookCount"),
                cb.max(metadata.get("seriesTotal")).alias("seriesTotal"),
                cb.sum(cb.<Long>selectCase().when(cb.equal(statusCase, ReadStatus.READ), 1L).otherwise(0L)).alias("readCount"),
                cb.sum(cb.<Long>selectCase()
                        .when(cb.equal(statusCase, ReadStatus.READING), 1L)
                        .when(cb.equal(statusCase, ReadStatus.RE_READING), 1L)
                        .otherwise(0L)).alias("readingCount"),
                cb.sum(cb.<Long>selectCase().when(cb.equal(statusCase, ReadStatus.PARTIALLY_READ), 1L).otherwise(0L)).alias("partiallyReadCount"),
                cb.sum(cb.<Long>selectCase().when(cb.equal(statusCase, ReadStatus.PAUSED), 1L).otherwise(0L)).alias("pausedCount"),
                cb.sum(cb.<Long>selectCase().when(cb.equal(statusCase, ReadStatus.ABANDONED), 1L).otherwise(0L)).alias("abandonedCount"),
                cb.sum(cb.<Long>selectCase().when(cb.equal(statusCase, ReadStatus.WONT_READ), 1L).otherwise(0L)).alias("wontReadCount"),
                cb.sum(cb.<Long>selectCase()
                        .when(cb.equal(statusCase, ReadStatus.UNREAD), 1L)
                        .when(cb.equal(statusCase, ReadStatus.UNSET), 1L)
                        .otherwise(0L)).alias("unreadCount"),
                cb.avg(progress.get("personalRating")).alias("avgPersonalRating"));
        cq.where(predicates.toArray(Predicate[]::new));
        cq.groupBy(metadata.get("seriesName"));
        cq.orderBy(cb.desc(cb.countDistinct(root.get("id"))));

        List<Tuple> rows = entityManager.createQuery(cq).setMaxResults(limit).getResultList();
        return rows.stream()
                .map(t -> {
                    String seriesName = (String) t.get("seriesName");
                    return LibrarySeriesStat.builder()
                            .seriesName(seriesName)
                            .bookCount(((Number) t.get("bookCount")).longValue())
                            .seriesTotal((Integer) t.get("seriesTotal"))
                            .readCount(((Number) t.get("readCount")).longValue())
                            .readingCount(((Number) t.get("readingCount")).longValue())
                            .partiallyReadCount(((Number) t.get("partiallyReadCount")).longValue())
                            .pausedCount(((Number) t.get("pausedCount")).longValue())
                            .abandonedCount(((Number) t.get("abandonedCount")).longValue())
                            .wontReadCount(((Number) t.get("wontReadCount")).longValue())
                            .unreadCount(((Number) t.get("unreadCount")).longValue())
                            .nextUnreadTitle(nextUnreadTitle(seriesName, userId, libraryId))
                            .avgPersonalRating(t.get("avgPersonalRating") == null ? null : ((Number) t.get("avgPersonalRating")).doubleValue())
                            .avgExternalRating(avgExternalRatingForSeries(seriesName, libraryId))
                            .build();
                })
                .toList();
    }

    private String nextUnreadTitle(String seriesName, Long userId, Long libraryId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        var metadata = root.join("metadata", JoinType.LEFT);
        Join<BookEntity, UserBookProgressEntity> progress = progressJoin(cb, root, userId);
        Expression<ReadStatus> statusCase = cb.<ReadStatus>selectCase()
                .when(progress.get("id").isNull(), ReadStatus.UNSET)
                .otherwise(progress.get("readStatus"));

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.equal(metadata.get("seriesName"), seriesName));
        predicates.add(cb.notEqual(statusCase, ReadStatus.READ));

        cq.multiselect(metadata.get("title").alias("title"));
        cq.where(predicates.toArray(Predicate[]::new));
        cq.orderBy(cb.asc(metadata.get("seriesNumber")));
        List<Tuple> result = entityManager.createQuery(cq).setMaxResults(1).getResultList();
        return result.isEmpty() ? null : (String) result.get(0).get("title");
    }

    private Double avgExternalRatingForSeries(String seriesName, Long libraryId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Double> cq = cb.createQuery(Double.class);
        Root<BookEntity> root = cq.from(BookEntity.class);
        var metadata = root.join("metadata", JoinType.LEFT);

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.equal(metadata.get("seriesName"), seriesName));

        cq.select(cb.avg(externalRatingAvgExpr(cb, metadata)));
        cq.where(predicates.toArray(Predicate[]::new));
        return entityManager.createQuery(cq).getSingleResult();
    }

    // Average of every non-null external provider rating on the book - mirrors the client's own
    // getExternalRating() fallback chain (goodreads/amazon/hardcover/lubimyczytac/ranobedb).
    private static Expression<Double> externalRatingAvgExpr(CriteriaBuilder cb, jakarta.persistence.criteria.Path<?> metadata) {
        Expression<Double> goodreads = metadata.get("goodreadsRating");
        Expression<Double> amazon = metadata.get("amazonRating");
        Expression<Double> hardcover = metadata.get("hardcoverRating");
        Expression<Double> lubimyczytac = metadata.get("lubimyczytacRating");
        Expression<Double> ranobedb = metadata.get("ranobedbRating");

        Expression<Double> sum = cb.sum(cb.sum(cb.sum(cb.sum(
                cb.coalesce(goodreads, 0.0), cb.coalesce(amazon, 0.0)),
                cb.coalesce(hardcover, 0.0)), cb.coalesce(lubimyczytac, 0.0)), cb.coalesce(ranobedb, 0.0));
        Expression<Long> presentCount = cb.sum(cb.sum(cb.sum(cb.sum(
                presentFlag(cb, goodreads), presentFlag(cb, amazon)),
                presentFlag(cb, hardcover)), presentFlag(cb, lubimyczytac)), presentFlag(cb, ranobedb));

        return cb.<Double>selectCase()
                .when(cb.equal(presentCount, 0L), cb.nullLiteral(Double.class))
                .otherwise(cb.quot(sum, presentCount).as(Double.class));
    }

    private static Expression<Long> presentFlag(CriteriaBuilder cb, Expression<Double> rating) {
        return cb.<Long>selectCase().when(cb.isNotNull(rating), 1L).otherwise(0L);
    }

    // ---------------------------------------------------------------- rated books

    @SuppressWarnings("unchecked")
    public List<LibraryRatedBook> ratedBooks(Long libraryId) {
        String key = cacheKey("ratedBooks", libraryId);
        return (List<LibraryRatedBook>) cache.get(key, k -> computeRatedBooks(libraryId));
    }

    private List<LibraryRatedBook> computeRatedBooks(Long libraryId) {
        Long userId = currentUserId();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        var metadata = root.join("metadata", JoinType.LEFT);
        Join<BookEntity, UserBookProgressEntity> progress = progressJoin(cb, root, userId);
        Expression<ReadStatus> statusCase = cb.<ReadStatus>selectCase()
                .when(progress.get("id").isNull(), ReadStatus.UNSET)
                .otherwise(progress.get("readStatus"));

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(progress.get("personalRating")));

        cq.multiselect(
                root.get("id").alias("bookId"),
                metadata.get("title").alias("title"),
                metadata.get("pageCount").alias("pageCount"),
                progress.get("personalRating").alias("personalRating"),
                externalRatingAvgExpr(cb, metadata).alias("externalRatingAvg"),
                statusCase.alias("readStatus"),
                cb.function("YEAR", Integer.class, metadata.get("publishedDate")).alias("publishedYear"),
                root.get("addedOn").alias("addedOn"));
        cq.where(predicates.toArray(Predicate[]::new));
        cq.distinct(true);

        return entityManager.createQuery(cq).getResultList().stream()
                .map(t -> LibraryRatedBook.builder()
                        .bookId((Long) t.get("bookId"))
                        .title((String) t.get("title"))
                        .pageCount((Integer) t.get("pageCount"))
                        .personalRating((Integer) t.get("personalRating"))
                        .externalRatingAvg((Double) t.get("externalRatingAvg"))
                        .readStatus(String.valueOf(t.get("readStatus")))
                        .publishedYear((Integer) t.get("publishedYear"))
                        .addedOn(t.get("addedOn") == null ? null : t.get("addedOn", Instant.class).atZone(ZoneOffset.UTC).toLocalDate().toString())
                        .build())
                .toList();
    }

    // ---------------------------------------------------------------- crosstab

    private static final Set<String> CROSSTAB_ROW_FIELDS = Set.of("added_quarter", "read_status");
    private static final Set<String> CROSSTAB_COL_FIELDS = Set.of("read_status", "personal_rating_bucket");

    @SuppressWarnings("unchecked")
    public List<LibraryCrosstabCell> crosstab(String rowField, String colField, Long libraryId) {
        if (!CROSSTAB_ROW_FIELDS.contains(rowField) || !CROSSTAB_COL_FIELDS.contains(colField) || rowField.equals(colField)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Unsupported crosstab: " + rowField + "x" + colField);
        }
        String key = cacheKey("crosstab", rowField, colField, libraryId);
        return (List<LibraryCrosstabCell>) cache.get(key, k -> computeCrosstab(rowField, colField, libraryId));
    }

    // Both allow-listed crosstabs need at most a two-column projection per book, never the full
    // Book DTO - a lean raw fetch bucketed in Java, not a second book-facing endpoint.
    private List<LibraryCrosstabCell> computeCrosstab(String rowField, String colField, Long libraryId) {
        Long userId = currentUserId();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        Join<BookEntity, UserBookProgressEntity> progress = progressJoin(cb, root, userId);
        Expression<ReadStatus> statusCase = cb.<ReadStatus>selectCase()
                .when(progress.get("id").isNull(), ReadStatus.UNSET)
                .otherwise(progress.get("readStatus"));

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }

        cq.multiselect(
                root.get("addedOn").alias("addedOn"),
                statusCase.alias("readStatus"),
                progress.get("personalRating").alias("personalRating"));
        cq.where(predicates.toArray(Predicate[]::new));

        Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
        for (Tuple row : entityManager.createQuery(cq).getResultList()) {
            Instant addedOn = row.get("addedOn", Instant.class);
            ReadStatus status = (ReadStatus) row.get("readStatus");
            Integer rating = (Integer) row.get("personalRating");

            String rowValue = "added_quarter".equals(rowField) ? quarterLabel(addedOn) : statusLabel(status);
            String colValue = "read_status".equals(colField) ? statusLabel(status) : ratingBucketLabel(rating);

            counts.computeIfAbsent(rowValue, k -> new LinkedHashMap<>()).merge(colValue, 1L, Long::sum);
        }

        List<LibraryCrosstabCell> cells = new ArrayList<>();
        counts.forEach((row, cols) -> cols.forEach((col, count) ->
                cells.add(LibraryCrosstabCell.builder().row(row).col(col).count(count).build())));
        return cells;
    }

    private static String quarterLabel(Instant addedOn) {
        if (addedOn == null) {
            return "Unknown";
        }
        var date = addedOn.atZone(ZoneOffset.UTC);
        int quarter = (date.getMonthValue() - 1) / 3 + 1;
        return date.getYear() + " Q" + quarter;
    }

    // Mirrors book-flow-chart's own status grouping so the two migrated crosstab calls line up
    // with the Sankey's existing node labels.
    private static String statusLabel(ReadStatus status) {
        if (status == null) {
            return "Other";
        }
        return switch (status) {
            case READ -> "Read";
            case READING, RE_READING -> "Reading";
            case UNREAD, UNSET -> "Unread";
            case PAUSED -> "Paused";
            case ABANDONED, WONT_READ -> "Abandoned";
            default -> "Other";
        };
    }

    private static String ratingBucketLabel(Integer rating) {
        if (rating == null || rating <= 0) {
            return "Unrated";
        }
        double normalized = rating / 2.0;
        if (normalized >= 4) return "Rated 4-5";
        if (normalized >= 3) return "Rated 3";
        return "Rated 1-2";
    }

    // ---------------------------------------------------------------- summary

    @SuppressWarnings("unchecked")
    public LibrarySummary summary(Long libraryId) {
        String key = cacheKey("summary", libraryId);
        return (LibrarySummary) cache.get(key, k -> computeSummary(libraryId));
    }

    // totalSizeKb is a SUM, not a COUNT(DISTINCT ...), so it runs as its own query - joined
    // alongside the many-to-many authors join below it would be inflated once per author on a
    // book, the way computeAuthors()'s totalPages would be inflated by a category join.
    private LibrarySummary computeSummary(Long libraryId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        var metadata = root.join("metadata", JoinType.LEFT);
        var authorJoin = metadata.join("authors", JoinType.LEFT);

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }

        cq.multiselect(
                cb.countDistinct(root.get("id")).alias("totalBooks"),
                cb.countDistinct(authorJoin.get("id")).alias("distinctAuthors"),
                cb.countDistinct(metadata.get("seriesName")).alias("distinctSeries"),
                cb.countDistinct(metadata.get("publisher")).alias("distinctPublishers"));
        cq.where(predicates.toArray(Predicate[]::new));

        Tuple row = entityManager.createQuery(cq).getSingleResult();
        return LibrarySummary.builder()
                .totalBooks(((Number) row.get("totalBooks")).longValue())
                .totalSizeKb(computeTotalSizeKb(libraryId))
                .distinctAuthors(((Number) row.get("distinctAuthors")).longValue())
                .distinctSeries(((Number) row.get("distinctSeries")).longValue())
                .distinctPublishers(((Number) row.get("distinctPublishers")).longValue())
                .build();
    }

    // ponytail: sums every is-book-format file rather than resolving the library's format
    // priority per book (BookEntity#getPrimaryBookFile is Java-side); revisit if a book with
    // two ebook formats skews the total noticeably.
    private long computeTotalSizeKb(Long libraryId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<BookEntity> root = cq.from(BookEntity.class);
        var files = root.join("bookFiles", JoinType.LEFT);
        files.on(cb.isTrue(files.get("isBookFormat")));

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = scope(libraryId).toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }

        cq.select(cb.sum(cb.coalesce(files.<Long>get("fileSizeKb"), 0L)));
        cq.where(predicates.toArray(Predicate[]::new));
        Long result = entityManager.createQuery(cq).getSingleResult();
        return result == null ? 0L : result;
    }
}
