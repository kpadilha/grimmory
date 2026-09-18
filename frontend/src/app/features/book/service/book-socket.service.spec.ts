import {TestBed} from '@angular/core/testing';
import {QueryClient} from '@tanstack/angular-query-experimental';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {Book} from '../model/book.model';
import {bookDetailQueryKey, bookDetailQueryPrefix, bookRecommendationsQueryKey} from './book-query-keys';
import {bookQueryKeys} from '../data/book-query-keys';
import {BookSocketService} from './book-socket.service';

function makeBook(id: number, overrides: Partial<Book> = {}): Book {
  return {
    id,
    libraryId: 1,
    libraryName: 'Library',
    metadata: {
      bookId: id,
      title: `Book ${id}`,
      coverUpdatedOn: '2026-03-01T00:00:00Z',
    },
    ...overrides,
  };
}

describe('BookSocketService', () => {
  let service: BookSocketService;
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient();

    TestBed.configureTestingModule({
      providers: [
        BookSocketService,
        {provide: QueryClient, useValue: queryClient},
      ],
    });

    service = TestBed.inject(BookSocketService);
  });

  afterEach(() => {
    queryClient.clear();
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('invalidates the app-books browse caches for a newly created book, never a full collection cache', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    service.handleNewlyCreatedBook(makeBook(2));

    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
  });

  it('invalidates the paged/faceted browse queries and removes detail queries for removed ids', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    const kept = makeBook(2);
    queryClient.setQueryData(bookDetailQueryKey(1, false), makeBook(1));
    queryClient.setQueryData(bookRecommendationsQueryKey(1, 20), [kept]);

    service.handleRemovedBookIds([1]);

    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: bookQueryKeys.collections()});
    expect(queryClient.getQueryData(bookDetailQueryKey(1, false))).toBeUndefined();
    expect(queryClient.getQueryData(bookRecommendationsQueryKey(1, 20))).toBeUndefined();
  });

  it('invalidates detail and app-books caches for an updated book, never a full collection cache', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    service.handleBookUpdate(makeBook(7, {libraryName: 'Updated'}));

    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(7)});
    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
  });

  it('patches app-books cover timestamps and invalidates their detail queries', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    service.handleMultipleBookCoverPatches([{id: 3, coverUpdatedOn: '2026-03-26T12:34:00Z'}]);

    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: ['books', 'detail', 3]});
  });

  it('ignores empty cover patch lists', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    service.handleMultipleBookCoverPatches([]);

    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});
