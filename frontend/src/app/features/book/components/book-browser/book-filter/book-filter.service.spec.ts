import {computed, signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {provideTanStackQuery, QueryClient} from '@tanstack/angular-query-experimental';

import {BookQueryService} from '../../../data/book-query.service';
import {BookCollectionFilterParams} from '../../../data/book-query-params';
import {BookFacetGroup} from '../../../data/book-query.models';
import {BookService} from '../../../service/book.service';
import {LibraryService} from '../../../service/library.service';
import {ShelfService} from '../../../service/shelf.service';
import {LanguageResolverService} from '../../../../../shared/service/language-resolver.service';
import {EntityType} from '../book-browser.component';
import {FilterType} from './book-filter.config';
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
  // A group-scoped query key (params.group) so tanstack never collapses two different panels'
  // fetches into one cache entry - each group is requested, and gated, independently.
  const requestedGroups: (readonly string[] | undefined)[] = [];
  const facetsSpy = vi.fn((params: BookCollectionFilterParams) => ({
    queryKey: ['books', 'query', 'collection', 'facets', params] as const,
    queryFn: () => {
      requestedGroups.push(params.group);
      return Promise.resolve(options.facets ?? []);
    },
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
  return {service, facetsSpy, requestedGroups};
}

function createSignals(
  service: BookFilterService,
  expanded: FilterType[],
  entity: Parameters<BookFilterService['createFilterSignals']>[0] = signal(null),
  entityType: Parameters<BookFilterService['createFilterSignals']>[1] = signal(EntityType.ALL_BOOKS),
) {
  return TestBed.runInInjectionContext(() => service.createFilterSignals(
    entity, entityType, signal(null), signal('and'), signal(new Set(expanded)),
  ));
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

  it('never requests a group until its panel is expanded', async () => {
    const {service, requestedGroups} = createService({
      facets: [facetGroup('author', [{value: 'Frank Herbert', count: 3}])],
    });

    const signals = createSignals(service, []);
    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(signals.author()).toEqual([]);
    expect(requestedGroups).toEqual([]);
  });

  it('fetches only the expanded group, scoped by its own facet key, never every group at once', async () => {
    const {service, requestedGroups} = createService({
      facets: [facetGroup('author', [{value: 'Frank Herbert', count: 3}])],
    });

    const signals = createSignals(service, ['author']);
    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(signals.author()).toEqual([{value: {id: 'Frank Herbert', name: 'Frank Herbert'}, bookCount: 3}]);
    expect(requestedGroups).toEqual([['author']]);
    expect(requestedGroups.every(group => (group?.length ?? 0) === 1)).toBe(true);
  });

  it('expanding a second panel adds its group without re-requesting the first', async () => {
    const {service, requestedGroups} = createService({
      facets: [
        facetGroup('author', [{value: 'Frank Herbert', count: 3}]),
        facetGroup('read_status', [{value: 'READ', count: 4}, {value: 'UNSET', count: 1}]),
      ],
    });

    const expanded = signal<FilterType[]>(['author']);
    const signals = TestBed.runInInjectionContext(() => service.createFilterSignals(
      signal(null), signal(EntityType.ALL_BOOKS), signal(null), signal('and'),
      computed(() => new Set(expanded())),
    ));
    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();
    expect(signals.readStatus()).toEqual([]);

    expanded.set(['author', 'readStatus']);
    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(signals.readStatus()).toEqual([
      {value: {id: 'READ', name: 'Read'}, bookCount: 4},
      {value: {id: 'UNSET', name: 'Unset'}, bookCount: 1},
    ]);
    expect(requestedGroups.map(g => g?.[0])).toEqual(['author', 'read_status']);
  });

  it('resolves library and shelf names from their own catalogue services once expanded', async () => {
    const {service} = createService({
      facets: [facetGroup('library', [{value: '1', count: 10}]), facetGroup('shelf', [{value: '5', count: 2}])],
    });

    const signals = createSignals(service, ['library', 'shelf']);
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

    const signals = createSignals(service, ['fileSize', 'pageCount']);
    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(signals.fileSize()).toEqual([{value: {id: 1, name: '1–10 MB', sortIndex: 1}, bookCount: 1}]);
    expect(signals.pageCount()).toEqual([{value: {id: 6, name: '1000+ pages', sortIndex: 6}, bookCount: 1}]);
  });

  it('expands a comic_creator "name:role" facet value into a "Name (Role)" label', async () => {
    const {service} = createService({
      facets: [facetGroup('comic_creator', [{value: 'Jack Kirby:penciller', count: 3}])],
    });

    const signals = createSignals(service, ['comicCreator']);
    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(signals.comicCreator()).toEqual([
      {value: {id: 'Jack Kirby:penciller', name: 'Jack Kirby (Penciller)'}, bookCount: 3},
    ]);
  });

  it('scopes the facets request to the current route entity', async () => {
    const {service, facetsSpy} = createService();

    createSignals(service, ['library'], signal({id: 9, name: 'Sci-Fi'}), signal(EntityType.LIBRARY));
    TestBed.flushEffects();

    expect(facetsSpy).toHaveBeenCalledWith({facets: {library: ['9']}, facetLogic: 'and', group: ['library']});
  });

  it('keeps numeric-id coercion for library/shelf-style filter clicks', () => {
    const {service} = createService();

    expect(service.processFilterValue('library', '7')).toBe(7);
    expect(service.processFilterValue('author', 'Frank Herbert')).toBe('Frank Herbert');
    expect(service.isNumericFilter('shelf')).toBe(true);
    expect(service.isNumericFilter('author')).toBe(false);
  });
});
