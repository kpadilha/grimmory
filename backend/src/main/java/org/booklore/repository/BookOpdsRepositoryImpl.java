package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.ListJoin;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.booklore.app.specification.AppBookSpecification;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.OpdsSortOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;

public class BookOpdsRepositoryImpl implements BookOpdsRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Page<Long> findBookIds(Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable) {
        return findIds(AppBookSpecification.notDeleted().and(restriction), false, sortOrder, pageable);
    }

    @Override
    public Page<Long> findRecentBookIds(Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIds(restriction, sortOrder, pageable);
    }

    @Override
    public Page<Long> findBookIdsByLibraryIds(Collection<Long> libraryIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable) {
        return findIds(AppBookSpecification.notDeleted()
                .and(BookOpdsSpecifications.inLibraries(libraryIds))
                .and(restriction), false, sortOrder, pageable);
    }

    @Override
    public Page<Long> findRecentBookIdsByLibraryIds(Collection<Long> libraryIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable) {
        return findBookIdsByLibraryIds(libraryIds, restriction, sortOrder, pageable);
    }

    @Override
    public Page<Long> findBookIdsByShelfIds(Collection<Long> libraryIds, Collection<Long> shelfIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable) {
        return findIds(AppBookSpecification.notDeleted()
                .and(BookOpdsSpecifications.inLibraries(libraryIds))
                .and(BookOpdsSpecifications.inShelves(shelfIds))
                .and(restriction), true, sortOrder, pageable);
    }

    @Override
    public Page<Long> findBookIdsByMetadataSearch(String text, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable) {
        return findIds(AppBookSpecification.notDeleted()
                .and(BookOpdsSpecifications.metadataSearch(text))
                .and(restriction), true, sortOrder, pageable);
    }

    @Override
    public Page<Long> findBookIdsByMetadataSearchAndLibraryIds(String text, Collection<Long> libraryIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable) {
        return findIds(AppBookSpecification.notDeleted()
                .and(BookOpdsSpecifications.inLibraries(libraryIds))
                .and(BookOpdsSpecifications.metadataSearch(text))
                .and(restriction), true, sortOrder, pageable);
    }

    @Override
    public Page<Long> findBookIdsByMetadataSearchAndShelfIds(String text, Collection<Long> libraryIds, Collection<Long> shelfIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable) {
        return findIds(AppBookSpecification.notDeleted()
                .and(BookOpdsSpecifications.inLibraries(libraryIds))
                .and(BookOpdsSpecifications.inShelves(shelfIds))
                .and(BookOpdsSpecifications.metadataSearch(text))
                .and(restriction), true, sortOrder, pageable);
    }

    @Override
    public Page<Long> findBookIdsByAuthorName(String authorName, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable) {
        return findIds(AppBookSpecification.notDeleted()
                .and(BookOpdsSpecifications.hasAuthorName(authorName))
                .and(restriction), true, sortOrder, pageable);
    }

    @Override
    public Page<Long> findBookIdsByAuthorNameAndLibraryIds(String authorName, Collection<Long> libraryIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable) {
        return findIds(AppBookSpecification.notDeleted()
                .and(BookOpdsSpecifications.hasAuthorName(authorName))
                .and(BookOpdsSpecifications.inLibraries(libraryIds))
                .and(restriction), true, sortOrder, pageable);
    }

    @Override
    public Page<Long> findBookIdsBySeriesName(String seriesName, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable) {
        return findIds(AppBookSpecification.notDeleted()
                .and(BookOpdsSpecifications.hasSeriesName(seriesName))
                .and(restriction), true, sortOrder, pageable);
    }

    @Override
    public Page<Long> findBookIdsBySeriesNameAndLibraryIds(String seriesName, Collection<Long> libraryIds, Specification<BookEntity> restriction, OpdsSortOrder sortOrder, Pageable pageable) {
        return findIds(AppBookSpecification.notDeleted()
                .and(BookOpdsSpecifications.hasSeriesName(seriesName))
                .and(BookOpdsSpecifications.inLibraries(libraryIds))
                .and(restriction), true, sortOrder, pageable);
    }

    @Override
    public Page<Long> findBookIdsBySeriesNameInSeriesOrder(String seriesName, Specification<BookEntity> restriction, Pageable pageable) {
        return findIdsInSeriesOrder(AppBookSpecification.notDeleted()
                .and(BookOpdsSpecifications.hasSeriesName(seriesName))
                .and(restriction), true, pageable);
    }

    @Override
    public Page<Long> findBookIdsBySeriesNameAndLibraryIdsInSeriesOrder(String seriesName, Collection<Long> libraryIds, Specification<BookEntity> restriction, Pageable pageable) {
        return findIdsInSeriesOrder(AppBookSpecification.notDeleted()
                .and(BookOpdsSpecifications.hasSeriesName(seriesName))
                .and(BookOpdsSpecifications.inLibraries(libraryIds))
                .and(restriction), true, pageable);
    }

    // Two-query pattern's first half: ids for the requested page, plus the true total for the same
    // WHERE clause. Sort is built as real Criteria Order objects (COALESCE/CASE/arithmetic) because
    // JpaSort.unsafe's JPQL-text sorts only resolve against an id-query's own aliases, not a
    // Specification's anonymous Criteria root. `distinct` mirrors the original per-query JPQL: only
    // set when a join in the WHERE clause (shelf/author membership) can multiply rows - forcing it
    // unconditionally breaks strict engines that require DISTINCT's ORDER BY columns to be selected.
    private Page<Long> findIds(Specification<BookEntity> filter, boolean distinct, OpdsSortOrder sortOrder, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Long> idQuery = cb.createQuery(Long.class);
        Root<BookEntity> idRoot = idQuery.from(BookEntity.class);
        Join<BookEntity, BookMetadataEntity> m = OpdsSortCriteria.joinMetadata(idRoot);
        ListJoin<BookMetadataEntity, AuthorEntity> sa = OpdsSortCriteria.needsFirstAuthor(sortOrder)
                ? OpdsSortCriteria.joinFirstAuthor(cb, m) : null;
        idQuery.select(idRoot.get("id")).distinct(distinct);
        applyWhere(idQuery, idRoot, cb, filter);
        idQuery.orderBy(OpdsSortCriteria.orders(cb, idRoot, m, sa, sortOrder));

        List<Long> ids = paginate(idQuery, pageable);
        long total = count(filter, cb);
        return new PageImpl<>(ids, pageable, total);
    }

    private Page<Long> findIdsInSeriesOrder(Specification<BookEntity> filter, boolean distinct, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Long> idQuery = cb.createQuery(Long.class);
        Root<BookEntity> idRoot = idQuery.from(BookEntity.class);
        Join<BookEntity, BookMetadataEntity> m = OpdsSortCriteria.joinMetadata(idRoot);
        idQuery.select(idRoot.get("id")).distinct(distinct);
        applyWhere(idQuery, idRoot, cb, filter);
        idQuery.orderBy(OpdsSortCriteria.seriesNaturalOrders(cb, idRoot, m));

        List<Long> ids = paginate(idQuery, pageable);
        long total = count(filter, cb);
        return new PageImpl<>(ids, pageable, total);
    }

    private void applyWhere(CriteriaQuery<Long> query, Root<BookEntity> root, CriteriaBuilder cb, Specification<BookEntity> filter) {
        Predicate where = filter.toPredicate(root, query, cb);
        if (where != null) {
            query.where(where);
        }
    }

    private List<Long> paginate(CriteriaQuery<Long> idQuery, Pageable pageable) {
        TypedQuery<Long> typedQuery = em.createQuery(idQuery);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        return typedQuery.getResultList();
    }

    private long count(Specification<BookEntity> filter, CriteriaBuilder cb) {
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<BookEntity> countRoot = countQuery.from(BookEntity.class);
        countQuery.select(cb.countDistinct(countRoot.get("id")));
        applyWhere(countQuery, countRoot, cb, filter);
        return em.createQuery(countQuery).getSingleResult();
    }
}
