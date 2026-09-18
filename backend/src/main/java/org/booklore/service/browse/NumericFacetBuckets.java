package org.booklore.service.browse;

import java.util.List;

// Bucket boundaries mirroring the frontend's RangeConfig tables (book-filter.config.ts) 1:1 -
// BookFacetService counts into these buckets and BookFacetRegistry filters by the same ones, so
// sidebar counts and click-through filtering can never drift apart.
final class NumericFacetBuckets {

    record Bucket(String id, double min, double max) {
    }

    private NumericFacetBuckets() {
    }

    static final List<Bucket> RATING_5 = List.of(
            new Bucket("0", 0, 1),
            new Bucket("1", 1, 2),
            new Bucket("2", 2, 3),
            new Bucket("3", 3, 4),
            new Bucket("4", 4, 4.5),
            new Bucket("5", 4.5, Double.POSITIVE_INFINITY));

    // metadataMatchScore is stored 0-100 (MetadataMatchService), not 0-1 - matches
    // LibraryStatsService.METADATA_SCORE_RANGES on the same field.
    static final List<Bucket> MATCH_SCORE = List.of(
            new Bucket("0", 95, Double.POSITIVE_INFINITY),
            new Bucket("1", 90, 95),
            new Bucket("2", 80, 90),
            new Bucket("3", 70, 80),
            new Bucket("4", 50, 70),
            new Bucket("5", 30, 50),
            new Bucket("6", 0, 30));

    static final List<Bucket> FILE_SIZE = List.of(
            new Bucket("0", 0, 1024),
            new Bucket("1", 1024, 10240),
            new Bucket("2", 10240, 51200),
            new Bucket("3", 51200, 102400),
            new Bucket("4", 102400, 512000),
            new Bucket("5", 512000, 1048576),
            new Bucket("6", 1048576, 2097152),
            new Bucket("7", 2097152, Double.POSITIVE_INFINITY));

    static final List<Bucket> PAGE_COUNT = List.of(
            new Bucket("0", 0, 50),
            new Bucket("1", 50, 100),
            new Bucket("2", 100, 200),
            new Bucket("3", 200, 400),
            new Bucket("4", 400, 600),
            new Bucket("5", 600, 1000),
            new Bucket("6", 1000, Double.POSITIVE_INFINITY));

    static final List<Bucket> AGE_RATING = List.of(
            new Bucket("0", 0, 6),
            new Bucket("6", 6, 10),
            new Bucket("10", 10, 13),
            new Bucket("13", 13, 16),
            new Bucket("16", 16, 18),
            new Bucket("18", 18, 21),
            new Bucket("21", 21, Double.POSITIVE_INFINITY));
}
