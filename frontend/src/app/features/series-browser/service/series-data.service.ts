import {computed, inject, Injectable} from '@angular/core';
import {injectQuery} from '@tanstack/angular-query-experimental';
import {BookService} from '../../book/service/book.service';
import {BookQueryService} from '../../book/data/book-query.service';
import {GLOBAL_FACETS_PARAMS} from '../../book/data/book-query-params';
import {toFacetDistinctCount} from '../../book/data/book-query.models';
import {AuthService} from '../../../shared/service/auth.service';
import {Book, computeSeriesReadStatus, ReadStatus} from '../../book/model/book.model';
import {SeriesSummary} from '../model/series.model';

@Injectable({
  providedIn: 'root'
})
export class SeriesDataService {

  private bookService = inject(BookService);
  private readonly bookQueryService = inject(BookQueryService);
  private readonly authService = inject(AuthService);
  private readonly token = this.authService.token;

  allSeries = computed(() => this.buildSeriesSummaries(this.bookService.books()));

  // Sidebar badge count from the server's series facet group, not the full 132k-book collection.
  private readonly globalFacetsQuery = injectQuery(() => ({
    ...this.bookQueryService.facets(GLOBAL_FACETS_PARAMS),
    enabled: !!this.token(),
  }));

  readonly totalSeriesCount = computed(() => toFacetDistinctCount(this.globalFacetsQuery.data(), 'series'));

  private buildSeriesSummaries(books: Book[]): SeriesSummary[] {
    const seriesMap = new Map<string, Book[]>();

    for (const book of books) {
      const seriesName = book.metadata?.seriesName;
      if (!seriesName) continue;

      const key = seriesName.trim().toLowerCase();
      if (!seriesMap.has(key)) {
        seriesMap.set(key, []);
      }
      seriesMap.get(key)!.push(book);
    }

    const summaries: SeriesSummary[] = [];

    for (const seriesBooks of seriesMap.values()) {
      const sorted = seriesBooks.sort((a, b) => {
        const aNum = a.metadata?.seriesNumber ?? Number.MAX_SAFE_INTEGER;
        const bNum = b.metadata?.seriesNumber ?? Number.MAX_SAFE_INTEGER;
        return aNum - bNum;
      });

      const displayName = sorted[0].metadata?.seriesName?.trim() || '';
      const authorSet = new Set<string>();
      const categorySet = new Set<string>();

      for (const book of sorted) {
        book.metadata?.authors?.forEach(a => authorSet.add(a));
        book.metadata?.categories?.forEach(c => categorySet.add(c));
      }

      const readCount = sorted.filter(b => b.readStatus === ReadStatus.READ).length;
      const bookCount = sorted.length;

      const lastReadTime = sorted
        .map(b => b.lastReadTime)
        .filter((t): t is string => !!t)
        .sort((a, b) => new Date(b).getTime() - new Date(a).getTime())[0] || null;

      const addedOn = sorted
        .map(b => b.addedOn)
        .filter((t): t is string => !!t)
        .sort((a, b) => new Date(b).getTime() - new Date(a).getTime())[0] || null;

      const nextUnread = sorted.find(b => b.readStatus !== ReadStatus.READ) || null;

      summaries.push({
        seriesName: displayName,
        books: sorted,
        authors: Array.from(authorSet),
        categories: Array.from(categorySet),
        bookCount,
        readCount,
        progress: bookCount > 0 ? readCount / bookCount : 0,
        seriesStatus: computeSeriesReadStatus(sorted),
        nextUnread,
        lastReadTime,
        coverBooks: sorted.slice(0, 7),
        addedOn
      });
    }

    return summaries;
  }

}
