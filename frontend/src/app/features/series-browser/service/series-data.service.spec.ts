import {TestBed} from '@angular/core/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {afterEach, describe, expect, it} from 'vitest';

import {createAuthServiceStub, createQueryClientHarness, flushQueryAsync, flushSignalAndQueryEffects} from '../../../core/testing/query-testing';
import {AuthService} from '../../../shared/service/auth.service';
import {ReadStatus} from '../../book/model/book.model';
import {SeriesDataService} from './series-data.service';

function seriesDataServiceProviders() {
  return [
    ...createQueryClientHarness().providers,
    SeriesDataService,
    {provide: AuthService, useValue: createAuthServiceStub()},
  ];
}

describe('SeriesDataService', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('maps the series summary endpoint response, never the full book collection', async () => {
    TestBed.configureTestingModule({providers: seriesDataServiceProviders()});

    const service = TestBed.inject(SeriesDataService);
    const httpTestingController = TestBed.inject(HttpTestingController);
    flushSignalAndQueryEffects();

    const summaryRequest = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/series/summary'));
    expect(summaryRequest.request.method).toBe('GET');
    summaryRequest.flush([{
      seriesName: 'Alpha',
      bookCount: 2,
      readCount: 1,
      progress: 0.5,
      seriesStatus: 'PARTIALLY_READ',
      nextUnreadBookId: 1,
      lastReadTime: '2026-03-26T10:00:00Z',
      addedOn: '2026-03-26T09:00:00Z',
      authors: ['Bert', 'Cy', 'Ada'],
      categories: ['Fantasy', 'Sci-Fi'],
      coverBooks: [
        {bookId: 2, bookType: 'EPUB', coverUpdatedOn: '2026-03-26T09:00:00Z'},
        {bookId: 1, bookType: 'EPUB', coverUpdatedOn: '2026-03-25T09:00:00Z'},
      ],
    }]);
    await flushQueryAsync();

    const facetsRequest = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/facets'));
    facetsRequest.flush({facets: []});
    await flushQueryAsync();

    const summaries = service.allSeries();
    expect(summaries).toHaveLength(1);
    expect(summaries[0]).toMatchObject({
      seriesName: 'Alpha',
      bookCount: 2,
      readCount: 1,
      progress: 0.5,
      seriesStatus: ReadStatus.PARTIALLY_READ,
      nextUnreadBookId: 1,
      lastReadTime: '2026-03-26T10:00:00Z',
      addedOn: '2026-03-26T09:00:00Z',
    });
    expect(summaries[0].authors).toEqual(['Bert', 'Cy', 'Ada']);
    expect(summaries[0].categories).toEqual(['Fantasy', 'Sci-Fi']);
    expect(summaries[0].coverBooks.map(book => book.id)).toEqual([2, 1]);

    httpTestingController.verify();
  });

  it('derives the sidebar series count from the series facet, never the full collection', async () => {
    TestBed.configureTestingModule({providers: seriesDataServiceProviders()});

    const service = TestBed.inject(SeriesDataService);
    const httpTestingController = TestBed.inject(HttpTestingController);
    flushSignalAndQueryEffects();

    const summaryRequest = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/series/summary'));
    summaryRequest.flush([]);

    const facetsRequest = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/facets'));
    facetsRequest.flush({
      facets: [{
        metadata: {rel: 'facet', key: 'series', title: 'Series'},
        links: [
          {rel: ['facet'], href: '', type: 'application/json', value: 'Alpha', title: 'Alpha', properties: {numberOfItems: 2}},
          {rel: ['facet'], href: '', type: 'application/json', value: 'Beta', title: 'Beta', properties: {numberOfItems: 1}},
        ],
      }],
    });
    await flushQueryAsync();

    expect(service.totalSeriesCount()).toBe(2);
    httpTestingController.verify();
  });

  it('reads the exact distinct count above the facet listing cap, not the capped link count', async () => {
    TestBed.configureTestingModule({providers: seriesDataServiceProviders()});

    const service = TestBed.inject(SeriesDataService);
    const httpTestingController = TestBed.inject(HttpTestingController);
    flushSignalAndQueryEffects();

    const summaryRequest = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/series/summary'));
    summaryRequest.flush([]);

    const cappedLinks = Array.from({length: 100}, (_, i) => ({
      rel: ['facet'], href: '', type: 'application/json', value: `Series${i}`, title: `Series${i}`, properties: {numberOfItems: 1},
    }));
    const facetsRequest = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/facets'));
    facetsRequest.flush({
      facets: [{
        metadata: {rel: 'facet', key: 'series', title: 'Series'},
        links: cappedLinks,
        distinctCount: 23731,
      }],
    });
    await flushQueryAsync();

    expect(service.totalSeriesCount()).toBe(23731);
    httpTestingController.verify();
  });
});
