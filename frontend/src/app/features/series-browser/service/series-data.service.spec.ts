import {HttpTestingController} from '@angular/common/http/testing';
import {Injectable, inject} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {injectInfiniteQuery} from '@tanstack/angular-query-experimental';
import {afterEach, describe, expect, it, vi} from 'vitest';

import {API_CONFIG} from '../../../core/config/api-config';
import {createAuthServiceStub, createQueryClientHarness, flushSignalAndQueryEffects, flushQueryAsync} from '../../../core/testing/query-testing';
import {AuthService} from '../../../shared/service/auth.service';
import {ReadStatus} from '../../book/model/book.model';
import {SeriesDataService} from './series-data.service';

const PARAMS = {sort: 'name-asc', query: '', status: 'all'};

@Injectable()
class InfiniteSeriesQueryHost {
  private readonly series = inject(SeriesDataService);
  readonly query = injectInfiniteQuery(() => this.series.infinitePage(PARAMS));
}

function seriesDataServiceProviders() {
  return [
    ...createQueryClientHarness().providers,
    SeriesDataService,
    InfiniteSeriesQueryHost,
    {provide: AuthService, useValue: createAuthServiceStub()},
  ];
}

describe('SeriesDataService', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('pages the series summary endpoint instead of loading the whole collection at once', async () => {
    TestBed.configureTestingModule({providers: seriesDataServiceProviders()});

    const host = TestBed.inject(InfiniteSeriesQueryHost);
    const httpTestingController = TestBed.inject(HttpTestingController);
    flushSignalAndQueryEffects();

    // SeriesDataService also runs the sidebar's series-count facet query eagerly - not this
    // test's concern, but it must be drained for httpTestingController.verify() to pass.
    httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/facets')).flush({facets: []});

    const firstRequest = httpTestingController.expectOne(
      req => req.url.endsWith('/api/v1/books/series/summary') && req.params.get('page') === '0',
    );
    expect(firstRequest.request.method).toBe('GET');
    expect(firstRequest.request.params.get('size')).toBe('30');
    expect(firstRequest.request.params.get('sort')).toBe('name-asc');
    firstRequest.flush({
      content: [{
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
      }],
      page: {number: 0, size: 30, totalElements: 40, totalPages: 2, cursor: null},
      links: [
        {rel: ['self'], href: '/api/v1/books/series/summary?page=0&size=30&sort=name-asc', type: 'application/json'},
        {rel: ['next'], href: '/api/v1/books/series/summary?page=1&size=30&sort=name-asc', type: 'application/json'},
      ],
    });
    await vi.waitFor(() => expect(host.query.isSuccess()).toBe(true));

    const firstPage = host.query.data()?.pages[0];
    expect(firstPage?.page.totalElements).toBe(40);
    expect(firstPage?.content).toHaveLength(1);
    expect(firstPage?.content[0]).toMatchObject({
      seriesName: 'Alpha',
      bookCount: 2,
      readCount: 1,
      progress: 0.5,
      seriesStatus: ReadStatus.PARTIALLY_READ,
      nextUnreadBookId: 1,
    });
    expect(firstPage?.content[0].authors).toEqual(['Bert', 'Cy', 'Ada']);
    expect(firstPage?.content[0].coverBooks.map(book => book.id)).toEqual([2, 1]);
    expect(host.query.hasNextPage()).toBe(true);

    const nextPromise = host.query.fetchNextPage();
    const nextRequest = httpTestingController.expectOne(
      `${API_CONFIG.BASE_URL}/api/v1/books/series/summary?page=1&size=30&sort=name-asc`,
    );
    nextRequest.flush({
      content: [{
        seriesName: 'Beta',
        bookCount: 1,
        readCount: 0,
        progress: 0,
        seriesStatus: 'UNREAD',
        authors: [],
        categories: [],
        coverBooks: [],
      }],
      page: {number: 1, size: 30, totalElements: 40, totalPages: 2, cursor: null},
      links: [{rel: ['self'], href: '/api/v1/books/series/summary?page=1&size=30&sort=name-asc', type: 'application/json'}],
    });
    const nextResult = await nextPromise;
    await flushQueryAsync();

    expect(nextResult.data?.pages.flatMap(page => page.content.map(series => series.seriesName)))
      .toEqual(['Alpha', 'Beta']);
    expect(host.query.hasNextPage()).toBe(false);

    httpTestingController.verify();
  });

  it('derives the sidebar series count from the series facet, never the full collection', async () => {
    TestBed.configureTestingModule({providers: seriesDataServiceProviders()});

    const service = TestBed.inject(SeriesDataService);
    const httpTestingController = TestBed.inject(HttpTestingController);
    flushSignalAndQueryEffects();

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
