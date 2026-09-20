package org.booklore.repository;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.enums.OpdsSortOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;

/**
 * Id-query half of the OPDS two-query paging pattern. {@code restriction} is always
 * {@code ContentRestrictionSpecification.from(...)} - the single implementation of what a user is
 * allowed to see - composed into the same WHERE clause as the scope filter (library/shelf/search),
 * so the returned {@link Page#getTotalElements()} is the count the caller can actually reach.
 */
public interface BookOpdsRepositoryCustom {

    Page<Long> findBookIds(Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findRecentBookIds(Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByLibraryIds(Collection<Long> libraryIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findRecentBookIdsByLibraryIds(Collection<Long> libraryIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByShelfIds(Collection<Long> libraryIds, Collection<Long> shelfIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByMetadataSearch(String text, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByMetadataSearchAndLibraryIds(String text, Collection<Long> libraryIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByMetadataSearchAndShelfIds(String text, Collection<Long> libraryIds, Collection<Long> shelfIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByAuthorName(String authorName, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsByAuthorNameAndLibraryIds(String authorName, Collection<Long> libraryIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsBySeriesName(String seriesName, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable);

    Page<Long> findBookIdsBySeriesNameAndLibraryIds(String seriesName, Collection<Long> libraryIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable);

    // Series-browse default (RECENT/null) is series order, not addedOn - see OpdsSortCriteria#seriesNaturalOrders.
    Page<Long> findBookIdsBySeriesNameInSeriesOrder(String seriesName, Specification<BookEntity> restriction, Pageable pageable);

    Page<Long> findBookIdsBySeriesNameAndLibraryIdsInSeriesOrder(String seriesName, Collection<Long> libraryIds, Specification<BookEntity> restriction, Pageable pageable);
}
