package org.booklore.service.metadata.parser;

import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.service.SleepService;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenLibraryAuthorParserTest {

    private static final String SANDERSON_SEARCH = """
            {"numFound": 3, "docs": [
              {"key": "OL16029248A", "name": "Brandon Sanderson", "top_work": "Wind and Truth", "work_count": 14},
              {"key": "OL1394865A", "name": "Brandon Sanderson", "top_work": "The Final Empire", "work_count": 206},
              {"key": "OL9999999A", "name": "Tara Sanderson", "top_work": "Elantris", "work_count": 300}
            ]}""";

    @Mock private HttpClient httpClient;
    @Mock private SleepService sleepService;
    @Mock private AppSettingService appSettingService;
    private OpenLibraryAuthorParser parser;

    @BeforeEach
    void setUp() {
        parser = new OpenLibraryAuthorParser(new OpenLibraryParser(httpClient, new ObjectMapper(), appSettingService, sleepService));
    }

    @SuppressWarnings("unchecked")
    private void respond(Map<String, String> bodiesByPath) throws Exception {
        when(httpClient.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            String body = bodiesByPath.get(request.uri().getPath());
            HttpResponse<InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(body == null ? 404 : 200);
            if (body != null) {
                when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            }
            return response;
        });
    }

    @Test
    void picksSameNameRecordWhoseWorksOverlapOurTitles() throws Exception {
        respond(Map.of(
                "/search/authors.json", SANDERSON_SEARCH,
                "/authors/OL1394865A/works.json", """
                        {"entries": [{"title": "Mistborn"}, {"title": "The Way of Kings"}]}""",
                "/authors/OL1394865A.json", """
                        {"key": "/authors/OL1394865A", "name": "Brandon Sanderson",
                         "bio": {"type": "/type/text", "value": "Fantasy author."}, "photos": [-1, 7238532]}"""));

        AuthorSearchResult result = parser.quickSearch("Brandon Sanderson", "us", List.of("The Way of Kings: Stormlight 1"));

        assertThat(result).isNotNull();
        assertThat(result.getSource()).isEqualTo(AuthorMetadataSource.OPENLIBRARY);
        assertThat(result.getOpenLibraryId()).isEqualTo("OL1394865A");
        assertThat(result.getAsin()).isNull();
        assertThat(result.getDescription()).isEqualTo("Fantasy author.");
        assertThat(result.getImageUrl()).isEqualTo("https://covers.openlibrary.org/a/id/7238532-L.jpg");
    }

    @Test
    void topWorkOverlapNeedsNoWorksLookup() throws Exception {
        respond(Map.of(
                "/search/authors.json", SANDERSON_SEARCH,
                "/authors/OL16029248A.json", """
                        {"key": "/authors/OL16029248A", "name": "Brandon Sanderson", "bio": "Plain bio"}"""));

        AuthorSearchResult result = parser.quickSearch("Brandon Sanderson", "us", List.of("Wind and Truth"));

        assertThat(result.getOpenLibraryId()).isEqualTo("OL16029248A");
        assertThat(result.getDescription()).isEqualTo("Plain bio");
        assertThat(result.getImageUrl()).isNull();
    }

    @Test
    void sameNameWithoutWorkOverlapIsNoMatch() throws Exception {
        respond(Map.of(
                "/search/authors.json", SANDERSON_SEARCH,
                "/authors/OL1394865A/works.json", "{\"entries\": [{\"title\": \"Mistborn\"}]}",
                "/authors/OL16029248A/works.json", "{\"entries\": [{\"title\": \"Wind and Truth\"}]}"));

        assertThat(parser.quickSearch("Brandon Sanderson", "us", List.of("Elantris"))).isNull();
    }

    @Test
    void severalSameNameRecordsAndNoTitlesIsNoMatch() throws Exception {
        respond(Map.of("/search/authors.json", SANDERSON_SEARCH));

        assertThat(parser.quickSearch("Brandon Sanderson", "us", List.of())).isNull();
    }

    @Test
    void differentNameIsNeverAccepted() throws Exception {
        respond(Map.of("/search/authors.json", """
                {"docs": [{"key": "OL1A", "name": "C. C. Humphreys", "top_work": "Betrayal", "work_count": 9}]}"""));

        assertThat(parser.quickSearch("A C Cobble", "us", List.of("Betrayal"))).isNull();
    }

    @Test
    void getAuthorByAsinIgnoresNonOpenLibraryIds() {
        assertThat(parser.getAuthorByAsin("B001IGFHW6", "us")).isNull();
        verifyNoInteractions(httpClient);
    }
}
