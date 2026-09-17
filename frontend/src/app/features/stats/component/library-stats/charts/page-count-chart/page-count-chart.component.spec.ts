import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService, type LibraryHistogramBucket} from '../../service/library-stats.service';
import {PageCountChartComponent} from './page-count-chart.component';

const EXPECTED_PAGE_RANGE_LABELS = ['0-100', '101-200', '201-300', '301-500', '501-750', '751-1000', '1000+'];
const EXPECTED_PAGE_RANGE_COLORS = ['#06B6D4', '#0EA5E9', '#3B82F6', '#6366F1', '#8B5CF6', '#A855F7', '#D946EF'];

describe('PageCountChartComponent', () => {
  const selectedLibrary = signal<number | null>(null);
  let histogram: ReturnType<typeof vi.fn>;
  const translate = vi.fn((key: string, params?: Record<string, number | string>) =>
    params ? `${key}|${Object.entries(params).map(([n, v]) => `${n}=${v}`).join('|')}` : key
  );

  beforeEach(() => {
    selectedLibrary.set(null);
    histogram = vi.fn(() => of([] as LibraryHistogramBucket[]));
    translate.mockClear();

    TestBed.configureTestingModule({
      providers: [
        {provide: LibraryStatsService, useValue: {histogram}},
        {provide: LibraryFilterService, useValue: {selectedLibrary}},
        {provide: TranslocoService, useValue: {translate}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  function createComponent(): PageCountChartComponent {
    return TestBed.runInInjectionContext(() => new PageCountChartComponent());
  }

  it('requests the page_count histogram scoped to the selected library', async () => {
    selectedLibrary.set(1);
    histogram.mockReturnValue(of([
      {range: '0-100', min: 0, max: 100, count: 1},
      {range: '751-1000', min: 751, max: 1000, count: 1},
    ]));

    const component = createComponent();
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(histogram).toHaveBeenCalledWith('page_count', 1);
    expect(component.totalBooks()).toBe(2);
  });

  it('builds deterministic bar chart labels, counts, and colors from the server histogram', async () => {
    histogram.mockReturnValue(of([
      {range: '0-100', min: 0, max: 100, count: 2},
      {range: '101-200', min: 101, max: 200, count: 2},
      {range: '201-300', min: 201, max: 300, count: 2},
      {range: '301-500', min: 301, max: 500, count: 2},
      {range: '501-750', min: 501, max: 750, count: 2},
      {range: '751-1000', min: 751, max: 1000, count: 2},
      {range: '1000+', min: 1001, max: 2147483647, count: 1},
    ]));

    const component = createComponent();
    await new Promise(resolve => setTimeout(resolve, 0));
    const chartData = component.chartData();
    const dataset = chartData.datasets[0];

    expect(chartData.labels).toEqual(EXPECTED_PAGE_RANGE_LABELS);
    expect(dataset?.data).toEqual([2, 2, 2, 2, 2, 2, 1]);
    expect(dataset?.backgroundColor).toEqual(EXPECTED_PAGE_RANGE_COLORS);
  });

  it('formats the tooltip title and singular-versus-plural labels without a live Chart.js instance', () => {
    const component = createComponent();
    const tooltipTitle = component.chartOptions?.plugins?.tooltip?.callbacks?.title as (context: {label: string}[]) => string;
    const tooltipLabel = component.chartOptions?.plugins?.tooltip?.callbacks?.label as (context: {parsed: {y: number}}) => string;

    expect(tooltipTitle([{label: '301-500'}])).toBe('statsLibrary.pageCount.tooltipTitle|label=301-500');
    expect(tooltipLabel({parsed: {y: 1}})).toBe('statsLibrary.pageCount.tooltipLabel|value=1');
    expect(tooltipLabel({parsed: {y: 3}})).toBe('statsLibrary.pageCount.tooltipLabelPlural|value=3');
  });
});
