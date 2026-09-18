package org.booklore.service.browse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.browse.Link;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.browse.FacetGroupsResponse;
import org.booklore.model.dto.browse.FacetGroupsResponse.FacetGroup;
import org.booklore.model.dto.browse.FacetGroupsResponse.FacetLink;
import org.booklore.model.dto.browse.FacetValueBookIds;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.ComicCreatorEntity;
import org.booklore.model.entity.ComicCreatorMappingEntity;
import org.booklore.model.entity.ComicMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ComicCreatorRole;
import org.booklore.model.enums.ContentRestrictionMode;
import org.booklore.model.enums.ContentRestrictionType;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = BookloreApplication.class)
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:facettest;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false"
})
@Import(BookFacetServiceTest.TestConfig.class)
class BookFacetServiceTest {

    @Autowired
    private BookFacetService facetService;
    @Autowired
    private ObjectMapper springMapper;
    @MockitoBean
    private AuthenticationService authenticationService;

    @PersistenceContext
    private EntityManager em;

    private BookLoreUserEntity userEntity;
    private LibraryEntity library;
    private LibraryPathEntity libraryPath;
    private final Map<String, CategoryEntity> categories = new HashMap<>();
    private final Map<String, AuthorEntity> authors = new HashMap<>();

    @TestConfiguration
    public static class TestConfig {
        @Bean("flyway")
        @Primary
        public Flyway flyway() {
            return mock(Flyway.class);
        }

        @Bean
        @Primary
        public TaskCronService taskCronService() {
            return mock(TaskCronService.class);
        }
    }

    @BeforeEach
    void seed() {
        facetService.clearCache();
        userEntity = BookLoreUserEntity.builder().username("reader").passwordHash("x").name("Reader").build();
        em.persist(userEntity);
        library = LibraryEntity.builder().name("Lib").icon("book").watch(false)
                .formatPriority(List.of(BookFileType.EPUB)).build();
        em.persist(library);
        libraryPath = LibraryPathEntity.builder().library(library).path("/p").build();
        em.persist(libraryPath);
        when(authenticationService.getAuthenticatedUser()).thenReturn(nonAdminUser());
    }

    private BookLoreUser nonAdminUser() {
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(false);
        return BookLoreUser.builder()
                .id(userEntity.getId())
                .assignedLibraries(List.of(Library.builder().id(library.getId()).build()))
                .permissions(permissions)
                .build();
    }

    private void book(String title, String genre, String authorName) {
        BookEntity bookEntity = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(bookEntity);
        BookMetadataEntity metadata = BookMetadataEntity.builder().book(bookEntity).title(title).build();
        metadata.setCategories(java.util.Set.of(category(genre)));
        metadata.setAuthors(List.of(author(authorName)));
        em.persist(metadata);
        bookEntity.setMetadata(metadata);
    }

    private void bookInSeries(String title, String seriesName) {
        BookEntity bookEntity = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(bookEntity);
        BookMetadataEntity metadata = BookMetadataEntity.builder().book(bookEntity).title(title).seriesName(seriesName).build();
        em.persist(metadata);
        bookEntity.setMetadata(metadata);
    }

    private BookMetadataEntity bookWithMetadata(String title) {
        BookEntity bookEntity = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(bookEntity);
        BookMetadataEntity metadata = BookMetadataEntity.builder().book(bookEntity).title(title).build();
        em.persist(metadata);
        bookEntity.setMetadata(metadata);
        return metadata;
    }

