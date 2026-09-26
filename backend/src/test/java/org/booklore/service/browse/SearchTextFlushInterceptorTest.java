package org.booklore.service.browse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.ComicMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Guards the fix documented on {@link org.booklore.config.SearchTextFlushInterceptor}: registering
 * any Hibernate Interceptor disables the bytecode-enhanced fast-path dirty check for
 * {@code @DynamicUpdate} entities, so a scalar-only change must still survive flush.
 */
@SpringBootTest(classes = BookloreApplication.class)
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:searchtextflushinterceptortest;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
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
@Import(SearchTextFlushInterceptorTest.TestConfig.class)
class SearchTextFlushInterceptorTest {

    @PersistenceContext
    private EntityManager em;

    private LibraryEntity library;
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
        library = LibraryEntity.builder().name("Lib").icon("book").watch(false)
                .formatPriority(List.of(BookFileType.EPUB)).build();
        em.persist(library);
        libraryPath = LibraryPathEntity.builder().library(library).path("/p").build();
        em.persist(libraryPath);
    }

    private BookEntity book() {
        BookEntity bookEntity = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(bookEntity);
        return bookEntity;
    }

    @Test
    void scalarUpdateOnBookMetadataSurvivesFlushWithNoCollectionTouched() {
        BookEntity bookEntity = book();
        BookMetadataEntity metadata = BookMetadataEntity.builder().book(bookEntity).title("Original Title").build();
        em.persist(metadata);
        em.flush();
        em.clear();

        BookMetadataEntity reloaded = em.find(BookMetadataEntity.class, bookEntity.getId());
        reloaded.setTitle("Updated Title");
        em.flush();
        em.clear();

        assertThat(em.find(BookMetadataEntity.class, bookEntity.getId()).getTitle()).isEqualTo("Updated Title");
    }

    @Test
    void scalarUpdateOnComicMetadataSurvivesFlushWithNoCollectionTouched() {
        BookEntity bookEntity = book();
        BookMetadataEntity metadata = BookMetadataEntity.builder().book(bookEntity).title("Comic Title").build();
        em.persist(metadata);
        ComicMetadataEntity comic = ComicMetadataEntity.builder()
                .bookId(bookEntity.getId())
                .bookMetadata(metadata)
                .format("Trade Paperback")
                .build();
        em.persist(comic);
        em.flush();
        em.clear();

        ComicMetadataEntity reloaded = em.find(ComicMetadataEntity.class, bookEntity.getId());
        reloaded.setFormat("Hardcover");
        em.flush();
        em.clear();

        assertThat(em.find(ComicMetadataEntity.class, bookEntity.getId()).getFormat()).isEqualTo("Hardcover");
    }
}
