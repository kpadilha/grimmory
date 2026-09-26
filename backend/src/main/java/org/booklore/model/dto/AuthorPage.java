package org.booklore.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One page of the author listing, with the total row count for the collection being paged.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorPage {
    private List<AuthorSummary> content;
    private long totalElements;
}
