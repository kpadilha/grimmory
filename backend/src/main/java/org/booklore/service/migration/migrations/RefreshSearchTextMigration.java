package org.booklore.service.migration.migrations;

import lombok.RequiredArgsConstructor;
import org.booklore.service.migration.Migration;
import org.springframework.stereotype.Component;

/** Recomputes search text once more after it gained categories, tags, identifiers and phonetic keys. */
@Component
@RequiredArgsConstructor
public class RefreshSearchTextMigration implements Migration {

    private final PopulateSearchTextMigration populateSearchTextMigration;

    @Override
    public String getKey() {
        return "refreshSearchTextWithTagsAndPhonetic";
    }

    @Override
    public String getDescription() {
        return "Recompute search_text and search_phonetic for all books";
    }

    @Override
    public void execute() {
        populateSearchTextMigration.execute();
    }
}
