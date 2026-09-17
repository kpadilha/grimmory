import {computed, inject, Injectable} from '@angular/core';
import {injectQueries} from '@tanstack/angular-query-experimental/inject-queries-experimental';

import {Book, ReadStatus} from '../../book/model/book.model';
import {SortDirection, SortOption} from '../../book/model/sort.model';
import {BookQueryService} from '../../book/data/book-query.service';
import {BookPageParams, BookSortTerm, DEFAULT_BOOK_SORT_TERMS} from '../../book/data/book-query-params';
import {bookSummaryToBook} from '../../book/data/book-query.models';
import {toBookSortTerms} from '../../book/components/book-browser/all-books-query.mapper';
import {MagicShelfService} from '../../magic-shelf/service/magic-shelf.service';
import {DEFAULT_MAX_ITEMS, ScrollerConfig, ScrollerType} from '../models/dashboard-config.model';
import {DashboardConfigService} from './dashboard-config.service';

// LAST_READ/LAST_LISTENED candidates: readStatus and lastReadTime live on one UserBookProgress
// row per book (shared by ebook and audiobook activity), so the server can't filter by format.
// Overfetch a bounded candidate page and split by format client-side below.
const RECENT_ACTIVITY_CANDIDATE_MULTIPLIER = 5;
const RECENT_ACTIVITY_CANDIDATE_CAP = 200;
const RECENT_ACTIVITY_STATUSES = [ReadStatus.READING, ReadStatus.RE_READING, ReadStatus.PAUSED];

// Matches the pre-migration in-memory filter: RE_READING and UNSET/no-progress books stay candidates.
const RANDOM_EXCLUDED_STATUSES = [
  ReadStatus.READ, ReadStatus.PARTIALLY_READ, ReadStatus.READING,
  ReadStatus.PAUSED, ReadStatus.WONT_READ, ReadStatus.ABANDONED,
];

@Injectable({
  providedIn: 'root'
})
export class DashboardBookService {
  private readonly bookQueryService = inject(BookQueryService);
  private readonly magicShelfService = inject(MagicShelfService);
  private readonly configService = inject(DashboardConfigService);

  private readonly enabledScrollers = computed(() =>
    this.configService.config().scrollers.filter(scroller => scroller.enabled)
  );

  // One small server-paged query per scroller - never the full collection. Scrollers with
  // identical params (LAST_READ/LAST_LISTENED) share a single cached request automatically.
  private readonly scrollerQueries = injectQueries(() => ({
    queries: this.enabledScrollers().map(scroller => ({
      ...this.bookQueryService.page(this.paramsForScroller(scroller)),
      enabled: this.isFetchable(scroller),
    })),
  }));

  // A disabled query (e.g. a stale magic-shelf id) stays isPending() forever without ever
  // fetching - isEnabled() excludes it so it can't pin the dashboard spinner on indefinitely.
  readonly isLoading = computed(() => this.scrollerQueries().some(query => query.isEnabled() && query.isPending()));

  /**
   * Computed map of scroller ID to its server-paged book list.
   * This centralizes all dashboard filtering logic and keeps it reactive.
   */
  readonly scrollerBooksMap = computed(() => {
    const scrollers = this.enabledScrollers();
    const results = this.scrollerQueries();
    const scrollerMap = new Map<string, Book[]>();

    scrollers.forEach((scroller, index) => {
      const books = (results[index]?.data()?.content ?? []).map(bookSummaryToBook);
      scrollerMap.set(scroller.id, this.refineForScrollerType(scroller, books));
    });

    return scrollerMap;
  });

  private isFetchable(scroller: ScrollerConfig): boolean {
    if (scroller.type !== ScrollerType.MAGIC_SHELF) return true;
    return scroller.magicShelfId != null
      && this.magicShelfService.shelves().some(shelf => shelf.id === scroller.magicShelfId);
  }

  private paramsForScroller(scroller: ScrollerConfig): BookPageParams {
    const size = scroller.maxItems || DEFAULT_MAX_ITEMS;

    switch (scroller.type) {
      case ScrollerType.LATEST_ADDED:
        return {facets: {}, facetLogic: 'and', sort: [{key: 'addedOn', direction: 'desc'}], size};

      case ScrollerType.LAST_READ:
      case ScrollerType.LAST_LISTENED:
        return {
          facets: {read_status: RECENT_ACTIVITY_STATUSES},
          facetLogic: 'and',
          sort: [{key: 'lastReadTime', direction: 'desc'}],
          size: Math.min(size * RECENT_ACTIVITY_CANDIDATE_MULTIPLIER, RECENT_ACTIVITY_CANDIDATE_CAP),
        };

      case ScrollerType.RANDOM:
        return {facets: {read_status: RANDOM_EXCLUDED_STATUSES}, facetLogic: 'not', sort: [{key: 'random', direction: 'asc'}], size};

      case ScrollerType.MAGIC_SHELF:
        return {
          facets: scroller.magicShelfId != null ? {shelf: [`magic:${scroller.magicShelfId}`]} : {},
          facetLogic: 'and',
          sort: this.magicShelfSortTerms(scroller),
          size,
        };
    }
  }

  private magicShelfSortTerms(scroller: ScrollerConfig): readonly BookSortTerm[] {
    if (!scroller.sortField || !scroller.sortDirection) {
      return DEFAULT_BOOK_SORT_TERMS;
    }

    const sortOption: SortOption = {
      label: '',
      field: scroller.sortField,
      direction: scroller.sortDirection === 'asc' ? SortDirection.ASCENDING : SortDirection.DESCENDING,
    };

    return toBookSortTerms([sortOption]);
  }

  private refineForScrollerType(scroller: ScrollerConfig, books: Book[]): Book[] {
    const maxItems = scroller.maxItems || DEFAULT_MAX_ITEMS;

    switch (scroller.type) {
      case ScrollerType.LAST_READ:
        return books.filter(book => this.hasEbookProgress(book)).slice(0, maxItems);
      case ScrollerType.LAST_LISTENED:
        return books.filter(book => !!book.audiobookProgress).slice(0, maxItems);
      default:
        return books.slice(0, maxItems);
    }
  }

  private hasEbookProgress(book: Book): boolean {
    return !!(book.epubProgress || book.pdfProgress || book.cbxProgress || book.koreaderProgress || book.koboProgress);
  }
}
