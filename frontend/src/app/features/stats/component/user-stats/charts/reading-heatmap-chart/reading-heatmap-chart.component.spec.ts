import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {nextChartEmission} from '../../../../../../core/testing/chart-testing';
import {UserStatsService, type BookCompletionHeatmapResponse} from '../../../../../settings/user-management/user-stats.service';
import {ReadingHeatmapChartComponent} from './reading-heatmap-chart.component';

describe('ReadingHeatmapChartComponent', () => {
  let getBookCompletionHeatmap: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    getBookCompletionHeatmap = vi.fn(() => of([] as BookCompletionHeatmapResponse[]));

    TestBed.configureTestingModule({
      providers: [
        {provide: UserStatsService, useValue: {getBookCompletionHeatmap}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('places server-side year/month completion counts onto the matrix grid', async () => {
    const currentYear = new Date().getFullYear();
    getBookCompletionHeatmap.mockReturnValue(of([
      {year: currentYear, month: 3, count: 5},
      {year: currentYear - 1, month: 12, count: 2},
    ]));

    const component = TestBed.runInInjectionContext(() => new ReadingHeatmapChartComponent());
    const nextEmission = nextChartEmission(component.chartData$);
    component.ngOnInit();
    const chartData = await nextEmission;

    expect(getBookCompletionHeatmap).toHaveBeenCalled();
    const points = chartData.datasets[0]?.data as {x: number; y: number; v: number}[];
    expect(points.find(p => p.x === 2 && p.y === 9)?.v).toBe(5);
    expect(points.filter(p => p.v > 0)).toHaveLength(2);
  });
});
