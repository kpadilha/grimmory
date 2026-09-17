import {computed, inject, Injectable} from '@angular/core';
import {toObservable, toSignal} from '@angular/core/rxjs-interop';
import {catchError, of, switchMap} from 'rxjs';
import {LibraryFilterService} from './library-filter.service';
import {LibraryStatsService} from './library-stats.service';

export interface BooksSummary {
  totalBooks: number;
  totalSizeKb: number;
  totalAuthors: number;
  totalSeries: number;
  totalPublishers: number;
}

const EMPTY_SUMMARY: BooksSummary = {totalBooks: 0, totalSizeKb: 0, totalAuthors: 0, totalSeries: 0, totalPublishers: 0};

@Injectable({
  providedIn: 'root'
})
export class LibrariesSummaryService {
  private libraryStatsService = inject(LibraryStatsService);
  private libraryFilterService = inject(LibraryFilterService);

  private readonly summarySignal = toSignal(
    toObservable(this.libraryFilterService.selectedLibrary).pipe(
      switchMap(libraryId => this.libraryStatsService.summary(libraryId).pipe(catchError(() => of(null))))
    ),
    {initialValue: null}
  );

  readonly booksSummary = computed<BooksSummary>(() => {
    const summary = this.summarySignal();
    if (!summary) {
      return EMPTY_SUMMARY;
    }
    return {
      totalBooks: summary.totalBooks,
      totalSizeKb: summary.totalSizeKb,
      totalAuthors: summary.distinctAuthors,
      totalSeries: summary.distinctSeries,
      totalPublishers: summary.distinctPublishers
    };
  });
  readonly formattedSize = computed(() => this.formatSizeKb(this.booksSummary().totalSizeKb));

  private formatSizeKb(kb: number): string {
    if (!kb) return '0 KB';
    const kilo = 1024;
    const megaKb = kilo; // 1 MB = 1024 KB
    const gigaKb = kilo * megaKb; // 1 GB = 1024 * 1024 KB
    if (kb >= gigaKb) {
      return (kb / gigaKb).toFixed(2) + ' GB';
    }
    if (kb >= megaKb) {
      return (kb / megaKb).toFixed(2) + ' MB';
    }
    return kb + ' KB';
  }
}
