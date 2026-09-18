import {HttpParams} from '@angular/common/http';

export const SERIES_PAGE_SIZE = 30;
export const DEFAULT_SERIES_SORT = 'name-asc';
export const DEFAULT_SERIES_STATUS = 'all';

export interface SeriesQueryParams {
  sort: string;
  query: string;
  status: string;
}

export function normalizeSeriesQueryParams(params: SeriesQueryParams): SeriesQueryParams {
  return {
    sort: params.sort || DEFAULT_SERIES_SORT,
    query: params.query.trim(),
    status: params.status || DEFAULT_SERIES_STATUS,
  };
}

export function toSeriesPageHttpParams(params: SeriesQueryParams): HttpParams {
  let httpParams = new HttpParams()
    .set('page', '0')
    .set('size', SERIES_PAGE_SIZE.toString())
    .set('sort', params.sort);

  if (params.query) {
    httpParams = httpParams.set('query', params.query);
  }
  if (params.status !== DEFAULT_SERIES_STATUS) {
    httpParams = httpParams.set('status', params.status);
  }

  return httpParams;
}
