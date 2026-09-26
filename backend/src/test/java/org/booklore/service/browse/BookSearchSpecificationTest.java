package org.booklore.service.browse;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BookSearchSpecificationTest {

    @Test
    void wordsAreNormalisedLikeSearchText() {
        assertThat(BookSearchSpecification.words("  Zoë   ARCHER ")).containsExactly("zoe", "archer");
    }

    @Test
    void shortWordsAreKept() {
        assertThat(BookSearchSpecification.words("J R R Tolkien")).containsExactly("j", "r", "tolkien");
        assertThat(BookSearchSpecification.words("Sa")).containsExactly("sa");
    }

    @Test
    void keepsOnlyTheFirstSixteenWords() {
        String query = IntStream.range(0, 40).mapToObj(i -> "w" + (100 + i)).collect(Collectors.joining(" "));
        assertThat(BookSearchSpecification.words(query))
                .hasSize(BookSearchSpecification.MAX_TERMS)
                .startsWith("w100")
                .endsWith("w115");
    }

    @Test
    void readsOnlyTheFirst256Characters() {
        // 63 x "zzz " fills 252 characters, so the cut at 256 leaves "abcd" and drops "ghi".
        String query = "zzz ".repeat(63) + "abcdef ghi";
        assertThat(BookSearchSpecification.words(query)).containsExactly("zzz", "abcd");
    }

    @Test
    void blankQueryHasNoWords() {
        assertThat(BookSearchSpecification.words("   ")).isEmpty();
        assertThat(BookSearchSpecification.words(null)).isEmpty();
    }
}
