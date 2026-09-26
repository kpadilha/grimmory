package org.booklore.repository;

import org.booklore.model.entity.AuthorEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface AuthorRepository extends JpaRepository<AuthorEntity, Long> {

    Optional<AuthorEntity> findByName(String name);

    Optional<AuthorEntity> findByNameIgnoreCase(String name);

    @Query("SELECT a FROM AuthorEntity a JOIN a.bookMetadataEntityList bm WHERE bm.bookId = :bookId")
    List<AuthorEntity> findAuthorsByBookId(@Param("bookId") Long bookId);

    Optional<AuthorEntity> findByAsin(String asin);

    // Ordered and paged in the query itself (LIMIT/OFFSET pushed to the DB) - never the whole table.
    @Query("SELECT a, COUNT(bm) FROM AuthorEntity a LEFT JOIN a.bookMetadataEntityList bm GROUP BY a ORDER BY a.name")
    List<Object[]> findAllWithBookCount(Pageable pageable);

    @Query("SELECT COUNT(a) FROM AuthorEntity a")
    long countAllAuthors();

    @Query("SELECT a, COUNT(DISTINCT bm) FROM AuthorEntity a LEFT JOIN a.bookMetadataEntityList bm JOIN bm.book b WHERE b.library.id IN :libraryIds GROUP BY a ORDER BY a.name")
    List<Object[]> findAllWithBookCountByLibraryIds(@Param("libraryIds") Set<Long> libraryIds, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT a) FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.book b WHERE b.library.id IN :libraryIds")
    long countAllAuthorsByLibraryIds(@Param("libraryIds") Set<Long> libraryIds);

    @Query("SELECT COUNT(b) > 0 FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.book b WHERE a.id = :authorId AND b.library.id IN :libraryIds")
    boolean existsByIdAndLibraryIds(@Param("authorId") Long authorId, @Param("libraryIds") Set<Long> libraryIds);

    @Query("SELECT bm.bookId AS bookId, a.name AS authorName FROM AuthorEntity a JOIN a.bookMetadataEntityList bm WHERE bm.bookId IN :bookIds ORDER BY a.name")
    List<AuthorBookProjection> findAuthorNamesByBookIds(@Param("bookIds") Set<Long> bookIds);

    // Enrichment below is fetched in bulk for a page of author IDs at a time (never per-author
    // loops, never the full author table), replacing what author-browser used to compute by
    // downloading the entire book collection client-side.
    //
    // Each has a ...ByLibraryIds sibling that adds the same b.library.id IN :libraryIds filter
    // findAllWithBookCountByLibraryIds already applies - without it, a non-admin's enrichment
    // would leak library/category/series/addedOn/progress from books outside their assigned
    // libraries, for an author who also has books they cannot see.

    @Query("SELECT a.id AS authorId, b.library.name AS libraryName FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.book b WHERE a.id IN :authorIds")
    List<AuthorLibraryRow> findLibraryNamesForAuthors(@Param("authorIds") Set<Long> authorIds);

    @Query("SELECT a.id AS authorId, b.library.name AS libraryName FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.book b WHERE a.id IN :authorIds AND b.library.id IN :libraryIds")
    List<AuthorLibraryRow> findLibraryNamesForAuthorsByLibraryIds(@Param("authorIds") Set<Long> authorIds, @Param("libraryIds") Set<Long> libraryIds);

    @Query("SELECT a.id AS authorId, c.name AS categoryName FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.categories c WHERE a.id IN :authorIds")
    List<AuthorCategoryRow> findCategoriesForAuthors(@Param("authorIds") Set<Long> authorIds);

    @Query("SELECT a.id AS authorId, c.name AS categoryName FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.categories c JOIN bm.book b WHERE a.id IN :authorIds AND b.library.id IN :libraryIds")
    List<AuthorCategoryRow> findCategoriesForAuthorsByLibraryIds(@Param("authorIds") Set<Long> authorIds, @Param("libraryIds") Set<Long> libraryIds);

    @Query("SELECT a.id AS authorId, bm.seriesName AS seriesName FROM AuthorEntity a JOIN a.bookMetadataEntityList bm WHERE a.id IN :authorIds AND bm.seriesName IS NOT NULL")
    List<AuthorSeriesRow> findSeriesNamesForAuthors(@Param("authorIds") Set<Long> authorIds);

    @Query("SELECT a.id AS authorId, bm.seriesName AS seriesName FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.book b WHERE a.id IN :authorIds AND bm.seriesName IS NOT NULL AND b.library.id IN :libraryIds")
    List<AuthorSeriesRow> findSeriesNamesForAuthorsByLibraryIds(@Param("authorIds") Set<Long> authorIds, @Param("libraryIds") Set<Long> libraryIds);

    @Query("SELECT a.id AS authorId, b.addedOn AS addedOn FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.book b WHERE a.id IN :authorIds")
    List<AuthorAddedOnRow> findAddedOnForAuthors(@Param("authorIds") Set<Long> authorIds);

    @Query("SELECT a.id AS authorId, b.addedOn AS addedOn FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.book b WHERE a.id IN :authorIds AND b.library.id IN :libraryIds")
    List<AuthorAddedOnRow> findAddedOnForAuthorsByLibraryIds(@Param("authorIds") Set<Long> authorIds, @Param("libraryIds") Set<Long> libraryIds);

    @Query("SELECT a.id AS authorId, p.readStatus AS readStatus, p.lastReadTime AS lastReadTime, p.personalRating AS personalRating " +
            "FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.book b JOIN b.userBookProgress p " +
            "WHERE a.id IN :authorIds AND p.user.id = :userId")
    List<AuthorProgressRow> findProgressForAuthors(@Param("authorIds") Set<Long> authorIds, @Param("userId") Long userId);

    @Query("SELECT a.id AS authorId, p.readStatus AS readStatus, p.lastReadTime AS lastReadTime, p.personalRating AS personalRating " +
            "FROM AuthorEntity a JOIN a.bookMetadataEntityList bm JOIN bm.book b JOIN b.userBookProgress p " +
            "WHERE a.id IN :authorIds AND p.user.id = :userId AND b.library.id IN :libraryIds")
    List<AuthorProgressRow> findProgressForAuthorsByLibraryIds(@Param("authorIds") Set<Long> authorIds, @Param("userId") Long userId, @Param("libraryIds") Set<Long> libraryIds);

    interface AuthorBookProjection {
        Long getBookId();
        String getAuthorName();
    }

    interface AuthorLibraryRow {
        Long getAuthorId();
        String getLibraryName();
    }

    interface AuthorCategoryRow {
        Long getAuthorId();
        String getCategoryName();
    }

    interface AuthorSeriesRow {
        Long getAuthorId();
        String getSeriesName();
    }

    interface AuthorAddedOnRow {
        Long getAuthorId();
        Instant getAddedOn();
    }

    interface AuthorProgressRow {
        Long getAuthorId();
        org.booklore.model.enums.ReadStatus getReadStatus();
        Instant getLastReadTime();
        Integer getPersonalRating();
    }
}
