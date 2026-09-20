package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.dto.Book;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.model.entity.UserPermissionsEntity;
import org.booklore.model.enums.ContentRestrictionMode;
import org.booklore.model.enums.ContentRestrictionType;
import org.booklore.model.enums.OpdsSortOrder;
import org.booklore.security.policy.ContentRestrictionSpecification;
import org.booklore.service.opds.OpdsBookService;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import org.springframework.boot.test.context.TestConfiguration;



@SpringBootTest(classes = {
        BookloreApplication.class
})
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false",
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
@Import(BookOpdsRepositoryDataJpaTest.TestConfig.class)
class BookOpdsRepositoryDataJpaTest {

    @Autowired
    private BookOpdsRepository bookOpdsRepository;

    @Autowired
    private OpdsBookService opdsBookService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LegacyOpdsSortProbeRepository legacyOpdsSortProbeRepository;

    @PersistenceContext
    private EntityManager entityManager;

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

    @Test
    void contextLoads() {
        assertThat(bookOpdsRepository).isNotNull();
    }

    @Test
    void findAllWithMetadataByIds_executesAgainstJpaMetamodel() {
        LibraryEntity library = LibraryEntity.builder()
                .name("Test Library")
                .icon("book")
                .watch(false)
                .build();
        entityManager.persist(library);
        entityManager.flush();

        LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                .library(library)
                .path("/test/path")
                .build();
        entityManager.persist(libraryPath);
        entityManager.flush();

        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(libraryPath)
                .addedOn(Instant.now())
                .deleted(false)
                .build();
        entityManager.persist(book);
        entityManager.flush();

        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book)
                .bookId(book.getId())
                .title("Test Title")
                .build();
        entityManager.persist(metadata);
        entityManager.flush();

