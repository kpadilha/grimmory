package org.booklore.repository;

import org.booklore.model.entity.BookEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface BookOpdsRepository extends JpaRepository<BookEntity, Long>, JpaSpecificationExecutor<BookEntity>, BookOpdsRepositoryCustom {

    // ============================================
    // ENTITY FETCH BY IDS - second half of the two-query pattern. Id-selection (with restriction
    // and sort) lives in BookOpdsRepositoryImpl; these only load full entities for an already
    // decided, already-paged id list.
    // ============================================

    @EntityGraph(attributePaths = {"metadata", "bookFiles", "shelves"})
    @Query("SELECT b FROM BookEntity b WHERE b.id IN :ids AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByIds(@Param("ids") Collection<Long> ids);

    @EntityGraph(attributePaths = {"metadata", "bookFiles", "shelves"})
    @Query("SELECT b FROM BookEntity b WHERE b.id IN :ids AND b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByIdsAndLibraryIds(@Param("ids") Collection<Long> ids, @Param("libraryIds") Collection<Long> libraryIds);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "bookFiles", "shelves"})
    @Query("SELECT DISTINCT b FROM BookEntity b WHERE b.id IN :ids AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithFullMetadataByIds(@Param("ids") Collection<Long> ids);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "bookFiles", "shelves"})
    @Query("SELECT DISTINCT b FROM BookEntity b WHERE b.id IN :ids AND b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithFullMetadataByIdsAndLibraryIds(@Param("ids") Collection<Long> ids, @Param("libraryIds") Collection<Long> libraryIds);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "bookFiles", "shelves"})
    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            JOIN b.shelves s
            WHERE
                b.id IN :ids
                AND b.library.id IN :libraryIds
                AND s.id IN :shelfIds
                AND (b.deleted IS NULL OR b.deleted = false)
    """)
    List<BookEntity> findAllWithFullMetadataByIdsAndShelfIds(@Param("ids") Collection<Long> ids, @Param("libraryIds") Collection<Long> libraryIds, @Param("shelfIds") Collection<Long> shelfIds);

    @EntityGraph(attributePaths = {"metadata", "bookFiles", "shelves"})
    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            JOIN b.shelves s
            WHERE
                b.id IN :ids
                AND b.library.id IN :libraryIds
                AND s.id IN :shelfIds
                AND (b.deleted IS NULL OR b.deleted = false)
    """)
    List<BookEntity> findAllWithMetadataByIdsAndShelfIds(@Param("ids") Collection<Long> ids, @Param("libraryIds") Collection<Long> libraryIds, @Param("shelfIds") Collection<Long> shelfIds);

    // ============================================
    // RANDOM BOOKS - "Surprise Me" Feed
    // ============================================

    @Query("SELECT b.id FROM BookEntity b WHERE b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false) ORDER BY function('RAND')")
    List<Long> findRandomBookIdsByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);

    // ============================================
    // AUTHORS - Distinct Authors List
    // ============================================

    @Query("""
            SELECT DISTINCT a.name
            FROM AuthorEntity a
            JOIN a.bookMetadataEntityList m
            JOIN m.book b
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND a.name IS NOT NULL
            ORDER BY a.name
            """)
    Page<String> findDistinctAuthorNames(Pageable pageable);

    @Query("""
            SELECT DISTINCT a.name
            FROM AuthorEntity a
            JOIN a.bookMetadataEntityList m
            JOIN m.book b
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND b.library.id IN :libraryIds
              AND a.name IS NOT NULL
            ORDER BY a.name
            """)
    Page<String> findDistinctAuthorNamesByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);

    // ============================================
    // SERIES - Distinct Series List
    // ============================================

    @Query("""
            SELECT DISTINCT m.seriesName FROM BookMetadataEntity m
            JOIN m.book b
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND m.seriesName IS NOT NULL
              AND m.seriesName != ''
            ORDER BY m.seriesName
            """)
    Page<String> findDistinctSeries(Pageable pageable);

    @Query("""
            SELECT DISTINCT m.seriesName FROM BookMetadataEntity m
            JOIN m.book b
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND b.library.id IN :libraryIds
              AND m.seriesName IS NOT NULL
              AND m.seriesName != ''
            ORDER BY m.seriesName
            """)
    Page<String> findDistinctSeriesByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);
}
