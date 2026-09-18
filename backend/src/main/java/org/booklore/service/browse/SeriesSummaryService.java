package org.booklore.service.browse;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.booklore.app.specification.AppBookSpecification;
import org.booklore.browse.BrowsePage;
import org.booklore.browse.FacetLogic;
import org.booklore.browse.Link;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Per-series aggregate (covers, authors, progress) for the series browser grid.
 *
 * <p>Two-stage query instead of one full-collection load: stage 1 is a single indexed GROUP BY
 * over scalars (no entity hydration) covering every matching series, cheap enough to sort/filter
 * in memory; stage 2 hydrates authors/categories/covers/next-unread only for the series that
 * ended up on the requested page. This is what keeps the response small and page 1 fast at
 * library sizes where returning every series with covers in one call is what was slow. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeriesSummaryService {

    private static final int MAX_COVER_BOOKS = 3;
    private static final int DEFAULT_PAGE_SIZE = 24;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String SUMMARY_PATH = "/api/v1/books/series/summary";

    private final AuthenticationService authenticationService;
    private final BookFilterSpecifications filterSpecifications;
    private final BookRepository bookRepository;
    private final UserBookProgressRepository userBookProgressRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // Stage-1 aggregates are re-derivable at any time from the DB; a short TTL just spares
    // repeat scans during an infinite-scroll burst - same trade-off as BookFacetService's cache.
    private final Cache<Long, List<SeriesAggregate>> aggregateCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(200)
            .build();

    public BrowsePage<SeriesSummary> getSeriesSummaries(int page, int size, String sort, String query, String status) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        boolean isAdmin = user.getPermissions().isAdmin();
        Set<Long> libraryIds = BookFilterSpecifications.libraryIds(user);
        Specification<BookEntity> base = filterSpecifications.base(null, Map.of(), FacetLogic.AND, userId, isAdmin, libraryIds, null);

        int pageNumber = Math.max(page, 0);
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        List<SeriesAggregate> aggregates = aggregateCache.get(userId, id -> fetchAggregates(base, userId));
        List<SeriesAggregate> matching = filterAndSearch(aggregates, query, status);
        List<SeriesAggregate> sorted = sortAggregates(matching, sort);

        long totalElements = sorted.size();
        int fromIndex = Math.min(pageNumber * pageSize, sorted.size());
        int toIndex = Math.min(fromIndex + pageSize, sorted.size());
        List<SeriesAggregate> pageAggregates = sorted.subList(fromIndex, toIndex);

        List<SeriesSummary> content = hydrate(base, pageAggregates, userId);
        List<Link> links = buildLinks(pageNumber, pageSize, sort, query, status, totalElements);

        return BrowsePage.of(content, (long) pageNumber * pageSize, pageSize, totalElements, null, links);
    }

    // Package-private: lets tests reset the shared singleton cache between runs.
    void clearCache() {
        aggregateCache.invalidateAll();
    }

    // Cheap: one GROUP BY over indexed columns, no entity hydration - safe to run over every
    // matching series and sort/filter/paginate the (small, scalar) result in memory.
    private List<SeriesAggregate> fetchAggregates(Specification<BookEntity> base, Long userId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        Join<BookEntity, BookMetadataEntity> metadata = root.join("metadata", JoinType.INNER);
        Join<BookEntity, UserBookProgressEntity> progress = root.join("userBookProgress", JoinType.LEFT);
        progress.on(cb.equal(progress.get("user").get("id"), userId));

        Expression<String> seriesNameTrimmed = cb.trim(metadata.get("seriesName"));
        Expression<String> seriesKey = cb.lower(seriesNameTrimmed);

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = base.toPredicate(root, cq, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(metadata.get("seriesName")));

        Expression<Long> bookCount = cb.count(root.get("id"));
        Expression<Long> progressCount = cb.count(progress.get("id"));
        Expression<Long> readCount = countWhenIn(cb, progress.get("readStatus"), ReadStatus.READ);
        Expression<Long> readingCount = countWhenIn(cb, progress.get("readStatus"), ReadStatus.READING, ReadStatus.RE_READING, ReadStatus.PAUSED);
        Expression<Long> abandonedCount = countWhenIn(cb, progress.get("readStatus"), ReadStatus.ABANDONED);
        Expression<Long> wontReadCount = countWhenIn(cb, progress.get("readStatus"), ReadStatus.WONT_READ);
        Expression<String> displayName = cb.least(seriesNameTrimmed);
        Expression<Instant> addedOn = cb.greatest(root.<Instant>get("addedOn"));
        Expression<Instant> lastReadTime = cb.greatest(progress.<Instant>get("lastReadTime"));

        cq.multiselect(seriesKey.alias("key"), displayName.alias("name"), bookCount.alias("bookCount"),
                progressCount.alias("progressCount"), readCount.alias("readCount"), readingCount.alias("readingCount"),
                abandonedCount.alias("abandonedCount"), wontReadCount.alias("wontReadCount"),
                addedOn.alias("addedOn"), lastReadTime.alias("lastReadTime"));
        cq.where(predicates.toArray(Predicate[]::new));
        cq.groupBy(seriesKey);

        return entityManager.createQuery(cq).getResultList().stream().map(SeriesSummaryService::toAggregate).toList();
    }

    private static Expression<Long> countWhenIn(CriteriaBuilder cb, Path<ReadStatus> statusPath, ReadStatus... statuses) {
        CriteriaBuilder.Case<Long> when = cb.selectCase();
        for (ReadStatus status : statuses) {
            when = when.when(cb.equal(statusPath, status), 1L);
        }
        return cb.sum(when.otherwise(0L));
    }

    private static SeriesAggregate toAggregate(Tuple tuple) {
        String key = tuple.get("key", String.class);
        String displayName = tuple.get("name", String.class);
        int bookCount = toInt(tuple.get("bookCount"));
        int progressCount = toInt(tuple.get("progressCount"));
        int readCount = toInt(tuple.get("readCount"));
        int readingCount = toInt(tuple.get("readingCount"));
        int abandonedCount = toInt(tuple.get("abandonedCount"));
        int wontReadCount = toInt(tuple.get("wontReadCount"));
        int noProgressCount = bookCount - progressCount;
        Instant addedOn = tuple.get("addedOn", Instant.class);
        Instant lastReadTime = tuple.get("lastReadTime", Instant.class);

        ReadStatus status = computeSeriesStatus(bookCount, readCount, readingCount, abandonedCount, wontReadCount, noProgressCount);
        double progress = bookCount > 0 ? (double) readCount / bookCount : 0;

        return new SeriesAggregate(key, displayName == null ? "" : displayName.trim(), bookCount, readCount,
                addedOn, lastReadTime, status, progress);
    }

    private static int toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    // Mirrors computeSeriesReadStatus in frontend/.../book.model.ts - keep both in sync.
    private static ReadStatus computeSeriesStatus(int bookCount, int readCount, int readingCount, int abandonedCount, int wontReadCount, int noProgressCount) {
        if (bookCount == 0) return ReadStatus.UNREAD;
        if (wontReadCount > 0) return ReadStatus.WONT_READ;
        if (abandonedCount > 0) return ReadStatus.ABANDONED;
        if (readCount == bookCount) return ReadStatus.READ;
        if (readingCount > 0) return ReadStatus.READING;
        if (readCount > 0) return ReadStatus.PARTIALLY_READ;
        if (noProgressCount == bookCount) return ReadStatus.UNREAD;
        return ReadStatus.PARTIALLY_READ;
    }

    // ponytail: search is by series name only (not author, unlike the old client-side scan) -
    // an author join here would fan out the GROUP BY and corrupt the book/read counts above.
    private static List<SeriesAggregate> filterAndSearch(List<SeriesAggregate> aggregates, String query, String status) {
        String needle = query == null ? null : query.trim().toLowerCase();
        List<SeriesAggregate> result = new ArrayList<>();
        for (SeriesAggregate aggregate : aggregates) {
            if (needle != null && !needle.isEmpty() && !aggregate.displayName().toLowerCase().contains(needle)) {
                continue;
            }
            if (!matchesStatus(aggregate, status)) {
                continue;
            }
            result.add(aggregate);
        }
        return result;
    }

    private static boolean matchesStatus(SeriesAggregate aggregate, String status) {
        if (status == null) {
            return true;
        }
        return switch (status) {
            case "not-started" -> aggregate.status() == ReadStatus.UNREAD;
            case "in-progress" -> aggregate.status() == ReadStatus.READING || aggregate.status() == ReadStatus.PARTIALLY_READ;
            case "completed" -> aggregate.status() == ReadStatus.READ;
            case "abandoned" -> aggregate.status() == ReadStatus.ABANDONED || aggregate.status() == ReadStatus.WONT_READ;
            default -> true;
        };
    }

    private static List<SeriesAggregate> sortAggregates(List<SeriesAggregate> aggregates, String sort) {
        Comparator<SeriesAggregate> comparator = switch (sort == null ? "name-asc" : sort) {
            case "name-desc" -> Comparator.comparing(SeriesAggregate::displayName, String.CASE_INSENSITIVE_ORDER).reversed();
            case "book-count" -> Comparator.comparingInt(SeriesAggregate::bookCount).reversed();
            case "progress" -> Comparator.comparingDouble(SeriesAggregate::progress).reversed();
            case "recently-read" -> Comparator.comparing(SeriesAggregate::lastReadTime, Comparator.nullsFirst(Comparator.naturalOrder())).reversed();
            case "recently-added" -> Comparator.comparing(SeriesAggregate::addedOn, Comparator.nullsFirst(Comparator.naturalOrder())).reversed();
            default -> Comparator.comparing(SeriesAggregate::displayName, String.CASE_INSENSITIVE_ORDER);
        };
        List<SeriesAggregate> sorted = new ArrayList<>(aggregates);
        sorted.sort(comparator);
        return sorted;
    }

    // Expensive per-card data (authors, categories, covers, next-unread) hydrated only for the
    // series that made it onto this page - bounded by page size, not by collection size.
    private List<SeriesSummary> hydrate(Specification<BookEntity> base, List<SeriesAggregate> pageAggregates, Long userId) {
        if (pageAggregates.isEmpty()) {
            return List.of();
        }
        Set<String> keys = pageAggregates.stream().map(SeriesAggregate::key).collect(Collectors.toSet());
        Specification<BookEntity> spec = AppBookSpecification.combine(base, hasSeriesKeyIn(keys));
        List<BookEntity> books = bookRepository.findAll(spec);

        Map<Long, UserBookProgressEntity> progressByBookId = fetchProgress(userId, books);
        Map<String, List<BookEntity>> bySeriesKey = books.stream()
                .collect(Collectors.groupingBy(SeriesSummaryService::seriesKey, LinkedHashMap::new, Collectors.toList()));

        List<SeriesSummary> result = new ArrayList<>();
        for (SeriesAggregate aggregate : pageAggregates) {
            List<BookEntity> seriesBooks = bySeriesKey.getOrDefault(aggregate.key(), List.of());
            result.add(buildSummary(aggregate, seriesBooks, progressByBookId));
        }
        return result;
    }

    private static String seriesKey(BookEntity book) {
        return book.getMetadata().getSeriesName().trim().toLowerCase();
    }

    // Metadata is a lazy @OneToOne, read for every book below - fetch it once here instead of
    // one extra SELECT per book. Casting the Fetch to a Join is the standard Hibernate idiom for
    // filtering on the same association a query fetches; ponytail: Hibernate-only, fine here.
    @SuppressWarnings("unchecked")
    private static Specification<BookEntity> hasSeriesKeyIn(Set<String> keys) {
        return (root, query, cb) -> {
            Fetch<BookEntity, BookMetadataEntity> fetch = root.fetch("metadata", JoinType.INNER);
            Join<BookEntity, BookMetadataEntity> metadata = (Join<BookEntity, BookMetadataEntity>) fetch;
            return cb.lower(cb.trim(metadata.get("seriesName"))).in(keys);
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

    private SeriesSummary buildSummary(SeriesAggregate aggregate, List<BookEntity> seriesBooks, Map<Long, UserBookProgressEntity> progressByBookId) {
        List<BookEntity> sorted = seriesBooks.stream()
                .sorted(Comparator.comparing(
                        (BookEntity b) -> b.getMetadata().getSeriesNumber(),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        Set<String> authors = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        Long nextUnreadBookId = null;

        for (BookEntity book : sorted) {
            BookMetadataEntity metadata = book.getMetadata();
            if (metadata.getAuthors() != null) {
                metadata.getAuthors().forEach(a -> authors.add(a.getName()));
            }
            if (metadata.getCategories() != null) {
                metadata.getCategories().forEach(c -> categories.add(c.getName()));
            }
            if (nextUnreadBookId == null) {
                UserBookProgressEntity progress = progressByBookId.get(book.getId());
                ReadStatus status = progress == null
                        ? ReadStatus.UNREAD
                        : (progress.getReadStatus() == null ? ReadStatus.UNSET : progress.getReadStatus());
                if (status != ReadStatus.READ) {
                    nextUnreadBookId = book.getId();
                }
            }
        }

        List<SeriesCoverBook> coverBooks = sorted.stream()
                .limit(MAX_COVER_BOOKS)
                .map(SeriesSummaryService::toCoverBook)
                .toList();

        return SeriesSummary.builder()
                .seriesName(aggregate.displayName())
                .bookCount(aggregate.bookCount())
                .readCount(aggregate.readCount())
                .progress(aggregate.progress())
                .seriesStatus(aggregate.status().name())
                .nextUnreadBookId(nextUnreadBookId)
                .lastReadTime(aggregate.lastReadTime())
                .addedOn(aggregate.addedOn())
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

    private List<Link> buildLinks(int pageNumber, int pageSize, String sort, String query, String status, long totalElements) {
        List<Link> links = new ArrayList<>();
        links.add(Link.json(List.of("self"), pageHref(pageNumber, pageSize, sort, query, status)));
        boolean hasNext = (long) (pageNumber + 1) * pageSize < totalElements;
        if (hasNext) {
            links.add(Link.json(List.of("next"), pageHref(pageNumber + 1, pageSize, sort, query, status)));
        }
        return links;
    }

    private static String pageHref(int page, int size, String sort, String query, String status) {
        List<String> parts = new ArrayList<>();
        parts.add("page=" + page);
        parts.add("size=" + size);
        if (sort != null && !sort.isBlank()) {
            parts.add("sort=" + BrowseParams.encode(sort));
        }
        if (query != null && !query.isBlank()) {
            parts.add("query=" + BrowseParams.encode(query));
        }
        if (status != null && !status.isBlank()) {
            parts.add("status=" + BrowseParams.encode(status));
        }
        return SUMMARY_PATH + "?" + String.join("&", parts);
    }

    private record SeriesAggregate(String key, String displayName, int bookCount, int readCount,
                                    Instant addedOn, Instant lastReadTime, ReadStatus status, double progress) {
    }
}
