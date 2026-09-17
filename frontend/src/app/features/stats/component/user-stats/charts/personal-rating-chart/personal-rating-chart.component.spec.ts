import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {UserStatsService, type BookDistributionsResponse} from '../../../../../settings/user-management/user-stats.service';
import {PersonalRatingChartComponent} from './personal-rating-chart.component';

describe('PersonalRatingChartComponent', () => {
  const allRatingLabels = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10'];

  const translate = vi.fn((key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key
  );
  let getBookDistributions: ReturnType<typeof vi.fn>;

  function distributions(ratingDistribution: BookDistributionsResponse['ratingDistribution']): BookDistributionsResponse {
    return {ratingDistribution, progressDistribution: [], statusDistribution: []};
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
    return TestBed.runInInjectionContext(() => new PersonalRatingChartComponent());
  }

  it('reads pre-aggregated rating buckets from the book-distributions endpoint', () => {
    getBookDistributions.mockReturnValue(of(distributions([
      {rating: 1, count: 1},
      {rating: 2, count: 2},
      {rating: 10, count: 3},
    ])));

    const component = createComponent();
    const chartData = component.chartData();

    expect(getBookDistributions).toHaveBeenCalled();
    expect(chartData.labels).toEqual(allRatingLabels);
    expect(chartData.datasets[0]?.data).toEqual([1, 2, 0, 0, 0, 0, 0, 0, 0, 3]);
  });

  it('retains zero-count buckets for missing rating ranges', () => {
    getBookDistributions.mockReturnValue(of(distributions([{rating: 7, count: 1}])));

    const component = createComponent();
    const chartData = component.chartData();

    expect(chartData.labels).toEqual(allRatingLabels);
    expect(chartData.datasets[0]?.data).toEqual([0, 0, 0, 0, 0, 0, 1, 0, 0, 0]);
  });

  it('builds tooltip title and singular/plural labels via translation callbacks', () => {
    const component = createComponent();
    const callbacks = component.chartOptions?.plugins?.tooltip?.callbacks as
      | {
        title?: (context: {label?: string}[]) => string;
        label?: (context: {parsed: {y: number}}) => string;
      }
      | undefined;

    translate.mockClear();

    const title = callbacks?.title?.([{label: '8'}]);
    const singularLabel = callbacks?.label?.({parsed: {y: 1}});
    const pluralLabel = callbacks?.label?.({parsed: {y: 4}});

    expect(title).toBe('statsUser.personalRating.tooltipTitle:{"label":"8"}');
    expect(singularLabel).toBe('statsUser.personalRating.tooltipBook:{"value":1}');
    expect(pluralLabel).toBe('statsUser.personalRating.tooltipBooks:{"value":4}');
  });

  it('produces zero-filled buckets for an empty distribution response', () => {
    getBookDistributions.mockReturnValue(of(distributions([])));

    const component = createComponent();
    const chartData = component.chartData();

    expect(chartData.labels).toEqual(allRatingLabels);
    expect(chartData.datasets[0]?.data).toEqual([0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
  });
});
