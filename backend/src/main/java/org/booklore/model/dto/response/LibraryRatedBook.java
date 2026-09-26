package org.booklore.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LibraryRatedBook {
    private Long bookId;
    private String title;
    private Integer pageCount;
    private Integer personalRating;
    private Double externalRatingAvg;
    private String readStatus;
    private Integer publishedYear;
    private String addedOn;
}
