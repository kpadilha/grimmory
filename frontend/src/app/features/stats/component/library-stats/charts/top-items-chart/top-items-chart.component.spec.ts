import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService, type LibraryAggregateBucket} from '../../service/library-stats.service';
import {TopItemsChartComponent} from './top-items-chart.component';

describe('TopItemsChartComponent', () => {
  const selectedLibrary = signal<number | null>(null);
  let aggregate: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    selectedLibrary.set(null);
    aggregate = vi.fn(() => of([] as LibraryAggregateBucket[]));

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {aggregate}},
        {provide: LibraryFilterService, useValue: {selectedLibrary}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('requests the authors aggregate broken down by read_status, scoped to the selected library', async () => {
    selectedLibrary.set(4);

    TestBed.runInInjectionContext(() => new TopItemsChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(aggregate).toHaveBeenCalledWith('authors', 4, 'read_status');
  });

  it('re-fetches with the publisher field (singular) when the data type switches to publishers', async () => {
    aggregate.mockReturnValue(of([]));
    const component = TestBed.runInInjectionContext(() => new TopItemsChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));
    component.selectedDataType = component.dataTypeOptions.find(o => o.value === 'publishers')!;
    component.onDataTypeChange();
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(aggregate).toHaveBeenCalledWith('publisher', null, 'read_status');
  });

  it('builds a stacked bar dataset per read-status breakdown', async () => {
    aggregate.mockReturnValue(of([
      {value: 'Alice', count: 3, breakdown: [{value: 'READ', count: 2}, {value: 'UNREAD', count: 1}]},
      {value: 'Bob', count: 1, breakdown: [{value: 'READING', count: 1}]},
    ] as LibraryAggregateBucket[]));

    const component = TestBed.runInInjectionContext(() => new TopItemsChartComponent());
    await new Promise(resolve => setTimeout(resolve, 0));
    let chartData: {labels?: unknown; datasets: {data: unknown}[]} | undefined;
    component.chartData$.subscribe(d => chartData = d);

    expect(chartData?.labels).toEqual(['Alice', 'Bob']);
    expect(component.totalItems).toBe(2);
    expect(component.totalBooks).toBe(4);
  });
});
