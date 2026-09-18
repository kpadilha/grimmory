package org.booklore.service.opds;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.model.enums.OpdsSortOrder;
import org.booklore.repository.BookOpdsRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.repository.UserRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.library.LibraryService;
import org.booklore.service.restriction.ContentRestrictionService;
import org.booklore.util.BookUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class OpdsBookService {

    private final BookOpdsRepository bookOpdsRepository;
    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final UserRepository userRepository;
    private final BookLoreUserTransformer bookLoreUserTransformer;
    private final ShelfRepository shelfRepository;
    private final LibraryService libraryService;
    private final ContentRestrictionService contentRestrictionService;

    public List<Library> getAccessibleLibraries(Long userId) {
        if (userId == null) {
            return List.of();
        }

        BookLoreUserEntity entity = userRepository.findByIdWithDetails(userId)
                .orElseThrow(() -> ApiError.USER_NOT_FOUND.createException(userId));
        BookLoreUser user = bookLoreUserTransformer.toDTO(entity);

        List<Library> libraries;
        if (user.getPermissions() != null && user.getPermissions().isAdmin()) {
            libraries = libraryService.getAllLibraries();
        } else {
            libraries = user.getAssignedLibraries();
        }

        return libraries.stream()
                .sorted(Comparator.comparing(Library::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Page<Book> getBooksPage(Long userId, String query, Long libraryId, Set<Long> shelfIds, int page, int size) {
        return getBooksPage(userId, query, libraryId, shelfIds, page, size, OpdsSortOrder.RECENT);
    }

    public Page<Book> getBooksPage(Long userId, String query, Long libraryId, Set<Long> shelfIds, int page, int size, OpdsSortOrder sortOrder) {
        if (userId == null) {
            throw ApiError.FORBIDDEN.createException("Authentication required");
        }

        BookLoreUserEntity entity = userRepository.findByIdWithDetails(userId)
                .orElseThrow(() -> ApiError.USER_NOT_FOUND.createException(userId));

        BookLoreUser user = bookLoreUserTransformer.toDTO(entity);
        boolean isAdmin = user.getPermissions().isAdmin();
        Set<Long> userLibraryIds = getAccessibleLibraries(userId).stream().map(Library::getId).collect(Collectors.toSet());

        if (shelfIds != null && !shelfIds.isEmpty()) {
            validateShelfAccess(shelfIds, user.getId(), isAdmin);
            Page<Book> books = query != null && !query.isBlank()
                    ? searchByMetadataInShelvesPageInternal(BookUtils.normalizeForSearch(query), userLibraryIds, shelfIds, page, size, userId, sortOrder)
                    : getBooksByShelfIdsPageInternal(userLibraryIds, shelfIds, page, size, userId, sortOrder);
            return applyBookFilters(books, userId);
        }

        if (libraryId != null) {
            validateLibraryAccess(libraryId, userLibraryIds, isAdmin);
            Page<Book> books = query != null && !query.isBlank()
                    ? searchByMetadataInLibrariesPageInternal(BookUtils.normalizeForSearch(query), Set.of(libraryId), page, size, userId, sortOrder)
                    : getBooksByLibraryIdsPageInternal(Set.of(libraryId), page, size, userId, sortOrder);
            return applyBookFilters(books, userId);
        }

        if (isAdmin) {
            return query != null && !query.isBlank()
                    ? searchByMetadataPageInternal(BookUtils.normalizeForSearch(query), page, size, null, sortOrder)
                    : getAllBooksPageInternal(page, size, null, sortOrder);
        }

        Page<Book> books = query != null && !query.isBlank()
                ? searchByMetadataInLibrariesPageInternal(BookUtils.normalizeForSearch(query), userLibraryIds, page, size, userId, sortOrder)
                : getBooksByLibraryIdsPageInternal(userLibraryIds, page, size, userId, sortOrder);
        return applyBookFilters(books, userId);
    }

    public Page<Book> getRecentBooksPage(Long userId, int page, int size) {
        return getRecentBooksPage(userId, page, size, OpdsSortOrder.RECENT);
    }

    public Page<Book> getRecentBooksPage(Long userId, int page, int size, OpdsSortOrder sortOrder) {
        if (userId == null) {
            throw ApiError.FORBIDDEN.createException("Authentication required");
        }

        BookLoreUserEntity entity = userRepository.findByIdWithDetails(userId)
                .orElseThrow(() -> ApiError.USER_NOT_FOUND.createException(userId));
        BookLoreUser user = bookLoreUserTransformer.toDTO(entity);

        if (user.getPermissions().isAdmin()) {
            return getRecentBooksPageInternal(page, size, null, sortOrder);
        }

        Set<Long> libraryIds = user.getAssignedLibraries().stream()
                .map(Library::getId)
                .collect(Collectors.toSet());

        Page<Book> books = getRecentBooksByLibraryIdsPageInternal(libraryIds, page, size, userId, sortOrder);
        return applyBookFilters(books, userId);
    }

    public String getLibraryName(Long libraryId) {
        try {
            List<Library> libraries = libraryService.getAllLibraries();
            return libraries.stream()
                    .filter(lib -> lib.getId().equals(libraryId))
                    .map(Library::getName)
                    .findFirst()
                    .orElse("Library Books");
        } catch (Exception e) {
            log.warn("Failed to get library name for id: {}", libraryId, e);
            return "Library Books";
        }
    }

    public String getShelfName(Long shelfId) {
        return shelfRepository.findById(shelfId)
                .map(s -> s.getName() + " - Shelf")
                .orElse("Shelf Books");
    }

    public List<ShelfEntity> getUserShelves(Long userId) {
        return shelfRepository.findByUserId(userId);
    }

    public List<Book> getRandomBooks(Long userId, int count) {
        if (count < 1) {
            return List.of();
        }

        List<Library> accessibleLibraries = getAccessibleLibraries(userId);
        if (accessibleLibraries == null || accessibleLibraries.isEmpty()) {
            return List.of();
        }

        List<Long> libraryIds = accessibleLibraries.stream().map(Library::getId).toList();
        List<Long> ids = bookOpdsRepository.findRandomBookIdsByLibraryIds(libraryIds, PageRequest.of(0, count));

        if (ids.isEmpty()) {
            return List.of();
        }

        List<BookEntity> books = bookOpdsRepository.findAllWithMetadataByIds(ids);
        if (userId != null) {
            books = contentRestrictionService.applyRestrictions(books, userId);
        }
        return books.stream().map(bookMapper::toBook).toList();
    }

    public Page<String> getDistinctAuthors(Long userId, int page, int size) {
        if (userId == null) {
            return Page.empty();
        }

        Pageable pageable = PageRequest.of(Math.max(page, 0), size);

        BookLoreUserEntity entity = userRepository.findByIdWithDetails(userId)
                .orElseThrow(() -> ApiError.USER_NOT_FOUND.createException(userId));
        BookLoreUser user = bookLoreUserTransformer.toDTO(entity);

        if (user.getPermissions().isAdmin()) {
            return bookOpdsRepository.findDistinctAuthorNames(pageable);
        } else {
            Set<Long> libraryIds = user.getAssignedLibraries().stream()
                    .map(Library::getId)
                    .collect(Collectors.toSet());
            return bookOpdsRepository.findDistinctAuthorNamesByLibraryIds(libraryIds, pageable);
        }
    }

    public Page<Book> getBooksByAuthorName(Long userId, String authorName, int page, int size, OpdsSortOrder sortOrder) {
        if (userId == null) {
            throw ApiError.FORBIDDEN.createException("Authentication required");
        }

        BookLoreUserEntity entity = userRepository.findByIdWithDetails(userId)
                .orElseThrow(() -> ApiError.USER_NOT_FOUND.createException(userId));
        BookLoreUser user = bookLoreUserTransformer.toDTO(entity);

        Pageable pageable = PageRequest.of(Math.max(page, 0), size, resolveSort(sortOrder));

        if (user.getPermissions().isAdmin()) {
            Page<Long> idPage = bookOpdsRepository.findBookIdsByAuthorName(authorName, pageable);
            if (idPage.isEmpty()) {
                return new PageImpl<>(List.of(), pageable, 0);
            }
            List<BookEntity> books = bookOpdsRepository.findAllWithFullMetadataByIds(idPage.getContent());
            return createPageFromEntities(books, idPage, pageable, null);
        }

        Set<Long> libraryIds = user.getAssignedLibraries().stream()
                .map(Library::getId)
                .collect(Collectors.toSet());

        Page<Long> idPage = bookOpdsRepository.findBookIdsByAuthorNameAndLibraryIds(authorName, libraryIds, pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<BookEntity> books = bookOpdsRepository.findAllWithFullMetadataByIdsAndLibraryIds(idPage.getContent(), libraryIds);
        Page<Book> booksPage = createPageFromEntities(books, idPage, pageable, userId);
        return applyBookFilters(booksPage, userId);
    }

    public Page<String> getDistinctSeries(Long userId, int page, int size) {
        if (userId == null) {
            return Page.empty();
        }

        Pageable pageable = PageRequest.of(Math.max(page, 0), size);

        BookLoreUserEntity entity = userRepository.findByIdWithDetails(userId)
                .orElseThrow(() -> ApiError.USER_NOT_FOUND.createException(userId));
        BookLoreUser user = bookLoreUserTransformer.toDTO(entity);

        if (user.getPermissions().isAdmin()) {
            return bookOpdsRepository.findDistinctSeries(pageable);
        }

        Set<Long> libraryIds = user.getAssignedLibraries().stream()
                .map(Library::getId)
                .collect(Collectors.toSet());

        return bookOpdsRepository.findDistinctSeriesByLibraryIds(libraryIds, pageable);
    }

    public Page<Book> getBooksBySeriesName(Long userId, String seriesName, int page, int size, OpdsSortOrder sortOrder) {
        if (userId == null) {
            throw ApiError.FORBIDDEN.createException("Authentication required");
        }

        BookLoreUserEntity entity = userRepository.findByIdWithDetails(userId)
                .orElseThrow(() -> ApiError.USER_NOT_FOUND.createException(userId));
        BookLoreUser user = bookLoreUserTransformer.toDTO(entity);

        // Within a single series, RECENT means "series order" (matches the pre-existing
        // default), not addedOn DESC; explicit sort orders behave as everywhere else.
        Pageable pageable = PageRequest.of(Math.max(page, 0), size, resolveSeriesBrowseSort(sortOrder));

        if (user.getPermissions().isAdmin()) {
            Page<Long> idPage = bookOpdsRepository.findBookIdsBySeriesName(seriesName, pageable);
            if (idPage.isEmpty()) {
                return new PageImpl<>(List.of(), pageable, 0);
            }
            List<BookEntity> books = bookOpdsRepository.findAllWithFullMetadataByIds(idPage.getContent());
            return createPageFromEntities(books, idPage, pageable, null);
        }

        Set<Long> libraryIds = user.getAssignedLibraries().stream()
                .map(Library::getId)
                .collect(Collectors.toSet());

        Page<Long> idPage = bookOpdsRepository.findBookIdsBySeriesNameAndLibraryIds(seriesName, libraryIds, pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<BookEntity> books = bookOpdsRepository.findAllWithFullMetadataByIdsAndLibraryIds(idPage.getContent(), libraryIds);
        Page<Book> booksPage = createPageFromEntities(books, idPage, pageable, userId);
        return applyBookFilters(booksPage, userId);
    }

    private Page<Book> getAllBooksPageInternal(int page, int size, Long userId, OpdsSortOrder sortOrder) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size, resolveSort(sortOrder));

        Page<Long> idPage = bookOpdsRepository.findBookIds(pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<BookEntity> books = bookOpdsRepository.findAllWithMetadataByIds(idPage.getContent());
        return createPageFromEntities(books, idPage, pageable, userId);
    }

    private Page<Book> getRecentBooksPageInternal(int page, int size, Long userId, OpdsSortOrder sortOrder) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size, resolveSort(sortOrder));

        Page<Long> idPage = bookOpdsRepository.findRecentBookIds(pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<BookEntity> books = bookOpdsRepository.findAllWithMetadataByIds(idPage.getContent());
        return createPageFromEntities(books, idPage, pageable, userId);
    }

    private Page<Book> getBooksByLibraryIdsPageInternal(Set<Long> libraryIds, int page, int size, Long userId, OpdsSortOrder sortOrder) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size, resolveSort(sortOrder));

        Page<Long> idPage = bookOpdsRepository.findBookIdsByLibraryIds(libraryIds, pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<BookEntity> books = bookOpdsRepository.findAllWithMetadataByIdsAndLibraryIds(idPage.getContent(), libraryIds);
        return createPageFromEntities(books, idPage, pageable, userId);
    }

    private Page<Book> getRecentBooksByLibraryIdsPageInternal(Set<Long> libraryIds, int page, int size, Long userId, OpdsSortOrder sortOrder) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size, resolveSort(sortOrder));

        Page<Long> idPage = bookOpdsRepository.findRecentBookIdsByLibraryIds(libraryIds, pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<BookEntity> books = bookOpdsRepository.findAllWithMetadataByIdsAndLibraryIds(idPage.getContent(), libraryIds);
        return createPageFromEntities(books, idPage, pageable, userId);
    }

    // Dead code: no caller reaches this (see BookOpdsRepository#findBookIdsByShelfId); left as-is.
    private Page<Book> getBooksByShelfIdPageInternal(Long shelfId, int page, int size, Long userId) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size);

        Page<Long> idPage = bookOpdsRepository.findBookIdsByShelfId(shelfId, pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<BookEntity> books = bookOpdsRepository.findAllWithMetadataByIdsAndShelfId(idPage.getContent(), shelfId);
        return createPageFromEntities(books, idPage, pageable, userId);
    }

    private Page<Book> getBooksByShelfIdsPageInternal(Set<Long> libraryIds, Set<Long> shelfIds, int page, int size, Long userId, OpdsSortOrder sortOrder) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size, resolveSort(sortOrder));

        Page<Long> idPage = bookOpdsRepository.findBookIdsByShelfIds(libraryIds, shelfIds, pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<BookEntity> books = bookOpdsRepository.findAllWithMetadataByIdsAndShelfIds(idPage.getContent(), libraryIds, shelfIds);
        return createPageFromEntities(books, idPage, pageable, userId);
    }

    private Page<Book> searchByMetadataPageInternal(String text, int page, int size, Long userId, OpdsSortOrder sortOrder) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size, resolveSort(sortOrder));

        Page<Long> idPage = bookOpdsRepository.findBookIdsByMetadataSearch(text, pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<BookEntity> books = bookOpdsRepository.findAllWithFullMetadataByIds(idPage.getContent());
        return createPageFromEntities(books, idPage, pageable, userId);
    }

    private Page<Book> searchByMetadataInLibrariesPageInternal(String text, Set<Long> libraryIds, int page, int size, Long userId, OpdsSortOrder sortOrder) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size, resolveSort(sortOrder));

        Page<Long> idPage = bookOpdsRepository.findBookIdsByMetadataSearchAndLibraryIds(text, libraryIds, pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<BookEntity> books = bookOpdsRepository.findAllWithFullMetadataByIdsAndLibraryIds(idPage.getContent(), libraryIds);
        return createPageFromEntities(books, idPage, pageable, userId);
    }

    private Page<Book> searchByMetadataInShelvesPageInternal(String text, Set<Long> libraryIds, Set<Long> shelfIds, int page, int size, Long userId, OpdsSortOrder sortOrder) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size, resolveSort(sortOrder));

        Page<Long> idPage = bookOpdsRepository.findBookIdsByMetadataSearchAndShelfIds(text, libraryIds, shelfIds, pageable);
        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<BookEntity> books = bookOpdsRepository.findAllWithFullMetadataByIdsAndShelfIds(idPage.getContent(), libraryIds, shelfIds);
        return createPageFromEntities(books, idPage, pageable, userId);
    }

    private void validateShelfAccess(Long shelfId, Long userId, boolean isAdmin) {
        var shelf = shelfRepository.findByIdWithUser(shelfId)
                .orElseThrow(() -> ApiError.SHELF_NOT_FOUND.createException(shelfId));
        if (!shelf.getUser().getId().equals(userId) && !isAdmin) {
            throw ApiError.FORBIDDEN.createException("You are not allowed to access this shelf");
        }
    }

    private void validateShelfAccess(Set<Long> shelfIds, Long userId, boolean isAdmin) {
        for (Long shelfId : shelfIds) {
            validateShelfAccess(shelfId, userId, isAdmin);
        }
    }

    private void validateLibraryAccess(Long libraryId, Set<Long> userLibraryIds, boolean isAdmin) {
        if (!isAdmin && !userLibraryIds.contains(libraryId)) {
            throw ApiError.FORBIDDEN.createException("You are not allowed to access this library");
        }
    }

    public void validateBookContentAccess(Long bookId, Long userId) {
        if (userId == null) {
            throw ApiError.FORBIDDEN.createException("Authentication required");
        }

        BookLoreUserEntity entity = userRepository.findById(userId)
                .orElseThrow(() -> ApiError.USER_NOT_FOUND.createException(userId));

        if (entity.getPermissions() != null && entity.getPermissions().isPermissionAdmin()) {
            return;
        }

        BookEntity book = bookRepository.findById(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        BookLoreUser user = bookLoreUserTransformer.toDTO(entity);
        boolean hasLibraryAccess = user.getAssignedLibraries().stream()
                .anyMatch(library -> library.getId().equals(book.getLibrary().getId()));

        if (!hasLibraryAccess) {
            throw ApiError.FORBIDDEN.createException("You are not authorized to access this book.");
        }

        List<BookEntity> filtered = contentRestrictionService.applyRestrictions(List.of(book), userId);
        if (filtered.isEmpty()) {
            throw ApiError.FORBIDDEN.createException("You are not authorized to access this book.");
        }
    }

    private Page<Book> createPageFromEntities(List<BookEntity> books, Page<Long> idPage, Pageable pageable, Long userId) {
        Map<Long, BookEntity> bookMap = books.stream()
                .collect(Collectors.toMap(BookEntity::getId, Function.identity()));

        List<BookEntity> orderedEntities = idPage.getContent().stream()
                .map(bookMap::get)
                .filter(Objects::nonNull)
                .toList();

        if (userId != null) {
            orderedEntities = contentRestrictionService.applyRestrictions(orderedEntities, userId);
        }

        List<Book> sortedBooks = orderedEntities.stream()
                .map(bookMapper::toBook)
                .toList();

        return new PageImpl<>(sortedBooks, pageable, idPage.getTotalElements());
    }

    private Page<Book> applyBookFilters(Page<Book> books, Long userId) {
        List<Book> filtered = books.getContent().stream()
                .map(book -> filterBook(book, userId))
                .toList();
        return new PageImpl<>(filtered, books.getPageable(), books.getTotalElements());
    }

    private Book filterBook(Book dto, Long userId) {
        if (dto.getShelves() != null && userId != null) {
            dto.setShelves(dto.getShelves().stream()
                    .filter(shelf -> userId.equals(shelf.getUserId()))
                    .collect(Collectors.toSet()));
        }
        return dto;
    }

    // Maps OpdsSortOrder to a DB-level Sort against the m (metadata) / sa (first author, via
    // @OrderColumn INDEX(sa)=0) aliases every findBookIds* query left-joins; see SORT_JOINS.
    private static Sort resolveSort(OpdsSortOrder sortOrder) {
        return switch (sortOrder == null ? OpdsSortOrder.RECENT : sortOrder) {
            case RECENT -> recentSort();
            case TITLE_ASC -> titleSort(Sort.Direction.ASC);
            case TITLE_DESC -> titleSort(Sort.Direction.DESC);
            case AUTHOR_ASC -> authorSort(Sort.Direction.ASC);
            case AUTHOR_DESC -> authorSort(Sort.Direction.DESC);
            case SERIES_ASC -> seriesSort(Sort.Direction.ASC);
            case SERIES_DESC -> seriesSort(Sort.Direction.DESC);
            case RATING_ASC -> ratingSort(Sort.Direction.ASC);
            case RATING_DESC -> ratingSort(Sort.Direction.DESC);
        };
    }

    // Browsing a single series pre-existing default was series order, not addedOn; every other
    // sort order behaves like resolveSort.
    private static Sort resolveSeriesBrowseSort(OpdsSortOrder sortOrder) {
        if (sortOrder == null || sortOrder == OpdsSortOrder.RECENT) {
            return JpaSort.unsafe(Sort.Direction.ASC, "COALESCE(m.seriesNumber, 999999)")
                    .andUnsafe(Sort.Direction.DESC, "b.addedOn")
                    .andUnsafe(Sort.Direction.ASC, "b.id");
        }
        return resolveSort(sortOrder);
    }

    private static Sort recentSort() {
        return Sort.by(Sort.Direction.DESC, "addedOn").and(Sort.by(Sort.Direction.ASC, "id"));
    }

    private static Sort titleSort(Sort.Direction direction) {
        return JpaSort.unsafe(direction, "COALESCE(m.title, '')").andUnsafe(Sort.Direction.ASC, "b.id");
    }

    // sortName (not the raw name) matches the alphabetisation BookSortRegistry already uses.
    private static Sort authorSort(Sort.Direction direction) {
        return JpaSort.unsafe(direction, "COALESCE(sa.sortName, '')").andUnsafe(Sort.Direction.ASC, "b.id");
    }

    // Books without a series always sort after ones with a series, in both directions; a missing
    // series number sorts last within its series for the same reason.
    private static Sort seriesSort(Sort.Direction direction) {
        return JpaSort.unsafe(Sort.Direction.ASC, "CASE WHEN COALESCE(m.seriesName, '') = '' THEN 1 ELSE 0 END")
                .andUnsafe(direction, "m.seriesName")
                .andUnsafe(Sort.Direction.ASC, "CASE WHEN m.seriesNumber IS NULL THEN 1 ELSE 0 END")
                .andUnsafe(direction, "m.seriesNumber")
                .andUnsafe(Sort.Direction.ASC, "b.id");
    }

    // Rating is the average of the positive amazon/goodreads/hardcover ratings (mirrors
    // calculateRating below); NULLIF guards the division when none are positive.
    private static final String RATING_HAS_VALUE =
            "CASE WHEN (COALESCE(m.amazonRating,0)>0 OR COALESCE(m.goodreadsRating,0)>0 OR COALESCE(m.hardcoverRating,0)>0) THEN 0 ELSE 1 END";
    private static final String RATING_VALUE =
            "(COALESCE(NULLIF(m.amazonRating,0),0)+COALESCE(NULLIF(m.goodreadsRating,0),0)+COALESCE(NULLIF(m.hardcoverRating,0),0))"
                    + "/NULLIF((CASE WHEN COALESCE(m.amazonRating,0)>0 THEN 1 ELSE 0 END"
                    + "+CASE WHEN COALESCE(m.goodreadsRating,0)>0 THEN 1 ELSE 0 END"
                    + "+CASE WHEN COALESCE(m.hardcoverRating,0)>0 THEN 1 ELSE 0 END),0)";

    private static Sort ratingSort(Sort.Direction direction) {
        return JpaSort.unsafe(Sort.Direction.ASC, RATING_HAS_VALUE)
                .andUnsafe(direction, RATING_VALUE)
                .andUnsafe(Sort.Direction.ASC, "b.id");
    }

    // Retained for the magic-shelf catalog branch only, which queries BookEntity via
    // Specification (not the id-query pattern above) and still sorts a single page in memory.
    public Page<Book> applySortOrder(Page<Book> booksPage, OpdsSortOrder sortOrder) {
        if (sortOrder == null || sortOrder == OpdsSortOrder.RECENT) {
            return booksPage; // Already sorted by addedOn DESC from repository
        }

        List<Book> sortedBooks = new ArrayList<>(booksPage.getContent());
        
        switch (sortOrder) {
            case TITLE_ASC -> sortedBooks.sort((b1, b2) -> {
                String title1 = b1.getMetadata() != null && b1.getMetadata().getTitle() != null 
                    ? b1.getMetadata().getTitle() : "";
                String title2 = b2.getMetadata() != null && b2.getMetadata().getTitle() != null 
                    ? b2.getMetadata().getTitle() : "";
                return title1.compareToIgnoreCase(title2);
            });
            case TITLE_DESC -> sortedBooks.sort((b1, b2) -> {
                String title1 = b1.getMetadata() != null && b1.getMetadata().getTitle() != null 
                    ? b1.getMetadata().getTitle() : "";
                String title2 = b2.getMetadata() != null && b2.getMetadata().getTitle() != null 
                    ? b2.getMetadata().getTitle() : "";
                return title2.compareToIgnoreCase(title1);
            });
            case AUTHOR_ASC -> sortedBooks.sort((b1, b2) -> {
                String author1 = getFirstAuthor(b1);
                String author2 = getFirstAuthor(b2);
                return author1.compareToIgnoreCase(author2);
            });
            case AUTHOR_DESC -> sortedBooks.sort((b1, b2) -> {
                String author1 = getFirstAuthor(b1);
                String author2 = getFirstAuthor(b2);
                return author2.compareToIgnoreCase(author1);
            });
            case SERIES_ASC -> sortedBooks.sort((b1, b2) -> {
                String series1 = getSeriesName(b1);
                String series2 = getSeriesName(b2);
                boolean hasSeries1 = !series1.isEmpty();
                boolean hasSeries2 = !series2.isEmpty();
                
                // Books without series come after books with series
                if (!hasSeries1 && !hasSeries2) {
                    // Both have no series, sort by addedOn descending
                    return compareByAddedOn(b2, b1);
                }
                if (!hasSeries1) return 1;
                if (!hasSeries2) return -1;
                
                // Both have series, sort by series name then number
                int seriesComp = series1.compareToIgnoreCase(series2);
                if (seriesComp != 0) return seriesComp;
                return Float.compare(getSeriesNumber(b1), getSeriesNumber(b2));
            });
            case SERIES_DESC -> sortedBooks.sort((b1, b2) -> {
                String series1 = getSeriesName(b1);
                String series2 = getSeriesName(b2);
                boolean hasSeries1 = !series1.isEmpty();
                boolean hasSeries2 = !series2.isEmpty();
                
                // Books without series come after books with series
                if (!hasSeries1 && !hasSeries2) {
                    // Both have no series, sort by addedOn descending
                    return compareByAddedOn(b2, b1);
                }
                if (!hasSeries1) return 1;
                if (!hasSeries2) return -1;
                
                // Both have series, sort by series name then number
                int seriesComp = series2.compareToIgnoreCase(series1);
                if (seriesComp != 0) return seriesComp;
                return Float.compare(getSeriesNumber(b2), getSeriesNumber(b1));
            });
            case RATING_ASC -> sortedBooks.sort((b1, b2) -> {
                Float rating1 = calculateRating(b1);
                Float rating2 = calculateRating(b2);
                // Books with no rating go to the end
                if (rating1 == null && rating2 == null) {
                    // Both have no rating, fall back to addedOn descending
                    return compareByAddedOn(b2, b1);
                }
                if (rating1 == null) return 1;
                if (rating2 == null) return -1;
                int ratingComp = Float.compare(rating1, rating2); // Ascending order (lowest first)
                if (ratingComp != 0) return ratingComp;
                // Same rating, fall back to addedOn descending
                return compareByAddedOn(b2, b1);
            });
            case RATING_DESC -> sortedBooks.sort((b1, b2) -> {
                Float rating1 = calculateRating(b1);
                Float rating2 = calculateRating(b2);
                // Books with no rating go to the end
                if (rating1 == null && rating2 == null) {
                    // Both have no rating, fall back to addedOn descending
                    return compareByAddedOn(b2, b1);
                }
                if (rating1 == null) return 1;
                if (rating2 == null) return -1;
                int ratingComp = Float.compare(rating2, rating1); // Descending order (highest first)
                if (ratingComp != 0) return ratingComp;
                // Same rating, fall back to addedOn descending
                return compareByAddedOn(b2, b1);
            });
        }

        return new PageImpl<>(sortedBooks, booksPage.getPageable(), booksPage.getTotalElements());
    }

    private String getFirstAuthor(Book book) {
        if (book.getMetadata() != null && book.getMetadata().getAuthors() != null 
            && !book.getMetadata().getAuthors().isEmpty()) {
            return book.getMetadata().getAuthors().getFirst();
        }
        return "";
    }

    private String getSeriesName(Book book) {
        if (book.getMetadata() != null && book.getMetadata().getSeriesName() != null) {
            return book.getMetadata().getSeriesName();
        }
        return "";
    }

    private Float getSeriesNumber(Book book) {
        if (book.getMetadata() != null && book.getMetadata().getSeriesNumber() != null) {
            return book.getMetadata().getSeriesNumber();
        }
        return Float.MAX_VALUE;
    }

    private int compareByAddedOn(Book b1, Book b2) {
        if (b1.getAddedOn() == null && b2.getAddedOn() == null) return 0;
        if (b1.getAddedOn() == null) return 1;
        if (b2.getAddedOn() == null) return -1;
        return b1.getAddedOn().compareTo(b2.getAddedOn());
    }

    private Float calculateRating(Book book) {
        if (book.getMetadata() == null) {
            return null;
        }
        
        Double hardcoverRating = book.getMetadata().getHardcoverRating();
        Double amazonRating = book.getMetadata().getAmazonRating();
        Double goodreadsRating = book.getMetadata().getGoodreadsRating();

        double sum = 0;
        int count = 0;

        if (hardcoverRating != null && hardcoverRating > 0) {
            sum += hardcoverRating;
            count++;
        }
        if (amazonRating != null && amazonRating > 0) {
            sum += amazonRating;
            count++;
        }
        if (goodreadsRating != null && goodreadsRating > 0) {
            sum += goodreadsRating;
            count++;
        }

        if (count == 0) {
            return null;
        }

        return (float) (sum / count);
    }
}
