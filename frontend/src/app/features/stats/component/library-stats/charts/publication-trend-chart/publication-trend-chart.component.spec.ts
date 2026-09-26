import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService, type LibraryTimelineResponse} from '../../service/library-stats.service';
import {PublicationTrendChartComponent} from './publication-trend-chart.component';

describe('PublicationTrendChartComponent', () => {
  const selectedLibrary = signal<number | null>(null);
  let timeline: ReturnType<typeof vi.fn>;

  const emptyTimeline: LibraryTimelineResponse = {buckets: [], oldest: null, newest: null, avgDaysToFinish: null};

  beforeEach(() => {
    selectedLibrary.set(null);
    timeline = vi.fn(() => of(emptyTimeline));

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {timeline}},
        {provide: LibraryFilterService, useValue: {selectedLibrary}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('requests the published_date/year timeline scoped to the selected library', async () => {
    selectedLibrary.set(2);

    TestBed.runInInjectionContext(() => new PublicationTrendChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(timeline).toHaveBeenCalledWith('published_date', 'year', 2);
  });

  it('fills sparse years into a contiguous line dataset from server year/count pairs', async () => {
    timeline.mockReturnValue(of({
      buckets: [
        {period: '2018', count: 1},
        {period: '2020', count: 3},
      ],
      oldest: null,
      newest: null,
      avgDaysToFinish: null,
    } as LibraryTimelineResponse));

    const component = TestBed.runInInjectionContext(() => new PublicationTrendChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));
    const chartData = component.chartData();

    expect(component.totalBooks()).toBe(4);
    expect(chartData.labels).toEqual(['2018', '2019', '2020']);
    expect(chartData.datasets[0]?.data).toEqual([1, 0, 3]);
  });
});
