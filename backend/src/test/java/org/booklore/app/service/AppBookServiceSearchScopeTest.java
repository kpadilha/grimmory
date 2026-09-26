package org.booklore.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.app.dto.AppBookSummary;
import org.booklore.app.dto.BookListRequest;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.opds.MagicShelfBookService;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// The app applies the search fallback rule to the magic-shelf / unshelved scope it returns: an
// exact match outside that scope must not switch the tolerant rule off.
@SpringBootTest(classes = BookloreApplication.class)
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:appsearchscopetest;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE;MODE=MYSQL",
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
@Import(AppBookServiceSearchScopeTest.TestConfig.class)
class AppBookServiceSearchScopeTest {

    private static final long MAGIC_SHELF_ID = 42L;

    @Autowired
    private AppBookService appBookService;
    @MockitoBean
    private AuthenticationService authenticationService;
    @MockitoBean
    private MagicShelfBookService magicShelfBookService;

    @PersistenceContext
    private EntityManager em;

    private BookLoreUserEntity userEntity;
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
        userEntity = BookLoreUserEntity.builder().username("reader").passwordHash("x").name("Reader").build();
        em.persist(userEntity);
        library = LibraryEntity.builder().name("Lib").icon("book").watch(false)
                .formatPriority(List.of(BookFileType.EPUB)).build();
        em.persist(library);
        libraryPath = LibraryPathEntity.builder().library(library).path("/p").build();
        em.persist(libraryPath);

        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(true);
        when(authenticationService.getAuthenticatedUser())
                .thenReturn(BookLoreUser.builder().id(userEntity.getId()).permissions(permissions).build());
    }

    @Test
    void magicShelfSearchFallsBackWhenTheExactMatchIsOutsideTheShelf() {
        book("Glynn Stuart Elsewhere", null);
        BookEntity inShelf = book("Space Carrier Avalon", "Glynn Stewart");
        em.flush();
        Specification<BookEntity> onlyInShelf = (root, query, cb) -> cb.equal(root.get("id"), inShelf.getId());
        when(magicShelfBookService.toSpecification(eq(userEntity.getId()), eq(MAGIC_SHELF_ID))).thenReturn(onlyInShelf);

        BookListRequest request = request(Map.of("search", "Glynn Stuart", "magicShelfId", MAGIC_SHELF_ID));
        assertThat(appBookService.getBooks(request).getContent()).extracting(AppBookSummary::getId).containsExactly(inShelf.getId());
        assertThat(appBookService.getAllBookIds(request)).containsExactly(inShelf.getId());
    }

    @Test
    void unshelvedSearchFallsBackWhenTheExactMatchIsShelved() {
        BookEntity shelved = book("Glynn Stuart Shelved", null);
        ShelfEntity shelf = ShelfEntity.builder().user(userEntity).name("Shelf").build();
        em.persist(shelf);
        shelved.getShelves().add(shelf);
        BookEntity unshelved = book("Space Carrier Avalon", "Glynn Stewart");
        em.flush();

        BookListRequest request = request(Map.of("search", "Glynn Stuart", "unshelved", true));
        assertThat(appBookService.getBooks(request).getContent()).extracting(AppBookSummary::getId).containsExactly(unshelved.getId());
        assertThat(appBookService.getAllBookIds(request)).containsExactly(unshelved.getId());
    }

    private BookEntity book(String title, String authorName) {
        BookEntity book = BookEntity.builder().library(library).libraryPath(libraryPath)
                .addedOn(Instant.now()).deleted(false).isPhysical(true).build();
        em.persist(book);
        BookMetadataEntity metadata = BookMetadataEntity.builder().book(book).title(title).build();
        if (authorName != null) {
            AuthorEntity author = AuthorEntity.builder().name(authorName).build();
            em.persist(author);
            metadata.getAuthors().add(author);
        }
        em.persist(metadata);
        book.setMetadata(metadata);
        return book;
    }

    // BookListRequest has ~45 components; set only the named ones.
    private static BookListRequest request(Map<String, Object> values) {
        try {
            RecordComponent[] components = BookListRequest.class.getRecordComponents();
            Object[] args = Arrays.stream(components).map(c -> values.get(c.getName())).toArray();
            Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
            return BookListRequest.class.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
