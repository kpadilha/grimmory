package org.booklore.service.browse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.browse.BrowsePage;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.browse.SeriesSummary;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ReadStatus;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = BookloreApplication.class)
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:seriessummarytest;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
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
@Import(SeriesSummaryServiceTest.TestConfig.class)
class SeriesSummaryServiceTest {

    @Autowired
    private SeriesSummaryService seriesSummaryService;
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
        seriesSummaryService.clearCache();
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

    private BrowsePage<SeriesSummary> summaries(int page, int size, String sort, String query, String status) {
        return seriesSummaryService.getSeriesSummaries(page, size, sort, query, status);
    }

    @Test
    void aggregatesBookCountAuthorsAndCategoriesAcrossASeries() {
        bookInSeries("Book One", "Chronicles", 1f, "Author A", "Fantasy");
        bookInSeries("Book Two", "Chronicles", 2f, "Author B", "Adventure");
        em.flush();
        em.clear();

        List<SeriesSummary> content = summaries(0, 20, null, null, null).content();

        assertThat(content).hasSize(1);
        SeriesSummary series = content.get(0);
        assertThat(series.getSeriesName()).isEqualTo("Chronicles");
        assertThat(series.getBookCount()).isEqualTo(2);
        assertThat(series.getAuthors()).containsExactlyInAnyOrder("Author A", "Author B");
        assertThat(series.getCategories()).containsExactlyInAnyOrder("Fantasy", "Adventure");
    }

    @Test
    void capsCoverBooksAtThreeOrderedBySeriesNumber() {
        bookInSeries("Book Four", "Saga", 4f, "Author A", "Fantasy");
        bookInSeries("Book One", "Saga", 1f, "Author A", "Fantasy");
        bookInSeries("Book Three", "Saga", 3f, "Author A", "Fantasy");
        bookInSeries("Book Two", "Saga", 2f, "Author A", "Fantasy");
        em.flush();
        em.clear();

        SeriesSummary series = summaries(0, 20, null, null, null).content().get(0);

        assertThat(series.getBookCount()).isEqualTo(4);
        assertThat(series.getCoverBooks()).hasSize(3);
    }

    @Test
    void picksFirstNonReadBookAsNextUnreadAndTracksReadCount() {
        BookEntity first = bookInSeries("Book One", "Trilogy", 1f, "Author A", "Fantasy");
        BookEntity second = bookInSeries("Book Two", "Trilogy", 2f, "Author A", "Fantasy");
        bookInSeries("Book Three", "Trilogy", 3f, "Author A", "Fantasy");

        UserBookProgressEntity progress = UserBookProgressEntity.builder()
                .user(userEntity).book(first).readStatus(ReadStatus.READ).lastReadTime(Instant.now()).build();
        em.persist(progress);
        UserBookProgressEntity progress2 = UserBookProgressEntity.builder()
                .user(userEntity).book(second).readStatus(ReadStatus.READING).build();
        em.persist(progress2);
        em.flush();
        em.clear();

        SeriesSummary series = summaries(0, 20, null, null, null).content().get(0);

        assertThat(series.getReadCount()).isEqualTo(1);
        assertThat(series.getNextUnreadBookId()).isEqualTo(second.getId());
        assertThat(series.getSeriesStatus()).isEqualTo(ReadStatus.READING.name());
    }

    @Test
    void paginatesInNameOrderAndReportsTotalElementsAcrossTheWholeQuery() {
        bookInSeries("Book One", "Alpha", 1f, "Author A", "Fantasy");
        bookInSeries("Book One", "Bravo", 1f, "Author A", "Fantasy");
        bookInSeries("Book One", "Charlie", 1f, "Author A", "Fantasy");
        em.flush();
        em.clear();

        BrowsePage<SeriesSummary> firstPage = summaries(0, 2, "name-asc", null, null);
        assertThat(firstPage.content()).extracting(SeriesSummary::getSeriesName).containsExactly("Alpha", "Bravo");
        assertThat(firstPage.page().totalElements()).isEqualTo(3);
        assertThat(firstPage.page().totalPages()).isEqualTo(2);

        BrowsePage<SeriesSummary> secondPage = summaries(1, 2, "name-asc", null, null);
        assertThat(secondPage.content()).extracting(SeriesSummary::getSeriesName).containsExactly("Charlie");
        assertThat(secondPage.page().totalElements()).isEqualTo(3);
    }

