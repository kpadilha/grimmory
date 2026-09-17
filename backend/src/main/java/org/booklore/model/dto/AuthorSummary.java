package org.booklore.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthorSummary {
    private Long id;
    private String name;
    private String asin;
    private int bookCount;
    private boolean hasPhoto;
    // Bulk-aggregated per page (see AuthorRepository), never per-author loops - keeps the
    // author-browser off bookService.books() without reintroducing the N+1 that hasPhoto had.
    @Builder.Default
    private List<String> libraryNames = List.of();
    @Builder.Default
    private List<String> categories = List.of();
    private int seriesCount;
    private Instant latestAddedOn;
    private Instant lastReadTime;
    private int readCount;
    private int inProgressCount;
    private Double avgPersonalRating;
}
