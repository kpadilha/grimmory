package db.migration;

import org.booklore.util.BookUtils;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recomputes every book's search text and phonetic keys with the writers' own functions. It runs in
 * Flyway, before the application serves requests, so no concurrent edit can be overwritten by it.
 * Re-runnable: rows are upserted.
 */
public class V149_5__Refresh_search_text extends BaseJavaMigration {

    private static final int BATCH = 5000;

    private static final String BOOKS = "SELECT book_id, title, subtitle, series_name, isbn_13, isbn_10, asin"
            + " FROM book_metadata WHERE book_id > ? ORDER BY book_id LIMIT " + BATCH;
    private static final String AUTHORS = "SELECT am.book_id, a.name FROM book_metadata_author_mapping am"
            + " JOIN author a ON a.id = am.author_id WHERE am.book_id BETWEEN ? AND ? ORDER BY am.book_id, am.sort_order";
    private static final String CATEGORIES = "SELECT cm.book_id, c.name FROM book_metadata_category_mapping cm"
            + " JOIN category c ON c.id = cm.category_id WHERE cm.book_id BETWEEN ? AND ?";
    private static final String TAGS = "SELECT tm.book_id, t.name FROM book_metadata_tag_mapping tm"
            + " JOIN tag t ON t.id = tm.tag_id WHERE tm.book_id BETWEEN ? AND ?";
    private static final String UPSERT = "INSERT INTO book_metadata_search (book_id, search_text, search_phonetic)"
            + " VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE search_text = VALUES(search_text), search_phonetic = VALUES(search_phonetic)";

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        long lastId = 0;
        while (true) {
            List<Object[]> books = new ArrayList<>(BATCH);
            try (PreparedStatement select = connection.prepareStatement(BOOKS)) {
                select.setLong(1, lastId);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        books.add(new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), rs.getString(7)});
                    }
                }
            }
            if (books.isEmpty()) {
                return;
            }
            long first = (long) books.getFirst()[0];
            lastId = (long) books.getLast()[0];
            Map<Long, List<String>> authors = names(connection, AUTHORS, first, lastId);
            Map<Long, List<String>> categories = names(connection, CATEGORIES, first, lastId);
            Map<Long, List<String>> tags = names(connection, TAGS, first, lastId);

            try (PreparedStatement upsert = connection.prepareStatement(UPSERT)) {
                for (Object[] b : books) {
                    long id = (long) b[0];
                    List<String> bookAuthors = authors.getOrDefault(id, List.of());
                    upsert.setLong(1, id);
                    upsert.setString(2, BookUtils.buildSearchText((String) b[1], (String) b[2], (String) b[3], bookAuthors,
                            categories.getOrDefault(id, List.of()), tags.getOrDefault(id, List.of()),
                            (String) b[4], (String) b[5], (String) b[6]));
                    upsert.setString(3, BookUtils.buildSearchPhonetic(bookAuthors));
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
        }
    }

    private static Map<Long, List<String>> names(Connection connection, String sql, long from, long to) throws SQLException {
        Map<Long, List<String>> names = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setLong(1, from);
            select.setLong(2, to);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    names.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                }
            }
        }
        return names;
    }
}
