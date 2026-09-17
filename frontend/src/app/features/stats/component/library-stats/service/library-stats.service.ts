import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../../../core/config/api-config';

export interface LibraryAggregateBucket {
  value: string;
  count: number;
  breakdown?: LibraryAggregateBucket[];
}

export interface LibraryHistogramBucket {
  range: string;
  min: number;
  max: number;
  count: number;
}

export interface LibraryTimelineBucket {
  period: string;
  count: number;
}

export interface LibraryTimelineTitleYear {
  title: string;
  year: number;
}

export interface LibraryTimelineResponse {
  buckets: LibraryTimelineBucket[];
  oldest: LibraryTimelineTitleYear | null;
  newest: LibraryTimelineTitleYear | null;
  avgDaysToFinish: number | null;
}

export interface LibraryAuthorStat {
  author: string;
  bookCount: number;
  totalPages: number;
  avgRating: number | null;
  readCount: number;
  distinctCategories: number;
}

export interface LibrarySeriesStat {
  seriesName: string;
  bookCount: number;
  seriesTotal: number | null;
  readCount: number;
  readingCount: number;
  partiallyReadCount: number;
  pausedCount: number;
  abandonedCount: number;
  wontReadCount: number;
  unreadCount: number;
  nextUnreadTitle: string | null;
  avgPersonalRating: number | null;
  avgExternalRating: number | null;
}

export interface LibraryRatedBook {
  bookId: number;
  title: string;
  pageCount: number | null;
  personalRating: number;
  externalRatingAvg: number | null;
  readStatus: string;
  publishedYear: number | null;
  addedOn: string | null;
}

export interface LibraryCrosstabCell {
  row: string;
  col: string;
  count: number;
}

export interface LibrarySummary {
  totalBooks: number;
  totalSizeKb: number;
  distinctAuthors: number;
  distinctSeries: number;
  distinctPublishers: number;
}

@Injectable({
  providedIn: 'root'
})
export class LibraryStatsService {
  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/v1/library-stats`;
  private http = inject(HttpClient);

  private params(libraryId: number | null, extra: Record<string, string> = {}): Record<string, string> {
    return libraryId ? {...extra, libraryId: libraryId.toString()} : extra;
  }

  aggregate(field: string, libraryId: number | null, breakdownBy?: string): Observable<LibraryAggregateBucket[]> {
    return this.http.get<LibraryAggregateBucket[]>(`${this.baseUrl}/aggregate`, {
      params: this.params(libraryId, breakdownBy ? {field, breakdownBy} : {field})
    });
  }

  histogram(field: string, libraryId: number | null): Observable<LibraryHistogramBucket[]> {
    return this.http.get<LibraryHistogramBucket[]>(`${this.baseUrl}/histogram`, {params: this.params(libraryId, {field})});
  }

  timeline(field: string, granularity: 'year' | 'month', libraryId: number | null): Observable<LibraryTimelineResponse> {
    return this.http.get<LibraryTimelineResponse>(`${this.baseUrl}/timeline`, {params: this.params(libraryId, {field, granularity})});
  }

  authors(limit: number, libraryId: number | null): Observable<LibraryAuthorStat[]> {
    return this.http.get<LibraryAuthorStat[]>(`${this.baseUrl}/authors`, {params: this.params(libraryId, {limit: limit.toString()})});
  }

  series(limit: number, libraryId: number | null): Observable<LibrarySeriesStat[]> {
    return this.http.get<LibrarySeriesStat[]>(`${this.baseUrl}/series`, {params: this.params(libraryId, {limit: limit.toString()})});
  }

  ratedBooks(libraryId: number | null): Observable<LibraryRatedBook[]> {
    return this.http.get<LibraryRatedBook[]>(`${this.baseUrl}/rated-books`, {params: this.params(libraryId)});
  }

  crosstab(rowField: string, colField: string, libraryId: number | null): Observable<LibraryCrosstabCell[]> {
    return this.http.get<LibraryCrosstabCell[]>(`${this.baseUrl}/crosstab`, {params: this.params(libraryId, {rowField, colField})});
  }

  summary(libraryId: number | null): Observable<LibrarySummary> {
    return this.http.get<LibrarySummary>(`${this.baseUrl}/summary`, {params: this.params(libraryId)});
  }
}
