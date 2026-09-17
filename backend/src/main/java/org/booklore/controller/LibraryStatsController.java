package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.model.dto.response.LibraryAggregateBucket;
import org.booklore.model.dto.response.LibraryAuthorStat;
import org.booklore.model.dto.response.LibraryCrosstabCell;
import org.booklore.model.dto.response.LibraryHistogramBucket;
import org.booklore.model.dto.response.LibraryRatedBook;
import org.booklore.model.dto.response.LibrarySeriesStat;
import org.booklore.model.dto.response.LibrarySummary;
import org.booklore.model.dto.response.LibraryTimelineResponse;
import org.booklore.service.stats.LibraryStatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Every endpoint here is scoped server-side by LibraryStatsService via BookFilterSpecifications -
// this controller only adds the canAccessLibraryStats() gate, never book access decisions.
@RestController
@AllArgsConstructor
@RequestMapping("/api/v1/library-stats")
@Tag(name = "Library Stats", description = "Server-side aggregation for the library-stats dashboard, so charts never load the whole catalogue")
@PreAuthorize("@securityUtil.canAccessLibraryStats() or @securityUtil.isAdmin()")
public class LibraryStatsController {

    private final LibraryStatsService libraryStatsService;

    @Operation(summary = "Group-by count for a single field, optionally broken down by a second field")
    @GetMapping("/aggregate")
    public ResponseEntity<List<LibraryAggregateBucket>> aggregate(
            @RequestParam String field,
            @RequestParam(required = false) String breakdownBy,
            @RequestParam(required = false) Long libraryId) {
        return ResponseEntity.ok(libraryStatsService.aggregate(field, breakdownBy, libraryId));
    }

    @Operation(summary = "Fixed-range bucket counts for page count or metadata score")
    @GetMapping("/histogram")
    public ResponseEntity<List<LibraryHistogramBucket>> histogram(
            @RequestParam String field,
            @RequestParam(required = false) Long libraryId) {
        return ResponseEntity.ok(libraryStatsService.histogram(field, libraryId));
    }

    @Operation(summary = "Per-year or per-month counts for a date field, with edge titles for published_date")
    @GetMapping("/timeline")
    public ResponseEntity<LibraryTimelineResponse> timeline(
            @RequestParam String field,
            @RequestParam(defaultValue = "year") String granularity,
            @RequestParam(required = false) Long libraryId) {
        return ResponseEntity.ok(libraryStatsService.timeline(field, granularity, libraryId));
    }

    @Operation(summary = "Top authors by book count, with aggregate pages/rating/read stats")
    @GetMapping("/authors")
    public ResponseEntity<List<LibraryAuthorStat>> authors(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long libraryId) {
        return ResponseEntity.ok(libraryStatsService.authors(limit, libraryId));
    }

    @Operation(summary = "Per-series read/reading/unread breakdown for the authenticated user")
    @GetMapping("/series")
    public ResponseEntity<List<LibrarySeriesStat>> series(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long libraryId) {
        return ResponseEntity.ok(libraryStatsService.series(limit, libraryId));
    }

    @Operation(summary = "Every book the user has personally rated - always small")
    @GetMapping("/rated-books")
    public ResponseEntity<List<LibraryRatedBook>> ratedBooks(@RequestParam(required = false) Long libraryId) {
        return ResponseEntity.ok(libraryStatsService.ratedBooks(libraryId));
    }

    @Operation(summary = "Two-dimensional count for an allow-listed field pair")
    @GetMapping("/crosstab")
    public ResponseEntity<List<LibraryCrosstabCell>> crosstab(
            @RequestParam String rowField,
            @RequestParam String colField,
            @RequestParam(required = false) Long libraryId) {
        return ResponseEntity.ok(libraryStatsService.crosstab(rowField, colField, libraryId));
    }

    @Operation(summary = "Headline totals for the library-stats summary bar")
    @GetMapping("/summary")
    public ResponseEntity<LibrarySummary> summary(@RequestParam(required = false) Long libraryId) {
        return ResponseEntity.ok(libraryStatsService.summary(libraryId));
    }
}
