import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryStatsService} from '../../../library-stats/service/library-stats.service';
import {ReadingHabitsChartComponent} from './reading-habits-chart.component';

describe('ReadingHabitsChartComponent', () => {
  let summary: ReturnType<typeof vi.fn>;
  let aggregate: ReturnType<typeof vi.fn>;
  let histogram: ReturnType<typeof vi.fn>;
  let timeline: ReturnType<typeof vi.fn>;
  let authors: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    summary = vi.fn(() => of({totalBooks: 10, totalSizeKb: 0, distinctAuthors: 0, distinctSeries: 0, distinctPublishers: 0}));
    aggregate = vi.fn(() => of([]));
    histogram = vi.fn(() => of([]));
    timeline = vi.fn(() => of({buckets: [], oldest: null, newest: null, avgDaysToFinish: null}));
    authors = vi.fn(() => of([]));

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {summary, aggregate, histogram, timeline, authors}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ],
    });
  });

  it('composes the profile from primitive endpoints, never from books()', () => {
    TestBed.runInInjectionContext(() => new ReadingHabitsChartComponent());

    expect(summary).toHaveBeenCalledWith(null);
    expect(aggregate).toHaveBeenCalledWith('read_status', null);
    expect(aggregate).toHaveBeenCalledWith('progress_percent', null);
    expect(timeline).toHaveBeenCalledWith('date_finished', 'month', null);
    expect(authors).toHaveBeenCalledWith(200, null);
  });

  it('produces a bounded 0-100 score for every habit once signals resolve', () => {
    const component = TestBed.runInInjectionContext(() => new ReadingHabitsChartComponent());
    const chartData = component.chartData();

    (chartData.datasets[0]?.data as number[])?.forEach(score => {
      expect(score).toBeGreaterThanOrEqual(0);
      expect(score).toBeLessThanOrEqual(100);
    });
  });
});
