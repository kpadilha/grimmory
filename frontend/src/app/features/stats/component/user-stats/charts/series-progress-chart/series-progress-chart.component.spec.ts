import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryFilterService} from '../../../library-stats/service/library-filter.service';
import {LibraryStatsService, type LibrarySeriesStat} from '../../../library-stats/service/library-stats.service';
import {SeriesProgressChartComponent} from './series-progress-chart.component';

describe('SeriesProgressChartComponent', () => {
  const selectedLibrary = signal<number | null>(null);
  let series: ReturnType<typeof vi.fn>;

  function stat(overrides: Partial<LibrarySeriesStat>): LibrarySeriesStat {
    return {
      seriesName: 'Series A', bookCount: 5, seriesTotal: null, readCount: 3, readingCount: 1,
      partiallyReadCount: 0, pausedCount: 0, abandonedCount: 0, wontReadCount: 0, unreadCount: 1,
      nextUnreadTitle: null, avgPersonalRating: null, avgExternalRating: null,
      ...overrides
    };
  }

  beforeEach(() => {
    selectedLibrary.set(null);
    series = vi.fn(() => of([] as LibrarySeriesStat[]));

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {series}},
        {provide: LibraryFilterService, useValue: {selectedLibrary}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('requests the series endpoint scoped to the selected library instead of loading books()', async () => {
    selectedLibrary.set(9);

    TestBed.runInInjectionContext(() => new SeriesProgressChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(series).toHaveBeenCalledWith(200, 9);
  });

  it('classifies series status and completion from the server aggregate', async () => {
    series.mockReturnValue(of([
      stat({seriesName: 'Complete', bookCount: 3, readCount: 3, readingCount: 0, unreadCount: 0}),
      stat({seriesName: 'Reading Now', bookCount: 4, readCount: 1, readingCount: 1, unreadCount: 2}),
    ]));

    const component = TestBed.runInInjectionContext(() => new SeriesProgressChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(component.seriesList.find(s => s.name === 'Complete')?.status).toBe('completed');
    expect(component.seriesList.find(s => s.name === 'Complete')?.completionPercentage).toBe(100);
    expect(component.seriesList.find(s => s.name === 'Reading Now')?.status).toBe('in-progress');
    expect(component.stats?.totalSeries).toBe(2);
  });
});
