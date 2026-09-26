import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryStatsService, type LibraryTimelineResponse} from '../../../library-stats/service/library-stats.service';
import {ReadingDebtChartComponent} from './reading-debt-chart.component';

describe('ReadingDebtChartComponent', () => {
  let timeline: ReturnType<typeof vi.fn>;
  const emptyTimeline: LibraryTimelineResponse = {buckets: [], oldest: null, newest: null, avgDaysToFinish: null};

  beforeEach(() => {
    timeline = vi.fn(() => of(emptyTimeline));

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {timeline}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('requests both monthly timelines (added_on, date_finished) instead of loading books()', () => {
    TestBed.runInInjectionContext(() => new ReadingDebtChartComponent());

    expect(timeline).toHaveBeenCalledWith('added_on', 'month', null);
    expect(timeline).toHaveBeenCalledWith('date_finished', 'month', null);
  });

  it('computes a running backlog from the two server timelines for the current month', () => {
    const now = new Date();
    const key = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;

    timeline.mockImplementation((field: string) => {
      if (field === 'added_on') {
        return of({buckets: [{period: key, count: 5}], oldest: null, newest: null, avgDaysToFinish: null} as LibraryTimelineResponse);
      }
      return of({buckets: [{period: key, count: 2}], oldest: null, newest: null, avgDaysToFinish: null} as LibraryTimelineResponse);
    });

    const component = TestBed.runInInjectionContext(() => new ReadingDebtChartComponent());

    expect(component.hasData).toBe(true);
    expect(component.currentBacklog).toBe(3);
  });
});
