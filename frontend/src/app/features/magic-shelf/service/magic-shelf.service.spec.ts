import {HttpTestingController} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {createAuthServiceStub, createQueryClientHarness, flushSignalAndQueryEffects, flushQueryAsync} from '../../../core/testing/query-testing';
import {AuthService} from '../../../shared/service/auth.service';
import type {GroupRule} from '../component/magic-shelf-component';
import {BookService} from '../../book/service/book.service';
import {BookRuleEvaluatorService} from './book-rule-evaluator.service';
import {MagicShelfService, type MagicShelf} from './magic-shelf.service';

function buildMagicShelf(overrides: Partial<MagicShelf> = {}): MagicShelf {
  return {
    id: 1,
    name: 'Favorites',
    icon: 'star',
    iconType: 'LUCIDE',
    filterJson: JSON.stringify(buildGroupRule()),
    isPublic: false,
    ...overrides,
  };
}

function buildGroupRule(overrides: Partial<GroupRule> = {}): GroupRule {
  return {
    name: 'Favorites',
    type: 'group',
    join: 'and',
    rules: [],
    ...overrides,
  };
}

async function flushShelvesQuery(): Promise<void> {
  await flushQueryAsync();
}

// Each magic shelf badge is its own size-1 filtered page request (never the full collection) -
// flush them all once the shelves are hydrated, reading the shelf id back off the facet param.
function flushMagicShelfCountRequests(httpTestingController: HttpTestingController, counts: Record<number, number> = {}): void {
  for (const req of httpTestingController.match(r => r.url.endsWith('/api/v1/books/page'))) {
    const facetParam = req.request.params.getAll('facet') ?? [];
    const magicId = Number(facetParam.find(f => f.startsWith('shelf:magic:'))?.split(':')[2]);
    req.flush({
      content: [],
      page: {number: 0, size: 1, totalElements: counts[magicId] ?? 0, totalPages: 1, cursor: ''},
      links: [],
    });
  }
}

