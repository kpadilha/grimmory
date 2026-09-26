package org.booklore.model.dto.browse;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One cover candidate for a series card; audiobook vs. ebook picks a different timestamp field. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SeriesCoverBook {
    private Long bookId;
    private String bookType;
    private Instant coverUpdatedOn;
    private Instant audiobookCoverUpdatedOn;
}
