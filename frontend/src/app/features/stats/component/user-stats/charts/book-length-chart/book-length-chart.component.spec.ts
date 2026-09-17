import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryStatsService, type LibraryRatedBook} from '../../../library-stats/service/library-stats.service';
import {BookLengthChartComponent} from './book-length-chart.component';

describe('BookLengthChartComponent', () => {
  let ratedBooks: ReturnType<typeof vi.fn>;

  function book(overrides: Partial<LibraryRatedBook>): LibraryRatedBook {
    return {
      bookId: 1, title: 'Untitled', pageCount: 300, personalRating: 8,
      externalRatingAvg: null, readStatus: 'READ', publishedYear: null, addedOn: null,
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

  it('sources scatter points from the rated-books endpoint, not from book()', () => {
    ratedBooks.mockReturnValue(of([
      book({bookId: 1, title: 'A', pageCount: 250, personalRating: 9, readStatus: 'READ'}),
      book({bookId: 2, title: 'B', pageCount: 0, personalRating: 5, readStatus: 'READING'}),
    ]));

    const component = TestBed.runInInjectionContext(() => new BookLengthChartComponent());

    expect(ratedBooks).toHaveBeenCalledWith(null);
    expect(component.totalRatedBooks()).toBe(1); // book B has no page count, filtered out
    expect(component.chartData().datasets[0]?.data).toEqual([
      expect.objectContaining({x: 250, y: 9, bookTitle: 'A'}),
    ]);
  });
});
