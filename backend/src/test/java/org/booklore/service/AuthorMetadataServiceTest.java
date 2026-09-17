package org.booklore.service;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.model.dto.AuthorDetails;
import org.booklore.model.dto.AuthorPage;
import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.dto.AuthorSummary;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.request.AuthorMatchRequest;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.enums.AuditAction;
import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.AuthorRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.metadata.DuckDuckGoCoverService;
import org.booklore.service.metadata.parser.AuthorParser;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AuthorMetadataServiceTest {

    @Mock private AuthorRepository authorRepository;
    @Mock private AuthorParser authorParser;
    @Mock private AuditService auditService;
    @Mock private FileService fileService;
    @Mock private DuckDuckGoCoverService duckDuckGoCoverService;
    @Mock private AuthenticationService authenticationService;
    @Mock private AppSettingService appSettingService;

    private AuthorMetadataService service;

    @BeforeEach
    void setUp() {
        Map<AuthorMetadataSource, AuthorParser> authorParserMap = Map.of(
                AuthorMetadataSource.AUDNEXUS, authorParser
        );

        service = new AuthorMetadataService(
                authorRepository,
                authorParserMap,
                auditService,
                fileService,
                duckDuckGoCoverService,
                authenticationService,
                appSettingService
        );

        BookLoreUser.UserPermissions adminPermissions = new BookLoreUser.UserPermissions();
        adminPermissions.setAdmin(true);
        BookLoreUser adminUser = BookLoreUser.builder().id(1L).permissions(adminPermissions).build();
        lenient().when(authenticationService.getAuthenticatedUser()).thenReturn(adminUser);
    }

    @Test
    void searchAuthorMetadata_returnsResults() {
        AuthorSearchResult r1 = AuthorSearchResult.builder()
                .source(AuthorMetadataSource.AUDNEXUS).asin("B000APZGGS")
                .name("Stephen King").description("American author")
                .imageUrl("https://example.com/king.jpg")
                .build();
        AuthorSearchResult r2 = AuthorSearchResult.builder()
                .source(AuthorMetadataSource.AUDNEXUS).asin("B000AP1234")
                .name("Stephen King Jr").description("Another author")
                .imageUrl("https://example.com/kingjr.jpg")
                .build();

        when(authorParser.searchAuthors("Stephen King", "us")).thenReturn(List.of(r1, r2));

        List<AuthorSearchResult> results = service.searchAuthorMetadata("Stephen King", "us");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getAsin()).isEqualTo("B000APZGGS");
        assertThat(results.get(0).getSource()).isEqualTo(AuthorMetadataSource.AUDNEXUS);
        assertThat(results.get(0).getName()).isEqualTo("Stephen King");
        assertThat(results.get(0).getDescription()).isEqualTo("American author");
        assertThat(results.get(0).getImageUrl()).isEqualTo("https://example.com/king.jpg");
        assertThat(results.get(1).getAsin()).isEqualTo("B000AP1234");
    }

    @Test
    void searchAuthorMetadata_returnsEmptyListWhenNull() {
        when(authorParser.searchAuthors("Unknown", "us")).thenReturn(null);

        List<AuthorSearchResult> results = service.searchAuthorMetadata("Unknown", "us");

        assertThat(results).isEmpty();
    }

    @Test
    void searchAuthorMetadata_returnsEmptyListWhenEmpty() {
        when(authorParser.searchAuthors("Nobody", "us")).thenReturn(Collections.emptyList());

        List<AuthorSearchResult> results = service.searchAuthorMetadata("Nobody", "us");

        assertThat(results).isEmpty();
    }

    @Test
    void matchAuthor_updatesEntityAndReturnsDetails() {
        AuthorEntity author = new AuthorEntity();
        author.setId(1L);
        author.setName("Stephen King");

        when(authorRepository.findById(1L)).thenReturn(Optional.of(author));

        AuthorSearchResult result = AuthorSearchResult.builder()
                .source(AuthorMetadataSource.AUDNEXUS).asin("B000APZGGS")
                .description("Master of horror").imageUrl("https://example.com/king.jpg")
                .name("Stephen King")
                .build();

        when(authorParser.getAuthorByAsin("B000APZGGS", "us")).thenReturn(result);
        when(authorRepository.save(any(AuthorEntity.class))).thenAnswer(i -> i.getArgument(0));

        AuthorMatchRequest request = new AuthorMatchRequest();
        request.setAsin("B000APZGGS");
        request.setSource(AuthorMetadataSource.AUDNEXUS);
        request.setRegion("us");

        AuthorDetails details = service.matchAuthor(1L, request);

        assertThat(details.getId()).isEqualTo(1L);
        assertThat(details.getName()).isEqualTo("Stephen King");
        assertThat(details.getDescription()).isEqualTo("Master of horror");
        assertThat(details.getAsin()).isEqualTo("B000APZGGS");

        assertThat(author.getDescription()).isEqualTo("Master of horror");
        assertThat(author.getAsin()).isEqualTo("B000APZGGS");

        verify(authorRepository).save(author);
        verify(fileService).createAuthorThumbnailFromUrl(eq(1L), eq("https://example.com/king.jpg"));
        verify(auditService).log(eq(AuditAction.AUTHOR_METADATA_UPDATED), eq("Author"), eq(1L), anyString());
    }

    @Test
    void matchAuthor_throwsWhenAuthorNotFound() {
        when(authorRepository.findById(99L)).thenReturn(Optional.empty());

        AuthorMatchRequest request = new AuthorMatchRequest();
        request.setAsin("B000APZGGS");
        request.setSource(AuthorMetadataSource.AUDNEXUS);

        assertThatThrownBy(() -> service.matchAuthor(99L, request))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("Author not found");
    }

    @Test
    void matchAuthor_throwsWhenProviderReturnsNull() {
        AuthorEntity author = new AuthorEntity();
        author.setId(1L);
        author.setName("Test Author");

        when(authorRepository.findById(1L)).thenReturn(Optional.of(author));
        when(authorParser.getAuthorByAsin("INVALID", "us")).thenReturn(null);

        AuthorMatchRequest request = new AuthorMatchRequest();
        request.setAsin("INVALID");
        request.setSource(AuthorMetadataSource.AUDNEXUS);
        request.setRegion("us");

        assertThatThrownBy(() -> service.matchAuthor(1L, request))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("Failed to fetch author metadata");
    }

    @Test
    void matchAuthor_throwsWhenSourceUnsupported() {
        AuthorEntity author = new AuthorEntity();
        author.setId(1L);
        author.setName("Test Author");

        when(authorRepository.findById(1L)).thenReturn(Optional.of(author));

        AuthorMatchRequest request = new AuthorMatchRequest();
        request.setAsin("12345");
        request.setSource(null);
        request.setRegion("us");

        assertThatThrownBy(() -> service.matchAuthor(1L, request))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("Unsupported author metadata source");
    }

    @Test
    void getAuthorDetails_returnsDetails() {
        AuthorEntity author = new AuthorEntity();
        author.setId(5L);
        author.setName("Brandon Sanderson");
        author.setDescription("Fantasy author");
        author.setAsin("B001IGFHW6");

        when(authorRepository.findById(5L)).thenReturn(Optional.of(author));

        AuthorDetails details = service.getAuthorDetails(5L);

        assertThat(details.getId()).isEqualTo(5L);
        assertThat(details.getName()).isEqualTo("Brandon Sanderson");
        assertThat(details.getDescription()).isEqualTo("Fantasy author");
        assertThat(details.getAsin()).isEqualTo("B001IGFHW6");
    }

    @Test
    void getAuthorDetails_throwsWhenNotFound() {
        when(authorRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAuthorDetails(99L))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("Author not found");
    }

    @Test
    void getAuthorPhoto_returnsNullWhenFileDoesNotExist() {
        when(fileService.getAuthorPhotoFile(1L)).thenReturn("/nonexistent/path/photo.jpg");

        assertThat(service.getAuthorPhoto(1L)).isNull();
    }

    @Test
    void getAuthorThumbnail_returnsNullWhenFileDoesNotExist() {
        when(fileService.getAuthorThumbnailFile(1L)).thenReturn("/nonexistent/path/thumbnail.jpg");

        assertThat(service.getAuthorThumbnail(1L)).isNull();
    }

    @Test
    void matchAuthor_skipsPhotoWhenPhotoLocked() {
        AuthorEntity author = new AuthorEntity();
        author.setId(1L);
        author.setName("Test Author");
        author.setPhotoLocked(true);

        when(authorRepository.findById(1L)).thenReturn(Optional.of(author));

        AuthorSearchResult result = AuthorSearchResult.builder()
                .source(AuthorMetadataSource.AUDNEXUS).asin("B000APZGGS")
                .description("Bio").imageUrl("https://example.com/photo.jpg")
                .name("Test Author")
                .build();

        when(authorParser.getAuthorByAsin("B000APZGGS", "us")).thenReturn(result);
        when(authorRepository.save(any(AuthorEntity.class))).thenAnswer(i -> i.getArgument(0));

        AuthorMatchRequest request = new AuthorMatchRequest();
        request.setAsin("B000APZGGS");
        request.setSource(AuthorMetadataSource.AUDNEXUS);
        request.setRegion("us");

        service.matchAuthor(1L, request);

        verify(fileService, never()).createAuthorThumbnailFromUrl(anyLong(), anyString());
    }

    @Test
    void getAllAuthors_paginatesInsteadOfLoadingTheWholeTable() {
        Pageable pageable = PageRequest.of(2, 50);
        AuthorEntity author = authorEntity(1L, "Author One", null);
        when(authorRepository.findAllWithBookCount(pageable)).thenReturn(List.of(new Object[]{author, 3L}));
        when(authorRepository.countAllAuthors()).thenReturn(48_620L);
        stubEmptyEnrichment(Set.of(1L));
        when(fileService.listAuthorIdsWithPhotos()).thenReturn(Set.of());

        AuthorPage page = service.getAllAuthors(pageable);

        verify(authorRepository).findAllWithBookCount(pageable);
        assertThat(page.getTotalElements()).isEqualTo(48_620L);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getBookCount()).isEqualTo(3);
    }

    @Test
    void getAllAuthors_nonAdminScopesToAssignedLibraries() {
        BookLoreUser.UserPermissions nonAdmin = new BookLoreUser.UserPermissions();
        nonAdmin.setAdmin(false);
        Library library = new Library();
        library.setId(9L);
        BookLoreUser user = BookLoreUser.builder().id(2L).permissions(nonAdmin).assignedLibraries(List.of(library)).build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        Pageable pageable = PageRequest.of(0, 50);
        when(authorRepository.findAllWithBookCountByLibraryIds(eq(Set.of(9L)), eq(pageable))).thenReturn(List.of());
        when(authorRepository.countAllAuthorsByLibraryIds(Set.of(9L))).thenReturn(0L);

        service.getAllAuthors(pageable);

        verify(authorRepository).findAllWithBookCountByLibraryIds(Set.of(9L), pageable);
        verify(authorRepository, never()).findAllWithBookCount(any());
    }

    @Test
    void getAllAuthors_resolvesHasPhotoWithOneReaddirNeverPerAuthorStat() {
        Pageable pageable = PageRequest.of(0, 50);
        AuthorEntity withPhoto = authorEntity(1L, "Has Photo", null);
        AuthorEntity withoutPhoto = authorEntity(2L, "No Photo", null);
        when(authorRepository.findAllWithBookCount(pageable)).thenReturn(List.of(
                new Object[]{withPhoto, 1L}, new Object[]{withoutPhoto, 1L}
        ));
        when(authorRepository.countAllAuthors()).thenReturn(2L);
        when(fileService.listAuthorIdsWithPhotos()).thenReturn(Set.of(1L));
        stubEmptyEnrichment(Set.of(1L, 2L));

        AuthorPage page = service.getAllAuthors(pageable);

        Map<Long, AuthorSummary> byId = page.getContent().stream()
                .collect(java.util.stream.Collectors.toMap(AuthorSummary::getId, s -> s));
        assertThat(byId.get(1L).isHasPhoto()).isTrue();
        assertThat(byId.get(2L).isHasPhoto()).isFalse();

        // Proves the N+1 is gone: one directory listing, never a per-author file stat.
        verify(fileService, times(1)).listAuthorIdsWithPhotos();
        verify(fileService, never()).getAuthorThumbnailFile(anyLong());
    }

    @Test
    void getAllAuthors_aggregatesEnrichmentInBulkScopedToThePageAuthorIds() {
        Pageable pageable = PageRequest.of(0, 50);
        AuthorEntity author = authorEntity(5L, "Brandon Sanderson", null);
        when(authorRepository.findAllWithBookCount(pageable)).thenReturn(List.of(new Object[]{author, 4L}));
        when(authorRepository.countAllAuthors()).thenReturn(1L);
        when(fileService.listAuthorIdsWithPhotos()).thenReturn(Set.of());

        Set<Long> pageIds = Set.of(5L);
        when(authorRepository.findLibraryNamesForAuthors(pageIds)).thenReturn(List.of(
                libraryRow(5L, "Main"), libraryRow(5L, "Main"), libraryRow(5L, "Archive")
        ));
        when(authorRepository.findCategoriesForAuthors(pageIds)).thenReturn(List.of(categoryRow(5L, "Fantasy")));
        when(authorRepository.findSeriesNamesForAuthors(pageIds)).thenReturn(List.of(
                seriesRow(5L, "Mistborn"), seriesRow(5L, "mistborn")
        ));
        Instant older = Instant.parse("2025-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-01-01T00:00:00Z");
        when(authorRepository.findAddedOnForAuthors(pageIds)).thenReturn(List.of(addedOnRow(5L, older), addedOnRow(5L, newer)));
        when(authorRepository.findProgressForAuthors(pageIds, 1L)).thenReturn(List.of(
                progressRow(5L, ReadStatus.READ, null, 5),
                progressRow(5L, ReadStatus.READING, newer, 3)
        ));

        AuthorSummary summary = service.getAllAuthors(pageable).getContent().get(0);

        assertThat(summary.getLibraryNames()).containsExactlyInAnyOrder("Main", "Archive");
        assertThat(summary.getCategories()).containsExactly("Fantasy");
        assertThat(summary.getSeriesCount()).isEqualTo(1); // "Mistborn" and "mistborn" dedupe case-insensitively
        assertThat(summary.getLatestAddedOn()).isEqualTo(newer);
        assertThat(summary.getLastReadTime()).isEqualTo(newer);
        assertThat(summary.getReadCount()).isEqualTo(1);
        assertThat(summary.getInProgressCount()).isEqualTo(1);
        assertThat(summary.getAvgPersonalRating()).isEqualTo(4.0);
    }

    private void stubEmptyEnrichment(Set<Long> authorIds) {
        lenient().when(authorRepository.findLibraryNamesForAuthors(authorIds)).thenReturn(List.of());
        lenient().when(authorRepository.findCategoriesForAuthors(authorIds)).thenReturn(List.of());
        lenient().when(authorRepository.findSeriesNamesForAuthors(authorIds)).thenReturn(List.of());
        lenient().when(authorRepository.findAddedOnForAuthors(authorIds)).thenReturn(List.of());
        lenient().when(authorRepository.findProgressForAuthors(eq(authorIds), anyLong())).thenReturn(List.of());
    }

    private AuthorEntity authorEntity(Long id, String name, String asin) {
        AuthorEntity author = new AuthorEntity();
        author.setId(id);
        author.setName(name);
        author.setAsin(asin);
        return author;
    }

    private AuthorRepository.AuthorLibraryRow libraryRow(Long authorId, String libraryName) {
        AuthorRepository.AuthorLibraryRow row = mock(AuthorRepository.AuthorLibraryRow.class);
        lenient().when(row.getAuthorId()).thenReturn(authorId);
        lenient().when(row.getLibraryName()).thenReturn(libraryName);
        return row;
    }

    private AuthorRepository.AuthorCategoryRow categoryRow(Long authorId, String categoryName) {
        AuthorRepository.AuthorCategoryRow row = mock(AuthorRepository.AuthorCategoryRow.class);
        lenient().when(row.getAuthorId()).thenReturn(authorId);
        lenient().when(row.getCategoryName()).thenReturn(categoryName);
        return row;
    }

    private AuthorRepository.AuthorSeriesRow seriesRow(Long authorId, String seriesName) {
        AuthorRepository.AuthorSeriesRow row = mock(AuthorRepository.AuthorSeriesRow.class);
        lenient().when(row.getAuthorId()).thenReturn(authorId);
        lenient().when(row.getSeriesName()).thenReturn(seriesName);
        return row;
    }

    private AuthorRepository.AuthorAddedOnRow addedOnRow(Long authorId, Instant addedOn) {
        AuthorRepository.AuthorAddedOnRow row = mock(AuthorRepository.AuthorAddedOnRow.class);
        lenient().when(row.getAuthorId()).thenReturn(authorId);
        lenient().when(row.getAddedOn()).thenReturn(addedOn);
        return row;
    }

    private AuthorRepository.AuthorProgressRow progressRow(Long authorId, ReadStatus status, Instant lastReadTime, Integer rating) {
        AuthorRepository.AuthorProgressRow row = mock(AuthorRepository.AuthorProgressRow.class);
        lenient().when(row.getAuthorId()).thenReturn(authorId);
        lenient().when(row.getReadStatus()).thenReturn(status);
        lenient().when(row.getLastReadTime()).thenReturn(lastReadTime);
        lenient().when(row.getPersonalRating()).thenReturn(rating);
        return row;
    }
}
