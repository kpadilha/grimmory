import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryStatsService} from '../../../library-stats/service/library-stats.service';
import {ReadingDNAChartComponent} from './reading-dna-chart.component';

describe('ReadingDNAChartComponent', () => {
  let summary: ReturnType<typeof vi.fn>;
  let aggregate: ReturnType<typeof vi.fn>;
  let histogram: ReturnType<typeof vi.fn>;
  let timeline: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    summary = vi.fn(() => of({totalBooks: 10, totalSizeKb: 0, distinctAuthors: 0, distinctSeries: 0, distinctPublishers: 0}));
    aggregate = vi.fn(() => of([]));
    histogram = vi.fn(() => of([]));
    timeline = vi.fn(() => of({buckets: [], oldest: null, newest: null, avgDaysToFinish: null}));

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {summary, aggregate, histogram, timeline}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ],
    });
  });

  it('composes the profile from primitive endpoints, never from books()', () => {
    TestBed.runInInjectionContext(() => new ReadingDNAChartComponent());

    expect(summary).toHaveBeenCalledWith(null);
    expect(aggregate).toHaveBeenCalledWith('categories', null);
    expect(aggregate).toHaveBeenCalledWith('read_status', null);
    expect(histogram).toHaveBeenCalledWith('page_count', null);
    expect(timeline).toHaveBeenCalledWith('published_date', 'year', null);
  });

  it('produces a bounded 0-100 score for every trait once signals resolve', () => {
    const component = TestBed.runInInjectionContext(() => new ReadingDNAChartComponent());
    const chartData = component.chartData();

    expect(chartData.datasets[0]?.data).toBeDefined();
    (chartData.datasets[0]?.data as number[])?.forEach(score => {
      expect(score).toBeGreaterThanOrEqual(0);
      expect(score).toBeLessThanOrEqual(100);
    });
  });
});
