package org.booklore.model.dto.browse;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/** Aggregated per-series card data for the series browser grid, scoped to the current user. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SeriesSummary {
    private String seriesName;
    private int bookCount;
    private int readCount;
    private double progress;
    private String seriesStatus;
    private Long nextUnreadBookId;
    private Instant lastReadTime;
    private Instant addedOn;
    private List<String> authors;
    private List<String> categories;
    private List<SeriesCoverBook> coverBooks;
}
