import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService} from '@jsverse/transloco';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService, type LibraryAggregateBucket} from '../../service/library-stats.service';
import {LanguageChartComponent} from './language-chart.component';

describe('LanguageChartComponent', () => {
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
        {provide: TranslocoService, useValue: {translate, getActiveLang: () => 'en', langChanges$: of('en')}},
      ],
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  function createComponent(): LanguageChartComponent {
    return TestBed.runInInjectionContext(() => new LanguageChartComponent());
  }

  it('requests the language aggregate scoped to the selected library', async () => {
    selectedLibrary.set(1);
    aggregate.mockReturnValue(of([]));

    createComponent();
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(aggregate).toHaveBeenCalledWith('language', 1);
  });

  it('re-merges server buckets under one canonical language via the resolver', async () => {
    aggregate.mockReturnValue(of([
      {value: 'en', count: 2},
      {value: 'eng', count: 1},
      {value: 'English', count: 1},
      {value: 'spa', count: 2},
      {value: 'klingon', count: 1},
    ]));

    const component = createComponent();
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(component.booksWithLanguage()).toBe(7);
    expect(component.languageStats()).toEqual([
      {language: 'en', displayName: 'English', count: 4, percentage: (4 / 7) * 100},
      {language: 'es', displayName: 'Spanish', count: 2, percentage: (2 / 7) * 100},
      {language: 'klingon', displayName: 'Klingon', count: 1, percentage: (1 / 7) * 100},
    ]);
  });

  it('keeps only the top fifteen languages', async () => {
    const buckets: LibraryAggregateBucket[] = Array.from({length: 17}, (_, index) => ({
      value: `lang${String(index + 1).padStart(2, '0')}`,
      count: 17 - index,
    }));
    aggregate.mockReturnValue(of(buckets));

    const component = createComponent();
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(component.languageStats().length).toBe(15);
    expect(component.languageStats()[0]).toEqual(expect.objectContaining({count: 17}));
  });

  it('formats the tooltip callback from computed chart data without a live Chart.js instance', async () => {
    aggregate.mockReturnValue(of([
      {value: 'en', count: 3},
      {value: 'spa', count: 2},
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
      label: 'English',
    })).toBe('statsLibrary.language.tooltipLabel|label=English|value=3|percentage=60.0');
  });
});