    // metadataMatchScore lives on BookEntity, not BookMetadataEntity - unlike the rating fields.
    private void bookWithMatchScore(String title, float score) {
        BookEntity bookEntity = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false)
                .metadataMatchScore(score).build();
        em.persist(bookEntity);
        BookMetadataEntity metadata = BookMetadataEntity.builder().book(bookEntity).title(title).build();
        em.persist(metadata);
        bookEntity.setMetadata(metadata);
    }

    private BookEntity physicalBook(String title) {
        BookEntity bookEntity = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false).isPhysical(true).build();
        em.persist(bookEntity);
        BookMetadataEntity metadata = BookMetadataEntity.builder().book(bookEntity).title(title).build();
        em.persist(metadata);
        bookEntity.setMetadata(metadata);
        return bookEntity;
    }

    private BookMetadataEntity bookWithFile(String title, BookFileType type, Long fileSizeKb) {
        BookEntity bookEntity = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(bookEntity);
        BookMetadataEntity metadata = BookMetadataEntity.builder().book(bookEntity).title(title).build();
        em.persist(metadata);
        bookEntity.setMetadata(metadata);
        BookFileEntity file = BookFileEntity.builder()
                .book(bookEntity).fileName(title).fileSubPath("").isBookFormat(true)
                .bookType(type).fileSizeKb(fileSizeKb).build();
        em.persist(file);
        return metadata;
    }

    private BookMetadataEntity bookWithComicCreator(String title, String creatorName, ComicCreatorRole... roles) {
        BookEntity bookEntity = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(bookEntity);
        BookMetadataEntity metadata = BookMetadataEntity.builder().book(bookEntity).title(title).build();
        em.persist(metadata);
        bookEntity.setMetadata(metadata);

        ComicMetadataEntity comicMetadata = ComicMetadataEntity.builder().bookId(metadata.getBookId()).build();
        em.persist(comicMetadata);
        metadata.setComicMetadata(comicMetadata);

        ComicCreatorEntity creator = ComicCreatorEntity.builder().name(creatorName).build();
        em.persist(creator);
        java.util.Set<ComicCreatorMappingEntity> mappings = new java.util.HashSet<>();
        for (ComicCreatorRole role : roles) {
            ComicCreatorMappingEntity mapping = ComicCreatorMappingEntity.builder()
                    .comicMetadata(comicMetadata).creator(creator).role(role).build();
            em.persist(mapping);
            mappings.add(mapping);
        }
        comicMetadata.setCreatorMappings(mappings);
        return metadata;
    }

    private CategoryEntity category(String name) {
        return categories.computeIfAbsent(name, n -> {
            CategoryEntity e = CategoryEntity.builder().name(n).build();
            em.persist(e);
            return e;
        });
    }

    private AuthorEntity author(String name) {
        return authors.computeIfAbsent(name, n -> {
            AuthorEntity e = AuthorEntity.builder().name(n).build();
            em.persist(e);
            return e;
        });
    }

    private FacetGroup group(FacetGroupsResponse response, String key) {
        return response.facets().stream()
                .filter(g -> g.metadata() != null && key.equals(g.metadata().key()))
                .findFirst().orElseThrow();
    }

    private Long count(FacetGroup group, String value) {
        Optional<FacetLink> link = group.links().stream().filter(l -> value.equals(l.value())).findFirst();
        return link.map(l -> l.properties().numberOfItems()).orElse(null);
    }

    private FacetLink link(FacetGroup group, String value) {
        return group.links().stream()
                .filter(l -> value.equals(l.value()))
                .findFirst().orElseThrow();
    }

    @Test
    void countsDiscreteFacetsWithCounts() {
        book("A", "Horror", "Alice");
        book("B", "Romance", "Bob");
        em.flush();

        FacetGroupsResponse response = facetService.getFacets(null, null, null);

        assertThat(count(group(response, "genre"), "Horror")).isEqualTo(1);
        assertThat(count(group(response, "genre"), "Romance")).isEqualTo(1);
        assertThat(group(response, "author").links()).extracting(FacetLink::value).contains("Alice", "Bob");
    }

    @Test
    void selectedFacetIsOmittedFromItsOwnCounts() {
        book("A", "Horror", "Alice");
        book("B", "Horror", "Alice");
        book("C", "Romance", "Bob");
        em.flush();

        FacetGroupsResponse response = facetService.getFacets(List.of("genre:Horror"), null, null);

        // genre omits itself: both Horror (2) and Romance (1) still appear with full counts.
        assertThat(count(group(response, "genre"), "Horror")).isEqualTo(2);
        assertThat(count(group(response, "genre"), "Romance")).isEqualTo(1);

        // a different facet honors the genre:Horror filter: only Horror authors remain.
        assertThat(group(response, "author").links()).extracting(FacetLink::value).containsExactly("Alice");
    }

    @Test
    void valuesAreOrderedByCountDescending() {
        book("A", "Horror", "Alice");
        book("B", "Horror", "Alice");
        book("C", "Romance", "Bob");
        em.flush();

        List<String> genres = group(facetService.getFacets(null, null, null), "genre").links()
                .stream().map(FacetLink::value).toList();
        assertThat(genres).containsExactly("Horror", "Romance");
    }

    @Test
    void linksCarryAddHrefAndCount() {
        book("A", "Horror", "Alice");
        em.flush();

        FacetLink horror = link(group(facetService.getFacets(null, null, null), "genre"), "Horror");
        assertThat(horror.href()).isEqualTo("/api/v1/books/page?facet=genre%3AHorror");
        assertThat(horror.properties().numberOfItems()).isEqualTo(1);
        assertThat(horror.rel()).containsExactly("facet");
    }

    @Test
    void responseIsCachedPerParameters() {
        book("A", "Horror", "Alice");
        em.flush();
        FacetGroupsResponse first = facetService.getFacets(null, null, null);
        FacetGroupsResponse second = facetService.getFacets(null, null, null);
        assertThat(first).isSameAs(second);
    }

    @Test
    void groupParamComputesOnlyTheRequestedFacet() {
        book("A", "Horror", "Alice");
        book("B", "Romance", "Bob");
        em.flush();

        FacetGroupsResponse response = facetService.getFacets(null, null, null, List.of("genre"));

        assertThat(response.facets()).extracting(g -> g.metadata().key())
                .containsExactlyInAnyOrder("sort", "genre");
        assertThat(count(group(response, "genre"), "Horror")).isEqualTo(1);
    }

    @Test
    void groupParamAcceptsMultipleKeys() {
        book("A", "Horror", "Alice");
        em.flush();

        FacetGroupsResponse response = facetService.getFacets(null, null, null, List.of("genre", "author"));

        assertThat(response.facets()).extracting(g -> g.metadata().key())
                .containsExactlyInAnyOrder("sort", "genre", "author");
    }

    @Test
    void includesSortGroup() {
        book("A", "Horror", "Alice");
        em.flush();
        FacetGroup sort = group(facetService.getFacets(null, null, null), "sort");
        assertThat(sort.metadata().rel()).isEqualTo("sort");
        assertThat(sort.links()).extracting(FacetLink::value).contains("title", "-title");
        assertThat(sort.links()).allSatisfy(l -> assertThat(l.rel()).containsExactly("sort"));
    }

    @Test
    void emptyFacetListBehavesLikeNullFacet() {
        book("A", "Horror", "Alice");
        book("B", "Romance", "Bob");
        em.flush();

        FacetGroupsResponse withNull = facetService.getFacets(null, null, null);
        FacetGroupsResponse withEmpty = facetService.getFacets(List.of(), null, null);

        assertThat(count(group(withEmpty, "genre"), "Horror")).isEqualTo(count(group(withNull, "genre"), "Horror"));
        assertThat(count(group(withEmpty, "genre"), "Romance")).isEqualTo(count(group(withNull, "genre"), "Romance"));
        assertThat(count(group(withEmpty, "genre"), "Horror")).isEqualTo(1);
    }

    @Test
    void multipleSelectionsFilterAcrossFacets() {
        book("A", "Horror", "Alice");
        book("B", "Horror", "Bob");
        book("C", "Romance", "Alice");
        em.flush();

        FacetGroupsResponse response = facetService.getFacets(List.of("genre:Horror", "author:Alice"), null, null);

        assertThat(count(group(response, "genre"), "Horror")).isEqualTo(1);
        assertThat(count(group(response, "genre"), "Romance")).isEqualTo(1);

        assertThat(count(group(response, "author"), "Alice")).isEqualTo(1);
        assertThat(count(group(response, "author"), "Bob")).isEqualTo(1);
    }

    @Test
    void facetLogicCombinesSelectedValues() {
        book("A", "Horror", "Alice");
        book("B", "Romance", "Bob");
        book("C", "Fantasy", "Cara");
        em.flush();

        List<String> genres = List.of("genre:Horror", "genre:Romance");

        assertThat(group(facetService.getFacets(genres, "or", null), "author").links())
                .extracting(FacetLink::value).containsExactlyInAnyOrder("Alice", "Bob");

        assertThat(group(facetService.getFacets(genres, "and", null), "author").links()).isEmpty();

        assertThat(group(facetService.getFacets(genres, "not", null), "author").links())
                .extracting(FacetLink::value).containsExactly("Cara");
    }

    @Test
    void truncatesValuesAtMax() {
        for (int i = 0; i < 101; i++) {
            book("T" + i, "Genre" + i, "Author" + i);
        }
        em.flush();

        FacetGroupsResponse response = facetService.getFacets(null, null, null);

        assertThat(group(response, "genre").links()).hasSize(100);
        assertThat(group(response, "author").links()).hasSize(100);
    }

    @Test
    void seriesDistinctCountIsExactBeyondTheCap() {
        for (int i = 0; i < 101; i++) {
            bookInSeries("S" + i + "-1", "Series" + i);
        }
        em.flush();

        FacetGroup series = group(facetService.getFacets(null, null, null), "series");

        assertThat(series.links()).hasSize(100);
        assertThat(series.distinctCount()).isEqualTo(101);
    }

    @Test
    void nonDistinctCountFacetsLeaveDistinctCountNull() {
        book("A", "Horror", "Alice");
        em.flush();

        FacetGroupsResponse response = facetService.getFacets(null, null, null);

        assertThat(group(response, "genre").distinctCount()).isNull();
        assertThat(group(response, "author").distinctCount()).isNull();
    }

    // Domain size (distinct libraries) is what the cap counts, not book count - only the admin
    // view sees every library, so this proves 101 distinct values survive uncapped for that view.
    @Test
    void libraryFacetIsNeverCappedSoSidebarCountsStayComplete() {
        BookLoreUser.UserPermissions adminPermissions = new BookLoreUser.UserPermissions();
        adminPermissions.setAdmin(true);
        when(authenticationService.getAuthenticatedUser()).thenReturn(
                BookLoreUser.builder().id(userEntity.getId()).permissions(adminPermissions).build());

        for (int i = 0; i < 101; i++) {
            LibraryEntity lib = LibraryEntity.builder().name("Lib" + i).icon("book").watch(false)
                    .formatPriority(List.of(BookFileType.EPUB)).build();
            em.persist(lib);
            LibraryPathEntity path = LibraryPathEntity.builder().library(lib).path("/p" + i).build();
            em.persist(path);
            BookEntity bookEntity = BookEntity.builder().library(lib).libraryPath(path).addedOn(Instant.now()).deleted(false).build();
            em.persist(bookEntity);
        }
        em.flush();

        FacetGroup libraryGroup = group(facetService.getFacets(null, null, null), "library");

        assertThat(libraryGroup.links()).hasSize(101);
    }

    // A non-admin only ever sees their own assigned library, so the unbounded distinct count
    // for a facet outside it must stay zero - proves the scoping isn't lost with the cap removed.
    @Test
    void distinctCountNeverCountsSeriesOutsideTheUsersAssignedLibrary() {
        LibraryEntity otherLibrary = LibraryEntity.builder().name("Other").icon("book").watch(false)
                .formatPriority(List.of(BookFileType.EPUB)).build();
        em.persist(otherLibrary);
        LibraryPathEntity otherPath = LibraryPathEntity.builder().library(otherLibrary).path("/other").build();
        em.persist(otherPath);

        BookEntity otherBook = BookEntity.builder()
                .library(otherLibrary).libraryPath(otherPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(otherBook);
        BookMetadataEntity otherMetadata = BookMetadataEntity.builder().book(otherBook).title("Hidden").seriesName("HiddenSeries").build();
        em.persist(otherMetadata);
        otherBook.setMetadata(otherMetadata);

        bookInSeries("Visible", "VisibleSeries");
        em.flush();

        FacetGroup series = group(facetService.getFacets(null, null, null), "series");

        assertThat(series.distinctCount()).isEqualTo(1);
        assertThat(series.links()).extracting(FacetLink::value).containsExactly("VisibleSeries");
    }

    @Test
    void activeFacetIsMarkedSelfWithCurrentPageHref() {
        book("A", "Horror", "Alice");
        book("B", "Romance", "Bob");
        em.flush();

        FacetGroup genre = group(facetService.getFacets(List.of("genre:Horror"), null, null), "genre");
        FacetLink horror = link(genre, "Horror");
        FacetLink romance = link(genre, "Romance");

        assertThat(horror.rel()).containsExactly("self", "facet");
        assertThat(horror.href()).isEqualTo("/api/v1/books/page?facet=genre%3AHorror");

        assertThat(romance.rel()).containsExactly("facet");
        assertThat(romance.href()).isEqualTo("/api/v1/books/page?facet=genre%3AHorror&facet=genre%3ARomance");
    }

    @Test
    void activeFacetSelfHrefKeepsAllSelections() {
        book("A", "Horror", "Alice");
        em.flush();

        FacetGroup genre = group(facetService.getFacets(List.of("genre:Horror", "author:Alice"), null, null), "genre");
        FacetLink horror = link(genre, "Horror");

        assertThat(horror.rel()).containsExactly("self", "facet");
        assertThat(horror.href()).isEqualTo("/api/v1/books/page?facet=genre%3AHorror&facet=author%3AAlice");
    }

    @Test
    void activeFacetMatchIsCaseInsensitive() {
        book("A", "Horror", "Alice");
        em.flush();

        FacetGroup genre = group(facetService.getFacets(List.of("genre:horror"), null, null), "genre");
        FacetLink horror = link(genre, "Horror");

        assertThat(horror.rel()).contains("self");
    }

    @Test
    void responseCarriesTopLevelSelfLink() {
        book("A", "Horror", "Alice");
        em.flush();

        Link bare = facetService.getFacets(null, null, null).links().getFirst();
        assertThat(bare.rel()).containsExactly("self");
        assertThat(bare.href()).isEqualTo("/api/v1/books/facets");
        assertThat(bare.type()).isEqualTo(Link.JSON_TYPE);

        Link filtered = facetService.getFacets(List.of("genre:Horror"), null, "dune").links().getFirst();
        assertThat(filtered.rel()).containsExactly("self");
        assertThat(filtered.href()).isEqualTo("/api/v1/books/facets?facet=genre%3AHorror&query=dune");
    }

    // Serializes through the Spring-managed Jackson 3 mapper, the same one the HTTP
    // layer uses, so a mapper/annotation mismatch can't slip through unit tests again.
    @Test
    void springMapperSerializesSingleRelAsString() {
        book("A", "Horror", "Alice");
        em.flush();

        String json = springMapper.writeValueAsString(facetService.getFacets(List.of("genre:Horror"), null, null));

        assertThat(json).contains("\"rel\":\"self\"");
        assertThat(json).contains("\"rel\":[\"self\",\"facet\"]");
        assertThat(json).doesNotContain("\"rel\":[\"self\"]");
        assertThat(json).doesNotContain("\"rel\":[\"facet\"]");
    }

    @Test
    void amazonRatingBucketsIntoRangesNotExactValues() {
        bookWithMetadata("Low").setAmazonRating(0.5);
        bookWithMetadata("High").setAmazonRating(4.7);
        em.flush();

        FacetGroup ratings = group(facetService.getFacets(null, null, null), "amazon_rating");

        assertThat(ratings.links()).extracting(FacetLink::value).containsExactlyInAnyOrder("0", "5");
        assertThat(count(ratings, "0")).isEqualTo(1);
        assertThat(count(ratings, "5")).isEqualTo(1);
    }

    // The same bucket table backs counting and the click-through filter (BookFacetRegistry) - a
    // book right on a bucket boundary must count under the higher bucket in both places.
    @Test
    void amazonRatingBucketBoundaryIsInclusiveOnTheLowerEdge() {
        BookMetadataEntity metadata = bookWithMetadata("Boundary");
        metadata.setAmazonRating(4.0);
        metadata.setAuthors(List.of(author("Boundary Author")));
        em.flush();

        FacetGroup ratings = group(facetService.getFacets(null, null, null), "amazon_rating");
        assertThat(count(ratings, "3")).isNull();
        assertThat(count(ratings, "4")).isEqualTo(1);

        // BookFacetRegistry.numericBucket must parse bucket id "4" into the same [4, 4.5) range
        // the count above used, otherwise selecting this bucket on /books/page would 0-match it.
        assertThat(group(facetService.getFacets(List.of("amazon_rating:4"), null, null), "author").links())
                .extracting(FacetLink::value).containsExactly("Boundary Author");
        assertThat(group(facetService.getFacets(List.of("amazon_rating:3"), null, null), "author").links())
                .isEmpty();
    }

    @Test
    void matchScorePageCountAndFileSizeBucketIntoTheirOwnRanges() {
        bookWithMatchScore("Scored", 40f);
        bookWithMetadata("Long").setPageCount(1200);
        bookWithFile("Big", BookFileType.EPUB, 3_000_000L);
        em.flush();

        FacetGroupsResponse response = facetService.getFacets(null, null, null);
        assertThat(count(group(response, "match_score"), "5")).isEqualTo(1);
        assertThat(count(group(response, "page_count"), "6")).isEqualTo(1);
        assertThat(count(group(response, "file_size"), "7")).isEqualTo(1);
    }

    @Test
    void fileTypeGivesPhysicalBooksTheirOwnBucket() {
        physicalBook("Paperback");
        bookWithFile("Ebook", BookFileType.EPUB, 1000L);
        em.flush();

        FacetGroup fileType = group(facetService.getFacets(null, null, null), "file_type");

        assertThat(count(fileType, "PHYSICAL")).isEqualTo(1);
        assertThat(count(fileType, "EPUB")).isEqualTo(1);
    }

    @Test
    void physicalFilterMatchesOnlyPhysicalBooks() {
        physicalBook("Paperback");
        bookWithFile("Ebook", BookFileType.EPUB, 1000L);
        em.flush();

        FacetGroupsResponse response = facetService.getFacets(List.of("file_type:PHYSICAL"), null, null);
        assertThat(group(response, "file_type").links())
                .filteredOn(l -> l.rel().contains("self"))
                .extracting(FacetLink::value)
                .containsExactly("PHYSICAL");
    }

    @Test
    void comicCreatorGroupsByNameAndRoleSeparately() {
        bookWithComicCreator("Issue 1", "Jack Kirby", ComicCreatorRole.PENCILLER, ComicCreatorRole.INKER);
        em.flush();

        FacetGroup creators = group(facetService.getFacets(null, null, null), "comic_creator");

        assertThat(creators.links()).extracting(FacetLink::value)
                .containsExactlyInAnyOrder("Jack Kirby:penciller", "Jack Kirby:inker");
        assertThat(count(creators, "Jack Kirby:penciller")).isEqualTo(1);
        assertThat(count(creators, "Jack Kirby:inker")).isEqualTo(1);
    }

    @Test
    void facetValueBookIdsIsExhaustiveAndNeverCapped() {
        book("A", "Horror", "Alice");
        book("B", "Horror", "Bob");
        book("C", "Romance", "Alice");
        em.flush();

        Map<String, List<FacetValueBookIds>> result = facetService.getFacetValueBookIds(List.of("author", "genre"));

        List<FacetValueBookIds> authors = result.get("author");
        FacetValueBookIds alice = authors.stream().filter(f -> f.value().equals("Alice")).findFirst().orElseThrow();
        assertThat(alice.bookIds()).hasSize(2);

        List<FacetValueBookIds> genres = result.get("genre");
        assertThat(genres).extracting(FacetValueBookIds::value).containsExactlyInAnyOrder("Horror", "Romance");
    }

    @Test
    void searchFacetValuesMatchesCaseInsensitivePrefix() {
        book("A", "Horror", "Alice Adams");
        book("B", "Horror", "Alan Turing");
        book("C", "Romance", "Bob Marley");
        em.flush();

        assertThat(facetService.searchFacetValues("author", "al", 20))
                .containsExactlyInAnyOrder("Alice Adams", "Alan Turing");
    }

    @Test
    void searchFacetValuesCapsAtLimit() {
        for (int i = 0; i < 10; i++) {
            book("T" + i, "Genre" + i, "Author" + i);
        }
        em.flush();

        assertThat(facetService.searchFacetValues("author", "", 3)).hasSize(3);
    }

    @Test
    void searchFacetValuesNeverCrossesLibraryScope() {
        LibraryEntity otherLibrary = LibraryEntity.builder().name("Other").icon("book").watch(false)
                .formatPriority(List.of(BookFileType.EPUB)).build();
        em.persist(otherLibrary);
        LibraryPathEntity otherPath = LibraryPathEntity.builder().library(otherLibrary).path("/other").build();
        em.persist(otherPath);
        BookEntity otherBook = BookEntity.builder()
                .library(otherLibrary).libraryPath(otherPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(otherBook);
        BookMetadataEntity otherMetadata = BookMetadataEntity.builder().book(otherBook).title("Hidden").build();
        otherMetadata.setAuthors(List.of(author("Hidden Author")));
        em.persist(otherMetadata);
        otherBook.setMetadata(otherMetadata);

        book("Visible", "Horror", "Visible Author");
        em.flush();

        assertThat(facetService.searchFacetValues("author", "", 20)).containsExactly("Visible Author");
    }

    @Test
    void searchFacetValuesRejectsUnknownFacet() {
        assertThatThrownBy(() -> facetService.searchFacetValues("not-a-facet", "", 20))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("Unknown facet");
    }

    // Guards the id-grouped fast path in countByLookup(): a book with two authors and two
    // categories must not inflate either facet's count - each value still reflects exactly the
    // one book that carries it, not a join fan-out from the other many-to-many association.
    @Test
    void multiValueAssociationsDoNotInflateOtherFacetCounts() {
        BookMetadataEntity metadata = bookWithMetadata("Multi");
        metadata.setAuthors(List.of(author("Alice"), author("Bob")));
        metadata.setCategories(java.util.Set.of(category("Horror"), category("Romance")));
        em.flush();

        FacetGroupsResponse response = facetService.getFacets(null, null, null);

        assertThat(count(group(response, "author"), "Alice")).isEqualTo(1);
        assertThat(count(group(response, "author"), "Bob")).isEqualTo(1);
        assertThat(count(group(response, "genre"), "Horror")).isEqualTo(1);
        assertThat(count(group(response, "genre"), "Romance")).isEqualTo(1);
    }

    @Test
    void deletedBooksAreExcludedFromLookupFacetCounts() {
        book("A", "Horror", "Alice");
        BookEntity deleted = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(true).build();
        em.persist(deleted);
        BookMetadataEntity deletedMetadata = BookMetadataEntity.builder().book(deleted).title("Gone").build();
        deletedMetadata.setCategories(java.util.Set.of(category("Horror")));
        deletedMetadata.setAuthors(List.of(author("Alice")));
        em.persist(deletedMetadata);
        deleted.setMetadata(deletedMetadata);
        em.flush();

        FacetGroupsResponse response = facetService.getFacets(null, null, null);

        assertThat(count(group(response, "genre"), "Horror")).isEqualTo(1);
        assertThat(count(group(response, "author"), "Alice")).isEqualTo(1);
    }

    // Admin bypasses both the assigned-library filter and ContentRestriction (BookFilterSpecifications.base),
    // so facet counts must span every library - proves countByLookup() didn't hardcode a scope.
    @Test
    void adminSeesBooksAcrossAllLibrariesInFacetCounts() {
        LibraryEntity otherLibrary = LibraryEntity.builder().name("Other").icon("book").watch(false)
                .formatPriority(List.of(BookFileType.EPUB)).build();
        em.persist(otherLibrary);
        LibraryPathEntity otherPath = LibraryPathEntity.builder().library(otherLibrary).path("/other").build();
        em.persist(otherPath);
        BookEntity otherBook = BookEntity.builder()
                .library(otherLibrary).libraryPath(otherPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(otherBook);
        BookMetadataEntity otherMetadata = BookMetadataEntity.builder().book(otherBook).title("Elsewhere").build();
        otherMetadata.setAuthors(List.of(author("Carol")));
        otherMetadata.setCategories(java.util.Set.of(category("SciFi")));
        em.persist(otherMetadata);
        otherBook.setMetadata(otherMetadata);

        book("A", "Horror", "Alice");
        em.flush();

        BookLoreUser.UserPermissions adminPermissions = new BookLoreUser.UserPermissions();
        adminPermissions.setAdmin(true);
        when(authenticationService.getAuthenticatedUser()).thenReturn(
                BookLoreUser.builder().id(userEntity.getId()).permissions(adminPermissions).build());

        FacetGroupsResponse response = facetService.getFacets(null, null, null);

        assertThat(group(response, "author").links()).extracting(FacetLink::value).containsExactlyInAnyOrder("Alice", "Carol");
        assertThat(group(response, "genre").links()).extracting(FacetLink::value).containsExactlyInAnyOrder("Horror", "SciFi");
    }

    // A restricted (non-admin) user's ContentRestriction excludes a whole category - the lookup
    // facet path must honour it exactly like the plain-count path already does for other facets.
    @Test
    void contentRestrictionHidesExcludedCategoryFromNonAdminFacetCounts() {
        book("A", "Horror", "Alice");
        book("B", "Romance", "Bob");
        em.persist(UserContentRestrictionEntity.builder()
                .user(userEntity)
                .restrictionType(ContentRestrictionType.CATEGORY)
                .mode(ContentRestrictionMode.EXCLUDE)
                .value("Romance")
                .build());
        em.flush();

        FacetGroupsResponse response = facetService.getFacets(null, null, null);

        assertThat(group(response, "genre").links()).extracting(FacetLink::value).containsExactly("Horror");
        assertThat(group(response, "author").links()).extracting(FacetLink::value).containsExactly("Alice");
    }
}
