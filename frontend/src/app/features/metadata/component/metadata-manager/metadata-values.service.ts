import {HttpClient, HttpParams} from '@angular/common/http';
import {inject, Injectable, Signal} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {firstValueFrom, Observable, Subject, switchMap} from 'rxjs';

import {API_CONFIG} from '../../../../core/config/api-config';

export interface FacetValueBookIds {
  value: string;
  bookIds: number[];
}

// GET /books/facets/values is exhaustive and uncapped, unlike /books/facets - merge/rename/delete
// need every book per value, not the top-100 the sidebar facet counts are capped at.
@Injectable({providedIn: 'root'})
export class MetadataValuesService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/v1/books/facets/values`;
  private readonly searchUrl = `${API_CONFIG.BASE_URL}/api/v1/books/metadata-values`;

  fetch(facetKeys: readonly string[]): Promise<Record<string, FacetValueBookIds[]>> {
    let params = new HttpParams();
    for (const key of facetKeys) {
      params = params.append('facet', key);
    }
    return firstValueFrom(this.http.get<Record<string, FacetValueBookIds[]>>(this.baseUrl, {params}));
  }

  // GET /books/metadata-values returns distinct values only, capped server-side - the typeahead
  // counterpart to fetch(), which must never load per-value book id lists for an autocomplete.
  search(field: string, query: string, limit = 20): Observable<string[]> {
    const params = new HttpParams().set('field', field).set('query', query).set('limit', limit);
    return this.http.get<string[]>(this.searchUrl, {params});
  }
}

// Wires a PrimeNG AutoComplete's completeMethod to server-side search - PrimeNG's own 300ms
// `delay` throttles keystrokes, switchMap here only drops a slow response overtaken by a newer one.
// Must run in an injection context (a field initializer or constructor), like toSignal itself.
export function metadataValueTypeahead(field: string, limit = 20): {
  filter: (event: { query: string }) => void;
  results: Signal<string[]>;
} {
  const service = inject(MetadataValuesService);
  const query$ = new Subject<string>();
  const results = toSignal(query$.pipe(switchMap(q => service.search(field, q, limit))), {initialValue: []});
  return {filter: (event: { query: string }) => query$.next(event.query), results};
}
