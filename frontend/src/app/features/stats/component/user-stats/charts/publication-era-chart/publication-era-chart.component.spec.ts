import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryStatsService, type LibraryRatedBook} from '../../../library-stats/service/library-stats.service';
import {PublicationEraChartComponent} from './publication-era-chart.component';

describe('PublicationEraChartComponent', () => {
  let ratedBooks: ReturnType<typeof vi.fn>;

  function book(overrides: Partial<LibraryRatedBook>): LibraryRatedBook {
    return {
      bookId: 1, title: 'Untitled', pageCount: null, personalRating: 8,
      externalRatingAvg: null, readStatus: 'READ', publishedYear: 1985, addedOn: null,
      ...overrides
    };
  }

  beforeEach(() => {
    ratedBooks = vi.fn(() => of([] as LibraryRatedBook[]));

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {ratedBooks}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('sources rated books with a publishedYear straight from the endpoint, not from books()', () => {
    ratedBooks.mockReturnValue(of([
      book({bookId: 1, publishedYear: 1985, personalRating: 9}),
      book({bookId: 2, publishedYear: 1987, personalRating: 8}),
      book({bookId: 3, publishedYear: 2015, personalRating: 3}),
    ]));

    const component = TestBed.runInInjectionContext(() => new PublicationEraChartComponent());

    expect(ratedBooks).toHaveBeenCalledWith(null);
    expect(component.totalRated).toBe(3);
    expect(component.bestDecade).toBe('1980s');
    expect(component.hasData).toBe(true);
  });
});
