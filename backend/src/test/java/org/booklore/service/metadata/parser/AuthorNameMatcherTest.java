package org.booklore.service.metadata.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorNameMatcherTest {

    @Test
    void rejectsDifferentAuthorWithSimilarInitials() {
        assertThat(AuthorNameMatcher.matches("A C Cobble", "C. C. Humphreys")).isFalse();
        assertThat(AuthorNameMatcher.matches("A C Cobble", "C. C. Mitchell")).isFalse();
        assertThat(AuthorNameMatcher.matches("Brandon Sanderson", "Tara Sanderson")).isFalse();
        assertThat(AuthorNameMatcher.matches("Glynn Stewart", "Glyn Stewart")).isFalse();
    }

    @Test
    void treatsInitialSpellingsAsEqual() {
        for (String spelling : List.of("A.C. Cobble", "A. C. Cobble", "AC Cobble", "a c cobble", "Cobble, A. C.")) {
            assertThat(AuthorNameMatcher.matches("A C Cobble", spelling)).as(spelling).isTrue();
        }
    }

    @Test
    void acceptsLastCommaFirst() {
        assertThat(AuthorNameMatcher.matches("Sanderson, Brandon", "Brandon Sanderson")).isTrue();
    }

    @Test
    void ignoresAccentsCaseAndApostrophes() {
        assertThat(AuthorNameMatcher.matches("Gabriel García Márquez", "Gabriel Garcia Marquez")).isTrue();
        assertThat(AuthorNameMatcher.matches("BRANDON SANDERSON", "Brandon Sanderson")).isTrue();
        assertThat(AuthorNameMatcher.matches("Patrick O'Brian", "Patrick OBrian")).isTrue();
        assertThat(AuthorNameMatcher.matches("LEE CHILD", "Lee Child")).isTrue();
    }

    @Test
    void rejectsBlank() {
        assertThat(AuthorNameMatcher.matches("", "")).isFalse();
        assertThat(AuthorNameMatcher.matches(null, "x")).isFalse();
    }

    @Test
    void queryVariantsSpellInitialsBothWays() {
        assertThat(AuthorNameMatcher.queryVariants("A C Cobble"))
                .containsExactly("A C Cobble", "A.C. Cobble", "A. C. Cobble");
        assertThat(AuthorNameMatcher.queryVariants("Brandon Sanderson")).containsExactly("Brandon Sanderson");
    }

    @Test
    void titlesDropSubtitles() {
        assertThat(AuthorNameMatcher.normaliseTitle("The Way of Kings: Book One of the Stormlight Archive"))
                .isEqualTo("the way of kings");
    }
}
