package org.booklore.service.browse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.browse.FacetLogic;
import org.booklore.browse.SortParser;
import org.booklore.browse.SortTerm;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.TagEntity;
import org.booklore.model.dto.request.AuthorUpdateRequest;
import org.booklore.service.AuthorMetadataService;
import org.booklore.service.metadata.MetadataManagementService;
import org.booklore.model.enums.MergeMetadataType;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.BookRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = BookloreApplication.class)
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:browsetest;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE;MODE=MYSQL",
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
@Import(BookBrowseRegistryTest.TestConfig.class)
class BookBrowseRegistryTest {

    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private BookSortRegistry sortRegistry;
    @Autowired
    private BookFacetRegistry facetRegistry;
    @Autowired
    private AuthorMetadataService authorMetadataService;
    @Autowired
    private BookSearchResolver searchResolver;
    @Autowired
    private MetadataManagementService metadataManagementService;

    @PersistenceContext
    private EntityManager em;

    private BookLoreUserEntity user;
    private LibraryEntity library;
    private LibraryPathEntity libraryPath;
    private final Map<String, CategoryEntity> categories = new HashMap<>();
    private final Map<String, AuthorEntity> authors = new HashMap<>();

    @TestConfiguration
    public static class TestConfig {
        @Bean("flyway")
        @Primary
        public Flyway flyway() {
            return org.mockito.Mockito.mock(Flyway.class);
        }

        @Bean
        @Primary
        public TaskCronService taskCronService() {
            return org.mockito.Mockito.mock(TaskCronService.class);
        }
    }

    @BeforeEach
    void seed() {
        user = BookLoreUserEntity.builder().username("reader").passwordHash("x").name("Reader").build();
        em.persist(user);
        library = LibraryEntity.builder().name("Lib").icon("book").watch(false)
                .formatPriority(List.of(BookFileType.EPUB)).build();
        em.persist(library);
        libraryPath = LibraryPathEntity.builder().library(library).path("/p").build();
        em.persist(libraryPath);
    }

    private BookEntity book(String title) {
        return book(title, null, null, Instant.now(), List.of(), List.of(), null);
    }

    private BookEntity book(String title, String seriesName, Float seriesNumber, Instant addedOn,
                            List<String> categoryNames, List<String> authorNames, String isbn13) {
        BookEntity bookEntity = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(addedOn).deleted(false).build();
        em.persist(bookEntity);
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(bookEntity).title(title).seriesName(seriesName).seriesNumber(seriesNumber)
                .isbn13(isbn13).publishedDate(LocalDate.of(2020, 1, 1)).build();
        metadata.setCategories(categoryNames.stream().map(this::category).collect(Collectors.toSet()));
        metadata.setAuthors(authorNames.stream().map(this::author).toList());
        em.persist(metadata);
        bookEntity.setMetadata(metadata);
        return bookEntity;
    }

