import {HttpTestingController} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {Router} from '@angular/router';
import {TranslocoService} from '@jsverse/transloco';
import {MessageService} from '@openng/optimus-ui/api';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {createAuthServiceStub, createQueryClientHarness, flushSignalAndQueryEffects, flushQueryAsync} from '../../../core/testing/query-testing';
import type {Book, BookMetadata} from '../model/book.model';
import type {Shelf} from '../model/shelf.model';
import {AuthService} from '../../../shared/service/auth.service';
import {BookPatchService} from './book-patch.service';
import {BOOKS_QUERY_KEY} from './book-query-keys';
import {BookSocketService} from './book-socket.service';
import {BookService} from './book.service';

type BuildBookOverrides = Omit<Partial<Book>, 'metadata' | 'shelves'> & {
  metadata?: Partial<BookMetadata>;
  shelves?: Shelf[];
};

function buildShelf(id: number, overrides: Partial<Shelf> = {}): Shelf {
  return {
    id,
    name: `Shelf ${id}`,
    userId: 7,
    bookCount: 0,
    ...overrides,
  };
}

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

async function flushBooksQuery(): Promise<void> {
  await flushQueryAsync();
}

// The sidebar/boot facet counts are eager (cheap, token-gated only) - every test that
// authenticates picks up this request regardless of whether it cares about the counts.
function flushFacetsRequest(httpTestingController: HttpTestingController): void {
  httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/facets')).flush({facets: []});
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
    expect(service.isBooksLoading()).toBe(false);
  });

  it('fetches books only once something reads books(), then hydrates query-backed state, loading state, and unique metadata', async () => {
    setup();
    flushFacetsRequest(httpTestingController);

    const response = [
      buildBook(1, {
        metadata: {
          authors: ['Le Guin', 'Le Guin'],
          categories: ['Fantasy'],
          moods: ['Calm'],
          tags: ['Classic', 'Classic'],
          publisher: 'Ace',
          seriesName: 'Earthsea',
        },
      }),
      buildBook(2, {
        metadata: {
          authors: ['Pratchett'],
          categories: ['Fantasy', 'Humor'],
          moods: ['Calm', 'Funny'],
          tags: ['Classic', 'Satire'],
          publisher: 'Corgi',
          seriesName: 'Discworld',
        },
      }),
    ];

    expect(service.books()).toEqual([]);
    httpTestingController.expectNone(req => req.url.endsWith('/api/v1/books'));

    await flushBooksQuery();

    expect(service.isBooksLoading()).toBe(true);
    expect(service.booksError()).toBeNull();

    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books'));
    expect(request.request.method).toBe('GET');
    request.flush(response);
    await flushBooksQuery();

    expect(service.books()).toEqual(response);
    expect(service.findBookById(2)).toEqual(response[1]);
    expect(service.findBookById(999)).toBeUndefined();
    expect(service.uniqueMetadata()).toEqual({
      authors: ['Le Guin', 'Pratchett'],
      categories: ['Fantasy', 'Humor'],
      moods: ['Calm', 'Funny'],
      tags: ['Classic', 'Satire'],
      publishers: ['Ace', 'Corgi'],
      series: ['Earthsea', 'Discworld'],
    });
    expect(service.isBooksLoading()).toBe(false);
    expect(service.booksError()).toBeNull();
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

  it('gates loading on both the auth token and demand, fetching once both are present', async () => {
    setup(null);

    expect(service.books()).toEqual([]);
    expect(service.isBooksLoading()).toBe(false);
    expect(service.booksError()).toBeNull();
    httpTestingController.expectNone(req => req.url.endsWith('/api/v1/books'));

    await flushBooksQuery();
    httpTestingController.expectNone(req => req.url.endsWith('/api/v1/books'));

    authService.token.set('token-123');
    await flushBooksQuery();
    flushFacetsRequest(httpTestingController);

    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books'));
    expect(service.isBooksLoading()).toBe(true);
    request.flush([buildBook(7)]);
    await flushBooksQuery();

    expect(service.books()).toEqual([buildBook(7)]);
    expect(service.isBooksLoading()).toBe(false);
    expect(service.booksError()).toBeNull();
  });

  it('surfaces query errors through booksError and clears the loading flag', async () => {
    setup();
    flushFacetsRequest(httpTestingController);

    service.books();
    await flushBooksQuery();

    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books'));
    request.flush({message: 'boom'}, {status: 500, statusText: 'Server Error'});
    await flushBooksQuery();

    expect(service.books()).toEqual([]);
    expect(service.isBooksLoading()).toBe(false);
    expect(service.booksError()).toBe('Failed to load books');
  });

  it('removes a shelf from the cached books query without disturbing other shelf assignments', async () => {
    setup();
    flushFacetsRequest(httpTestingController);

    const targetShelf = buildShelf(10, {name: 'Favorites'});
    const untouchedShelf = buildShelf(11, {name: 'Archive'});
    const initialBooks = [
      buildBook(1, {shelves: [targetShelf, untouchedShelf]}),
      buildBook(2, {shelves: [targetShelf]}),
    ];

    service.books();
    await flushBooksQuery();
    httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books')).flush(initialBooks);
    await flushBooksQuery();

    service.removeBooksFromShelf(10);
    await flushBooksQuery();

    expect(queryClientHarness.queryClient.getQueryData<Book[]>(BOOKS_QUERY_KEY)).toEqual([
      buildBook(1, {shelves: [untouchedShelf]}),
      buildBook(2, {shelves: []}),
    ]);
    expect(service.books()).toEqual([
      buildBook(1, {shelves: [untouchedShelf]}),
      buildBook(2, {shelves: []}),
    ]);
  });

  it('removes the books query cache when the auth token is cleared', async () => {
    setup();
    flushFacetsRequest(httpTestingController);

    const removeQueriesSpy = vi.spyOn(queryClientHarness.queryClient, 'removeQueries');

    service.books();
    await flushBooksQuery();
    httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books')).flush([
      buildBook(1),
      buildBook(2),
    ]);
    await flushBooksQuery();

    expect(queryClientHarness.queryClient.getQueryData<Book[]>(BOOKS_QUERY_KEY)).toEqual([
      buildBook(1),
      buildBook(2),
    ]);

    authService.token.set(null);
    await flushBooksQuery();

    expect(removeQueriesSpy).toHaveBeenCalledWith({queryKey: BOOKS_QUERY_KEY});
    expect(queryClientHarness.queryClient.getQueryData(BOOKS_QUERY_KEY)).toBeUndefined();
    expect(service.isBooksLoading()).toBe(false);
    expect(service.booksError()).toBeNull();
  });
});
