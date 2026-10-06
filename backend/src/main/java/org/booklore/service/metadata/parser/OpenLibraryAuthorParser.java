package org.booklore.service.metadata.parser;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.service.metadata.parser.OpenLibraryParser.OpenLibraryTypedValue;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenLibraryAuthorParser implements AuthorParser {

    private static final String BASE_URI = "https://openlibrary.org";
    private static final String COVERS_URI = "https://covers.openlibrary.org";
    private static final Pattern OLID = Pattern.compile("OL\\d{1,17}A");
    private static final int SEARCH_LIMIT = 10;
    // OpenLibrary keeps many duplicate records per author; only the biggest few are worth a works lookup.
    private static final int MAX_WORK_LOOKUPS = 3;

    record SearchDoc(String key, String name, @JsonProperty("top_work") String topWork,
                     @JsonProperty("work_count") Integer workCount) {
        int works() {
            return workCount == null ? 0 : workCount;
        }
    }

    record SearchResult(List<SearchDoc> docs) {}

    record Author(String key, String name, Optional<OpenLibraryTypedValue> bio, Optional<List<Integer>> photos) {}

    record Work(String title) {}

    record Works(List<Work> entries) {}

    private final OpenLibraryParser openLibrary;

    @Override
    public List<AuthorSearchResult> searchAuthors(String name, String region) {
        try {
            return search(name).stream().limit(SEARCH_LIMIT)
                    .map(doc -> AuthorSearchResult.builder()
                            .source(AuthorMetadataSource.OPENLIBRARY)
                            .openLibraryId(doc.key())
                            .name(doc.name())
                            .imageUrl(COVERS_URI + "/a/olid/" + doc.key() + "-M.jpg?default=false")
                            .build())
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.error("OpenLibrary author search failed for name: {}", name, e);
            return List.of();
        }
    }

    @Override
    public AuthorSearchResult getAuthorByAsin(String olid, String region) {
        if (olid == null || !OLID.matcher(olid).matches()) return null;
        try {
            Author author = openLibrary.sendRequest(get("/authors/{olid}.json", olid), Author.class);
            String photo = author.photos().orElse(List.of()).stream()
                    .filter(id -> id != null && id > 0).findFirst()
                    .map(id -> COVERS_URI + "/a/id/" + id + "-L.jpg").orElse(null);
            return AuthorSearchResult.builder()
                    .source(AuthorMetadataSource.OPENLIBRARY)
                    .openLibraryId(olid)
                    .name(author.name())
                    .description(author.bio().map(OpenLibraryTypedValue::value).orElse(null))
                    .imageUrl(photo)
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.error("OpenLibrary author detail failed for OLID: {}", olid, e);
            return null;
        }
    }

    @Override
    public AuthorSearchResult quickSearch(String name, String region, Collection<String> bookTitles) {
        try {
            List<SearchDoc> sameName = search(name).stream()
                    .filter(doc -> doc.key() != null && AuthorNameMatcher.matches(name, doc.name()))
                    .sorted(Comparator.comparingInt(SearchDoc::works).reversed())
                    .toList();
            String olid = pick(sameName, AuthorNameMatcher.normaliseTitles(bookTitles));
            return olid == null ? null : getAuthorByAsin(olid, region);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.error("OpenLibrary author quick search failed for name: {}", name, e);
            return null;
        }
    }

    /** The biggest same-name record whose works include one of our titles; same name alone is not enough. */
    private String pick(List<SearchDoc> sameName, Set<String> ourTitles) throws InterruptedException {
        if (ourTitles.isEmpty()) {
            return sameName.size() == 1 ? sameName.getFirst().key() : null;
        }
        for (SearchDoc doc : sameName.stream().limit(MAX_WORK_LOOKUPS).toList()) {
            if (ourTitles.contains(AuthorNameMatcher.normaliseTitle(doc.topWork())) || worksOverlap(doc.key(), ourTitles)) {
                return doc.key();
            }
        }
        return null;
    }

    private boolean worksOverlap(String olid, Set<String> ourTitles) throws InterruptedException {
        try {
            Works works = openLibrary.sendRequest(get("/authors/{olid}/works.json?limit=100", olid), Works.class);
            return works.entries() != null && works.entries().stream()
                    .anyMatch(work -> ourTitles.contains(AuthorNameMatcher.normaliseTitle(work.title())));
        } catch (RuntimeException e) {
            // An unreadable work list only fails to confirm this record; the next one may still prove itself.
            log.warn("OpenLibrary works lookup failed for OLID {}: {}", olid, e.getMessage());
            return false;
        }
    }

    private List<SearchDoc> search(String name) throws InterruptedException {
        URI uri = UriComponentsBuilder.fromUriString(BASE_URI)
                .path("/search/authors.json")
                .queryParam("q", name)
                .queryParam("fields", "key,name,top_work,work_count")
                .encode()
                .build()
                .toUri();
        SearchResult result = openLibrary.sendRequest(HttpRequest.newBuilder(uri).GET().build(), SearchResult.class);
        return result.docs() == null ? List.of() : result.docs();
    }

    private static HttpRequest get(String pathTemplate, String olid) {
        URI uri = UriComponentsBuilder.fromUriString(BASE_URI + pathTemplate).build(olid);
        return HttpRequest.newBuilder(uri).GET().build();
    }
}