describe('MagicShelfService', () => {
  let service: MagicShelfService;
  let httpTestingController: HttpTestingController;
  let authService: ReturnType<typeof createAuthServiceStub>;
  let queryClientHarness: ReturnType<typeof createQueryClientHarness>;
  let bookService: {
    books: ReturnType<typeof vi.fn>;
  };
  let ruleEvaluatorService: {
    evaluateGroup: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    authService = createAuthServiceStub();
    queryClientHarness = createQueryClientHarness();
    queryClientHarness.queryClient.setDefaultOptions({
      queries: {
        retry: false,
      },
    });

    bookService = {
      books: vi.fn(() => []),
    };
    ruleEvaluatorService = {
      evaluateGroup: vi.fn(() => false),
    };

    vi.spyOn(queryClientHarness.queryClient, 'invalidateQueries').mockResolvedValue(undefined);
    vi.spyOn(queryClientHarness.queryClient, 'removeQueries').mockImplementation(() => undefined);

    TestBed.configureTestingModule({
      providers: [
        ...queryClientHarness.providers,
        MagicShelfService,
        {provide: AuthService, useValue: authService},
        {provide: BookService, useValue: bookService},
        {provide: BookRuleEvaluatorService, useValue: ruleEvaluatorService},
      ],
    });

    service = TestBed.inject(MagicShelfService);
    httpTestingController = TestBed.inject(HttpTestingController);
    flushSignalAndQueryEffects();
  });

  afterEach(() => {
    httpTestingController.verify();
    queryClientHarness.queryClient.clear();
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('eagerly fetches shelves and hydrates the computed shelves signal', async () => {
    const shelves = [
      buildMagicShelf({id: 1, name: 'Reading'}),
      buildMagicShelf({id: 2, name: 'Finished', isPublic: true}),
    ];

    expect(service.shelves()).toEqual([]);
    expect(service.isShelvesLoading()).toBe(true);
    expect(service.shelvesError()).toBeNull();

    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/magic-shelves'));
    expect(request.request.method).toBe('GET');
    request.flush(shelves);
    await flushShelvesQuery();
    flushMagicShelfCountRequests(httpTestingController);

    expect(service.shelves()).toEqual(shelves);
    expect(service.isShelvesLoading()).toBe(false);
    expect(service.shelvesError()).toBeNull();
  });

  it('removes shelf queries when the auth token is cleared', async () => {
    const removeQueriesSpy = vi.spyOn(queryClientHarness.queryClient, 'removeQueries').mockImplementation(() => undefined);

    httpTestingController.expectOne(req => req.url.endsWith('/api/magic-shelves')).flush([
      buildMagicShelf({id: 7, name: 'Cached'}),
    ]);
    await flushShelvesQuery();
    flushMagicShelfCountRequests(httpTestingController);

    authService.token.set(null);
    flushSignalAndQueryEffects();

    expect(removeQueriesSpy).toHaveBeenCalledWith({queryKey: ['magicShelves']});
  });

  it('invalidates shelf queries after save and serializes the group payload', () => {
    const invalidateQueriesSpy = vi.spyOn(queryClientHarness.queryClient, 'invalidateQueries').mockResolvedValue(undefined);
    const group = buildGroupRule({
      rules: [
        {
          field: 'title',
          operator: 'contains',
          value: 'magic',
        },
      ],
    });

    httpTestingController.expectOne(req => req.url.endsWith('/api/magic-shelves')).flush([]);

    service.saveShelf({
      name: 'Magic',
      icon: 'zap',
      iconType: 'LUCIDE',
      group,
      isPublic: true,
    }).subscribe();

    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/magic-shelves'));
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(expect.objectContaining({
      name: 'Magic',
      icon: 'zap',
      iconType: 'LUCIDE',
      filterJson: JSON.stringify(group),
      isPublic: true,
    }));
    request.flush(buildMagicShelf({id: 11, name: 'Magic', filterJson: JSON.stringify(group), isPublic: true}));

    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['magicShelves'], exact: true});
  });

  it('invalidates shelf queries after delete', () => {
    const invalidateQueriesSpy = vi.spyOn(queryClientHarness.queryClient, 'invalidateQueries').mockResolvedValue(undefined);

    httpTestingController.expectOne(req => req.url.endsWith('/api/magic-shelves')).flush([]);

    service.deleteShelf(11).subscribe();

    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/magic-shelves/11'));
    expect(request.request.method).toBe('DELETE');
    request.flush(null);

    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['magicShelves'], exact: true});
  });

  it('finds shelves by id from the hydrated query state', async () => {
    const readingShelf = buildMagicShelf({id: 1, name: 'Reading'});
    const archiveShelf = buildMagicShelf({id: 2, name: 'Archive'});

    httpTestingController.expectOne(req => req.url.endsWith('/api/magic-shelves')).flush([
      readingShelf,
      archiveShelf,
    ]);
    await flushShelvesQuery();
    flushMagicShelfCountRequests(httpTestingController);

    expect(service.findShelfById(2)).toEqual(archiveShelf);
    expect(service.findShelfById(999)).toBeUndefined();
  });

  it('derives sidebar badge counts from a small filtered page per magic shelf, not the full collection', async () => {
    httpTestingController.expectOne(req => req.url.endsWith('/api/magic-shelves')).flush([
      buildMagicShelf({id: 1, name: 'Reading'}),
      buildMagicShelf({id: 2, name: 'Finished'}),
    ]);
    await flushShelvesQuery();
    flushMagicShelfCountRequests(httpTestingController, {1: 4, 2: 0});
    await flushShelvesQuery();

    expect(service.bookCountByMagicShelfId()).toEqual(new Map([[1, 4], [2, 0]]));
    expect(bookService.books).not.toHaveBeenCalled();
    expect(ruleEvaluatorService.evaluateGroup).not.toHaveBeenCalled();
  });
});
