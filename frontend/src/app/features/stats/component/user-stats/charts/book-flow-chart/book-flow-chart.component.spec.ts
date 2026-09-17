import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryStatsService, type LibraryCrosstabCell} from '../../../library-stats/service/library-stats.service';
import {StatsChartThemeService} from '../../../shared/stats-chart-theme.service';
import {BookFlowChartComponent} from './book-flow-chart.component';

describe('BookFlowChartComponent', () => {
  let crosstab: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    crosstab = vi.fn(() => of([] as LibraryCrosstabCell[]));

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {crosstab}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
        {provide: StatsChartThemeService, useValue: {themeRevision: () => 0, colors: () => ({textMuted: '#000', text: '#000', grid: '#ccc'})}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('requests both crosstabs (added_quarter x read_status, read_status x personal_rating_bucket) instead of loading books()', () => {
    TestBed.runInInjectionContext(() => new BookFlowChartComponent());

    expect(crosstab).toHaveBeenCalledWith('added_quarter', 'read_status', null);
    expect(crosstab).toHaveBeenCalledWith('read_status', 'personal_rating_bucket', null);
  });

  it('derives quarter/status/rating totals and completion rate from the two crosstabs', () => {
    crosstab.mockImplementation((rowField: string) => {
      if (rowField === 'added_quarter') {
        return of([
          {row: '2024 Q1', col: 'Read', count: 3},
          {row: '2024 Q1', col: 'Unread', count: 1},
        ] as LibraryCrosstabCell[]);
      }
      return of([
        {row: 'Read', col: 'Rated 4-5', count: 3},
      ] as LibraryCrosstabCell[]);
    });

    const component = TestBed.runInInjectionContext(() => new BookFlowChartComponent());

    expect(component.hasData).toBe(true);
    expect(component.totalBooks).toBe(4);
    expect(component.topQuarter).toBe('2024 Q1');
    expect(component.completionRate).toBe('75%');
  });
});
