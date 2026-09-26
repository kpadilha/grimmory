import {computed, inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {lastValueFrom} from 'rxjs';
import {injectQuery, queryOptions} from '@tanstack/angular-query-experimental';
import {API_CONFIG} from '../../../core/config/api-config';
import {AuthService} from '../../../shared/service/auth.service';
import {ReadStatus} from '../../book/model/book.model';
import {BookQueryService} from '../../book/data/book-query.service';
import {GLOBAL_FACETS_PARAMS} from '../../book/data/book-query-params';
import {toFacetDistinctCount} from '../../book/data/book-query.models';
import {SeriesCoverBook, SeriesSummary} from '../model/series.model';

const SERIES_SUMMARY_QUERY_KEY = ['books', 'series', 'summary'] as const;

interface SeriesCoverBookDto {
  bookId: number;
  bookType?: string;
  coverUpdatedOn?: string;
  audiobookCoverUpdatedOn?: string;
}

interface SeriesSummaryDto {
  seriesName: string;
  bookCount: number;
  readCount: number;
  progress: number;
  seriesStatus: string;
  nextUnreadBookId?: number;
  lastReadTime?: string;
  addedOn?: string;
  authors: string[];
  categories: string[];
  coverBooks: SeriesCoverBookDto[];
}

@Injectable({
  providedIn: 'root'
})
export class SeriesDataService {

  private readonly http = inject(HttpClient);
  private readonly bookQueryService = inject(BookQueryService);
  private readonly authService = inject(AuthService);
  private readonly token = this.authService.token;
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/books/series/summary`;

  private seriesSummaryQuery = injectQuery(() => ({
    ...this.getSeriesSummaryQueryOptions(),
    enabled: !!this.token(),
  }));

  // Server-aggregated per-series data (SeriesSummaryService), not a client scan of bookService.books().
  allSeries = computed(() => this.seriesSummaryQuery.data() ?? []);

  isSeriesLoading = computed(() => !!this.token() && this.seriesSummaryQuery.isPending());

  // Sidebar badge count from the server's series facet group, not the full 132k-book collection.
  private readonly globalFacetsQuery = injectQuery(() => ({
    ...this.bookQueryService.facets(GLOBAL_FACETS_PARAMS),
    enabled: !!this.token(),
  }));

  readonly totalSeriesCount = computed(() => toFacetDistinctCount(this.globalFacetsQuery.data(), 'series'));

  private getSeriesSummaryQueryOptions() {
    return queryOptions({
      queryKey: SERIES_SUMMARY_QUERY_KEY,
      queryFn: () => lastValueFrom(this.http.get<SeriesSummaryDto[]>(this.url)).then(dtos => dtos.map(toSeriesSummary)),
    });
  }

}

function toSeriesSummary(dto: SeriesSummaryDto): SeriesSummary {
  return {
    seriesName: dto.seriesName,
    authors: dto.authors,
    categories: dto.categories,
    bookCount: dto.bookCount,
    readCount: dto.readCount,
    progress: dto.progress,
    seriesStatus: dto.seriesStatus as ReadStatus,
    nextUnreadBookId: dto.nextUnreadBookId ?? null,
    lastReadTime: dto.lastReadTime ?? null,
    addedOn: dto.addedOn ?? null,
    coverBooks: dto.coverBooks.map(toSeriesCoverBook),
  };
}

function toSeriesCoverBook(dto: SeriesCoverBookDto): SeriesCoverBook {
  return {
    id: dto.bookId,
    bookType: dto.bookType,
    coverUpdatedOn: dto.coverUpdatedOn,
    audiobookCoverUpdatedOn: dto.audiobookCoverUpdatedOn,
  };
}
