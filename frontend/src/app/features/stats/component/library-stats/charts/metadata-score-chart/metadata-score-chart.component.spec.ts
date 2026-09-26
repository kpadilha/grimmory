import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService, type LibraryHistogramBucket} from '../../service/library-stats.service';
import {MetadataScoreChartComponent} from './metadata-score-chart.component';

const EXPECTED_SCORE_COLORS = ['#16A34A', '#22C55E', '#F59E0B', '#F97316', '#DC2626'];

describe('MetadataScoreChartComponent', () => {
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

  function createComponent(): MetadataScoreChartComponent {
    return TestBed.runInInjectionContext(() => new MetadataScoreChartComponent());
  }

  it('returns empty computed stats when the histogram is empty', () => {
    const component = createComponent();

    expect(component.totalBooks()).toBe(0);
    expect(component.averageScore()).toBe(0);
    expect(component.scoreStats()).toEqual([]);
    expect(component.chartData()).toEqual({labels: [], datasets: []});
  });

  it('requests the metadata_score histogram scoped to the selected library and approximates the average', async () => {
    selectedLibrary.set(1);
    histogram.mockReturnValue(of([
      {range: 'excellent', min: 90, max: 100, count: 1},
      {range: 'good', min: 70, max: 89, count: 2},
      {range: 'fair', min: 50, max: 69, count: 2},
      {range: 'poor', min: 25, max: 49, count: 2},
      {range: 'veryPoor', min: 0, max: 24, count: 2},
    ]));

    const component = createComponent();
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(histogram).toHaveBeenCalledWith('metadata_score', 1);
    expect(component.totalBooks()).toBe(9);
    expect(component.scoreStats()).toEqual([
      {range: 'statsLibrary.metadataScore.excellent', count: 1, percentage: 100 / 9, color: '#16A34A'},
      {range: 'statsLibrary.metadataScore.good', count: 2, percentage: 200 / 9, color: '#22C55E'},
      {range: 'statsLibrary.metadataScore.fair', count: 2, percentage: 200 / 9, color: '#F59E0B'},
      {range: 'statsLibrary.metadataScore.poor', count: 2, percentage: 200 / 9, color: '#F97316'},
      {range: 'statsLibrary.metadataScore.veryPoor', count: 2, percentage: 200 / 9, color: '#DC2626'},
    ]);
  });

  it('builds deterministic doughnut chart labels, counts, and colors from computed score stats', async () => {
    histogram.mockReturnValue(of([
      {range: 'excellent', min: 90, max: 100, count: 1},
      {range: 'good', min: 70, max: 89, count: 2},
      {range: 'fair', min: 50, max: 69, count: 2},
      {range: 'poor', min: 25, max: 49, count: 2},
      {range: 'veryPoor', min: 0, max: 24, count: 2},
    ]));

    const component = createComponent();
    await new Promise(resolve => setTimeout(resolve, 0));
    const chartData = component.chartData();
    const dataset = chartData.datasets[0];

    expect(chartData.labels).toEqual([
      'statsLibrary.metadataScore.excellent',
      'statsLibrary.metadataScore.good',
      'statsLibrary.metadataScore.fair',
      'statsLibrary.metadataScore.poor',
      'statsLibrary.metadataScore.veryPoor',
    ]);
    expect(dataset?.data).toEqual([1, 2, 2, 2, 2]);
    expect(dataset?.backgroundColor).toEqual(EXPECTED_SCORE_COLORS);
  });

  it('formats the tooltip callback from computed chart data without a live Chart.js instance', async () => {
    histogram.mockReturnValue(of([
      {range: 'excellent', min: 90, max: 100, count: 2},
      {range: 'veryPoor', min: 0, max: 24, count: 1},
    ]));

    const component = createComponent();
    await new Promise(resolve => setTimeout(resolve, 0));
    const chartData = component.chartData();
    const dataset = chartData.datasets[0];
    const tooltipLabel = component.chartOptions?.plugins?.tooltip?.callbacks?.label as
      (context: {parsed: number; dataset: {data: number[]}}) => string;

    expect(tooltipLabel({
      parsed: 2,
      dataset: {data: dataset?.data as number[]},
    })).toBe('statsLibrary.metadataScore.tooltipLabel|value=2|percentage=66.7');
  });
});