        List<BookEntity> result = bookOpdsRepository.findAllWithMetadataByIds(List.of(book.getId()));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(book.getId());
    }

    /**
     * Regression for the cross-page OPDS sort bug: applySortOrder sorted only the page already
     * fetched in addedOn order, so concatenating page 0 and page 1 was locally, not globally,
     * sorted. getBooksPage(..., sortOrder) now pushes the Sort into the id query itself.
     */
    @Test
    void getBooksPage_sortsGloballyAcrossPages_forTitleAndAuthor() {
        BookLoreUserEntity admin = BookLoreUserEntity.builder()
                .username("admin-sort-test")
                .passwordHash("hash")
                .isDefaultPassword(false)
                .name("Admin")
                .createdAt(LocalDateTime.now())
                .build();
        entityManager.persist(admin);
        entityManager.persist(UserPermissionsEntity.builder().user(admin).permissionAdmin(true).build());

        LibraryEntity library = LibraryEntity.builder().name("Sort Test Library").icon("book").watch(false).build();
        entityManager.persist(library);
        LibraryPathEntity libraryPath = LibraryPathEntity.builder().library(library).path("/sort/test").build();
        entityManager.persist(libraryPath);
        entityManager.flush();

        Instant base = Instant.now().minus(10, ChronoUnit.DAYS);
        // addedOn order (Charlie, Alpha, Delta, Bravo) is neither title order nor its reverse,
        // so paging by addedOn alone can never fake a correct title/author sort.
        persistBook(library, libraryPath, "Charlie", "Charlie", base);
        persistBook(library, libraryPath, "Alpha", "Alpha", base.plusSeconds(60));
        persistBook(library, libraryPath, "Delta", "Delta", base.plusSeconds(120));
        persistBook(library, libraryPath, "Bravo", "Bravo", base.plusSeconds(180));
        entityManager.flush();
        entityManager.clear();

        // Old behaviour: fetch each page in DB (addedOn DESC) order, then sort only that page in
        // memory - still exactly what applySortOrder does today for the one caller that still
        // needs it (the magic-shelf branch).
        Page<Book> oldPage0 = opdsBookService.applySortOrder(
                opdsBookService.getBooksPage(admin.getId(), null, null, null, 0, 2), OpdsSortOrder.TITLE_ASC);
        Page<Book> oldPage1 = opdsBookService.applySortOrder(
                opdsBookService.getBooksPage(admin.getId(), null, null, null, 1, 2), OpdsSortOrder.TITLE_ASC);
        List<String> oldConcatenatedTitles = concatTitles(oldPage0, oldPage1);
        assertThat(oldConcatenatedTitles)
                .as("per-page sort is not globally ordered: this is the bug")
                .isNotEqualTo(List.of("Alpha", "Bravo", "Charlie", "Delta"));

        Page<Book> newPage0 = opdsBookService.getBooksPage(admin.getId(), null, null, null, 0, 2, OpdsSortOrder.TITLE_ASC);
        Page<Book> newPage1 = opdsBookService.getBooksPage(admin.getId(), null, null, null, 1, 2, OpdsSortOrder.TITLE_ASC);
        assertThat(concatTitles(newPage0, newPage1)).isEqualTo(List.of("Alpha", "Bravo", "Charlie", "Delta"));

        Page<Book> authorPage0 = opdsBookService.getBooksPage(admin.getId(), null, null, null, 0, 2, OpdsSortOrder.AUTHOR_ASC);
        Page<Book> authorPage1 = opdsBookService.getBooksPage(admin.getId(), null, null, null, 1, 2, OpdsSortOrder.AUTHOR_ASC);
        assertThat(concatAuthors(authorPage0, authorPage1)).isEqualTo(List.of("Alpha", "Bravo", "Charlie", "Delta"));
    }

    /**
     * Regression for the OPDS 500: QueryUtils prefixes the root alias onto an ORDER BY term
     * holding no literal '(' and no known alias, so a bare CASE became `b.CASE` and failed to
     * parse as HQL. Every OpdsSortOrder must build and execute a catalogue query without
     * throwing. This asserts executability only, not the resulting order.
     */
    @Test
    void getBooksPage_buildsAndExecutesForEverySortOrder() {
        BookLoreUserEntity admin = BookLoreUserEntity.builder()
                .username("admin-allsort-test")
                .passwordHash("hash")
                .isDefaultPassword(false)
                .name("Admin")
                .createdAt(LocalDateTime.now())
                .build();
        entityManager.persist(admin);
        entityManager.persist(UserPermissionsEntity.builder().user(admin).permissionAdmin(true).build());

        LibraryEntity library = LibraryEntity.builder().name("AllSort Test Library").icon("book").watch(false).build();
        entityManager.persist(library);
        LibraryPathEntity libraryPath = LibraryPathEntity.builder().library(library).path("/allsort/test").build();
        entityManager.persist(libraryPath);
        entityManager.flush();

        persistBook(library, libraryPath, "Echo", "Echo", Instant.now());
        entityManager.flush();
        entityManager.clear();

        for (OpdsSortOrder sortOrder : OpdsSortOrder.values()) {
            assertThatCode(() -> opdsBookService.getBooksPage(admin.getId(), null, null, null, 0, 10, sortOrder))
                    .as("sort order %s must build and execute without throwing", sortOrder)
                    .doesNotThrowAnyException();
        }
    }

    private void persistBook(LibraryEntity library, LibraryPathEntity libraryPath, String title, String authorName, Instant addedOn) {
        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(libraryPath)
                .addedOn(addedOn)
                .deleted(false)
                .build();
        entityManager.persist(book);

        AuthorEntity author = AuthorEntity.builder().name(authorName).build();
        entityManager.persist(author);

        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book)
                .bookId(book.getId())
                .title(title)
                .authors(new ArrayList<>(List.of(author)))
                .build();
        entityManager.persist(metadata);
    }

    private List<String> concatTitles(Page<Book> page0, Page<Book> page1) {
        return Stream.concat(page0.getContent().stream(), page1.getContent().stream())
                .map(b -> b.getMetadata().getTitle())
                .toList();
    }

    private List<String> concatAuthors(Page<Book> page0, Page<Book> page1) {
        return Stream.concat(page0.getContent().stream(), page1.getContent().stream())
                .map(b -> b.getMetadata().getAuthors().get(0))
                .toList();
    }

    // ==================== Sort-order gate, promoted ====================

    // Frozen copy of the pre-refactor OpdsBookService#resolveSort. LegacyOpdsSortProbeRepository
    // carries the matching pre-refactor JPQL/join shape, so this proves the Criteria-based
    // OpdsSortCriteria produces the identical id order to what JpaSort.unsafe used to, rather than
    // merely asserting it "does not throw" (see getBooksPage_buildsAndExecutesForEverySortOrder).
    // Originally run as a throwaway probe against a real MariaDB with the same seed data and
    // assertions; promoted here since H2 agrees with MariaDB on every construct this sort uses
    // (COALESCE, CASE, arithmetic division, indexed LEFT JOIN) once DISTINCT is not forced onto a
    // query whose ORDER BY references a column outside the SELECT list.
    private static Sort legacySort(OpdsSortOrder sortOrder) {
        return switch (sortOrder) {
            case RECENT -> Sort.by(Sort.Direction.DESC, "addedOn").and(Sort.by(Sort.Direction.ASC, "id"));
            case TITLE_ASC -> JpaSort.unsafe(Sort.Direction.ASC, "COALESCE(m.title, '')").andUnsafe(Sort.Direction.ASC, "b.id");
            case TITLE_DESC -> JpaSort.unsafe(Sort.Direction.DESC, "COALESCE(m.title, '')").andUnsafe(Sort.Direction.ASC, "b.id");
            case AUTHOR_ASC -> JpaSort.unsafe(Sort.Direction.ASC, "COALESCE(sa.sortName, '')").andUnsafe(Sort.Direction.ASC, "b.id");
            case AUTHOR_DESC -> JpaSort.unsafe(Sort.Direction.DESC, "COALESCE(sa.sortName, '')").andUnsafe(Sort.Direction.ASC, "b.id");
            case SERIES_ASC -> legacySeriesSort(Sort.Direction.ASC);
            case SERIES_DESC -> legacySeriesSort(Sort.Direction.DESC);
            case RATING_ASC -> legacyRatingSort(Sort.Direction.ASC);
            case RATING_DESC -> legacyRatingSort(Sort.Direction.DESC);
        };
    }

    private static Sort legacySeriesSort(Sort.Direction direction) {
        return JpaSort.unsafe(Sort.Direction.ASC, "CASE WHEN COALESCE(m.seriesName, '') = '' THEN 1 ELSE 0 END")
                .andUnsafe(direction, "m.seriesName")
                .andUnsafe(Sort.Direction.ASC, "(CASE WHEN m.seriesNumber IS NULL THEN 1 ELSE 0 END)")
                .andUnsafe(direction, "m.seriesNumber")
                .andUnsafe(Sort.Direction.ASC, "b.id");
    }

    private static final String LEGACY_RATING_HAS_VALUE =
            "CASE WHEN (COALESCE(m.amazonRating,0)>0 OR COALESCE(m.goodreadsRating,0)>0 OR COALESCE(m.hardcoverRating,0)>0) THEN 0 ELSE 1 END";
    private static final String LEGACY_RATING_VALUE =
            "(COALESCE(NULLIF(m.amazonRating,0),0)+COALESCE(NULLIF(m.goodreadsRating,0),0)+COALESCE(NULLIF(m.hardcoverRating,0),0))"
                    + "/NULLIF((CASE WHEN COALESCE(m.amazonRating,0)>0 THEN 1 ELSE 0 END"
                    + "+CASE WHEN COALESCE(m.goodreadsRating,0)>0 THEN 1 ELSE 0 END"
                    + "+CASE WHEN COALESCE(m.hardcoverRating,0)>0 THEN 1 ELSE 0 END),0)";

    private static Sort legacyRatingSort(Sort.Direction direction) {
        return JpaSort.unsafe(Sort.Direction.ASC, LEGACY_RATING_HAS_VALUE)
                .andUnsafe(direction, LEGACY_RATING_VALUE)
                .andUnsafe(Sort.Direction.ASC, "b.id");
    }

    @Test
    void everySortOrder_matchesLegacyJpqlOrder() {
        LibraryEntity library = LibraryEntity.builder().name("Sort Gate Library").icon("book").watch(false).build();
        entityManager.persist(library);
        LibraryPathEntity libraryPath = LibraryPathEntity.builder().library(library).path("/sort/gate").build();
        entityManager.persist(libraryPath);
        entityManager.flush();

        Instant base = Instant.now().minus(30, ChronoUnit.DAYS);
        seedForSort(library, libraryPath, "Zebra Book", "Zed Author", "Alpha Series", 3.0f, base, 4.0, 0.0, 0.0);
        seedForSort(library, libraryPath, "alpha book", "amy author", "Alpha Series", 1.0f, base.plusSeconds(10), 0.0, 0.0, 0.0);
        seedForSort(library, libraryPath, null, null, null, null, base.plusSeconds(20), 0.0, 0.0, 0.0);
        seedForSort(library, libraryPath, "Middle Book", "Middle Author", "Beta Series", null, base.plusSeconds(30), 5.0, 3.0, 0.0);
        seedForSort(library, libraryPath, "Another Middle", "Another Author", null, null, base.plusSeconds(40), 0.0, 2.0, 0.0);
        seedForSort(library, libraryPath, "Tied Title", "Tied Author", "Alpha Series", 1.0f, base.plusSeconds(50), 0.0, 0.0, 0.0);
        seedForSort(library, libraryPath, "Rated High", "Rated Author", null, null, base.plusSeconds(60), 5.0, 5.0, 5.0);
        entityManager.flush();
        entityManager.clear();

        Pageable window = PageRequest.of(0, 100);
        for (OpdsSortOrder sortOrder : OpdsSortOrder.values()) {
            List<Long> legacy = legacyOpdsSortProbeRepository.findBookIds(PageRequest.of(0, 100, legacySort(sortOrder))).getContent();
            List<Long> current = bookOpdsRepository.findBookIds(ContentRestrictionSpecification.from(List.of()), sortOrder, window).getContent();
            assertThat(current).as("order for %s", sortOrder).isEqualTo(legacy);
        }
    }

    // title is NOT NULL on book_metadata; the "no title" case a LEFT JOIN can produce is a book
    // with no metadata row at all, so title == null here skips creating one entirely.
    private void seedForSort(LibraryEntity library, LibraryPathEntity libraryPath, String title, String authorName,
                              String seriesName, Float seriesNumber, Instant addedOn,
                              double amazonRating, double goodreadsRating, double hardcoverRating) {
        BookEntity book = BookEntity.builder().library(library).libraryPath(libraryPath).addedOn(addedOn).deleted(false).build();
        entityManager.persist(book);
        if (title == null) {
            return;
        }

        List<AuthorEntity> authors = new ArrayList<>();
        if (authorName != null) {
            AuthorEntity author = AuthorEntity.builder().name(authorName).build();
            entityManager.persist(author);
            authors.add(author);
        }

        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book).bookId(book.getId()).title(title)
                .seriesName(seriesName).seriesNumber(seriesNumber)
                .authors(authors)
                .amazonRating(amazonRating).goodreadsRating(goodreadsRating).hardcoverRating(hardcoverRating)
                .build();
        entityManager.persist(metadata);
    }

    // ==================== Restriction pagination regression ====================

    /**
     * PAGINATION regression: 10 books, 5 excluded by a CATEGORY/EXCLUDE restriction, with the
     * excluded books landing on the first page in the default addedOn-DESC sort. Before the fix,
     * restriction was applied after the id-query sliced the page (OpdsBookService#createPageFromEntities
     * called ContentRestrictionService#applyRestrictions post-slice), so page 1 came back with
     * fewer entries than requested (or empty) and totalElements never reflected what the user
     * could actually see. Red on e64955106; ported from fallback/opds-restriction-branching.
     */
    @Test
    void getBooksPage_restrictedFirstPageIsFullAndTotalMatchesVisibleCount() {
        BookLoreUserEntity user = BookLoreUserEntity.builder()
                .username("restriction-pagination-user")
                .passwordHash("hash")
                .isDefaultPassword(false)
                .name("Restricted")
                .createdAt(LocalDateTime.now())
                .build();
        entityManager.persist(user);
        entityManager.persist(UserPermissionsEntity.builder().user(user).permissionAdmin(false).build());

        LibraryEntity library = LibraryEntity.builder().name("Restriction Pagination Library").icon("book").watch(false).build();
        entityManager.persist(library);
        LibraryPathEntity libraryPath = LibraryPathEntity.builder().library(library).path("/restriction/pagination").build();
        entityManager.persist(libraryPath);
        user.getLibraries().add(library);
        entityManager.flush();

        CategoryEntity horror = CategoryEntity.builder().name("Horror").build();
        entityManager.persist(horror);

        // The 5 excluded books are the most recently added, so they occupy the first page under
        // the default RECENT (addedOn DESC) sort - exactly the arrangement the bug report used.
        Instant base = Instant.now().minus(10, ChronoUnit.DAYS);
        for (int i = 0; i < 5; i++) {
            BookEntity book = BookEntity.builder().library(library).libraryPath(libraryPath)
                    .addedOn(base.plusSeconds(1000 + i)).deleted(false).build();
            entityManager.persist(book);
            entityManager.persist(BookMetadataEntity.builder()
                    .book(book).bookId(book.getId()).title("Excluded" + i)
                    .categories(new HashSet<>(Set.of(horror))).build());
        }
        for (int i = 0; i < 5; i++) {
            BookEntity book = BookEntity.builder().library(library).libraryPath(libraryPath)
                    .addedOn(base.plusSeconds(i)).deleted(false).build();
            entityManager.persist(book);
            entityManager.persist(BookMetadataEntity.builder()
                    .book(book).bookId(book.getId()).title("Visible" + i).build());
        }
        entityManager.persist(UserContentRestrictionEntity.builder()
                .user(user).restrictionType(ContentRestrictionType.CATEGORY).mode(ContentRestrictionMode.EXCLUDE).value("Horror").build());
        entityManager.flush();
        entityManager.clear();

        for (int size : List.of(3, 5, 10)) {
            Set<Long> collected = new HashSet<>();
            long totalElements = -1;
            for (int page = 0; page * size < 5 + size; page++) {
                Page<Book> result = opdsBookService.getBooksPage(user.getId(), null, null, null, page, size);
                totalElements = result.getTotalElements();
                if (page == 0) {
                    assertThat(result.getContent())
                            .as("first page must be full (or hold every visible book) for size=%d, not short/empty", size)
                            .hasSize(Math.min(size, 5));
                }
                if (result.getContent().isEmpty()) {
                    break;
                }
                result.getContent().forEach(b -> collected.add(b.getId()));
            }
            assertThat(totalElements).as("totalElements for size=%d", size).isEqualTo(5);
            assertThat(collected).as("ids collected across all pages for size=%d", size).hasSize(5);
        }
    }
}
