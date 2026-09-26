import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService, type LibraryAuthorStat} from '../../service/library-stats.service';
import {AuthorUniverseChartComponent} from './author-universe-chart.component';

describe('AuthorUniverseChartComponent', () => {
  const selectedLibrary = signal<number | null>(null);
  let authors: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    selectedLibrary.set(null);
    authors = vi.fn(() => of([] as LibraryAuthorStat[]));

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {authors}},
        {provide: LibraryFilterService, useValue: {selectedLibrary}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('requests the top-50 authors scoped to the selected library', async () => {
    selectedLibrary.set(5);

    TestBed.runInInjectionContext(() => new AuthorUniverseChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(authors).toHaveBeenCalledWith(50, 5);
  });

  it('drops single-book authors and derives completion rate from server stats', async () => {
    authors.mockReturnValue(of([
      {author: 'Alice', bookCount: 4, totalPages: 800, avgRating: 4.2, readCount: 3, distinctCategories: 2},
      {author: 'Solo', bookCount: 1, totalPages: 100, avgRating: null, readCount: 0, distinctCategories: 0},
    ] as LibraryAuthorStat[]));

    const component = TestBed.runInInjectionContext(() => new AuthorUniverseChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(component.totalAuthors).toBe(1);
    expect(component.topAuthors).toEqual([
      expect.objectContaining({name: 'Alice', bookCount: 4, completionRate: 75}),
    ]);
  });
});