    private void progress(BookEntity book, BookLoreUserEntity forUser, Integer personalRating,
                          ReadStatus status, Float epubPercent, Float pdfPercent) {
        UserBookProgressEntity p = UserBookProgressEntity.builder()
                .user(forUser).book(book).personalRating(personalRating).readStatus(status)
                .epubProgressPercent(epubPercent).pdfProgressPercent(pdfPercent)
                .lastReadTime(Instant.now()).build();
        em.persist(p);
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
            e.computeSortName();
            em.persist(e);
            return e;
        });
    }

    private List<Long> sortedIds(String sortString, Long userId) {
        List<SortTerm> terms = SortParser.parse(sortString, sortRegistry.registry().keys());
        Specification<BookEntity> spec = (root, query, cb) -> {
            query.orderBy(sortRegistry.registry().toOrders(terms, root, query, cb, userId, null));
            return cb.conjunction();
        };
        return bookRepository.findAll(spec).stream().map(BookEntity::getId).toList();
    }

    private Set<Long> facetIds(String facet, List<String> values, FacetLogic logic, Long userId) {
        return bookRepository.findAll(facetRegistry.toSpecification(facet, values, logic, userId)).stream()
                .map(BookEntity::getId).collect(Collectors.toSet());
    }

    // ---- sorts ----

    @Test
    void titleAscendingAndDescending() {
        Long a = book("Alpha").getId();
        Long b = book("Bravo").getId();
        Long c = book("Charlie").getId();
        em.flush();
        assertThat(sortedIds("title", user.getId())).containsExactly(a, b, c);
        assertThat(sortedIds("-title", user.getId())).containsExactly(c, b, a);
    }

    @Test
    void authorAscendingAndDescending() {
        Long a = book("Alpha", null, null, Instant.now(), List.of(), List.of("Ned McDodd"), null).getId();
        Long b = book("Bravo", null, null, Instant.now(), List.of(), List.of("Sally O'Malley"), null).getId();
        Long c = book("Charlie", null, null, Instant.now(), List.of(), List.of("Haily Yelp"), null).getId();
        em.flush();
        assertThat(sortedIds("authorName", user.getId())).containsExactly(c, a, b);
        assertThat(sortedIds("-authorName", user.getId())).containsExactly(b, a, c);
    }

    @Test
    void emptyAuthorAscendingAndDescending() {
        Long a = book("Alpha", null, null, Instant.now(), List.of(), List.of(), null).getId();
        Long b = book("Bravo", null, null, Instant.now(), List.of(), List.of("Sally O'Malley"), null).getId();
        Long c = book("Charlie", null, null, Instant.now(), List.of(), List.of("Haily Yelp"), null).getId();
        em.flush();
        assertThat(sortedIds("authorName", user.getId())).containsExactly(a, c, b);
        assertThat(sortedIds("-authorName", user.getId())).containsExactly(b, c, a);
    }

    @Test
    void multiAuthorAscendingAndDescending() {
        Long a = book("Alpha", null, null, Instant.now(), List.of(), List.of("Ned McDodd", "Euchariah Who"), null).getId();
        Long b = book("Bravo", null, null, Instant.now(), List.of(), List.of("Sally O'Malley", "Axl"), null).getId();
        Long c = book("Charlie", null, null, Instant.now(), List.of(), List.of("Haily Yelp"), null).getId();
        em.flush();

        // These are the same because of how the sorting works with multiple authors.
        // `b` has both the highest and lowest.
        assertThat(sortedIds("authorName", user.getId())).containsExactly(b, a, c);
        assertThat(sortedIds("-authorName", user.getId())).containsExactly(b, a, c);
    }

    @Test
    void authorSortNameAscendingAndDescending() {
        Long a = book("Alpha", null, null, Instant.now(), List.of(), List.of("Red McDodd"), null).getId();
        Long b = book("Bravo", null, null, Instant.now(), List.of(), List.of("Sally O'Malley"), null).getId();
        Long c = book("Charlie", null, null, Instant.now(), List.of(), List.of("Haily Yelp"), null).getId();
        em.flush();
        assertThat(sortedIds("authorSortName", user.getId())).containsExactly(a, b, c);
        assertThat(sortedIds("-authorSortName", user.getId())).containsExactly(c, b, a);
    }

    @Test
    void idTiebreakerOrdersEqualKeysById() {
        Long first = book("Same").getId();
        Long second = book("Same").getId();
        em.flush();
        assertThat(sortedIds("title", user.getId())).containsExactly(first, second);
    }

    @Test
    void idTiebreakerFollowsDescendingSort() {
        Long first = book("Same").getId();
        Long second = book("Same").getId();
        em.flush();
        assertThat(sortedIds("-title", user.getId())).containsExactly(second, first);
    }

    @Test
    void titleSortUsesGeneratedKey() {
        BookEntity book = book("Generated");
        em.flush();
        em.refresh(book.getMetadata());
        assertThat(book.getMetadata().getTitleSort()).isEqualTo("Generated");
    }

    @Test
    void addedOnSorts() {
        Long older = book("Old", null, null, Instant.parse("2020-01-01T00:00:00Z"), List.of(), List.of(), null).getId();
        Long newer = book("New", null, null, Instant.parse("2024-01-01T00:00:00Z"), List.of(), List.of(), null).getId();
        em.flush();
        assertThat(sortedIds("addedOn", user.getId())).containsExactly(older, newer);
        assertThat(sortedIds("-addedOn", user.getId())).containsExactly(newer, older);
    }

    @Test
    void perUserPersonalRatingSorts() {
        BookEntity low = book("Low");
        BookEntity high = book("High");
        progress(low, user, 3, ReadStatus.READ, null, null);
        progress(high, user, 7, ReadStatus.READ, null, null);
        em.flush();
        assertThat(sortedIds("personalRating", user.getId())).containsExactly(low.getId(), high.getId());
    }

    @Test
    void perUserSortIgnoresOtherUsersProgress() {
        BookLoreUserEntity other = BookLoreUserEntity.builder().username("other").passwordHash("x").name("Other").build();
        em.persist(other);
        BookEntity book = book("Rated");
        progress(book, other, 9, ReadStatus.READ, null, null);
        em.flush();
        // The requesting user has no progress row, so the rating is null, not 9.
        List<Long> byRating = sortedIds("-personalRating", user.getId());
        assertThat(byRating).containsExactly(book.getId());
    }

    @Test
    void readingProgressUsesGreatestOfPercentFields() {
        BookEntity half = book("Half");
        BookEntity nearlyDone = book("NearlyDone");
        progress(half, user, null, ReadStatus.READING, 0.5f, null);
        progress(nearlyDone, user, null, ReadStatus.READING, null, 0.9f);
        em.flush();
        assertThat(sortedIds("-readingProgress", user.getId())).containsExactly(nearlyDone.getId(), half.getId());
    }

    // ---- facets ----

    @Test
    void genreFacetOrAndNot() {
        Long horror = book("H", null, null, Instant.now(), List.of("Horror"), List.of(), null).getId();
        Long romance = book("R", null, null, Instant.now(), List.of("Romance"), List.of(), null).getId();
        em.flush();
        assertThat(facetIds("genre", List.of("Horror"), FacetLogic.OR, user.getId())).containsExactlyInAnyOrder(horror);
        assertThat(facetIds("genre", List.of("Horror"), FacetLogic.NOT, user.getId())).contains(romance).doesNotContain(horror);
    }

    @Test
    void readStatusFacetIsPerUser() {
        BookEntity read = book("Read");
        BookEntity unread = book("Unread");
        progress(read, user, null, ReadStatus.READ, null, null);
        progress(unread, user, null, ReadStatus.UNREAD, null, null);
        em.flush();
        assertThat(facetIds("read_status", List.of("READ"), FacetLogic.OR, user.getId())).containsExactlyInAnyOrder(read.getId());
    }

    // ---- query ----

    @Test
    void queryMatchesSubstringsAnywhereInAnyField() {
        Long title = book("The Black Wall of Jerusalem", null, null, Instant.now(), List.of(), List.of(), null).getId();
        Long ugly = book("Confessions of an Ugly Stepsister", null, null, Instant.now(), List.of(), List.of(), null).getId();
        Long byAuthor = book("Unrelated", null, null, Instant.now(), List.of(), List.of("J.R.R. Tolkien"), null).getId();
        Long byCategory = book("Other", null, null, Instant.now(), List.of("Space Opera"), List.of(), null).getId();
        Long byIsbn = book("Another", null, null, Instant.now(), List.of(), List.of(), "9780261103344").getId();
        em.flush();

        assertThat(matchIds("salem")).containsExactlyInAnyOrder(title);
        assertThat(matchIds("gly")).containsExactlyInAnyOrder(ugly);
        assertThat(matchIds("TOLKIEN")).containsExactlyInAnyOrder(byAuthor);
        assertThat(matchIds("opera")).containsExactlyInAnyOrder(byCategory);
        assertThat(matchIds("261103")).containsExactlyInAnyOrder(byIsbn);
    }

    @Test
    void everyWordMustMatchInAnyOrderAcrossFields() {
        Long wanted = book("Space Carrier Avalon", null, null, Instant.now(), List.of(), List.of("Glynn Stewart"), null).getId();
        book("Space Carrier", null, null, Instant.now(), List.of(), List.of("Someone Else"), null);
        em.flush();

        assertThat(matchIds("avalon space")).containsExactlyInAnyOrder(wanted);
        assertThat(matchIds("stewart carrier")).containsExactlyInAnyOrder(wanted);
    }

    @Test
    void misspelledAuthorMatchesPhoneticallyOnlyWhenNothingMatchesExactly() {
        Long stewart = book("Space Carrier Avalon", null, null, Instant.now(), List.of(), List.of("Glynn Stewart"), null).getId();
        Long stuart = book("Other Book", null, null, Instant.now(), List.of(), List.of("Stuart Hill"), null).getId();
        em.flush();

        assertThat(matchIds("Glynn Stuart")).containsExactlyInAnyOrder(stewart);
        // "stuart" matches a book as a substring, so the phonetic fallback stays off.
        assertThat(matchIds("stuart")).containsExactlyInAnyOrder(stuart);
    }

    @Test
    void backslashInTheQueryIsLiteral() {
        Long backslash = book("100\\ Proof", null, null, Instant.now(), List.of(), List.of(), null).getId();
        book("Nothing Here", null, null, Instant.now(), List.of(), List.of(), null);
        em.flush();

        assertThat(matchIds("0\\ p")).containsExactlyInAnyOrder(backslash);
    }

    // ---- search text maintenance ----

    @Test
    void addingAnAuthorToTheCollectionAloneRefreshesSearchText() {
        BookEntity book = book("Plain Title");
        em.flush();
        em.clear();

        BookMetadataEntity metadata = em.find(BookMetadataEntity.class, book.getId());
        metadata.getAuthors().add(author("Glynn Stewart"));
        metadata.getTags().add(tag("as Glynnis Kincaid"));
        em.flush();
        em.clear();

        BookMetadataEntity reloaded = em.find(BookMetadataEntity.class, book.getId());
        assertThat(reloaded.getSearchText()).isEqualTo("plain title glynn stewart as glynnis kincaid");
        assertThat(reloaded.getSearchPhonetic()).isEqualTo(" G450 S363 ");
    }

    @Test
    void editingAFieldKeepsUnloadedAuthorsInSearchText() {
        BookEntity book = book("Old Title", null, null, Instant.now(), List.of(), List.of("Glynn Stewart"), null);
        em.flush();
        em.clear();

        em.find(BookMetadataEntity.class, book.getId()).setTitle("New Title");
        em.flush();
        em.clear();

        assertThat(em.find(BookMetadataEntity.class, book.getId()).getSearchText()).isEqualTo("new title glynn stewart");
    }

    @Test
    void renamingAnAuthorRefreshesTheirBooks() {
        BookEntity book = book("Title", null, null, Instant.now(), List.of(), List.of("Glyn Stewrt"), null);
        em.flush();
        em.clear();

        AuthorUpdateRequest rename = new AuthorUpdateRequest();
        rename.setName("Glynn Stewart");
        authorMetadataService.updateAuthor(authors.get("Glyn Stewrt").getId(), rename);
        em.flush();
        em.clear();

        assertThat(em.find(BookMetadataEntity.class, book.getId()).getSearchText()).isEqualTo("title glynn stewart");
    }

    @Test
    void flywayBackfillWritesWhatTheEntityWriterWrites() throws Exception {
        BookEntity book = book("Space Carrier Avalon", "Avalon", null, Instant.now(), List.of("Space Opera"), List.of("Glynn Stewart"), "9781988035009");
        book.getMetadata().getTags().add(tag("as Glynnis Kincaid"));
        em.flush();
        BookMetadataEntity written = em.find(BookMetadataEntity.class, book.getId());
        String expectedText = written.getSearchText();
        String expectedPhonetic = written.getSearchPhonetic();
        em.createNativeQuery("UPDATE book_metadata_search SET search_text = 'stale', search_phonetic = NULL").executeUpdate();
        em.createNativeQuery("DELETE FROM book_metadata_search WHERE book_id <> " + book.getId()).executeUpdate();

        em.unwrap(org.hibernate.Session.class).doWork(connection -> {
            org.flywaydb.core.api.migration.Context context = org.mockito.Mockito.mock(org.flywaydb.core.api.migration.Context.class);
            org.mockito.Mockito.when(context.getConnection()).thenReturn(connection);
            new db.migration.V149_5__Refresh_search_text().migrate(context);
        });
        em.clear();

        BookMetadataEntity refreshed = em.find(BookMetadataEntity.class, book.getId());
        assertThat(expectedText).isEqualTo("space carrier avalon avalon glynn stewart space opera as glynnis kincaid 9781988035009");
        assertThat(refreshed.getSearchText()).isEqualTo(expectedText);
        assertThat(refreshed.getSearchPhonetic()).isEqualTo(expectedPhonetic);
    }

    @Test
    void consolidatingIntoARenamedTargetRefreshesItsBooks() {
        // Dotless "ı" upper-cases to "I", so the ignore-case lookup finds the existing names, yet
        // the normalised search text changes: the rename must reach every book carrying them.
        BookEntity book = book("Title", null, null, Instant.now(), List.of("Kincaid Genre"), List.of("Glynn Kincaid"), null);
        book.getMetadata().getTags().add(tag("as Glynnis Kincaid"));
        em.flush();
        em.clear();

        metadataManagementService.consolidateMetadata(MergeMetadataType.authors, List.of("Glynn Kıncaid"), List.of());
        metadataManagementService.consolidateMetadata(MergeMetadataType.categories, List.of("Kıncaid Genre"), List.of());
        metadataManagementService.consolidateMetadata(MergeMetadataType.tags, List.of("as Glynnis Kıncaid"), List.of());
        em.flush();
        em.clear();

        assertThat(em.find(BookMetadataEntity.class, book.getId()).getSearchText())
                .isEqualTo("title glynn kıncaid kıncaid genre as glynnis kıncaid");
    }

    private TagEntity tag(String name) {
        TagEntity tag = TagEntity.builder().name(name).build();
        em.persist(tag);
        return tag;
    }

    private Set<Long> matchIds(String query) {
        return bookRepository.findAll(searchResolver.resolve(query, (root, q, cb) -> cb.conjunction())).stream()
                .map(BookEntity::getId).collect(Collectors.toSet());
    }
}
