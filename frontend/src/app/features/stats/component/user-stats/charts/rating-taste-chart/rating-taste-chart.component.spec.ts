import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryFilterService} from '../../../library-stats/service/library-filter.service';
import {LibraryStatsService, type LibraryRatedBook} from '../../../library-stats/service/library-stats.service';
import {RatingTasteChartComponent} from './rating-taste-chart.component';

describe('RatingTasteChartComponent', () => {
  const selectedLibrary = signal<number | null>(null);
  let ratedBooks: ReturnType<typeof vi.fn>;

  function book(overrides: Partial<LibraryRatedBook>): LibraryRatedBook {
    return {
      bookId: 1, title: 'Untitled', pageCount: null, personalRating: 8,
      externalRatingAvg: 4, readStatus: 'READ', publishedYear: null, addedOn: null,
      ...overrides
    };
  }

  beforeEach(() => {
    selectedLibrary.set(null);
    ratedBooks = vi.fn(() => of([] as LibraryRatedBook[]));

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {ratedBooks}},
        {provide: LibraryFilterService, useValue: {selectedLibrary}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('requests rated books scoped to the selected library instead of loading books()', async () => {
    selectedLibrary.set(7);

    TestBed.runInInjectionContext(() => new RatingTasteChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(ratedBooks).toHaveBeenCalledWith(7);
  });

  it('classifies books into quadrants using the server-computed external rating average', async () => {
    ratedBooks.mockReturnValue(of([
      book({bookId: 1, personalRating: 9, externalRatingAvg: 4.5}), // popular favourite (normalized 4.5 >= 3, ext >= 3)
      book({bookId: 2, personalRating: 2, externalRatingAvg: 4.5}), // overrated (normalized 1 < 3, ext >= 3)
      book({bookId: 3, personalRating: 0, externalRatingAvg: 0}),  // filtered out: no external rating
    ]));

    const component = TestBed.runInInjectionContext(() => new RatingTasteChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(component.totalRatedBooks()).toBe(2);
  });
});
