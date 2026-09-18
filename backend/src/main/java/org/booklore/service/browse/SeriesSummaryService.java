package org.booklore.service.browse;

import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import lombok.RequiredArgsConstructor;
import org.booklore.app.specification.AppBookSpecification;
import org.booklore.browse.FacetLogic;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.browse.SeriesCoverBook;
import org.booklore.model.dto.browse.SeriesSummary;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.BookRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Per-series aggregate (covers, authors, progress) for the series browser grid, replacing a
 * client-side scan of the entire book collection with one query scoped like /books/facets. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeriesSummaryService {

    private static final int MAX_COVER_BOOKS = 3;

    private final AuthenticationService authenticationService;
    private final BookFilterSpecifications filterSpecifications;
    private final BookRepository bookRepository;
    private final UserBookProgressRepository userBookProgressRepository;

    public List<SeriesSummary> getSeriesSummaries() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        boolean isAdmin = user.getPermissions().isAdmin();
        Set<Long> libraryIds = BookFilterSpecifications.libraryIds(user);

        Specification<BookEntity> base = filterSpecifications.base(null, Map.of(), FacetLogic.AND, userId, isAdmin, libraryIds, null);
        Specification<BookEntity> spec = AppBookSpecification.combine(base, hasSeriesWithMetadataFetch());

        List<BookEntity> books = bookRepository.findAll(spec);

        Map<Long, UserBookProgressEntity> progressByBookId = fetchProgress(userId, books);

        Map<String, List<BookEntity>> bySeries = books.stream()
                .collect(Collectors.groupingBy(
                        b -> b.getMetadata().getSeriesName().trim().toLowerCase(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<SeriesSummary> summaries = new ArrayList<>();
        for (List<BookEntity> seriesBooks : bySeries.values()) {
            summaries.add(buildSummary(seriesBooks, progressByBookId));
        }
        return summaries;
    }

    // Metadata is a lazy @OneToOne, read for every book below - fetch it once here instead of
    // one extra SELECT per book. Casting the Fetch to a Join is the standard Hibernate idiom for
    // filtering on the same association a query fetches; ponytail: Hibernate-only, fine here.
    @SuppressWarnings("unchecked")
    private static Specification<BookEntity> hasSeriesWithMetadataFetch() {
        return (root, query, cb) -> {
            Fetch<BookEntity, BookMetadataEntity> fetch = root.fetch("metadata", JoinType.INNER);
            Join<BookEntity, BookMetadataEntity> metadata = (Join<BookEntity, BookMetadataEntity>) fetch;
            return cb.isNotNull(metadata.get("seriesName"));
        };
    }

    private Map<Long, UserBookProgressEntity> fetchProgress(Long userId, List<BookEntity> books) {
        if (books.isEmpty()) {
            return Map.of();
        }
        Set<Long> bookIds = books.stream().map(BookEntity::getId).collect(Collectors.toSet());
        return userBookProgressRepository.findByUserIdAndBookIdIn(userId, bookIds).stream()
                .collect(Collectors.toMap(p -> p.getBook().getId(), p -> p));
    }

    private SeriesSummary buildSummary(List<BookEntity> seriesBooks, Map<Long, UserBookProgressEntity> progressByBookId) {
        List<BookEntity> sorted = seriesBooks.stream()
                .sorted(Comparator.comparing(
                        (BookEntity b) -> b.getMetadata().getSeriesNumber(),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        Set<String> authors = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        List<ReadStatus> statuses = new ArrayList<>();
        java.time.Instant addedOn = null;
        java.time.Instant lastReadTime = null;
        Long nextUnreadBookId = null;
        int readCount = 0;

        for (BookEntity book : sorted) {
            BookMetadataEntity metadata = book.getMetadata();
            if (metadata.getAuthors() != null) {
                metadata.getAuthors().forEach(a -> authors.add(a.getName()));
            }
            if (metadata.getCategories() != null) {
                metadata.getCategories().forEach(c -> categories.add(c.getName()));
            }
            if (book.getAddedOn() != null && (addedOn == null || book.getAddedOn().isAfter(addedOn))) {
                addedOn = book.getAddedOn();
            }

            UserBookProgressEntity progress = progressByBookId.get(book.getId());
            ReadStatus status = progress == null
                    ? ReadStatus.UNREAD
                    : (progress.getReadStatus() == null ? ReadStatus.UNSET : progress.getReadStatus());
            statuses.add(status);

            if (status == ReadStatus.READ) {
                readCount++;
            } else if (nextUnreadBookId == null) {
                nextUnreadBookId = book.getId();
            }

            if (progress != null && progress.getLastReadTime() != null
                    && (lastReadTime == null || progress.getLastReadTime().isAfter(lastReadTime))) {
                lastReadTime = progress.getLastReadTime();
            }
        }

        int bookCount = sorted.size();
        List<SeriesCoverBook> coverBooks = sorted.stream()
                .limit(MAX_COVER_BOOKS)
                .map(SeriesSummaryService::toCoverBook)
                .toList();

        return SeriesSummary.builder()
                .seriesName(sorted.get(0).getMetadata().getSeriesName().trim())
                .bookCount(bookCount)
                .readCount(readCount)
                .progress(bookCount > 0 ? (double) readCount / bookCount : 0)
                .seriesStatus(computeSeriesStatus(statuses).name())
                .nextUnreadBookId(nextUnreadBookId)
                .lastReadTime(lastReadTime)
                .addedOn(addedOn)
                .authors(new ArrayList<>(authors))
                .categories(new ArrayList<>(categories))
                .coverBooks(coverBooks)
                .build();
    }

    private static SeriesCoverBook toCoverBook(BookEntity book) {
        BookFileEntity primaryFile = book.getPrimaryBookFile();
        return SeriesCoverBook.builder()
                .bookId(book.getId())
                .bookType(primaryFile != null && primaryFile.getBookType() != null ? primaryFile.getBookType().name() : null)
                .coverUpdatedOn(book.getMetadata().getCoverUpdatedOn())
                .audiobookCoverUpdatedOn(book.getMetadata().getAudiobookCoverUpdatedOn())
                .build();
    }

    // Mirrors computeSeriesReadStatus in frontend/.../book.model.ts - keep both in sync.
    private static ReadStatus computeSeriesStatus(List<ReadStatus> statuses) {
        if (statuses.isEmpty()) return ReadStatus.UNREAD;
        if (statuses.contains(ReadStatus.WONT_READ)) return ReadStatus.WONT_READ;
        if (statuses.contains(ReadStatus.ABANDONED)) return ReadStatus.ABANDONED;
        if (statuses.stream().allMatch(s -> s == ReadStatus.READ)) return ReadStatus.READ;

        boolean anyReading = statuses.stream()
                .anyMatch(s -> s == ReadStatus.READING || s == ReadStatus.RE_READING || s == ReadStatus.PAUSED);
        if (anyReading) return ReadStatus.READING;

        if (statuses.stream().anyMatch(s -> s == ReadStatus.READ)) return ReadStatus.PARTIALLY_READ;
        if (statuses.stream().allMatch(s -> s == ReadStatus.UNREAD)) return ReadStatus.UNREAD;
        return ReadStatus.PARTIALLY_READ;
    }
}
