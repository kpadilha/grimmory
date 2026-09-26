import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {UserStatsService, type BookDistributionsResponse} from '../../../../../settings/user-management/user-stats.service';
import {ReadingProgressChartComponent} from './reading-progress-chart.component';

describe('ReadingProgressChartComponent', () => {
  const chartColors = ['#6c757d', '#ffc107', '#fd7e14', '#17a2b8', '#6f42c1', '#28a745'];

  const translate = vi.fn((key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key
  );
  let getBookDistributions: ReturnType<typeof vi.fn>;

  const serverBuckets: BookDistributionsResponse['progressDistribution'] = [
    {range: 'Not Started', min: 0, max: 0, count: 1},
    {range: 'Just Started', min: 1, max: 25, count: 2},
    {range: 'Getting Into It', min: 26, max: 50, count: 2},
    {range: 'Halfway Through', min: 51, max: 75, count: 2},
    {range: 'Almost Done', min: 76, max: 99, count: 2},
    {range: 'Completed', min: 100, max: 100, count: 1},
  ];

  function distributions(progressDistribution: BookDistributionsResponse['progressDistribution']): BookDistributionsResponse {
    return {ratingDistribution: [], progressDistribution, statusDistribution: []};
  }

  beforeEach(() => {
    getBookDistributions = vi.fn(() => of(distributions([])));
    translate.mockClear();

    TestBed.configureTestingModule({
      providers: [
        {provide: UserStatsService, useValue: {getBookDistributions}},
        {provide: TranslocoService, useValue: {translate}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  function createComponent() {
    return TestBed.runInInjectionContext(() => new ReadingProgressChartComponent());
  }

  it('derives percentage-range labels from the server bucket min/max, preserving fixed order', () => {
    getBookDistributions.mockReturnValue(of(distributions(serverBuckets)));

    const component = createComponent();
    const chartData = component.chartData();

    expect(getBookDistributions).toHaveBeenCalled();
    expect(chartData.labels).toEqual(['0%', '1-25%', '26-50%', '51-75%', '76-99%', '100%']);
    expect(chartData.datasets[0]?.data).toEqual([1, 2, 2, 2, 2, 1]);
    expect(chartData.datasets[0]?.backgroundColor).toEqual(chartColors);
  });

  it('builds translated tooltip titles and labels with mapped descriptions and singular-plural output', () => {
    const component = createComponent();
    const callbacks = component.chartOptions?.plugins?.tooltip?.callbacks as
      | {
        title?: (context: {label?: string}[]) => string;
        label?: (context: {parsed: number; dataset: {data: number[]}; label: string}) => string;
      }
      | undefined;

    translate.mockClear();

    const singularLabel = callbacks?.label?.({parsed: 1, dataset: {data: [1, 4]}, label: '51-75%'});
    const pluralLabel = callbacks?.label?.({parsed: 4, dataset: {data: [1, 4]}, label: '100%'});

    expect(singularLabel).toBe(
      'statsUser.readingProgress.tooltipLabel:{"value":1,"plural":"","description":"statsUser.readingProgress.halfwayThrough","percentage":"20.0"}'
    );
    expect(pluralLabel).toBe(
      'statsUser.readingProgress.tooltipLabel:{"value":4,"plural":"s","description":"statsUser.readingProgress.completed","percentage":"80.0"}'
    );
  });
});
