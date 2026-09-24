package org.booklore.service.browse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookSearchSpecificationTest {

    @Test
    void everyWordBecomesARequiredPrefixTerm() {
        assertThat(BookSearchSpecification.fulltextTerms("Glynn Stewart")).containsExactly("+Glynn*", "+Stewart*");
    }

    @Test
    void booleanOperatorsInInputAreStripped() {
        assertThat(BookSearchSpecification.fulltextTerms("-foo +bar* \"baz\" (qux) ~x<y>@z"))
                .containsExactly("+foo*", "+bar*", "+baz*", "+qux*");
    }

    @Test
    void shortWordsAreDroppedWhenALongerWordExists() {
        assertThat(BookSearchSpecification.fulltextTerms("lord of the rings")).containsExactly("+lord*", "+the*", "+rings*");
        assertThat(BookSearchSpecification.fulltextTerms("O'Malley")).containsExactly("+Malley*");
    }

    @Test
    void onlyShortWordsAreKeptAsPrefixes() {
        assertThat(BookSearchSpecification.fulltextTerms("Sa")).containsExactly("+Sa*");
        assertThat(BookSearchSpecification.fulltextTerms("J. R.")).containsExactly("+J*", "+R*");
    }

    @Test
    void unicodeLettersAndDuplicatesAreHandled() {
        assertThat(BookSearchSpecification.fulltextTerms("Žižek Žižek Łódź")).containsExactly("+Žižek*", "+Łódź*");
    }

    @Test
    void keepsOnlyTheFirstSixteenTerms() {
        String query = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "w" + (100 + i))
                .collect(java.util.stream.Collectors.joining(" "));
        assertThat(BookSearchSpecification.fulltextTerms(query))
                .hasSize(BookSearchSpecification.MAX_TERMS)
                .startsWith("+w100*")
                .endsWith("+w115*");
    }

    @Test
    void readsOnlyTheFirst256Characters() {
        // 63 x "zzz " fills 252 characters, so the cut at 256 leaves "abcd" and drops "ghi".
        String query = "zzz ".repeat(63) + "abcdef ghi";
        assertThat(query.length()).isGreaterThan(BookSearchSpecification.MAX_QUERY_LENGTH);
        assertThat(BookSearchSpecification.fulltextTerms(query)).containsExactly("+zzz*", "+abcd*");
    }

    @Test
    void punctuationOnlyQueryHasNoTerms() {
        assertThat(BookSearchSpecification.fulltextTerms("--- !!")).isEmpty();
    }
}
