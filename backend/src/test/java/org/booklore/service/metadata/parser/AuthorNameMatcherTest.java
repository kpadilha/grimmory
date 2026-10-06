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

    @Test
    void generationalSuffixIsOptionalButMustAgree() {
        assertThat(AuthorNameMatcher.matches("Kurt Vonnegut, Jr.", "Kurt Vonnegut Jr.")).isTrue();
        assertThat(AuthorNameMatcher.matches("Vonnegut, Kurt, Jr.", "Kurt Vonnegut Jr.")).isTrue();
        assertThat(AuthorNameMatcher.matches("Kurt Vonnegut", "Kurt Vonnegut Jr.")).isTrue();
        assertThat(AuthorNameMatcher.matches("John Smith Jr.", "John Smith Sr.")).isFalse();
        assertThat(AuthorNameMatcher.matches("John Smith III", "John Smith II")).isFalse();
    }

    @Test
    void allCapsInitials() {
        assertThat(AuthorNameMatcher.matches("JK ROWLING", "J.K. Rowling")).isTrue();
        assertThat(AuthorNameMatcher.matches("J. K. ROWLING", "JK Rowling")).isTrue();
    }

    @Test
    void transliteratesLettersNfkdKeepsWhole() {
        assertThat(AuthorNameMatcher.matches("Søren Kierkegaard", "Soren Kierkegaard")).isTrue();
        assertThat(AuthorNameMatcher.matches("Stanisław Lem", "Stanislaw Lem")).isTrue();
        assertThat(AuthorNameMatcher.matches("Bernhard Schlößer", "Bernhard Schlosser")).isTrue();
        assertThat(AuthorNameMatcher.matches("Æsa Jónsdóttir", "Aesa Jonsdottir")).isTrue();
        assertThat(AuthorNameMatcher.matches("Đorđe Balašević", "Dorde Balasevic")).isTrue();
        assertThat(AuthorNameMatcher.matches("Þóra Hjörleifsdóttir", "Thora Hjorleifsdottir")).isTrue();
    }
}
