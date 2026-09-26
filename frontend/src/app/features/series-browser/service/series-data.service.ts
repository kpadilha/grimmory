import {computed, inject, Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {lastValueFrom, Observable} from 'rxjs';
import {map, takeUntil} from 'rxjs/operators';
import {infiniteQueryOptions, injectQuery} from '@tanstack/angular-query-experimental';
import {API_CONFIG} from '../../../core/config/api-config';
import {AuthService} from '../../../shared/service/auth.service';
import {ReadStatus} from '../../book/model/book.model';
import {SeriesCoverBook, SeriesSummary} from '../model/series.model';
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

  // Sidebar badge count from the summary endpoint's cached aggregate totalElements, not the
  // series facet - that facet caps at 100 distinct values and freezes the badge past that.
  private readonly seriesCountQuery = injectQuery(() => ({
    queryKey: ['books', 'series', 'summary', 'count'] as const,
    queryFn: ({signal}: {signal: AbortSignal}) => lastValueFrom(this.http.get<RawSeriesPage>(this.url, {
      params: new HttpParams().set('page', '0').set('size', '1'),
    }).pipe(
      map(raw => raw.page.totalElements),
      takeUntil(abortSignal(signal)),
    )),
    enabled: !!this.token(),
    ...QUERY_DEFAULTS,
  }));

  readonly totalSeriesCount = computed(() => this.seriesCountQuery.data() ?? 0);

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
