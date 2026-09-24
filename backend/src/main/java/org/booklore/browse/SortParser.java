package org.booklore.browse;

import org.booklore.exception.ApiError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Parses the sort param- comma-separated keys, each optionally '-' prefixed for descending
// Always appends the primary key, in the last key's direction, so pagination is stable
public final class SortParser {

    public static final String TIEBREAKER_KEY = "id";

    private SortParser() {
    }

    public static List<SortTerm> parse(String sortString, Set<String> knownKeys) {
        List<SortTerm> terms = new ArrayList<>();

        if (sortString != null && !sortString.isBlank()) {
            for (String raw : sortString.split(",", -1)) {
                String token = raw.trim();
                boolean descending = false;
                if (token.startsWith("-")) {
                    descending = true;
                    token = token.substring(1).trim();
                }
                if (token.isEmpty()) {
                    throw ApiError.INVALID_SORT.createException("Empty sort key in: " + sortString);
                }
                // id is reserved for the implicit tiebreaker, so to callers it is not a sortable key.
                if (token.equals(TIEBREAKER_KEY) || !knownKeys.contains(token)) {
                    throw ApiError.INVALID_SORT.createException("Unknown sort key: " + token);
                }
                terms.add(new SortTerm(token, descending));
            }
        }

        // Same direction as the last key, so a (key, id) index can be walked backwards too.
        boolean descending = !terms.isEmpty() && terms.getLast().descending();
        terms.add(new SortTerm(TIEBREAKER_KEY, descending));
        return terms;
    }
}
