import {computed, inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {lastValueFrom} from 'rxjs';
import {injectQuery, queryOptions} from '@tanstack/angular-query-experimental';
import {ReadStatus} from '../../book/model/book.model';
import {SeriesCoverBook, SeriesSummary} from '../model/series.model';
import {AuthService} from '../../../shared/service/auth.service';
import {API_CONFIG} from '../../../core/config/api-config';
import {BookQueryService} from '../../book/data/book-query.service';
import {GLOBAL_FACET_PARAMS} from '../../book/data/book-query-params';
import {toFacetDistinctCount} from '../../book/data/book-query.models';

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
  private readonly authService = inject(AuthService);
  private readonly bookQueryService = inject(BookQueryService);
  private readonly token = this.authService.token;
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/books/series/summary`;

  private readonly seriesSummaryQuery = injectQuery(() => ({
    ...queryOptions({
      queryKey: ['books', 'series', 'summary'] as const,
      queryFn: () => lastValueFrom(this.http.get<SeriesSummaryDto[]>(this.url)),
    }),
    enabled: !!this.token(),
  }));

  readonly isLoading = computed(() => !!this.token() && this.seriesSummaryQuery.isPending());

  allSeries = computed<SeriesSummary[]>(() =>
    (this.seriesSummaryQuery.data() ?? []).map(toSeriesSummary)
  );

  // Sidebar badge count - the server's exact, uncapped COUNT(DISTINCT series), not the full collection.
  private readonly globalFacetsQuery = injectQuery(() => ({
    ...this.bookQueryService.facets(GLOBAL_FACET_PARAMS),
    enabled: !!this.token(),
  }));

  readonly totalSeriesCount = computed(() => toFacetDistinctCount(this.globalFacetsQuery.data(), 'series'));

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
