package org.booklore.repository;

import org.booklore.model.entity.BookEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Test-only: reproduces the pre-refactor {@code BookOpdsRepository#findBookIds} id-query exactly
 * (same JPQL, same alias/join shape), so {@code OpdsSortGateProbeTest} exercises the real Spring
 * Data {@code @Query} + Pageable-Sort pipeline instead of reimplementing it by hand.
 */
public interface LegacyOpdsSortProbeRepository extends JpaRepository<BookEntity, Long> {

    @Query("SELECT b.id FROM BookEntity b LEFT JOIN b.metadata m LEFT JOIN m.authors sa WITH INDEX(sa) = 0 WHERE (b.deleted IS NULL OR b.deleted = false)")
    Page<Long> findBookIds(Pageable pageable);
}