    @Test
    void neverHydratesAuthorsOrCoversForSeriesOutsideTheRequestedPage() {
        bookInSeries("Book One", "Alpha", 1f, "Author A", "Fantasy");
        bookInSeries("Book One", "Bravo", 1f, "Author B", "Adventure");
        em.flush();
        em.clear();

        BrowsePage<SeriesSummary> firstPage = summaries(0, 1, "name-asc", null, null);

        assertThat(firstPage.content()).hasSize(1);
        assertThat(firstPage.content().get(0).getSeriesName()).isEqualTo("Alpha");
        assertThat(firstPage.content().get(0).getAuthors()).containsExactly("Author A");
    }

    @Test
    void searchesBySeriesNameServerSide() {
        bookInSeries("Book One", "Wheel of Time", 1f, "Author A", "Fantasy");
        bookInSeries("Book One", "Discworld", 1f, "Author B", "Comedy");
        em.flush();
        em.clear();

        BrowsePage<SeriesSummary> page = summaries(0, 20, null, "wheel", null);

        assertThat(page.content()).extracting(SeriesSummary::getSeriesName).containsExactly("Wheel of Time");
        assertThat(page.page().totalElements()).isEqualTo(1);
    }

    @Test
    void filtersByStatusServerSide() {
        BookEntity readBook = bookInSeries("Book One", "Finished", 1f, "Author A", "Fantasy");
        bookInSeries("Book One", "Untouched", 1f, "Author B", "Adventure");
        em.persist(UserBookProgressEntity.builder().user(userEntity).book(readBook).readStatus(ReadStatus.READ).build());
        em.flush();
        em.clear();

        BrowsePage<SeriesSummary> completed = summaries(0, 20, null, null, "completed");
        assertThat(completed.content()).extracting(SeriesSummary::getSeriesName).containsExactly("Finished");

        BrowsePage<SeriesSummary> notStarted = summaries(0, 20, null, null, "not-started");
        assertThat(notStarted.content()).extracting(SeriesSummary::getSeriesName).containsExactly("Untouched");
    }

    @Test
    void sortsByBookCountDescendingWhenRequested() {
        bookInSeries("Book One", "Solo", 1f, "Author A", "Fantasy");
        bookInSeries("Book One", "Duo", 1f, "Author B", "Adventure");
        bookInSeries("Book Two", "Duo", 2f, "Author B", "Adventure");
        em.flush();
        em.clear();

        BrowsePage<SeriesSummary> page = summaries(0, 20, "book-count", null, null);

        assertThat(page.content()).extracting(SeriesSummary::getSeriesName).containsExactly("Duo", "Solo");
    }

    // A non-admin user only sees series built from books in libraries assigned to them - the
    // paginated aggregate query must apply the same scope the old full-load call did.
    @Test
    void scopesSeriesToLibrariesAssignedToTheUser() {
        LibraryEntity otherLibrary = LibraryEntity.builder().name("Other").icon("book").watch(false)
                .formatPriority(List.of(BookFileType.EPUB)).build();
        em.persist(otherLibrary);
        LibraryPathEntity otherPath = LibraryPathEntity.builder().library(otherLibrary).path("/other").build();
        em.persist(otherPath);

        bookInSeries("Book One", "Visible", 1f, "Author A", "Fantasy");
        BookEntity hiddenBook = BookEntity.builder()
                .library(otherLibrary).libraryPath(otherPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(hiddenBook);
        BookMetadataEntity hiddenMetadata = BookMetadataEntity.builder()
                .book(hiddenBook).title("Hidden Book").seriesName("Hidden").seriesNumber(1f).build();
        em.persist(hiddenMetadata);
        hiddenBook.setMetadata(hiddenMetadata);
        em.flush();
        em.clear();

        BrowsePage<SeriesSummary> page = summaries(0, 20, null, null, null);

        assertThat(page.content()).extracting(SeriesSummary::getSeriesName).containsExactly("Visible");
        assertThat(page.page().totalElements()).isEqualTo(1);
    }

    private BookEntity bookInSeries(String title, String seriesName, Float seriesNumber, String authorName, String genre) {
        BookEntity bookEntity = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(bookEntity);
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(bookEntity).title(title).seriesName(seriesName).seriesNumber(seriesNumber).build();
        metadata.setAuthors(List.of(author(authorName)));
        metadata.setCategories(java.util.Set.of(category(genre)));
        em.persist(metadata);
        bookEntity.setMetadata(metadata);
        return bookEntity;
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
}
