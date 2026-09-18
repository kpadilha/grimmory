package org.booklore.service.browse;

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
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.booklore.browse.FacetLogic;
import org.booklore.browse.Link;
import org.booklore.browse.ParamsHash;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.browse.FacetGroupsResponse;
import org.booklore.model.dto.browse.FacetGroupsResponse.FacetGroup;
import org.booklore.model.dto.browse.FacetGroupsResponse.FacetLink;
import org.booklore.model.dto.browse.FacetGroupsResponse.Metadata;
import org.booklore.model.dto.browse.FacetGroupsResponse.Properties;
import org.booklore.model.dto.browse.FacetValueBookIds;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.ComicCreatorRole;
import org.booklore.model.enums.ReadStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookFacetService {

    private static final String PAGE_PATH = "/api/v1/books/page";
    private static final String FACET_PATH = "/api/v1/books/facets";
    private static final int MAX_VALUES = 100;

    // Domain size is bounded by how many libraries/shelves exist, not by book count, so the
    // per-value listing never needs the cap - the sidebar Map stays complete without a second query.
    private static final Set<String> UNCAPPED_FACETS = Set.of("library", "shelf");
    // High-cardinality group where the sidebar needs an exact total distinct from the capped list.
    private static final Set<String> DISTINCT_COUNT_FACETS = Set.of("series");

    private static final List<FacetDef> FACETS = List.of(
            new FacetDef("author", "Authors", (cb, root, userId) -> metadata(root).join("authors", JoinType.LEFT).get("name")),
            new FacetDef("genre", "Genre", (cb, root, userId) -> metadata(root).join("categories", JoinType.LEFT).get("name")),
            new FacetDef("tag", "Tags", (cb, root, userId) -> metadata(root).join("tags", JoinType.LEFT).get("name")),
            new FacetDef("mood", "Moods", (cb, root, userId) -> metadata(root).join("moods", JoinType.LEFT).get("name")),
            new FacetDef("series", "Series", (cb, root, userId) -> metadata(root).get("seriesName")),
            new FacetDef("publisher", "Publisher", (cb, root, userId) -> metadata(root).get("publisher")),
            new FacetDef("language", "Language", (cb, root, userId) -> metadata(root).get("language")),
            new FacetDef("narrator", "Narrator", (cb, root, userId) -> metadata(root).get("narrator")),
            // Physical books carry no bookFiles row, so file_type joins bookFiles and layers
            // isPhysical on top - otherwise "PHYSICAL" never gets a facet value at all.
            new FacetDef("file_type", "File Type", (cb, root, userId) -> {
                Path<?> bookType = root.join("bookFiles", JoinType.LEFT).get("bookType");
                return cb.<String>selectCase()
                        .when(cb.isTrue(root.get("isPhysical")), "PHYSICAL")
                        .otherwise(bookType.as(String.class));
            }),
            new FacetDef("content_rating", "Content Rating", (cb, root, userId) -> metadata(root).get("contentRating")),
            new FacetDef("amazon_rating", "Amazon Rating", (cb, root, userId) -> bucketExpr(cb, metadata(root).<Double>get("amazonRating"), NumericFacetBuckets.RATING_5)),
            new FacetDef("goodreads_rating", "Goodreads Rating", (cb, root, userId) -> bucketExpr(cb, metadata(root).<Double>get("goodreadsRating"), NumericFacetBuckets.RATING_5)),
            new FacetDef("hardcover_rating", "Hardcover Rating", (cb, root, userId) -> bucketExpr(cb, metadata(root).<Double>get("hardcoverRating"), NumericFacetBuckets.RATING_5)),
            new FacetDef("ranobedb_rating", "RanobeDB Rating", (cb, root, userId) -> bucketExpr(cb, metadata(root).<Double>get("ranobedbRating"), NumericFacetBuckets.RATING_5)),
            new FacetDef("lubimyczytac_rating", "Lubimyczytac Rating", (cb, root, userId) -> bucketExpr(cb, metadata(root).<Double>get("lubimyczytacRating"), NumericFacetBuckets.RATING_5)),
            new FacetDef("audible_rating", "Audible Rating", (cb, root, userId) -> bucketExpr(cb, metadata(root).<Double>get("audibleRating"), NumericFacetBuckets.RATING_5)),
            new FacetDef("applebooks_rating", "Apple Books Rating", (cb, root, userId) -> metadata(root).get("applebooksRating")),
            new FacetDef("age_rating", "Age Rating", (cb, root, userId) -> bucketExpr(cb, metadata(root).<Integer>get("ageRating"), NumericFacetBuckets.AGE_RATING)),
            new FacetDef("page_count", "Page Count", (cb, root, userId) -> bucketExpr(cb, metadata(root).<Integer>get("pageCount"), NumericFacetBuckets.PAGE_COUNT)),
            new FacetDef("match_score", "Match Score", (cb, root, userId) -> bucketExpr(cb, root.<Float>get("metadataMatchScore"), NumericFacetBuckets.MATCH_SCORE)),
            new FacetDef("published_year", "Published Year", (cb, root, userId) ->
                    cb.function("YEAR", Integer.class, metadata(root).get("publishedDate"))),
            new FacetDef("library", "Library", (cb, root, userId) -> root.join("library").get("id")),
            new FacetDef("shelf", "Shelf", (cb, root, userId) -> root.join("shelves", JoinType.LEFT).get("id")),
            new FacetDef("shelf_status", "Shelf Status", (cb, root, userId) -> cb.<String>selectCase()
                    .when(cb.isNotEmpty(root.get("shelves")), "shelved")
                    .otherwise("unshelved")),
            new FacetDef("read_status", "Read Status", (cb, root, userId) -> {
                Join<BookEntity, UserBookProgressEntity> progress = root.join("userBookProgress", JoinType.LEFT);
                progress.on(cb.equal(progress.get("user").get("id"), userId));
                return cb.<ReadStatus>selectCase()
                        .when(progress.get("id").isNull(), ReadStatus.UNSET)
                        .otherwise(progress.get("readStatus"));
            }),
            new FacetDef("personal_rating", "Personal Rating", (cb, root, userId) -> {
                Join<BookEntity, UserBookProgressEntity> progress = root.join("userBookProgress");
                progress.on(cb.equal(progress.get("user").get("id"), userId));
                return progress.get("personalRating");
            }),
            new FacetDef("file_size", "File Size", (cb, root, userId) -> {
                Join<BookEntity, BookFileEntity> files = root.join("bookFiles", JoinType.LEFT);
                files.on(cb.isTrue(files.get("isBookFormat")));
                return bucketExpr(cb, files.<Long>get("fileSizeKb"), NumericFacetBuckets.FILE_SIZE);
            }),
            new FacetDef("comic_character", "Comic Characters", (cb, root, userId) -> metadata(root).join("comicMetadata", JoinType.LEFT).join("characters", JoinType.LEFT).get("name")),
            new FacetDef("comic_team", "Comic Teams", (cb, root, userId) -> metadata(root).join("comicMetadata", JoinType.LEFT).join("teams", JoinType.LEFT).get("name")),
            new FacetDef("comic_location", "Comic Locations", (cb, root, userId) -> metadata(root).join("comicMetadata", JoinType.LEFT).join("locations", JoinType.LEFT).get("name")),
            // Grouped by name alone this loses penciller/inker/... - "name:role" matches the
            // frontend's own composite key (and what AppBookSpecification.withComicCreators parses).
            new FacetDef("comic_creator", "Comic Creators", (cb, root, userId) -> {
                Join<?, ?> mapping = metadata(root).join("comicMetadata", JoinType.LEFT).join("creatorMappings", JoinType.LEFT);
                Join<?, ?> creator = mapping.join("creator", JoinType.LEFT);
                Expression<String> role = cb.<String>selectCase()
                        .when(cb.equal(mapping.get("role"), ComicCreatorRole.PENCILLER), "penciller")
                        .when(cb.equal(mapping.get("role"), ComicCreatorRole.INKER), "inker")
                        .when(cb.equal(mapping.get("role"), ComicCreatorRole.COLORIST), "colorist")
                        .when(cb.equal(mapping.get("role"), ComicCreatorRole.LETTERER), "letterer")
                        .when(cb.equal(mapping.get("role"), ComicCreatorRole.COVER_ARTIST), "coverArtist")
                        .when(cb.equal(mapping.get("role"), ComicCreatorRole.EDITOR), "editor")
                        .otherwise(cb.nullLiteral(String.class));
                return cb.concat(cb.concat(creator.<String>get("name"), cb.literal(":")), role);
            }));

    private final AuthenticationService authenticationService;
    private final BookFilterSpecifications filterSpecifications;
    private final BookSortRegistry sortRegistry;

    @PersistenceContext
    private EntityManager entityManager;

    private final Cache<String, FacetGroupsResponse> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(200)
            .build();

    public FacetGroupsResponse getFacets(List<String> facet, String facetLogicParam, String query) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        boolean isAdmin = user.getPermissions().isAdmin();
        Set<Long> libraryIds = BookFilterSpecifications.libraryIds(user);

        Map<String, List<String>> facets = BookFilterSpecifications.parseFacets(facet);
        FacetLogic facetLogic = FacetLogic.from(facetLogicParam);

        String cacheKey = userId + ":" + ParamsHash.compute(query, facets, facetLogic);
        return cache.get(cacheKey, key -> {
            String preserved = BrowseParams.preserved(facet, facetLogicParam, query);
            List<FacetGroup> groups = new ArrayList<>();
            groups.add(sortGroup(preserved));
            for (FacetDef def : FACETS) {
                Specification<BookEntity> base = filterSpecifications.base(query, facets, facetLogic, userId, isAdmin, libraryIds, def.key());
                Long distinctCount = DISTINCT_COUNT_FACETS.contains(def.key()) ? distinctCount(def, base, userId) : null;
                groups.add(toGroup(def, count(def, base, userId), distinctCount, facet, preserved));
            }
            List<Link> links = List.of(Link.json(List.of("self"), href(FACET_PATH, preserved)));
            return new FacetGroupsResponse(links, groups);
        });
    }

    // Package-private: lets tests reset the shared singleton cache between runs.
    void clearCache() {
        cache.invalidateAll();
    }

    // Exhaustive value -> book id map per requested facet key, unlike getFacets() this is never
    // capped at MAX_VALUES - metadata merge/rename/delete needs every affected book, not the top 100.
    public Map<String, List<FacetValueBookIds>> getFacetValueBookIds(List<String> facetKeys) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        boolean isAdmin = user.getPermissions().isAdmin();
        Set<Long> libraryIds = BookFilterSpecifications.libraryIds(user);

        Map<String, List<FacetValueBookIds>> result = new LinkedHashMap<>();
        for (String key : facetKeys) {
            FacetDef def = FACETS.stream().filter(f -> f.key().equals(key)).findFirst()
                    .orElseThrow(() -> ApiError.INVALID_FACET.createException("Unknown facet: " + key));
            Specification<BookEntity> base = filterSpecifications.base(null, Map.of(), FacetLogic.AND, userId, isAdmin, libraryIds, key);
            result.put(key, valueBookIds(def, base, userId));
        }
        return result;
    }

    // Distinct values for one facet key matching a case-insensitive prefix, scoped like the other
    // browse endpoints and capped at limit - backs typeahead inputs, never the exhaustive value list.
    public List<String> searchFacetValues(String facetKey, String query, int limit) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        boolean isAdmin = user.getPermissions().isAdmin();
        Set<Long> libraryIds = BookFilterSpecifications.libraryIds(user);

        FacetDef def = FACETS.stream().filter(f -> f.key().equals(facetKey)).findFirst()
                .orElseThrow(() -> ApiError.INVALID_FACET.createException("Unknown facet: " + facetKey));
        Specification<BookEntity> base = filterSpecifications.base(null, Map.of(), FacetLogic.AND, userId, isAdmin, libraryIds, facetKey);

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<String> cq = cb.createQuery(String.class);
        Root<BookEntity> root = cq.from(BookEntity.class);
        Expression<String> value = def.value().apply(cb, root, userId).as(String.class);

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = base.toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(value));
        if (query != null && !query.isBlank()) {
            predicates.add(cb.like(cb.lower(value), query.toLowerCase() + "%"));
        }

        cq.select(value).distinct(true);
        cq.where(predicates.toArray(Predicate[]::new));
        cq.orderBy(cb.asc(value));

        return entityManager.createQuery(cq).setMaxResults(limit).getResultList();
    }

    private List<FacetValueBookIds> valueBookIds(FacetDef def, Specification<BookEntity> base, Long userId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        Expression<?> value = def.value().apply(cb, root, userId);

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = base.toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(value));

        cq.multiselect(value.alias("value"), root.get("id").alias("bookId"));
        cq.where(predicates.toArray(Predicate[]::new));
        cq.orderBy(cb.asc(value));

        Map<String, List<Long>> grouped = new LinkedHashMap<>();
        for (Tuple tuple : entityManager.createQuery(cq).getResultList()) {
            String key = String.valueOf(tuple.get("value"));
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(((Number) tuple.get("bookId")).longValue());
        }
        return grouped.entrySet().stream().map(e -> new FacetValueBookIds(e.getKey(), e.getValue())).toList();
    }

    private List<FacetCount> count(FacetDef def, Specification<BookEntity> base, Long userId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        Expression<?> value = def.value().apply(cb, root, userId);
        Expression<Long> count = cb.countDistinct(root.get("id"));

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = base.toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(value));

        cq.multiselect(value.alias("value"), count.alias("count"));
        cq.where(predicates.toArray(Predicate[]::new));
        cq.groupBy(value);
        cq.orderBy(cb.desc(count), cb.asc(value));

        TypedQuery<Tuple> typedQuery = entityManager.createQuery(cq);
        if (!UNCAPPED_FACETS.contains(def.key())) {
            typedQuery.setMaxResults(MAX_VALUES);
        }
        return typedQuery.getResultList().stream()
                .map(tuple -> new FacetCount(String.valueOf(tuple.get("value")), ((Number) tuple.get("count")).longValue()))
                .toList();
    }

    // Exact COUNT(DISTINCT ...) over the same scoped predicate as count(), never the capped
    // top-100 list - the only way to report a total for a group larger than MAX_VALUES.
    private Long distinctCount(FacetDef def, Specification<BookEntity> base, Long userId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<BookEntity> root = cq.from(BookEntity.class);
        Expression<?> value = def.value().apply(cb, root, userId);

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = base.toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(value));

        cq.select(cb.countDistinct(value));
        cq.where(predicates.toArray(Predicate[]::new));
        return entityManager.createQuery(cq).getSingleResult();
    }

    private FacetGroup toGroup(FacetDef def, List<FacetCount> counts, Long distinctCount, List<String> facet, String preserved) {
        List<FacetLink> links = counts.stream()
                .map(c -> {
                    boolean active = BrowseParams.hasFacet(facet, def.key(), c.value());
                    List<String> rel = active ? List.of("self", "facet") : List.of("facet");
                    String href = active
                            ? href(PAGE_PATH, preserved)
                            : pageLink(preserved, "facet=" + BrowseParams.encode(def.key() + ":" + c.value()));
                    return new FacetLink(rel, href, Link.JSON_TYPE, c.value(), c.value(), new Properties(c.count()));
                })
                .toList();
        return new FacetGroup(new Metadata("facet", def.key(), def.title()), links, distinctCount);
    }

    private FacetGroup sortGroup(String preserved) {
        List<FacetLink> links = new ArrayList<>();
        for (String key : sortRegistry.registry().keys()) {
            if (key.equals("id")) {
                continue;
            }
            links.add(new FacetLink(List.of("sort"), pageLink(preserved, "sort=" + BrowseParams.encode(key)), Link.JSON_TYPE, key + " ascending", key, null));
            links.add(new FacetLink(List.of("sort"), pageLink(preserved, "sort=-" + BrowseParams.encode(key)), Link.JSON_TYPE, key + " descending", "-" + key, null));
        }
        return new FacetGroup(new Metadata("sort", "sort", "Sort"), links, null);
    }

    private static String pageLink(String preserved, String param) {
        return preserved.isBlank() ? PAGE_PATH + "?" + param : PAGE_PATH + "?" + preserved + "&" + param;
    }

    private static String href(String path, String preserved) {
        return preserved.isBlank() ? path : path + "?" + preserved;
    }

    private static Join<?, ?> metadata(Root<BookEntity> root) {
        return root.join("metadata", JoinType.LEFT);
    }

    // Explicit min<=x<max (or x>=min for the open top bucket) per WHEN, so bucket order in the
    // table never matters - unlike a cascading >= chain, reordering the list can't misbucket.
    private static Expression<String> bucketExpr(CriteriaBuilder cb, Expression<? extends Number> field, List<NumericFacetBuckets.Bucket> buckets) {
        CriteriaBuilder.Case<String> selectCase = cb.<String>selectCase();
        for (NumericFacetBuckets.Bucket bucket : buckets) {
            Predicate condition = Double.isInfinite(bucket.max())
                    ? cb.ge(field, bucket.min())
                    : cb.and(cb.ge(field, bucket.min()), cb.lt(field, bucket.max()));
            selectCase = selectCase.when(condition, bucket.id());
        }
        return selectCase.otherwise(cb.nullLiteral(String.class));
    }

    private interface FacetValueSource {
        Expression<?> apply(CriteriaBuilder cb, Root<BookEntity> root, Long userId);
    }

    private record FacetDef(String key, String title, FacetValueSource value) {
    }

    private record FacetCount(String value, long count) {
    }
}
