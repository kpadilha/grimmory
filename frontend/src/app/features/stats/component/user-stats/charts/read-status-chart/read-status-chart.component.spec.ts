import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {UserStatsService, type BookDistributionsResponse} from '../../../../../settings/user-management/user-stats.service';
import {ReadStatusChartComponent} from './read-status-chart.component';

describe('ReadStatusChartComponent', () => {
  let getBookDistributions: ReturnType<typeof vi.fn>;

  const translate = vi.fn((key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key
  );

  function distributions(statusDistribution: BookDistributionsResponse['statusDistribution']): BookDistributionsResponse {
    return {ratingDistribution: [], progressDistribution: [], statusDistribution};
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
    return TestBed.runInInjectionContext(() => new ReadStatusChartComponent());
  }

  it('sources status counts from the book-distributions endpoint, sorted by count', () => {
    getBookDistributions.mockReturnValue(of(distributions([
      {status: 'READ', count: 3},
      {status: 'READING', count: 7},
      {status: 'PAUSED', count: 1},
    ])));

    const component = createComponent();
    const chartData = component.chartData();

    expect(getBookDistributions).toHaveBeenCalled();
    expect(chartData.datasets[0]?.data).toEqual([7, 3, 1]);
    expect(chartData.datasets[0]?.backgroundColor).toEqual(['#17a2b8', '#28a745', '#fd7e14']);
  });

  it('falls back to UNSET colouring for an unrecognised status value', () => {
    getBookDistributions.mockReturnValue(of(distributions([{status: 'BOGUS', count: 2}])));

    const component = createComponent();
    const chartData = component.chartData();

    expect(chartData.datasets[0]?.data).toEqual([2]);
    expect(chartData.datasets[0]?.backgroundColor).toEqual(['#343a40']);
  });
});
