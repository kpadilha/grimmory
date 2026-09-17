import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {provideTanStackQuery, QueryClient} from '@tanstack/angular-query-experimental';

import {BookQueryService} from '../../../data/book-query.service';
import {BookFacetGroup} from '../../../data/book-query.models';
import {BookService} from '../../../service/book.service';
import {LibraryService} from '../../../service/library.service';
import {ShelfService} from '../../../service/shelf.service';
import {LanguageResolverService} from '../../../../../shared/service/language-resolver.service';
import {EntityType} from '../book-browser.component';
import {BookFilterService} from './book-filter.service';

// TanStack's notification manager batches through a real macrotask, which fake timers never
// fire - drop to real timers for one tick to let a mocked query settle, then restore.
async function resolveQueries(): Promise<void> {
  vi.useRealTimers();
  await new Promise(resolve => setTimeout(resolve, 10));
  vi.useFakeTimers();
}

function facetGroup(key: string, values: {value: string; count?: number}[]): BookFacetGroup {
  return {
    rel: 'facet',
    key,
    title: key,
    values: values.map(v => ({value: v.value, title: v.value, selected: false, ...(v.count === undefined ? {} : {count: v.count})})),
  };
}

function createService(options: {facets?: BookFacetGroup[]} = {}) {
  const facetsSpy = vi.fn(() => ({
    queryKey: ['books', 'query', 'collection', 'facets', 'harness'] as const,
    queryFn: () => Promise.resolve(options.facets ?? []),
  }));
  // A scoped filter panel must never fall back to the full collection - proven by making
  // the call throw rather than merely spying, so any regression fails loudly.
  const booksSpy = vi.fn(() => {
    throw new Error('bookService.books() must not be called by the sidebar filter panel');
  });

  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTanStackQuery(new QueryClient()),
      {provide: BookQueryService, useValue: {facets: facetsSpy}},
      {provide: BookService, useValue: {books: booksSpy}},
      {provide: LibraryService, useValue: {libraries: signal([{id: 1, name: 'Main Library'}])}},
      {provide: ShelfService, useValue: {shelves: signal([{id: 5, name: 'Favourites'}])}},
      {provide: LanguageResolverService, useValue: {displayName: (raw: string) => raw === 'en' ? 'English' : ''}},
    ],
  });

  const service = TestBed.inject(BookFilterService);
  return {service, facetsSpy, booksSpy};
}

describe('BookFilterService', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('builds every filter signal from a single /books/facets call, never bookService.books()', async () => {
    const {service, facetsSpy} = createService({
      facets: [facetGroup('author', [{value: 'Frank Herbert', count: 3}])],
    });

    const signals = TestBed.runInInjectionContext(() => service.createFilterSignals(
      signal(null), signal(EntityType.ALL_BOOKS), signal(null), signal('and')
    ));
    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(signals.author()).toEqual([{value: {id: 'Frank Herbert', name: 'Frank Herbert'}, bookCount: 3}]);
    expect(facetsSpy).toHaveBeenCalledTimes(1);
  });

  it('resolves the read-status label from the raw enum value returned by the server', async () => {
    const {service} = createService({
      facets: [facetGroup('read_status', [{value: 'READ', count: 4}, {value: 'UNSET', count: 1}])],
    });

    const signals = TestBed.runInInjectionContext(() => service.createFilterSignals(
      signal(null), signal(EntityType.ALL_BOOKS), signal(null), signal('and')
    ));
    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(signals.readStatus()).toEqual([
      {value: {id: 'READ', name: 'Read'}, bookCount: 4},
      {value: {id: 'UNSET', name: 'Unset'}, bookCount: 1},
    ]);
  });

  it('resolves library and shelf names from their own catalogue services, not from a book', async () => {
    const {service} = createService({
      facets: [facetGroup('library', [{value: '1', count: 10}]), facetGroup('shelf', [{value: '5', count: 2}])],
    });

    const signals = TestBed.runInInjectionContext(() => service.createFilterSignals(
      signal(null), signal(EntityType.ALL_BOOKS), signal(null), signal('and')
    ));
    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(signals.library()).toEqual([{value: {id: 1, name: 'Main Library'}, bookCount: 10}]);
    expect(signals.shelf()).toEqual([{value: {id: 5, name: 'Favourites'}, bookCount: 2}]);
  });

  it('resolves a range-bucketed filter type into its RangeConfig label and sort order', async () => {
    const {service} = createService({
      // Bucket id '1' is FILE_SIZE_RANGES[1] ('1–10 MB'); PAGE_COUNT_RANGES[6] ('1000+ pages').
      facets: [facetGroup('file_size', [{value: '1', count: 1}]), facetGroup('page_count', [{value: '6', count: 1}])],
    });

    const signals = TestBed.runInInjectionContext(() => service.createFilterSignals(
      signal(null), signal(EntityType.ALL_BOOKS), signal(null), signal('and')
    ));
    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(signals.fileSize()).toEqual([{value: {id: 1, name: '1–10 MB', sortIndex: 1}, bookCount: 1}]);
    expect(signals.pageCount()).toEqual([{value: {id: 6, name: '1000+ pages', sortIndex: 6}, bookCount: 1}]);
    expect(signals.matchScore()).toEqual([]);
  });

  it('expands a comic_creator "name:role" facet value into a "Name (Role)" label', async () => {
    const {service} = createService({
      facets: [facetGroup('comic_creator', [{value: 'Jack Kirby:penciller', count: 3}])],
    });

    const signals = TestBed.runInInjectionContext(() => service.createFilterSignals(
      signal(null), signal(EntityType.ALL_BOOKS), signal(null), signal('and')
    ));
    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(signals.comicCreator()).toEqual([
      {value: {id: 'Jack Kirby:penciller', name: 'Jack Kirby (Penciller)'}, bookCount: 3},
    ]);
  });

  it('scopes the facets request to the current route entity', async () => {
    const {service, facetsSpy} = createService();

    TestBed.runInInjectionContext(() => service.createFilterSignals(
      signal({id: 9, name: 'Sci-Fi'}), signal(EntityType.LIBRARY), signal(null), signal('and')
    ));
    TestBed.flushEffects();

    expect(facetsSpy).toHaveBeenCalledWith({facets: {library: ['9']}, facetLogic: 'and'});
  });

  it('keeps numeric-id coercion for library/shelf-style filter clicks', () => {
    const {service} = createService();

    expect(service.processFilterValue('library', '7')).toBe(7);
    expect(service.processFilterValue('author', 'Frank Herbert')).toBe('Frank Herbert');
    expect(service.isNumericFilter('shelf')).toBe(true);
    expect(service.isNumericFilter('author')).toBe(false);
  });
});
