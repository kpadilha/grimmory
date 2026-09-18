package org.booklore.service.stats;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.response.LibraryAggregateBucket;
import org.booklore.model.dto.response.LibraryHistogramBucket;
import org.booklore.model.dto.response.LibrarySummary;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.exception.APIException;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = BookloreApplication.class)
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:librarystatstest;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
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
@Import(LibraryStatsServiceTest.TestConfig.class)
class LibraryStatsServiceTest {

    @Autowired
    private LibraryStatsService libraryStatsService;
    @MockitoBean
    private AuthenticationService authenticationService;

    @PersistenceContext
    private EntityManager em;

    private BookLoreUserEntity userEntity;
    private LibraryEntity library;
    private LibraryEntity otherLibrary;
    private LibraryPathEntity libraryPath;

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
        userEntity = BookLoreUserEntity.builder().username("reader").passwordHash("x").name("Reader").build();
        em.persist(userEntity);
        library = LibraryEntity.builder().name("Lib").icon("book").watch(false).build();
        em.persist(library);
        otherLibrary = LibraryEntity.builder().name("OtherLib").icon("book").watch(false).build();
        em.persist(otherLibrary);
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

    private BookEntity book(String title, Integer pageCount, BookFileType fileType) {
        BookEntity bookEntity = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(bookEntity);
        BookMetadataEntity metadata = BookMetadataEntity.builder().book(bookEntity).title(title).pageCount(pageCount).build();
        em.persist(metadata);
        bookEntity.setMetadata(metadata);
        BookFileEntity file = BookFileEntity.builder()
                .book(bookEntity).fileName(title + ".bin").fileSubPath("").isBookFormat(true)
                .bookType(fileType).fileSizeKb(1000L).build();
        em.persist(file);
        bookEntity.getBookFiles().add(file);
        return bookEntity;
    }

    private void addBookFile(BookEntity book, BookFileType fileType, long fileSizeKb) {
        BookFileEntity file = BookFileEntity.builder()
                .book(book).fileName(book.getId() + "-" + fileType + ".bin").fileSubPath("").isBookFormat(true)
                .bookType(fileType).fileSizeKb(fileSizeKb).build();
        em.persist(file);
        book.getBookFiles().add(file);
    }

    private BookEntity bookInOtherLibrary(String title) {
        BookEntity bookEntity = BookEntity.builder()
                .library(otherLibrary).addedOn(Instant.now()).deleted(false).build();
        em.persist(bookEntity);
        BookMetadataEntity metadata = BookMetadataEntity.builder().book(bookEntity).title(title).build();
        em.persist(metadata);
        bookEntity.setMetadata(metadata);
        return bookEntity;
    }

    @Test
    void aggregatesFileTypeCounts() {
        book("A", 100, BookFileType.EPUB);
        book("B", 200, BookFileType.EPUB);
        book("C", 300, BookFileType.PDF);
        em.flush();

        List<LibraryAggregateBucket> buckets = libraryStatsService.aggregate("file_type", null, null);

        assertThat(buckets).extracting(LibraryAggregateBucket::getValue).contains("EPUB", "PDF");
        assertThat(buckets.stream().filter(b -> "EPUB".equals(b.getValue())).findFirst().orElseThrow().getCount()).isEqualTo(2);
    }

    @Test
    void bucketsPageCountIntoFixedRanges() {
        book("A", 50, BookFileType.EPUB);
        book("B", 950, BookFileType.EPUB);
        em.flush();

        List<LibraryHistogramBucket> histogram = libraryStatsService.histogram("page_count", null);

        assertThat(histogram).extracting(LibraryHistogramBucket::getRange)
                .containsExactly("0-100", "101-200", "201-300", "301-500", "501-750", "751-1000", "1000+");
        assertThat(histogram.get(0).getCount()).isEqualTo(1);
        assertThat(histogram.get(5).getCount()).isEqualTo(1);
    }

    @Test
    void summaryCountsOnlyBooksInScope() {
        book("A", 100, BookFileType.EPUB);
        book("B", 200, BookFileType.PDF);
        bookInOtherLibrary("Unreachable");
        em.flush();

        LibrarySummary summary = libraryStatsService.summary(null);

        assertThat(summary.getTotalBooks()).isEqualTo(2);
        assertThat(summary.getTotalSizeKb()).isEqualTo(2000L);
    }

    @Test
    void totalSizeKbCountsOnlyThePrimaryFilePerBook() {
        BookEntity book = book("A", 100, BookFileType.EPUB);
        addBookFile(book, BookFileType.PDF, 500L);
        em.flush();

        LibrarySummary summary = libraryStatsService.summary(null);

        assertThat(summary.getTotalBooks()).isEqualTo(1);
        assertThat(summary.getTotalSizeKb()).isEqualTo(1000L);
    }

    @Test
    void nonAdminNeverSeesBooksOutsideAssignedLibraries() {
        book("A", 100, BookFileType.EPUB);
        bookInOtherLibrary("Unreachable");
        em.flush();

        List<LibraryAggregateBucket> buckets = libraryStatsService.aggregate("file_type", null, null);

        long total = buckets.stream().mapToLong(LibraryAggregateBucket::getCount).sum();
        assertThat(total).isEqualTo(1);
    }

    @Test
    void libraryIdParamFiltersToASingleLibraryWithinScope() {
        book("A", 100, BookFileType.EPUB);
        em.flush();

        LibrarySummary inLibrary = libraryStatsService.summary(library.getId());
        LibrarySummary otherLibraryScoped = libraryStatsService.summary(otherLibrary.getId());

        assertThat(inLibrary.getTotalBooks()).isEqualTo(1);
        assertThat(otherLibraryScoped.getTotalBooks()).isEqualTo(0);
    }

    @Test
    void unknownAggregateFieldIsRejected() {
        em.flush();
        assertThrows(APIException.class, () -> libraryStatsService.aggregate("not_a_field", null, null));
    }
}
