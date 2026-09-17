package org.booklore.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LibrarySummary {
    private long totalBooks;
    private long totalSizeKb;
    private long distinctAuthors;
    private long distinctSeries;
    private long distinctPublishers;
}
