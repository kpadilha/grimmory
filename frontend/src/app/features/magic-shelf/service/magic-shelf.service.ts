import {computed, effect, inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {lastValueFrom, Observable} from 'rxjs';
import {tap} from 'rxjs/operators';
import {injectQuery, queryOptions, QueryClient} from '@tanstack/angular-query-experimental';
import {injectQueries} from '@tanstack/angular-query-experimental/inject-queries-experimental';

import {API_CONFIG} from '../../../core/config/api-config';
import {AuthService} from '../../../shared/service/auth.service';
import {IconType} from '../../../shared/icons/icon-selection';
import {BookQueryService} from '../../book/data/book-query.service';
import {BookPageParams} from '../../book/data/book-query-params';

export interface MagicShelf {
  id?: number | null;
  name: string;
  icon?: string | null;
  iconType?: IconType | null;
  filterJson: string;
  isPublic?: boolean;
}

const MAGIC_SHELVES_QUERY_KEY = ['magicShelves'] as const;

// Not a real shelf row, so its count can't come off the unfiltered shelf facet - a size-1
// filtered page (server evaluates the same rule as the OPDS feed) still beats 132k books.
function magicShelfCountParams(magicShelfId: number): BookPageParams {
  return {
    facets: {shelf: [`magic:${magicShelfId}`]},
    facetLogic: 'and',
    sort: [],
    size: 1,
  };
}

@Injectable({
  providedIn: 'root',
})
export class MagicShelfService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/magic-shelves`;

  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly queryClient = inject(QueryClient);
  private readonly bookQueryService = inject(BookQueryService);
  private readonly token = this.authService.token;

  private readonly shelvesQuery = injectQuery(() => ({
    ...this.getShelvesQueryOptions(),
    enabled: !!this.token(),
  }));

  readonly shelves = computed(() => this.shelvesQuery.data() ?? []);

  readonly shelvesError = computed<string | null>(() => {
    if (!this.token() || !this.shelvesQuery.isError()) {
      return null;
    }

    const error = this.shelvesQuery.error();
    return error instanceof Error ? error.message : 'Failed to load magic shelves';
  });

  readonly isShelvesLoading = computed(() => !!this.token() && this.shelvesQuery.isPending());

  constructor() {
    effect(() => {
      const token = this.token();
      if (token === null) {
        this.queryClient.removeQueries({queryKey: MAGIC_SHELVES_QUERY_KEY});
      }
    });
  }

  private getShelvesQueryOptions() {
    return queryOptions({
      queryKey: MAGIC_SHELVES_QUERY_KEY,
      queryFn: () => lastValueFrom(this.http.get<MagicShelf[]>(this.url))
    });
  }

  saveShelf(data: {
    id?: number;
    name: string | null;
    icon: string | null;
    iconType?: IconType | null;
    group: unknown;
    isPublic?: boolean | null;
  }): Observable<MagicShelf> {
    const payload: MagicShelf = {
      id: data.id,
      name: data.name ?? '',
      icon: data.icon,
      iconType: data.iconType,
      filterJson: JSON.stringify(data.group),
      isPublic: data.isPublic ?? false
    };

    return this.http.post<MagicShelf>(this.url, payload).pipe(
      tap(() => {
        void this.queryClient.invalidateQueries({queryKey: MAGIC_SHELVES_QUERY_KEY, exact: true});
      })
    );
  }

  findShelfById(id: number): MagicShelf | undefined {
    return this.shelves().find(shelf => shelf.id === id);
  }

  // Sidebar badge counts - one small server-filtered page per magic shelf, never the full
  // collection.
  private readonly idsWithMagicShelves = computed(() =>
    this.shelves().filter((shelf): shelf is MagicShelf & {id: number} => shelf.id != null)
  );

  private readonly shelfCountQueries = injectQueries(() => ({
    queries: this.idsWithMagicShelves().map(shelf => this.bookQueryService.page(magicShelfCountParams(shelf.id))),
  }));

  readonly bookCountByMagicShelfId = computed(() => {
    const shelves = this.idsWithMagicShelves();
    const results = this.shelfCountQueries();
    const counts = new Map<number, number>();
    shelves.forEach((shelf, index) => {
      counts.set(shelf.id, results[index]?.data()?.page.totalElements ?? 0);
    });
    return counts;
  });

  deleteShelf(id: number): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`).pipe(
      tap(() => {
        void this.queryClient.invalidateQueries({queryKey: MAGIC_SHELVES_QUERY_KEY, exact: true});
      })
    );
  }
}
