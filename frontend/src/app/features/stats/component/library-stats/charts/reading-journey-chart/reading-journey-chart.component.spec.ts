import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService, type LibraryTimelineResponse} from '../../service/library-stats.service';
import {ReadingJourneyChartComponent} from './reading-journey-chart.component';

describe('ReadingJourneyChartComponent', () => {
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
        {provide: TranslocoService, useValue: {translate: (key: string, params?: Record<string, unknown>) => params ? `${key}:${JSON.stringify(params)}` : key}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('requests both the added_on and date_finished monthly timelines scoped to the selected library', async () => {
    selectedLibrary.set(3);

    TestBed.runInInjectionContext(() => new ReadingJourneyChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(timeline).toHaveBeenCalledWith('added_on', 'month', 3);
    expect(timeline).toHaveBeenCalledWith('date_finished', 'month', 3);
  });

  it('builds cumulative added/finished series from the two server timelines', async () => {
    timeline.mockImplementation((field: string) => {
      if (field === 'added_on') {
        return of({
          buckets: [{period: '2024-01', count: 2}, {period: '2024-02', count: 1}],
          oldest: null, newest: null, avgDaysToFinish: null,
        } as LibraryTimelineResponse);
      }
      return of({
        buckets: [{period: '2024-02', count: 1}],
        oldest: null, newest: null, avgDaysToFinish: 5.5,
      } as LibraryTimelineResponse);
    });

    const component = TestBed.runInInjectionContext(() => new ReadingJourneyChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));
    const chartData = component.chartData();

    expect(component.totalBooks()).toBe(3);
    expect(chartData.datasets[0]?.data).toEqual([2, 3]); // cumulative added
    expect(chartData.datasets[1]?.data).toEqual([0, 1]); // cumulative finished
    expect(component.insights()?.avgTimeToFinishDays).toBe(6);
    expect(component.insights()?.currentBacklog).toBe(2);
  });
});
