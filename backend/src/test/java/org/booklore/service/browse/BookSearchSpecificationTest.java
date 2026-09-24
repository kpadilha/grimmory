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
    void punctuationOnlyQueryHasNoTerms() {
        assertThat(BookSearchSpecification.fulltextTerms("--- !!")).isEmpty();
    }
}
