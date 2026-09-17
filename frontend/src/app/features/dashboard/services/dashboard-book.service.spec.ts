import {HttpTestingController} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, describe, expect, it} from 'vitest';

import {createAuthServiceStub, createQueryClientHarness, flushSignalAndQueryEffects, flushQueryAsync} from '../../../core/testing/query-testing';
import {AuthService} from '../../../shared/service/auth.service';
import {MagicShelfService} from '../../magic-shelf/service/magic-shelf.service';
import type {BookSummary} from '../../book/data/book-response.models';
import type {DashboardConfig, ScrollerConfig} from '../models/dashboard-config.model';
import {ScrollerType} from '../models/dashboard-config.model';
import {DashboardConfigService} from './dashboard-config.service';
import {DashboardBookService} from './dashboard-book.service';

function scroller(type: ScrollerType, overrides: Partial<ScrollerConfig> = {}): ScrollerConfig {
  return {
    id: type,
    type,
    title: 'Scroller',
    enabled: true,
    order: 1,
    maxItems: 5,
    ...overrides,
  };
}

function configWith(scrollers: ScrollerConfig[]): DashboardConfig {
  return {scrollers};
}

function buildBookSummary(overrides: Partial<BookSummary> = {}): BookSummary {
  return {
    id: 1,
    libraryId: 1,
    libraryName: 'Main',
    ...overrides,
  } as BookSummary;
}

function pageResponse(content: BookSummary[]) {
  return {
    content,
    page: {number: 0, size: content.length, totalElements: content.length, totalPages: 1, cursor: ''},
    links: [],
  };
}

describe('DashboardBookService', () => {
  let httpTestingController: HttpTestingController;
  let queryClientHarness: ReturnType<typeof createQueryClientHarness>;

  function setup(config: DashboardConfig, shelves: {id?: number | null; name: string}[] = []): DashboardBookService {
    queryClientHarness = createQueryClientHarness();
    queryClientHarness.queryClient.setDefaultOptions({queries: {retry: false}});
    const authService = createAuthServiceStub();

    TestBed.configureTestingModule({
      providers: [
        ...queryClientHarness.providers,
        DashboardBookService,
        {provide: AuthService, useValue: authService},
        {provide: DashboardConfigService, useValue: {config: () => config}},
        {provide: MagicShelfService, useValue: {shelves: () => shelves}},
        // No BookService provider - proves the dashboard no longer reads the full collection.
      ],
    });

    const service = TestBed.inject(DashboardBookService);
    httpTestingController = TestBed.inject(HttpTestingController);
    flushSignalAndQueryEffects();
    return service;
  }

  afterEach(() => {
    httpTestingController?.verify();
    queryClientHarness?.queryClient.clear();
    TestBed.resetTestingModule();
  });

  it('constructs without a BookService provider, proving scrollers never read the full collection', () => {
    expect(() => setup(configWith([scroller(ScrollerType.LATEST_ADDED)]))).not.toThrow();
    httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page')).flush(pageResponse([]));
  });

  it('requests LATEST_ADDED as a small page sorted by addedOn desc', () => {
    setup(configWith([scroller(ScrollerType.LATEST_ADDED, {maxItems: 7})]));

    const req = httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page'));
    expect(req.request.params.get('sort')).toBe('-addedOn');
    expect(req.request.params.get('size')).toBe('7');
    req.flush(pageResponse([]));
  });

  it('requests RANDOM excluding already-read statuses via facetLogic=not', () => {
    setup(configWith([scroller(ScrollerType.RANDOM, {maxItems: 8})]));

    const req = httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page'));
    expect(req.request.params.get('facet_logic')).toBe('not');
    // Facet values are alphabetised by normalizeFacetValueMap regardless of declaration order.
    expect(req.request.params.getAll('facet')).toEqual([
      'read_status:ABANDONED', 'read_status:PARTIALLY_READ', 'read_status:PAUSED',
      'read_status:READ', 'read_status:READING', 'read_status:WONT_READ',
    ]);
    expect(req.request.params.get('sort')).toBe('random');
    expect(req.request.params.get('size')).toBe('8');
    req.flush(pageResponse([]));
  });

  it('requests MAGIC_SHELF via the shelf:magic facet when the shelf exists', () => {
    setup(
      configWith([scroller(ScrollerType.MAGIC_SHELF, {maxItems: 6, magicShelfId: 42})]),
      [{id: 42, name: 'Favourites'}],
    );

    const req = httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page'));
    expect(req.request.params.getAll('facet')).toEqual(['shelf:magic:42']);
    expect(req.request.params.get('sort')).toBe('title');
    expect(req.request.params.get('size')).toBe('6');
    req.flush(pageResponse([]));
  });

  it('maps an explicit magic-shelf sort field/direction to the server sort term', () => {
    setup(
      configWith([scroller(ScrollerType.MAGIC_SHELF, {maxItems: 6, magicShelfId: 42, sortField: 'lastReadTime', sortDirection: 'desc'})]),
      [{id: 42, name: 'Favourites'}],
    );

    const req = httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page'));
    expect(req.request.params.get('sort')).toBe('-lastReadTime');
    req.flush(pageResponse([]));
  });

  it('never fetches a MAGIC_SHELF scroller pointing at a shelf that no longer exists', () => {
    const service = setup(
      configWith([scroller(ScrollerType.MAGIC_SHELF, {id: 'stale', maxItems: 6, magicShelfId: 999})]),
      [{id: 42, name: 'Favourites'}],
    );

    httpTestingController.expectNone(r => r.url.endsWith('/api/v1/books/page'));
    expect(service.scrollerBooksMap().get('stale')).toEqual([]);
  });

  it('dedupes LAST_READ and LAST_LISTENED into one lastReadTime request and splits results by progress format', async () => {
    const service = setup(configWith([
      scroller(ScrollerType.LAST_READ, {id: 'read', maxItems: 2}),
      scroller(ScrollerType.LAST_LISTENED, {id: 'listen', maxItems: 2}),
    ]));

    const requests = httpTestingController.match(r => r.url.endsWith('/api/v1/books/page'));
    expect(requests).toHaveLength(1);

    const req = requests[0];
    expect(req.request.params.getAll('facet')).toEqual([
      'read_status:PAUSED', 'read_status:READING', 'read_status:RE_READING',
    ]);
    expect(req.request.params.get('sort')).toBe('-lastReadTime');
    expect(req.request.params.get('size')).toBe('10');

    req.flush(pageResponse([
      buildBookSummary({id: 1, epubProgress: {cfi: null, href: null, contentSourceProgressPercent: null, ttsPositionCfi: null, percentage: 40}}),
      buildBookSummary({id: 2, audiobookProgress: {positionMs: 1000, trackIndex: 0, trackPositionMs: 0, percentage: 20}}),
      buildBookSummary({id: 3}),
    ]));
    await flushQueryAsync();

    expect(service.scrollerBooksMap().get('read')?.map(book => book.id)).toEqual([1]);
    expect(service.scrollerBooksMap().get('listen')?.map(book => book.id)).toEqual([2]);
  });
});
