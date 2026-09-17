package org.booklore.model.dto.browse;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.annotation.JsonSerialize;
import org.booklore.browse.Link;
import org.booklore.browse.RelSerializer;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FacetGroupsResponse(List<Link> links, List<FacetGroup> facets) {

    // distinctCount is the exact, uncapped count of values for this group (unlike links.size(),
    // which stops at the facet listing's MAX_VALUES cap) - null where it was not computed.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FacetGroup(Metadata metadata, List<FacetLink> links, Long distinctCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metadata(String rel, String key, String title) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FacetLink(@JsonSerialize(using = RelSerializer.class) List<String> rel, String href, String type, String title, String value, Properties properties) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Properties(Long numberOfItems) {
    }
}
