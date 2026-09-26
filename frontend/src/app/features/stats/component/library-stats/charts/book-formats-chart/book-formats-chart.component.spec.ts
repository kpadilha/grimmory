import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService, type LibraryAggregateBucket} from '../../service/library-stats.service';
import {BookFormatsChartComponent} from './book-formats-chart.component';

const EXPECTED_FORMAT_COLORS = ['#0D9488', '#E11D48', '#6B7280', '#6B7280'];

describe('BookFormatsChartComponent', () => {
  const selectedLibrary = signal<number | null>(null);
  let aggregate: ReturnType<typeof vi.fn>;
  const translate = vi.fn((key: string, params?: Record<string, number | string>) =>
    params ? `${key}|${Object.entries(params).map(([n, v]) => `${n}=${v}`).join('|')}` : key
  );

  beforeEach(() => {
    selectedLibrary.set(null);
    aggregate = vi.fn(() => of([] as LibraryAggregateBucket[]));
    translate.mockClear();

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {aggregate}},
        {provide: LibraryFilterService, useValue: {selectedLibrary}},
        {provide: TranslocoService, useValue: {translate}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  function createComponent(): BookFormatsChartComponent {
    return TestBed.runInInjectionContext(() => new BookFormatsChartComponent());
  }

  it('requests the file_type aggregate scoped to the selected library and sorts by count', async () => {
    selectedLibrary.set(1);
    aggregate.mockReturnValue(of([
      {value: 'PDF', count: 3},
      {value: 'EPUB', count: 4},
      {value: 'Unknown', count: 1},
    ]));

    const component = createComponent();
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(aggregate).toHaveBeenCalledWith('file_type', 1);
    expect(component.totalBooks()).toBe(8);
    expect(component.formatStats()).toEqual([
      {format: 'EPUB', count: 4, percentage: 50},
      {format: 'PDF', count: 3, percentage: 37.5},
      {format: 'Unknown', count: 1, percentage: 12.5},
    ]);
  });

  it('builds deterministic pie chart labels, counts, and colors from computed format stats', async () => {
    aggregate.mockReturnValue(of([
      {value: 'EPUB', count: 4},
      {value: 'PDF', count: 3},
      {value: 'AUDIOBOOK', count: 2},
      {value: 'Unknown', count: 1},
    ]));

    const component = createComponent();
    await new Promise(resolve => setTimeout(resolve, 0));
    const chartData = component.chartData();
    const dataset = chartData.datasets[0];

    expect(chartData.labels).toEqual(['EPUB', 'PDF', 'AUDIOBOOK', 'Unknown']);
    expect(dataset?.data).toEqual([4, 3, 2, 1]);
    expect(dataset?.backgroundColor).toEqual(EXPECTED_FORMAT_COLORS);
  });

  it('formats the tooltip callback from computed chart data without a live Chart.js instance', async () => {
    aggregate.mockReturnValue(of([
      {value: 'EPUB', count: 3},
      {value: 'PDF', count: 1},
    ]));

    const component = createComponent();
    await new Promise(resolve => setTimeout(resolve, 0));
    const chartData = component.chartData();
    const dataset = chartData.datasets[0];
    const tooltipLabel = component.chartOptions?.plugins?.tooltip?.callbacks?.label as
      (context: {parsed: number; dataset: {data: number[]}; label: string}) => string;

    expect(tooltipLabel({
      parsed: 3,
      dataset: {data: dataset?.data as number[]},
      label: 'EPUB',
    })).toBe('statsLibrary.bookFormats.tooltipLabel|label=EPUB|value=3|percentage=75.0');
  });
});
