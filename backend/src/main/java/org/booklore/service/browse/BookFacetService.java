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
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.ComicCharacterEntity;
import org.booklore.model.entity.ComicLocationEntity;
import org.booklore.model.entity.ComicTeamEntity;
import org.booklore.model.entity.MoodEntity;
import org.booklore.model.entity.TagEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.ComicCreatorRole;
import org.booklore.model.enums.ReadStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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

    // 1:1 with book (metadata PK, book's own column, or a user_book_progress row uniquely
    // constrained on (user_id, book_id)) - COUNT(DISTINCT book.id) and COUNT(book.id) agree here,
    // and MariaDB's DISTINCT aggregation is measurably the expensive part (~10-20x on the 132k set).
    private static final Set<String> PLAIN_COUNT_FACETS = Set.of(
            "amazon_rating", "goodreads_rating", "hardcover_rating", "ranobedb_rating",
            "lubimyczytac_rating", "audible_rating", "applebooks_rating", "age_rating",
            "page_count", "match_score", "published_year", "library", "shelf_status",
            "read_status", "personal_rating",
            // book_metadata.book_id is @MapsId onto book.id, so these scalar columns are 1:1
            // with book too - same COUNT(DISTINCT)-to-COUNT() win as the numeric buckets above.
            "publisher", "series", "language", "narrator", "content_rating");

    // Grouping straight from book (LEFT JOIN out to the value) forces MariaDB to join the full
    // book set before it can count - grouping by the lookup row's id instead (cheap, no name join)
    // and resolving display names for just the winning ids afterwards is measured 15-40x faster on
    // the 132k-book set. Safe because each lookup table has a UNIQUE(name), so id<->name is a
    // bijection: grouping by id partitions books identically to grouping by name.
    private static final Map<String, LookupFacet<?>> LOOKUP_FACETS = Map.of(
            "author", lookupFacet((cb, root, userId) -> metadata(root).join("authors", JoinType.LEFT).get("id"),
                    AuthorEntity.class, AuthorEntity::getId, AuthorEntity::getName),
            "genre", lookupFacet((cb, root, userId) -> metadata(root).join("categories", JoinType.LEFT).get("id"),
                    CategoryEntity.class, CategoryEntity::getId, CategoryEntity::getName),
            "tag", lookupFacet((cb, root, userId) -> metadata(root).join("tags", JoinType.LEFT).get("id"),
                    TagEntity.class, TagEntity::getId, TagEntity::getName),
            "mood", lookupFacet((cb, root, userId) -> metadata(root).join("moods", JoinType.LEFT).get("id"),
                    MoodEntity.class, MoodEntity::getId, MoodEntity::getName),
            "comic_character", lookupFacet((cb, root, userId) -> metadata(root).join("comicMetadata", JoinType.LEFT).join("characters", JoinType.LEFT).get("id"),
                    ComicCharacterEntity.class, ComicCharacterEntity::getId, ComicCharacterEntity::getName),
            "comic_team", lookupFacet((cb, root, userId) -> metadata(root).join("comicMetadata", JoinType.LEFT).join("teams", JoinType.LEFT).get("id"),
                    ComicTeamEntity.class, ComicTeamEntity::getId, ComicTeamEntity::getName),
            "comic_location", lookupFacet((cb, root, userId) -> metadata(root).join("comicMetadata", JoinType.LEFT).join("locations", JoinType.LEFT).get("id"),
                    ComicLocationEntity.class, ComicLocationEntity::getId, ComicLocationEntity::getName));

    private static <E> LookupFacet<E> lookupFacet(FacetValueSource idSource, Class<E> entityClass, Function<E, Long> idOf, Function<E, String> nameOf) {
        return new LookupFacet<>(idSource, entityClass, idOf, nameOf);
    }

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
                // A SQL CAST (not just the Java-side .as()) keeps both branches the same wire
                // type - H2 infers CASE result type from the ENUM column otherwise and rejects
                // the "PHYSICAL" literal; MariaDB, where book_type is plain varchar, is unaffected.
                return cb.<String>selectCase()
                        .when(cb.isTrue(root.get("isPhysical")), "PHYSICAL")
                        .otherwise(bookType.cast(String.class));
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
                LookupFacet<?> lookup = LOOKUP_FACETS.get(def.key());
                List<FacetCount> counts = lookup != null ? countByLookup(lookup, base) : count(def, base, userId);
                groups.add(toGroup(def, counts, distinctCount, facet, preserved));
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
        // DISTINCT is only needed to guard against a join fan-out; PLAIN_COUNT_FACETS are all
        // provably 1:1 with book, so a plain COUNT gives the identical number far more cheaply.
        Expression<Long> count = PLAIN_COUNT_FACETS.contains(def.key())
                ? cb.count(root.get("id"))
                : cb.countDistinct(root.get("id"));

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

    // Two cheap queries beat one expensive one: group by the lookup row's id (no name join, so
    // MariaDB never has to join the full book set before counting), then resolve display names
    // for just the ids that matched. Phase 2's own ORDER BY name ASC reproduces the original
    // query's "ORDER BY count DESC, value ASC" collation exactly - List.sort is stable, so sorting
    // that name-ascending list by count descending preserves the name order within each tie.
    private <E> List<FacetCount> countByLookup(LookupFacet<E> facet, Specification<BookEntity> base) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        Expression<Long> id = facet.idSource().apply(cb, root, null).as(Long.class);
        Expression<Long> count = cb.countDistinct(root.get("id"));

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = base.toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(id));

        cq.multiselect(id.alias("id"), count.alias("count"));
        cq.where(predicates.toArray(Predicate[]::new));
        cq.groupBy(id);

        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Tuple tuple : entityManager.createQuery(cq).getResultList()) {
            counts.put(((Number) tuple.get("id")).longValue(), ((Number) tuple.get("count")).longValue());
        }
        if (counts.isEmpty()) {
            return List.of();
        }

        CriteriaBuilder cb2 = entityManager.getCriteriaBuilder();
        CriteriaQuery<E> nameQuery = cb2.createQuery(facet.entityClass());
        Root<E> lookupRoot = nameQuery.from(facet.entityClass());
        nameQuery.select(lookupRoot)
                .where(lookupRoot.get("id").in(counts.keySet()))
                .orderBy(cb2.asc(lookupRoot.get("name")));

        List<FacetCount> merged = new ArrayList<>(counts.size());
        for (E entity : entityManager.createQuery(nameQuery).getResultList()) {
            merged.add(new FacetCount(facet.nameOf().apply(entity), counts.get(facet.idOf().apply(entity))));
        }
        merged.sort(Comparator.comparingLong(FacetCount::count).reversed());
        return merged.size() > MAX_VALUES ? merged.subList(0, MAX_VALUES) : merged;
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

    // idSource is the lookup row's id (cheap to group by); idOf/nameOf read that same id and its
    // display name back off the resolved entity so counts (keyed by id) and names can be merged.
    private record LookupFacet<E>(FacetValueSource idSource, Class<E> entityClass, Function<E, Long> idOf, Function<E, String> nameOf) {
    }
}
