import {HttpTestingController} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {Router} from '@angular/router';
import {TranslocoService} from '@jsverse/transloco';
import {MessageService} from '@openng/optimus-ui/api';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {createAuthServiceStub, createQueryClientHarness, flushSignalAndQueryEffects, flushQueryAsync} from '../../../core/testing/query-testing';
import type {Book, BookMetadata} from '../model/book.model';
import type {Shelf} from '../model/shelf.model';
import {bookQueryKeys} from '../data/book-query-keys';
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
            handleBookMetadataUpdate: vi.fn(),
            handleMultipleBookCoverPatches: vi.fn(),
            handleTaskProgress: vi.fn(),
            handleReconnect: vi.fn(),
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

  // Every enabled instance boots the autocomplete-values query - never the full collection.
  // Tests that don't care about its data just drain it.
  function flushGlobalQueries(
    metadataValues: Record<string, {value: string; bookIds: number[]}[]> = {},
  ): void {
    httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/facets/values')).flush(metadataValues);
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

  it('derives uniqueMetadata from the server facet aggregates', async () => {
    setup();

    flushGlobalQueries(
      {
        author: [{value: 'Le Guin', bookIds: [1]}, {value: 'Pratchett', bookIds: [2]}],
        genre: [{value: 'Fantasy', bookIds: [1, 2]}, {value: 'Humor', bookIds: [2]}],
        mood: [{value: 'Calm', bookIds: [1, 2]}, {value: 'Funny', bookIds: [2]}],
        tag: [{value: 'Classic', bookIds: [1, 2]}, {value: 'Satire', bookIds: [2]}],
        publisher: [{value: 'Ace', bookIds: [1]}, {value: 'Corgi', bookIds: [2]}],
        series: [{value: 'Earthsea', bookIds: [1]}, {value: 'Discworld', bookIds: [2]}],
      },
    );
    await flushQueryAsync();

    expect(service.uniqueMetadata()).toEqual({
      authors: ['Le Guin', 'Pratchett'],
      categories: ['Fantasy', 'Humor'],
      moods: ['Calm', 'Funny'],
      tags: ['Classic', 'Satire'],
      publishers: ['Ace', 'Corgi'],
      series: ['Earthsea', 'Discworld'],
    });
  });

  it('gates the facet queries on the auth token and starts them once a token is available', async () => {
    setup(null);

    httpTestingController.expectNone(req => req.url.endsWith('/api/v1/books/facets/values'));

    authService.token.set('token-123');
    flushSignalAndQueryEffects();
    flushGlobalQueries();
    await flushQueryAsync();
  });

  it('fetches only the requested ids from /books/batch', async () => {
    setup();
    flushGlobalQueries();

    const resultPromise = service.getBooksByIds([2, 1]);
    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/batch'));
    expect(request.request.params.get('ids')).toBe('2,1');
    request.flush([buildBook(2), buildBook(1)]);

    await expect(resultPromise).resolves.toEqual([buildBook(2), buildBook(1)]);
  });

  it('resolves getBooksByIds to an empty array without a request when given no ids', async () => {
    setup();
    flushGlobalQueries();

    await expect(service.getBooksByIds([])).resolves.toEqual([]);
    httpTestingController.expectNone(req => req.url.endsWith('/api/v1/books/batch'));
  });

  it('removes a shelf from the cached books query without disturbing other shelf assignments', async () => {
    setup();
    flushGlobalQueries();

    const targetShelf = buildShelf(10, {name: 'Favorites'});
    const untouchedShelf = buildShelf(11, {name: 'Archive'});
    const initialBooks = [
      buildBook(1, {shelves: [targetShelf, untouchedShelf]}),
      buildBook(2, {shelves: [targetShelf]}),
    ];
    queryClientHarness.queryClient.setQueryData(BOOKS_QUERY_KEY, initialBooks);
    queryClientHarness.queryClient.setQueryData(bookQueryKeys.detail(1, false), {id: 1});

    service.removeBooksFromShelf(10);
    await flushQueryAsync();

    const reconciledBooks = [
      buildBook(1, {shelves: [untouchedShelf]}),
      buildBook(2, {shelves: []}),
    ];
    expect(queryClientHarness.queryClient.getQueryData<Book[]>(BOOKS_QUERY_KEY)).toEqual(reconciledBooks);
    expect(queryClientHarness.queryClient.getQueryState(bookQueryKeys.detail(1, false))?.isInvalidated).toBe(true);
  });

  it('removes the books query cache when the auth token is cleared', async () => {
    setup();
    flushGlobalQueries();

    const removeQueriesSpy = vi.spyOn(queryClientHarness.queryClient, 'removeQueries');
    queryClientHarness.queryClient.setQueryData(BOOKS_QUERY_KEY, [buildBook(1), buildBook(2)]);

    authService.token.set(null);
    await flushQueryAsync();

    expect(removeQueriesSpy).toHaveBeenCalledWith({queryKey: BOOKS_QUERY_KEY});
    expect(queryClientHarness.queryClient.getQueryData(BOOKS_QUERY_KEY)).toBeUndefined();
  });
});
