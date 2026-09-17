import {HttpClient, HttpParams} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {firstValueFrom} from 'rxjs';

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

  fetch(facetKeys: readonly string[]): Promise<Record<string, FacetValueBookIds[]>> {
    let params = new HttpParams();
    for (const key of facetKeys) {
      params = params.append('facet', key);
    }
    return firstValueFrom(this.http.get<Record<string, FacetValueBookIds[]>>(this.baseUrl, {params}));
  }
}
