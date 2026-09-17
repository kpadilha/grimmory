package org.booklore.model.dto.browse;

import java.util.List;

// Exhaustive book id list per facet value, scoped like FacetGroupsResponse but never capped -
// backs metadata management (merge/rename/delete), which must reach every affected book.
public record FacetValueBookIds(String value, List<Long> bookIds) {
}
