import {computed, inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {lastValueFrom, Observable} from 'rxjs';
import {map, takeUntil} from 'rxjs/operators';
import {infiniteQueryOptions, injectQuery} from '@tanstack/angular-query-experimental';
import {ReadStatus} from '../../book/model/book.model';
import {SeriesCoverBook, SeriesSummary} from '../model/series.model';
import {AuthService} from '../../../shared/service/auth.service';
import {API_CONFIG} from '../../../core/config/api-config';
import {BookQueryService} from '../../book/data/book-query.service';
import {GLOBAL_FACET_PARAMS} from '../../book/data/book-query-params';
import {toFacetDistinctCount} from '../../book/data/book-query.models';
import {BrowseLink, BrowsePage, BrowsePageMetadata, findBrowsePageLink} from '../../../core/data/browse.models';
import {mapBrowsePage} from '../../../core/data/browse-response';
import {abortSignal, QUERY_DEFAULTS} from '../../../core/data/query-transport';
import {normalizeSeriesQueryParams, SeriesQueryParams, toSeriesPageHttpParams} from '../data/series-query-params';

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

interface RawSeriesPage {
  content: SeriesSummaryDto[];
  page: BrowsePageMetadata;
  links: BrowseLink[];
}

export type SeriesPage = BrowsePage<SeriesSummary>;

@Injectable({
  providedIn: 'root'
})
export class SeriesDataService {

  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly bookQueryService = inject(BookQueryService);
  private readonly token = this.authService.token;
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/books/series/summary`;

  // The series grid is server-paginated like the book browser - search/sort/status all run
  // server-side (SeriesSummaryService), so the client only ever holds the pages it has scrolled.
  infinitePage(params: SeriesQueryParams) {
    const normalized = normalizeSeriesQueryParams(params);
    return infiniteQueryOptions({
      queryKey: ['books', 'series', 'summary', normalized] as const,
      queryFn: ({pageParam, signal}) => this.fetchPage(normalized, pageParam, signal),
      initialPageParam: null as string | null,
      getNextPageParam: page => findBrowsePageLink(page, 'next')?.href,
      enabled: !!this.token(),
      ...QUERY_DEFAULTS,
    });
  }

  // Sidebar badge count - the server's exact, uncapped COUNT(DISTINCT series), not the full collection.
  private readonly globalFacetsQuery = injectQuery(() => ({
    ...this.bookQueryService.facets(GLOBAL_FACET_PARAMS),
    enabled: !!this.token(),
  }));

  readonly totalSeriesCount = computed(() => toFacetDistinctCount(this.globalFacetsQuery.data(), 'series'));

  private fetchPage(params: SeriesQueryParams, nextHref: string | null, signal: AbortSignal): Promise<SeriesPage> {
    const request$: Observable<RawSeriesPage> = nextHref !== null
      ? this.http.get<RawSeriesPage>(`${API_CONFIG.BASE_URL}${nextHref}`)
      : this.http.get<RawSeriesPage>(this.url, {params: toSeriesPageHttpParams(params)});

    return lastValueFrom(request$.pipe(
      map(mapSeriesPage),
      takeUntil(abortSignal(signal)),
    ));
  }
}

function mapSeriesPage(raw: RawSeriesPage): SeriesPage {
  const page = mapBrowsePage<SeriesSummaryDto>(raw);
  return {...page, content: page.content.map(toSeriesSummary)};
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
