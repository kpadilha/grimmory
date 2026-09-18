import {HttpTestingController} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {Router} from '@angular/router';
import {TranslocoService} from '@jsverse/transloco';
import {MessageService} from '@openng/optimus-ui/api';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {firstValueFrom} from 'rxjs';

import {createAuthServiceStub, createQueryClientHarness, flushSignalAndQueryEffects} from '../../../core/testing/query-testing';
import type {Book, BookMetadata} from '../model/book.model';
import {AuthService} from '../../../shared/service/auth.service';
import {BookPatchService} from './book-patch.service';
import {BookSocketService} from './book-socket.service';
import {BookService} from './book.service';

type BuildBookOverrides = Omit<Partial<Book>, 'metadata' | 'shelves'> & {
  metadata?: Partial<BookMetadata>;
};

function buildBook(id: number, overrides: BuildBookOverrides = {}): Book {
  const {metadata, ...bookOverrides} = overrides;

  return {
    id,
    libraryId: 1,
    libraryName: 'Main Library',
    metadata: {
      bookId: id,
      title: `Book ${id}`,
      ...metadata,
    },
    ...bookOverrides,
  };
}

function facetGroupsResponse(groups: Record<string, string[]>) {
  return {
    facets: Object.entries(groups).map(([key, values]) => ({
      metadata: {rel: 'facet', key, title: key},
      links: values.map(value => ({
        rel: ['facet'], href: '', type: 'application/json', value, title: value, properties: {numberOfItems: 1},
      })),
    })),
  };
}

// The sidebar/boot facet counts are eager (cheap, token-gated only) - every test that
// authenticates picks up this request regardless of whether it cares about the counts.
function flushFacetsRequest(httpTestingController: HttpTestingController, groups: Record<string, string[]> = {}): void {
  httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/facets')).flush(facetGroupsResponse(groups));
}

describe('BookService', () => {
  let service: BookService;
  let httpTestingController: HttpTestingController;
  let authService: ReturnType<typeof createAuthServiceStub>;
  let queryClientHarness: ReturnType<typeof createQueryClientHarness>;

  function setup(initialToken: string | null = 'token-123'): void {
    authService = createAuthServiceStub(initialToken);
    queryClientHarness = createQueryClientHarness();
    queryClientHarness.queryClient.setDefaultOptions({
      queries: {
        retry: false,
      },
    });

    TestBed.configureTestingModule({
      providers: [
        ...queryClientHarness.providers,
        BookService,
        {provide: AuthService, useValue: authService},
        {provide: MessageService, useValue: {add: vi.fn()}},
        {provide: Router, useValue: {navigate: vi.fn()}},
        {
          provide: BookSocketService,
          useValue: {
            handleNewlyCreatedBook: vi.fn(),
            handleRemovedBookIds: vi.fn(),
            handleBookUpdate: vi.fn(),
            handleMultipleBookUpdates: vi.fn(),
            handleBookMetadataUpdate: vi.fn(),
            handleMultipleBookCoverPatches: vi.fn(),
          },
        },
        {
          provide: BookPatchService,
          useValue: {
            updateLastReadTime: vi.fn(),
            savePdfProgress: vi.fn(),
            saveCbxProgress: vi.fn(),
            updateDateFinished: vi.fn(),
            resetProgress: vi.fn(),
            updateBookReadStatus: vi.fn(),
            resetPersonalRating: vi.fn(),
            updatePersonalRating: vi.fn(),
            updateBookShelves: vi.fn(),
          },
        },
        {
          provide: TranslocoService,
          useValue: {
            translate: vi.fn((key: string) => key),
          },
        },
      ],
    });

    service = TestBed.inject(BookService);
    httpTestingController = TestBed.inject(HttpTestingController);
    flushSignalAndQueryEffects();
  }

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    httpTestingController?.verify();
    queryClientHarness?.queryClient.clear();
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('never requests the full collection at boot - only the cheap facet counts', () => {
    setup();

    flushFacetsRequest(httpTestingController);
    httpTestingController.expectNone(req => req.url.endsWith('/api/v1/books'));
  });

  // Regression test for the /facets/values full-load that boot-time uniqueMetadata used to
  // trigger (7.7MB, every bookId per value) - autocompletes now search server-side on demand.
  it('never requests the exhaustive facet-values list at boot', () => {
    setup();

    flushFacetsRequest(httpTestingController);
    httpTestingController.expectNone(req => req.url.endsWith('/api/v1/books/facets/values'));
  });

  it('resolves a selection by id from /books/batch, never the full collection', async () => {
    setup();
    flushFacetsRequest(httpTestingController);

    const promise = service.getBooksByIds([2, 999, 1]);
    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/batch'));
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('ids')).toBe('2,999,1');
    httpTestingController.expectNone(req => req.url.endsWith('/api/v1/books') && !req.url.includes('/batch'));

    const response = [buildBook(2), buildBook(1)];
    request.flush(response);

    await expect(promise).resolves.toEqual(response);
  });

  it('returns immediately without a request for an empty selection', async () => {
    setup();
    flushFacetsRequest(httpTestingController);

    await expect(service.getBooksByIds([])).resolves.toEqual([]);
  });

  it('pages /books/page scoped to the series facet for getBooksInSeries, never the full collection', async () => {
    setup();
    flushFacetsRequest(httpTestingController);

    const promise = firstValueFrom(service.getBooksInSeries('Earthsea'));

    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/page'));
    expect(request.request.params.getAll('facet')).toEqual(['series:Earthsea']);
    httpTestingController.expectNone(req => req.url.endsWith('/api/v1/books'));

    request.flush({
      content: [buildBook(1, {metadata: {seriesName: 'Earthsea'}})],
      page: {number: 0, size: 100, totalElements: 1, totalPages: 1, cursor: 'c'},
      links: [],
    });

    const result = await promise;
    expect(result.map(book => book.id)).toEqual([1]);
  });

  it('resolves an empty series without a request', async () => {
    setup();
    flushFacetsRequest(httpTestingController);

    await expect(firstValueFrom(service.getBooksInSeries(''))).resolves.toEqual([]);
  });

  it('invalidates the app-books browse caches when a shelf is removed, never a full collection cache', () => {
    setup();
    flushFacetsRequest(httpTestingController);

    const invalidateSpy = vi.spyOn(queryClientHarness.queryClient, 'invalidateQueries');

    service.removeBooksFromShelf(10);

    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
    httpTestingController.expectNone(req => req.url.endsWith('/api/v1/books'));
  });
});
