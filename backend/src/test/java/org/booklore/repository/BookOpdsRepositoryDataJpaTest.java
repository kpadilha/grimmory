package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.dto.Book;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.UserPermissionsEntity;
import org.booklore.model.enums.OpdsSortOrder;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
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
}
