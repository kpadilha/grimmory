package org.booklore.config;

import org.booklore.model.entity.BookMetadataEntity;
import org.hibernate.Interceptor;
import org.hibernate.collection.spi.PersistentCollection;

import java.util.Collection;
import java.util.Iterator;

/**
 * Recomputes a book's search text when only its authors, categories or tags changed: JPA fires no
 * {@code @PreUpdate} for collection-only changes, which left search_text stale for every such writer.
 * Runs before Hibernate's dirty check, so the new value is flushed in the same statement batch.
 */
public class SearchTextFlushInterceptor implements Interceptor {

    @Override
    public void preFlush(Iterator<Object> entities) {
        while (entities.hasNext()) {
            if (entities.next() instanceof BookMetadataEntity metadata
                    && (changed(metadata.getAuthors()) || changed(metadata.getCategories()) || changed(metadata.getTags()))) {
                metadata.updateSearchText();
            }
        }
    }

    // A plain collection was assigned by the caller; a persistent one reports its own changes.
    private static boolean changed(Collection<?> collection) {
        if (collection instanceof PersistentCollection<?> persistent) {
            return persistent.wasInitialized() && persistent.isDirty();
        }
        return collection != null;
    }
}
