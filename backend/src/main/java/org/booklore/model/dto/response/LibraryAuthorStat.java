package org.booklore.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LibraryAuthorStat {
    private String author;
    private long bookCount;
    private long totalPages;
    private Double avgRating;
    private long readCount;
    private long distinctCategories;
}
