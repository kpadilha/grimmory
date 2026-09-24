package org.booklore.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Registers {@code book_search_term(bookId, term)}: true when one MariaDB FULLTEXT boolean-mode term
 * matches the book's title, series, author, category or tag. MariaDB-only; H2 cannot run it.
 */
public class BookSearchFunctionContributor implements FunctionContributor {

    public static final String BOOK_SEARCH_TERM = "book_search_term";

    // A non-correlated UNION of index hits is materialised once, then probed per book.
    private static final String PATTERN = "?1 in (select fts.book_id from ("
            + "select m.book_id from book_metadata m where match(m.title, m.series_name) against(?2 in boolean mode)"
            + " union select am.book_id from book_metadata_author_mapping am join author a on a.id = am.author_id"
            + " where match(a.name) against(?2 in boolean mode)"
            + " union select cm.book_id from book_metadata_category_mapping cm join category c on c.id = cm.category_id"
            + " where match(c.name) against(?2 in boolean mode)"
            + " union select tm.book_id from book_metadata_tag_mapping tm join tag t on t.id = tm.tag_id"
            + " where match(t.name) against(?2 in boolean mode)"
            + ") fts)";

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        functionContributions.getFunctionRegistry().registerPattern(
                BOOK_SEARCH_TERM,
                PATTERN,
                functionContributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.BOOLEAN));
    }
}
