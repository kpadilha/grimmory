package org.booklore.service.browse;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.booklore.app.specification.AppBookSpecification;
import org.booklore.browse.FacetLogic;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.service.opds.MagicShelfBookService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class BookFacetRegistry {

    private static final String MAGIC_SHELF_PREFIX = "magic:";

    private static final Set<String> NAMES = Set.of(
            "author", "series", "genre", "tag", "mood", "language", "publisher", "narrator", "library", "shelf",
            "file_type", "read_status", "personal_rating", "amazon_rating", "goodreads_rating",
            "hardcover_rating", "ranobedb_rating", "lubimyczytac_rating", "audible_rating", "applebooks_rating",
            "age_rating", "content_rating", "match_score",
            "published_year", "file_size", "page_count", "shelf_status",
            "comic_character", "comic_team", "comic_location", "comic_creator");

    private final MagicShelfBookService magicShelfBookService;

    public BookFacetRegistry(MagicShelfBookService magicShelfBookService) {
        this.magicShelfBookService = magicShelfBookService;
    }

    public boolean has(String facetName) {
        return NAMES.contains(facetName);
    }

    public Set<String> facetNames() {
        return NAMES;
    }

    public Specification<BookEntity> toSpecification(String facetName, List<String> values, FacetLogic logic, Long userId) {
        String mode = mode(logic);
        return switch (facetName) {
            case "author" -> AppBookSpecification.withAuthors(values, mode);
            case "series" -> AppBookSpecification.inSeriesMulti(values, mode);
            case "genre" -> AppBookSpecification.withCategories(values, mode);
            case "tag" -> AppBookSpecification.withTags(values, mode);
            case "mood" -> AppBookSpecification.withMoods(values, mode);
            case "language" -> AppBookSpecification.withLanguages(values, mode);
            case "publisher" -> AppBookSpecification.withPublishers(values, mode);
            case "narrator" -> AppBookSpecification.withNarrators(values, mode);
            case "library" -> AppBookSpecification.inLibraries(values, mode);
            case "shelf" -> shelves(values, logic, userId);
            case "file_type" -> fileTypes(values, logic, mode);
            case "read_status" -> AppBookSpecification.withReadStatuses(values, userId, mode);
            case "personal_rating" -> AppBookSpecification.withPersonalRatings(values, userId, mode);
            // Bucketed the same way BookFacetService counts them - values are bucket ids, not raw
            // ratings, so this can't reuse AppBookSpecification's min..max range parser directly.
            case "amazon_rating" -> numericBucket(NumericFacetBuckets.RATING_5, metadataField("amazonRating"), values, logic);
            case "goodreads_rating" -> numericBucket(NumericFacetBuckets.RATING_5, metadataField("goodreadsRating"), values, logic);
            case "hardcover_rating" -> numericBucket(NumericFacetBuckets.RATING_5, metadataField("hardcoverRating"), values, logic);
            case "ranobedb_rating" -> numericBucket(NumericFacetBuckets.RATING_5, metadataField("ranobedbRating"), values, logic);
            case "lubimyczytac_rating" -> numericBucket(NumericFacetBuckets.RATING_5, metadataField("lubimyczytacRating"), values, logic);
            case "audible_rating" -> numericBucket(NumericFacetBuckets.RATING_5, metadataField("audibleRating"), values, logic);
            case "applebooks_rating" -> AppBookSpecification.withApplebooksRatings(values, mode);
            case "age_rating" -> numericBucket(NumericFacetBuckets.AGE_RATING, metadataField("ageRating"), values, logic);
            case "content_rating" -> AppBookSpecification.withContentRatings(values, mode);
            case "match_score" -> numericBucket(NumericFacetBuckets.MATCH_SCORE, (cb, root) -> root.<Number>get("metadataMatchScore"), values, logic);
            case "published_year" -> AppBookSpecification.withPublishedYears(values, mode);
            case "file_size" -> numericBucket(NumericFacetBuckets.FILE_SIZE, BookFacetRegistry::fileSizeField, values, logic);
            case "page_count" -> numericBucket(NumericFacetBuckets.PAGE_COUNT, metadataField("pageCount"), values, logic);
            case "shelf_status" -> AppBookSpecification.withShelfStatus(values, mode);
            case "comic_character" -> AppBookSpecification.withComicCharacters(values, mode);
            case "comic_team" -> AppBookSpecification.withComicTeams(values, mode);
            case "comic_location" -> AppBookSpecification.withComicLocations(values, mode);
            case "comic_creator" -> AppBookSpecification.withComicCreators(values, mode);
            default -> throw ApiError.INVALID_FACET.createException("Unknown facet: " + facetName);
        };
    }

    private Specification<BookEntity> shelves(List<String> values, FacetLogic logic, Long userId) {
        List<String> regularIds = new ArrayList<>();
        List<Specification<BookEntity>> specs = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (value.startsWith(MAGIC_SHELF_PREFIX)) {
                specs.add(magicShelfBookService.toSpecification(userId, parseMagicShelfId(value)));
            } else {
                regularIds.add(value);
            }
        }
        if (!regularIds.isEmpty()) {
            String inMode = logic == FacetLogic.AND ? "and" : "or";
            specs.add(AppBookSpecification.inShelves(regularIds, inMode));
        }
        if (specs.isEmpty()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return switch (logic) {
            case OR -> Specification.anyOf(specs);
            case NOT -> Specification.not(Specification.anyOf(specs));
            case AND -> Specification.allOf(specs);
        };
    }

    // PHYSICAL has no bookFiles row to match against, so it's split off and scored on isPhysical;
    // the rest still go through the file_type-aware AppBookSpecification lookup unchanged.
    private static Specification<BookEntity> fileTypes(List<String> values, FacetLogic logic, String mode) {
        List<String> regular = new ArrayList<>();
        boolean wantsPhysical = false;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if ("PHYSICAL".equalsIgnoreCase(value.trim())) {
                wantsPhysical = true;
            } else {
                regular.add(value);
            }
        }
        List<Specification<BookEntity>> specs = new ArrayList<>();
        if (wantsPhysical) {
            specs.add((root, query, cb) -> cb.isTrue(root.get("isPhysical")));
        }
        if (!regular.isEmpty()) {
            specs.add(AppBookSpecification.withFileTypes(regular, mode));
        }
        if (specs.isEmpty()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return switch (logic) {
            case OR -> Specification.anyOf(specs);
            case NOT -> Specification.not(Specification.anyOf(specs));
            case AND -> Specification.allOf(specs);
        };
    }

    private interface NumericFieldSource {
        Expression<? extends Number> apply(CriteriaBuilder cb, Root<BookEntity> root);
    }

    private static NumericFieldSource metadataField(String property) {
        return (cb, root) -> root.join("metadata", JoinType.LEFT).<Number>get(property);
    }

    private static Expression<? extends Number> fileSizeField(CriteriaBuilder cb, Root<BookEntity> root) {
        Join<BookEntity, BookFileEntity> files = root.join("bookFiles", JoinType.LEFT);
        files.on(cb.isTrue(files.get("isBookFormat")));
        return files.<Number>get("fileSizeKb");
    }

    // Mirrors BookFacetService's bucketExpr but as a WHERE predicate instead of a SELECT case -
    // same NumericFacetBuckets table, so a bucket id filters exactly what it was counted under.
    private static Specification<BookEntity> numericBucket(
            List<NumericFacetBuckets.Bucket> table, NumericFieldSource source, List<String> values, FacetLogic logic) {
        return (root, query, cb) -> {
            Expression<? extends Number> field = source.apply(cb, root);
            List<Predicate> predicates = new ArrayList<>();
            for (String id : values) {
                if (id == null) {
                    continue;
                }
                String trimmed = id.trim();
                for (NumericFacetBuckets.Bucket bucket : table) {
                    if (bucket.id().equals(trimmed)) {
                        predicates.add(Double.isInfinite(bucket.max())
                                ? cb.ge(field, bucket.min())
                                : cb.and(cb.ge(field, bucket.min()), cb.lt(field, bucket.max())));
                        break;
                    }
                }
            }
            if (predicates.isEmpty()) {
                return cb.conjunction();
            }
            Predicate combined = logic == FacetLogic.AND
                    ? cb.and(predicates.toArray(Predicate[]::new))
                    : cb.or(predicates.toArray(Predicate[]::new));
            return logic == FacetLogic.NOT ? cb.not(combined) : combined;
        };
    }

    private static long parseMagicShelfId(String value) {
        try {
            return Long.parseLong(value.substring(MAGIC_SHELF_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw ApiError.INVALID_FACET.createException("Invalid magic shelf id: " + value);
        }
    }

    private static String mode(FacetLogic logic) {
        return switch (logic) {
            case OR -> "or";
            case NOT -> "not";
            case AND -> "and";
        };
    }
}
