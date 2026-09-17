import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService, type LibraryTimelineResponse} from '../../service/library-stats.service';
import {PublicationTimelineChartComponent} from './publication-timeline-chart.component';

describe('PublicationTimelineChartComponent', () => {
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
    selectedLibrary.set(1);

    TestBed.runInInjectionContext(() => new PublicationTimelineChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(timeline).toHaveBeenCalledWith('published_date', 'year', 1);
  });

  it('derives decade buckets and insights from server year/count pairs, with oldest/newest from the response', async () => {
    timeline.mockReturnValue(of({
      buckets: [
        {period: '1985', count: 2},
        {period: '2020', count: 3},
      ],
      oldest: {title: 'Old Book', year: 1985},
      newest: {title: 'New Book', year: 2020},
      avgDaysToFinish: null,
    } as LibraryTimelineResponse));

    const component = TestBed.runInInjectionContext(() => new PublicationTimelineChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(component.totalBooks()).toBe(5);
    expect(component.chartData().labels).toEqual(['1980s', '2020s']);
    expect(component.insights()?.oldestBook).toEqual({title: 'Old Book', year: 1985});
    expect(component.insights()?.newestBook).toEqual({title: 'New Book', year: 2020});
    expect(component.insights()?.timeSpan).toBe(35);
  });
});
