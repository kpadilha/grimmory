package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.MoodEntity;
import org.booklore.model.entity.TagEntity;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.model.enums.ContentRestrictionMode;
import org.booklore.model.enums.ContentRestrictionType;
import org.booklore.model.enums.OpdsSortOrder;
import org.booklore.security.policy.ContentRestrictionSpecification;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * GAP 2: CATEGORY was the only {@link ContentRestrictionType} proven end to end through the OPDS
 * Criteria id-query ({@link BookOpdsRepositoryImpl}); the rest were covered only in isolation by
 * {@link org.booklore.service.restriction.ContentRestrictionSpecificationTest}. The id-query adds
 * joins and per-method DISTINCT, so a restriction subquery composed there is not proven by a
 * Specification-only test. This drives every (type, mode) pair through the real id-query, on a
 * plain method (no DISTINCT) and a DISTINCT one, and asserts the exact visible id set - a manually
 * determined ground truth, not a comparison against another implementation.
 *
 * <p>{@link #caseFor} switches exhaustively over both enums with no {@code default}: adding a new
 * {@link ContentRestrictionType} or {@link ContentRestrictionMode} constant fails compilation of
 * this test until a case is added, rather than the new constant silently going untested.
 */
@SpringBootTest(classes = {BookloreApplication.class})
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:restrictioncompositiontest;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE;MODE=MYSQL",
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
@Import(ContentRestrictionOpdsCompositionTest.TestConfig.class)
class ContentRestrictionOpdsCompositionTest {

    private static final String SHARED_SERIES = "Restriction Composition Series";

    @Autowired
    private BookOpdsRepository bookOpdsRepository;

    @Autowired
    private UserContentRestrictionRepository restrictionRepository;

    @PersistenceContext
    private EntityManager em;

    @TestConfiguration
    static class TestConfig {
        @Bean("flyway")
        @Primary
        Flyway flyway() {
            return mock(Flyway.class);
        }

        @Bean
        @Primary
        TaskCronService taskCronService() {
            return mock(TaskCronService.class);
        }
    }

    private record RestrictionCase(String value, Set<String> expectedVisibleTitles) {}

    // All titles ever seeded, so each case can express "everything except X" without repeating the
    // full set inline.
    private static final Set<String> ALL_TITLES = Set.of(
            "cat-horror", "cat-romance", "tag-explicit", "tag-safe", "mood-dark", "mood-light",
            "rating-mature", "rating-everyone", "age-18", "age-12");

    private static Set<String> allExcept(String... titles) {
        Set<String> result = new java.util.HashSet<>(ALL_TITLES);
        result.removeAll(Set.of(titles));
        return result;
    }

    // Exhaustive over both enums, no default: a new ContentRestrictionType or ContentRestrictionMode
    // constant fails this switch to compile until a case is written for it.
    private static RestrictionCase caseFor(ContentRestrictionType type, ContentRestrictionMode mode) {
        return switch (type) {
            case CATEGORY -> switch (mode) {
                case EXCLUDE -> new RestrictionCase("Horror", allExcept("cat-horror"));
                case ALLOW_ONLY -> new RestrictionCase("Romance", Set.of("cat-romance"));
            };
            case TAG -> switch (mode) {
                case EXCLUDE -> new RestrictionCase("Explicit", allExcept("tag-explicit"));
                case ALLOW_ONLY -> new RestrictionCase("Safe", Set.of("tag-safe"));
            };
            case MOOD -> switch (mode) {
                case EXCLUDE -> new RestrictionCase("Dark", allExcept("mood-dark"));
                case ALLOW_ONLY -> new RestrictionCase("Light", Set.of("mood-light"));
            };
            case CONTENT_RATING -> switch (mode) {
                case EXCLUDE -> new RestrictionCase("Mature", allExcept("rating-mature"));
                case ALLOW_ONLY -> new RestrictionCase("Everyone", Set.of("rating-everyone"));
            };
            case AGE_RATING -> switch (mode) {
                // Threshold 16 excludes age-18 (18 >= 16), keeps age-12 (12 < 16).
                case EXCLUDE -> new RestrictionCase("16", allExcept("age-18"));
                // ALLOW_ONLY has no defined semantics for AGE_RATING in either
                // ContentRestrictionSpecification or ContentRestrictionService: both silently ignore
                // it. This asserts that no-op is real - seeding the row must not break the id-query.
                case ALLOW_ONLY -> new RestrictionCase("16", ALL_TITLES);
            };
        };
    }

    static Stream<Arguments> restrictionCombinations() {
        List<Arguments> args = new java.util.ArrayList<>();
        for (ContentRestrictionType type : ContentRestrictionType.values()) {
            for (ContentRestrictionMode mode : ContentRestrictionMode.values()) {
                args.add(Arguments.of(type, mode));
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("restrictionCombinations")
    void idQueryVisibleSetMatchesRestrictionForEveryTypeAndMode(ContentRestrictionType type, ContentRestrictionMode mode) {
        RestrictionCase testCase = caseFor(type, mode);

        BookLoreUserEntity user = BookLoreUserEntity.builder()
                .username("restriction-composition-" + type + "-" + mode)
                .passwordHash("x").name("Restricted").build();
        em.persist(user);

        LibraryEntity library = LibraryEntity.builder().name("Composition Lib " + type + "-" + mode)
                .icon("book").watch(false).build();
        em.persist(library);
        LibraryPathEntity libraryPath = LibraryPathEntity.builder().library(library).path("/composition/" + type + "-" + mode).build();
        em.persist(libraryPath);

        java.util.Map<String, BookEntity> booksByTitle = new java.util.HashMap<>();
        booksByTitle.put("cat-horror", book(library, libraryPath, "cat-horror", null, null, List.of("Horror"), List.of(), List.of()));
        booksByTitle.put("cat-romance", book(library, libraryPath, "cat-romance", null, null, List.of("Romance"), List.of(), List.of()));
        booksByTitle.put("tag-explicit", book(library, libraryPath, "tag-explicit", null, null, List.of(), List.of("Explicit"), List.of()));
        booksByTitle.put("tag-safe", book(library, libraryPath, "tag-safe", null, null, List.of(), List.of("Safe"), List.of()));
        booksByTitle.put("mood-dark", book(library, libraryPath, "mood-dark", null, null, List.of(), List.of(), List.of("Dark")));
        booksByTitle.put("mood-light", book(library, libraryPath, "mood-light", null, null, List.of(), List.of(), List.of("Light")));
        booksByTitle.put("rating-mature", book(library, libraryPath, "rating-mature", "Mature", null, List.of(), List.of(), List.of()));
        booksByTitle.put("rating-everyone", book(library, libraryPath, "rating-everyone", "Everyone", null, List.of(), List.of(), List.of()));
        booksByTitle.put("age-18", book(library, libraryPath, "age-18", null, 18, List.of(), List.of(), List.of()));
        booksByTitle.put("age-12", book(library, libraryPath, "age-12", null, 12, List.of(), List.of(), List.of()));

        em.persist(UserContentRestrictionEntity.builder()
                .user(user).restrictionType(type).mode(mode).value(testCase.value()).build());
        em.flush();
        em.clear();

        Specification<BookEntity> restriction = ContentRestrictionSpecification.from(restrictionRepository.findByUserId(user.getId()));
        Pageable window = PageRequest.of(0, 100);

        Set<Long> expectedIds = testCase.expectedVisibleTitles().stream()
                .map(title -> booksByTitle.get(title).getId())
                .collect(java.util.stream.Collectors.toSet());

        // Non-DISTINCT id-query path (plain library scope).
        List<Long> plainIds = bookOpdsRepository.findBookIdsByLibraryIds(
                List.of(library.getId()), restriction, OpdsSortOrder.RECENT, window).getContent();
        assertThat(Set.copyOf(plainIds)).as("%s/%s via plain (non-DISTINCT) id-query", type, mode).isEqualTo(expectedIds);
        assertThat(plainIds).as("%s/%s: no duplicate ids from a non-DISTINCT query", type, mode).hasSize(plainIds.size());

        // DISTINCT id-query path: every seeded book shares one series name, so the series join can
        // multiply rows the same way a shelf/author join would.
        List<Long> distinctIds = bookOpdsRepository.findBookIdsBySeriesName(
                SHARED_SERIES, restriction, OpdsSortOrder.RECENT, window).getContent();
        assertThat(Set.copyOf(distinctIds)).as("%s/%s via DISTINCT id-query", type, mode).isEqualTo(expectedIds);
        assertThat(distinctIds).as("%s/%s: DISTINCT query returns each id once", type, mode).hasSize(Set.copyOf(distinctIds).size());
    }

    private BookEntity book(LibraryEntity library, LibraryPathEntity libraryPath, String title,
                            String contentRating, Integer ageRating,
                            List<String> categoryNames, List<String> tagNames, List<String> moodNames) {
        BookEntity bookEntity = BookEntity.builder()
                .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false).build();
        em.persist(bookEntity);

        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(bookEntity).bookId(bookEntity.getId()).title(title)
                .contentRating(contentRating).ageRating(ageRating)
                .seriesName(SHARED_SERIES)
                .build();
        metadata.setCategories(categoryNames.stream().map(this::category).collect(java.util.stream.Collectors.toSet()));
        metadata.setTags(tagNames.stream().map(this::tag).collect(java.util.stream.Collectors.toSet()));
        metadata.setMoods(moodNames.stream().map(this::mood).collect(java.util.stream.Collectors.toSet()));
        em.persist(metadata);
        bookEntity.setMetadata(metadata);
        return bookEntity;
    }

    private CategoryEntity category(String name) {
        CategoryEntity e = CategoryEntity.builder().name(name).build();
        em.persist(e);
        return e;
    }

    private TagEntity tag(String name) {
        TagEntity e = TagEntity.builder().name(name).build();
        em.persist(e);
        return e;
    }

    private MoodEntity mood(String name) {
        MoodEntity e = MoodEntity.builder().name(name).build();
        em.persist(e);
        return e;
    }
}
