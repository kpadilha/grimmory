import {HttpTestingController} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {AuthService} from '../../../shared/service/auth.service';
import {createAuthServiceStub, createQueryClientHarness, flushQueryAsync, flushSignalAndQueryEffects} from '../../../core/testing/query-testing';
import type {Library} from '../model/library.model';
import {BookService} from './book.service';
import {BOOKS_QUERY_KEY} from './book-query-keys';
import {LIBRARIES_QUERY_KEY, libraryFormatCountsQueryKey} from './library-query-keys';
import {LibraryService} from './library.service';

function buildLibrary(overrides: Partial<Library> = {}): Library {
  return {
    id: 1,
    name: 'Zeta Library',
    watch: true,
    paths: [{path: '/books'}],
    ...overrides,
  };
}

// Sidebar badge counts are eager (cheap, token-gated only) - every test picks up this request.
function flushFacetsRequest(httpTestingController: HttpTestingController, facets: unknown[] = []): void {
  httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/facets')).flush({facets});
}

describe('LibraryService', () => {
  let service: LibraryService;
  let httpTestingController: HttpTestingController;
  let authService: ReturnType<typeof createAuthServiceStub>;
  let queryClientHarness: ReturnType<typeof createQueryClientHarness>;
  let bookService: {
    books: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    authService = createAuthServiceStub();
    queryClientHarness = createQueryClientHarness();
    bookService = {
      books: vi.fn(() => []),
    };

    vi.spyOn(queryClientHarness.queryClient, 'invalidateQueries').mockResolvedValue(undefined);
    vi.spyOn(queryClientHarness.queryClient, 'removeQueries').mockImplementation(() => undefined);

    TestBed.configureTestingModule({
      providers: [
        ...queryClientHarness.providers,
        LibraryService,
        {provide: AuthService, useValue: authService},
        {provide: BookService, useValue: bookService},
      ],
    });

    service = TestBed.inject(LibraryService);
    httpTestingController = TestBed.inject(HttpTestingController);
    flushSignalAndQueryEffects();
  });

  afterEach(() => {
    httpTestingController.verify();
    queryClientHarness.queryClient.clear();
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('refreshes and patches library metadata endpoints while invalidating the library cache', () => {
    httpTestingController.expectOne(req => req.url.endsWith('/api/v1/libraries')).flush([]);
    flushFacetsRequest(httpTestingController);

    service.refreshLibrary(9).subscribe();
    const refreshRequest = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/libraries/9/refresh'));
    expect(refreshRequest.request.method).toBe('PUT');
    refreshRequest.flush(null);

    service.updateLibraryFileNamingPattern(9, '{Authors}/{Title}').subscribe();
    const patternRequest = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/libraries/9/file-naming-pattern'));
    expect(patternRequest.request.method).toBe('PATCH');
    expect(patternRequest.request.body).toEqual({fileNamingPattern: '{Authors}/{Title}'});
    patternRequest.flush(buildLibrary({id: 9, fileNamingPattern: '{Authors}/{Title}'}));

    expect(queryClientHarness.queryClient.invalidateQueries).toHaveBeenCalledWith({queryKey: LIBRARIES_QUERY_KEY, exact: true});
  });

  it('invalidates library and book caches after update and delete flows', () => {
    httpTestingController.expectOne(req => req.url.endsWith('/api/v1/libraries')).flush([]);
    flushFacetsRequest(httpTestingController);

    service.updateLibrary(buildLibrary({name: 'Updated'}), 4).subscribe();
    const updateRequest = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/libraries/4'));
    expect(updateRequest.request.method).toBe('PUT');
    updateRequest.flush(buildLibrary({id: 4, name: 'Updated'}));

    service.deleteLibrary(4).subscribe();
    const deleteRequest = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/libraries/4'));
    expect(deleteRequest.request.method).toBe('DELETE');
    deleteRequest.flush(null);

    expect(queryClientHarness.queryClient.invalidateQueries).toHaveBeenCalledWith({queryKey: LIBRARIES_QUERY_KEY, exact: true});
    expect(queryClientHarness.queryClient.invalidateQueries).toHaveBeenCalledWith({queryKey: BOOKS_QUERY_KEY, exact: true});
    expect(queryClientHarness.queryClient.removeQueries).toHaveBeenCalledWith({queryKey: libraryFormatCountsQueryKey(4), exact: true});
  });

  it('hydrates format counts through ensureQueryData', async () => {
    httpTestingController.expectOne(req => req.url.endsWith('/api/v1/libraries')).flush([]);
    flushFacetsRequest(httpTestingController);

    const resultPromise = new Promise<Record<string, number>>((resolve, reject) => {
      service.getBookCountsByFormat(8).subscribe({next: resolve, error: reject});
    });
    await Promise.resolve();

    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/libraries/8/format-counts'));
    expect(request.request.method).toBe('GET');
    request.flush({EPUB: 7, PDF: 2});

    await expect(resultPromise).resolves.toEqual({EPUB: 7, PDF: 2});
  });

  it('removes library queries when the auth token becomes null', () => {
    const removeQueriesSpy = vi.spyOn(queryClientHarness.queryClient, 'removeQueries').mockImplementation(() => undefined);

    httpTestingController.expectOne(req => req.url.endsWith('/api/v1/libraries')).flush([]);
    flushFacetsRequest(httpTestingController);
    authService.token.set(null);
    flushSignalAndQueryEffects();

    expect(removeQueriesSpy).toHaveBeenCalledWith({queryKey: LIBRARIES_QUERY_KEY});
  });

  it('derives bookCountByLibraryId from the server-side library facet, never the full collection', async () => {
    httpTestingController.expectOne(req => req.url.endsWith('/api/v1/libraries')).flush([]);
    flushFacetsRequest(httpTestingController, [{
      metadata: {rel: 'facet', key: 'library', title: 'Library'},
      links: [
        {rel: ['facet'], href: '', type: 'application/json', value: '8', title: 'Main', properties: {numberOfItems: 2}},
        {rel: ['facet'], href: '', type: 'application/json', value: '9', title: 'Archive', properties: {numberOfItems: 5}},
      ],
    }]);
    await flushQueryAsync();

    expect(service.bookCountByLibraryId()).toEqual(new Map([[8, 2], [9, 5]]));
    expect(bookService.books).not.toHaveBeenCalled();
  });
});
