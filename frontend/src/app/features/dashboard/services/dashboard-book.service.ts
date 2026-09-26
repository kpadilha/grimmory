import {computed, inject, Injectable} from '@angular/core';
import {injectQueries} from '@tanstack/angular-query-experimental/inject-queries-experimental';

import {Book, ReadStatus} from '../../book/model/book.model';
import {BookQueryService} from '../../book/data/book-query.service';
import {
  BookPageParams,
  BookQuerySortKey,
  BookSortTerm,
  DEFAULT_BOOK_SORT_TERMS,
  EMPTY_FACET_SELECTION,
  isBookQuerySortKey,
} from '../../book/data/book-query-params';
import {bookSummaryToBook} from '../../book/data/book-query.models';
import {BOOK_FILE_TYPES} from '../../book/data/book-response.models';
import {MagicShelfService} from '../../magic-shelf/service/magic-shelf.service';
import {DEFAULT_MAX_ITEMS, ScrollerConfig, ScrollerType} from '../models/dashboard-config.model';
import {DashboardConfigService} from './dashboard-config.service';

// LAST_READ/LAST_LISTENED each add a file_type facet so the server, not a client-side split,
// separates ebook from audiobook activity - a shared query dominated by one format used to push
// the other format's genuinely-recent books outside the candidate window.
const EBOOK_FILE_TYPES = BOOK_FILE_TYPES.filter(type => type !== 'AUDIOBOOK');
const RECENT_ACTIVITY_CANDIDATE_MULTIPLIER = 5;
const RECENT_ACTIVITY_CANDIDATE_CAP = 200;
const RECENT_ACTIVITY_STATUSES = [ReadStatus.READING, ReadStatus.RE_READING, ReadStatus.PAUSED];

// Matches the previous in-memory filter: RE_READING and UNSET/no-progress books stay candidates.
const RANDOM_EXCLUDED_STATUSES = [
  ReadStatus.READ, ReadStatus.PARTIALLY_READ, ReadStatus.READING,
  ReadStatus.PAUSED, ReadStatus.WONT_READ, ReadStatus.ABANDONED,
];

// The dashboard settings' sortField options mapped onto BookSortRegistry's server columns; a
// field with no server column (fileName, filePath, bookType, ...) falls back to the default sort.
const SORT_FIELD_TO_SERVER_KEY: Readonly<Record<string, BookQuerySortKey>> = {
  title: 'title',
  author: 'authorName',
  authorSurnameVorname: 'authorSortName',
  seriesName: 'seriesName',
  seriesNumber: 'seriesNumber',
  lastReadTime: 'lastReadTime',
  personalRating: 'personalRating',
  addedOn: 'addedOn',
  publisher: 'publisher',
  publishedDate: 'publishedDate',
  readStatus: 'readStatus',
  dateFinished: 'dateFinished',
  readingProgress: 'readingProgress',
  pageCount: 'pageCount',
};

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

  // True while any enabled scroller's first page is still in flight - the dashboard spinner
  // gate, replacing the old wait on the full-collection query.
  readonly isLoading = computed(() => this.scrollerQueries().some(result => result.isPending()));

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
        return {facets: EMPTY_FACET_SELECTION, facetLogic: 'and', sort: [{key: 'addedOn', direction: 'desc'}], size};

      case ScrollerType.LAST_READ:
        return {
          facets: {read_status: RECENT_ACTIVITY_STATUSES, file_type: [...EBOOK_FILE_TYPES]},
          facetLogic: 'or',
          sort: [{key: 'lastReadTime', direction: 'desc'}],
          size: Math.min(size * RECENT_ACTIVITY_CANDIDATE_MULTIPLIER, RECENT_ACTIVITY_CANDIDATE_CAP),
        };

      case ScrollerType.LAST_LISTENED:
        return {
          facets: {read_status: RECENT_ACTIVITY_STATUSES, file_type: ['AUDIOBOOK']},
          facetLogic: 'or',
          sort: [{key: 'lastReadTime', direction: 'desc'}],
          size: Math.min(size * RECENT_ACTIVITY_CANDIDATE_MULTIPLIER, RECENT_ACTIVITY_CANDIDATE_CAP),
        };

      case ScrollerType.RANDOM:
        return {facets: {read_status: RANDOM_EXCLUDED_STATUSES}, facetLogic: 'not', sort: [{key: 'random', direction: 'asc'}], size};

      case ScrollerType.MAGIC_SHELF:
        return {
          facets: scroller.magicShelfId != null ? {shelf: [`magic:${scroller.magicShelfId}`]} : EMPTY_FACET_SELECTION,
          facetLogic: 'and',
          sort: this.magicShelfSortTerms(scroller),
          size,
        };
    }
  }

  private magicShelfSortTerms(scroller: ScrollerConfig): readonly BookSortTerm[] {
    const key = scroller.sortField ? SORT_FIELD_TO_SERVER_KEY[scroller.sortField] : undefined;
    if (!key || !isBookQuerySortKey(key) || !scroller.sortDirection) {
      return DEFAULT_BOOK_SORT_TERMS;
    }

    return [{key, direction: scroller.sortDirection === 'asc' ? 'asc' : 'desc'}];
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
