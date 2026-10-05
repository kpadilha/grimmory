package org.booklore.service.browse;

import lombok.RequiredArgsConstructor;
import org.booklore.app.specification.AppBookSpecification;
import org.booklore.browse.FacetLogic;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class BookFilterSpecifications {

    private final BookFacetRegistry facetRegistry;
    private final BookSearchResolver searchResolver;

    public Specification<BookEntity> base(
            String query,
            Map<String, List<String>> facets,
            FacetLogic facetLogic,
            BrowseScope scope,
            String omitFacet
    ) {
        return withSearch(search(query, facets, facetLogic, scope), facets, facetLogic, scope, omitFacet);
    }

    /** The query's search spec, resolved once against every facet so all facet groups share it. */
    public Specification<BookEntity> search(String query, Map<String, List<String>> facets, FacetLogic facetLogic, BrowseScope scope) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return searchResolver.resolve(query, withSearch(null, facets, facetLogic, scope, null));
    }

    public Specification<BookEntity> withSearch(
            Specification<BookEntity> search,
            Map<String, List<String>> facets,
            FacetLogic facetLogic,
            BrowseScope scope,
            String omitFacet
    ) {
        List<Specification<BookEntity>> specs = new ArrayList<>();
        specs.add(scope.visibleBooks());
        if (search != null) {
            specs.add(search);
        }
        for (Map.Entry<String, List<String>> entry : facets.entrySet()) {
            if (Objects.equals(entry.getKey(), omitFacet)) {
                continue;
            }
            if (!facetRegistry.has(entry.getKey())) {
                throw ApiError.INVALID_FACET.createException("Unknown facet: " + entry.getKey());
            }
            specs.add(facetRegistry.toSpecification(entry.getKey(), entry.getValue(), facetLogic, scope.userId()));
        }
        return AppBookSpecification.combine(specs.toArray(Specification[]::new));
    }
}
