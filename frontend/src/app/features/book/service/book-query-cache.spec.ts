import {beforeEach, describe, expect, it, vi} from 'vitest';
import {QueryClient} from '@tanstack/angular-query-experimental';

import {Book, BookMetadata} from '../model/book.model';
import {
  addBookToCache,
  invalidateBookDetailQueries,
  invalidateBookQueries,
  invalidateBooksQuery,
  patchBookFieldsInCache,
  patchBookInCacheWith,
  patchBookMetadataInCache,
  patchBooksInCache,
  removeBookQueries,
  removeBooksFromCache
} from './book-query-cache';
import {
  bookDetailQueryKey,
  bookDetailQueryPrefix,
  bookRecommendationsQueryKey
} from './book-query-keys';
import {bookQueryKeys} from '../data/book-query-keys';

function makeBook(id: number, overrides: Partial<Book> = {}): Book {
  return {
    id,
    libraryId: 1,
    libraryName: 'Test Library',
    metadata: {
      bookId: id,
      title: `Book ${id}`
    },
    ...overrides
  };
}

describe('book-query-cache', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient();
  });

  it('invalidates the app-books browse caches when a book is added, never a full collection cache', () => {
    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

    addBookToCache(queryClient, makeBook(1));

    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
  });

  it('invalidates the paged/faceted browse queries and book detail queries', () => {
    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

    invalidateBooksQuery(queryClient);
    invalidateBookDetailQueries(queryClient, [1, 1, 2]);
    invalidateBookQueries(queryClient, [3, 3]);

    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookQueryKeys.collections()});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookQueryKeys.idQueries()});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(1)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(2)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(3)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
  });

  it('invalidates matching detail and app-books queries when books are patched', () => {
    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

    patchBooksInCache(queryClient, [makeBook(2, {libraryName: 'Updated Library'})]);

    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(2)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
  });

  it('invalidates detail and app-books queries for metadata, field, and updater patches', () => {
    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

    const updatedMetadata: BookMetadata = {
      bookId: 1,
      title: 'Updated Title'
    };

    patchBookMetadataInCache(queryClient, 1, updatedMetadata);
    patchBookFieldsInCache(queryClient, [
      {bookId: 2, fields: {libraryName: 'Updated Library'}},
      {bookId: 3, fields: {personalRating: 4}}
    ]);
    patchBookInCacheWith(queryClient, 1, book => ({
      ...book,
      metadata: {
        ...(book.metadata ?? {bookId: book.id}),
        authors: ['New Author']
      }
    }));

    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(1)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(2)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(3)});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
  });

  it('removes detail and recommendation queries for deleted books', () => {
    const firstBook = makeBook(1);
    const secondBook = makeBook(2);

    queryClient.setQueryData(bookDetailQueryKey(1, false), firstBook);
    queryClient.setQueryData(bookDetailQueryKey(1, true), firstBook);
    queryClient.setQueryData(bookRecommendationsQueryKey(1, 20), [secondBook]);
    queryClient.setQueryData(bookDetailQueryKey(2, false), secondBook);

    removeBookQueries(queryClient, [1]);

    expect(queryClient.getQueryData(bookDetailQueryKey(1, false))).toBeUndefined();
    expect(queryClient.getQueryData(bookDetailQueryKey(1, true))).toBeUndefined();
    expect(queryClient.getQueryData(bookRecommendationsQueryKey(1, 20))).toBeUndefined();
    expect(queryClient.getQueryData(bookDetailQueryKey(2, false))).toEqual(secondBook);
  });

  it('removes detail/recommendation queries and invalidates app-books caches for deleted books', () => {
    const firstBook = makeBook(1);
    const secondBook = makeBook(2);
    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

    queryClient.setQueryData(bookDetailQueryKey(1, false), firstBook);
    queryClient.setQueryData(bookRecommendationsQueryKey(1, 20), [secondBook]);

    removeBooksFromCache(queryClient, [1]);

    expect(queryClient.getQueryData(bookDetailQueryKey(1, false))).toBeUndefined();
    expect(queryClient.getQueryData(bookRecommendationsQueryKey(1, 20))).toBeUndefined();
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-books']});
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: ['app-filter-options']});
  });

  it('ignores an empty remove request', () => {
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData');
    const removeQueriesSpy = vi.spyOn(queryClient, 'removeQueries');

    removeBooksFromCache(queryClient, []);

    expect(setQueryDataSpy).not.toHaveBeenCalled();
    expect(removeQueriesSpy).not.toHaveBeenCalled();
  });
});
