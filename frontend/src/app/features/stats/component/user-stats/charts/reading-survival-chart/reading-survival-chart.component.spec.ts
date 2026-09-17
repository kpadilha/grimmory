import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {describe, expect, it, vi} from 'vitest';

import {ReadingSurvivalChartComponent} from './reading-survival-chart.component';
import {LibraryStatsService, type LibraryAggregateBucket} from '../../../library-stats/service/library-stats.service';
import {TranslocoService} from '@jsverse/transloco';

describe('ReadingSurvivalChartComponent', () => {
  function createComponent(buckets: LibraryAggregateBucket[]) {
    const aggregate = vi.fn(() => of(buckets));

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {aggregate}},
        {provide: TranslocoService, useValue: {translate: (key: string, params?: Record<string, unknown>) => params ? `${key}:${Object.values(params).join(':')}` : key}},
      ]
    });

    const component = TestBed.runInInjectionContext(() => new ReadingSurvivalChartComponent());
    return {component, aggregate};
  }

  it('requests the progress_percent aggregate instead of loading books()', () => {
    const {aggregate} = createComponent([]);

    expect(aggregate).toHaveBeenCalledWith('progress_percent', null);
  });

  it('returns empty metrics when no books have been started', () => {
    const {component} = createComponent([]);

    expect(component.totalStarted()).toBe(0);
    expect(component.completionRate()).toBe(0);
    expect(component.chartData()).toEqual({labels: [], datasets: []});
  });

  it('reconstructs the exact survival curve from lower-bound-labelled server buckets', () => {
    // Mirrors five books with progress 5, 15, 35, 80, 100 - bucketed at their lower bound:
    // 5->"0" [0,10), 15->"10" [10,25), 35->"25" [25,50), 80->"75" [75,90), 100->"100".
    const {component} = createComponent([
      {value: '0', count: 1},
      {value: '10', count: 1},
      {value: '25', count: 1},
      {value: '75', count: 1},
      {value: '100', count: 1},
    ]);

    expect(component.totalStarted()).toBe(5);
    expect(component.completionRate()).toBe(20);
    expect(component.medianDropout()).toBe('25-50%');
    expect(component.dangerZoneRange()).toBe('0-10%');
    expect(component.dangerZoneDrop()).toBe('-20%');
    expect(component.chartData().labels).toEqual(['0%', '10%', '25%', '50%', '75%', '90%', '100%']);
    expect(component.chartData().datasets[0]?.label).toBe('statsUser.readingSurvival.survivalRate');
  });
});
