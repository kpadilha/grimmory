import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {LibrariesSummaryService} from './libraries-summary.service';
import {LibraryFilterService} from './library-filter.service';
import {LibraryStatsService, type LibrarySummary} from './library-stats.service';

describe('LibrariesSummaryService', () => {
  const selectedLibrary = signal<number | null>(null);
  let summary: ReturnType<typeof vi.fn>;

  function response(overrides: Partial<LibrarySummary>): LibrarySummary {
    return {totalBooks: 0, totalSizeKb: 0, distinctAuthors: 0, distinctSeries: 0, distinctPublishers: 0, ...overrides};
  }

  beforeEach(() => {
    selectedLibrary.set(null);
    summary = vi.fn(() => of(response({})));

    TestBed.configureTestingModule({
      providers: [
        LibrariesSummaryService,
        {provide: LibraryStatsService, useValue: {summary}},
        {provide: LibraryFilterService, useValue: {selectedLibrary}},
      ]
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('returns zeroed totals and zero size for an empty library set', () => {
    const service = TestBed.inject(LibrariesSummaryService);

    expect(service.booksSummary()).toEqual({
      totalBooks: 0,
      totalSizeKb: 0,
      totalAuthors: 0,
      totalSeries: 0,
      totalPublishers: 0,
    });
    expect(service.formattedSize()).toBe('0 KB');
  });

  it('maps the server summary response and formats gigabytes', async () => {
    summary.mockReturnValue(of(response({
      totalBooks: 2,
      totalSizeKb: 1024 + 1024 * 1024,
      distinctAuthors: 3,
      distinctSeries: 1,
      distinctPublishers: 1,
    })));

    const service = TestBed.inject(LibrariesSummaryService);
    await new Promise(resolve => setTimeout(resolve, 0));
    const result = service.booksSummary();

    expect(result).toEqual({
      totalBooks: 2,
      totalSizeKb: 1024 + 1024 * 1024,
      totalAuthors: 3,
      totalSeries: 1,
      totalPublishers: 1,
    });
    expect(service.formattedSize()).toBe('1.00 GB');
    expect(summary).toHaveBeenCalledWith(null);
  });

  it('requests the summary scoped to the selected library', async () => {
    selectedLibrary.set(1);
    summary.mockReturnValue(of(response({totalBooks: 1, totalSizeKb: 2048})));

    const service = TestBed.inject(LibrariesSummaryService);
    await new Promise(resolve => setTimeout(resolve, 0));
    service.booksSummary();

    expect(summary).toHaveBeenCalledWith(1);
  });
});
