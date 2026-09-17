import {signal, WritableSignal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {ActivatedRoute, convertToParamMap, ParamMap, Router} from '@angular/router';
import {BehaviorSubject, Subject} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {ConfirmationService, MessageService} from '@openng/optimus-ui/api';
import {provideTanStackQuery, QueryClient} from '@tanstack/angular-query-experimental';

import {PageTitleService} from '../../../../shared/service/page-title.service';
import {BookService} from '../../service/book.service';
import {BookQueryService} from '../../data/book-query.service';
import {BookPageParams} from '../../data/book-query-params';
import {BookMetadataManageService} from '../../service/book-metadata-manage.service';
import {Book} from '../../model/book.model';
import {SortDirection, SortOption} from '../../model/sort.model';
import {UserService} from '../../../settings/user-management/user.service';
import {SeriesCollapseFilter} from './filters/SeriesCollapseFilter';
import {CoverScalePreferenceService} from './cover-scale-preference.service';
import {BookDialogHelperService} from './book-dialog-helper.service';
import {TableColumnPreferenceService} from './table-column-preference.service';
import {BookMenuService} from '../../service/book-menu.service';
import {SidebarFilterTogglePrefService} from './filters/sidebar-filter-toggle-pref.service';
import {TaskHelperService} from '../../../settings/task-management/task-helper.service';
import {LoadingService} from '../../../../core/services/loading.service';
import {LocalStorageService} from '../../../../shared/service/local-storage.service';
import {BookNavigationService} from '../../service/book-navigation.service';
import {BookCardOverlayPreferenceService} from './book-card-overlay-preference.service';
import {BookSelectionService} from './book-selection.service';
import {BookBrowserQueryParamsService, VIEW_MODES} from './book-browser-query-params.service';
import {BookBrowserEntityService} from './book-browser-entity.service';
import {RouteScrollPositionService} from '../../../../shared/service/route-scroll-position.service';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {BookBrowserComponent, EntityType} from './book-browser.component';
import {TranslocoService} from '@jsverse/transloco';
import {LayoutService} from '../../../../shared/layout/layout.service';
import {type VirtualGridMetrics} from '../../../../shared/util/virtual-grid.util';

// TanStack's notification manager batches through a real macrotask, which fake timers never
// fire - drop to real timers for one tick to let a mocked query settle, then restore.
async function resolveQueries(): Promise<void> {
  vi.useRealTimers();
  await new Promise(resolve => setTimeout(resolve, 10));
  vi.useFakeTimers();
}

function makeBook(id: number, libraryId: number, title: string, addedOn: string): Book {
  return {
    id,
    libraryId,
    metadata: {
      bookId: id,
      title,
    },
    addedOn,
  } as Book;
}

function makeCurrentUser(options?: {visibleSortFields?: string[]}) {
  return {
    id: 1,
    username: 'tester',
    name: 'Tester',
    email: 'tester@example.com',
    locale: 'en',
    theme: 'grimmory',
    themeAccent: null,
    themeSyncEnabled: true,
    assignedLibraries: [],
    permissions: {
      admin: false,
      canUpload: false,
      canDownload: false,
      canEmailBook: false,
      canDeleteBook: false,
      canEditMetadata: false,
      canManageLibrary: false,
      canManageMetadataConfig: false,
      canSyncKoReader: false,
      canSyncKobo: false,
      canAccessOpds: false,
      canAccessBookdrop: false,
      canAccessLibraryStats: false,
      canAccessUserStats: false,
      canAccessTaskManager: false,
      canManageEmailConfig: false,
      canManageGlobalPreferences: false,
      canManageIcons: false,
      canManageFonts: false,
      demoUser: false,
      canBulkAutoFetchMetadata: false,
      canBulkCustomFetchMetadata: false,
      canBulkEditMetadata: false,
      canBulkRegenerateCover: false,
      canMoveOrganizeFiles: false,
      canBulkLockUnlockMetadata: false,
    },
    userSettings: {
      filterMode: 'and',
      enableSeriesView: false,
      visibleSortFields: options?.visibleSortFields ?? ['addedOn', 'title'],
      entityViewPreferences: {
        global: {
          sortKey: 'addedOn',
          sortDir: 'DESC',
          view: 'GRID',
          coverSize: 1,
          seriesCollapsed: false,
          overlayBookType: true,
        },
        overrides: [],
      },
    },
  } as const;
}

interface BookBrowserHarness {
  component: BookBrowserComponent;
  books: WritableSignal<Book[]>;
  booksError: WritableSignal<string | null>;
  isBooksLoading: WritableSignal<boolean>;
  paramMap$: BehaviorSubject<ParamMap>;
  setHasNextPage: (value: boolean) => void;
  setIsFetchingNextPage: (value: boolean) => void;
  bookQueryService: {
    infinitePage: ReturnType<typeof vi.fn>;
    facets: ReturnType<typeof vi.fn>;
    ids: ReturnType<typeof vi.fn>;
  };
  // Spies bookService.books() - a scoped route must never call it (that's the 132k-book load).
  booksSignalSpy: ReturnType<typeof vi.fn>;
  queryParamsService: {
    shouldForceExpandSeries: ReturnType<typeof vi.fn>;
    updateViewMode: ReturnType<typeof vi.fn>;
    updateFilters: ReturnType<typeof vi.fn>;
    updateFilterMode: ReturnType<typeof vi.fn>;
    updateMultiSort: ReturnType<typeof vi.fn>;
    parseQueryParams: ReturnType<typeof vi.fn>;
    syncQueryParams: ReturnType<typeof vi.fn>;
  };
  routeSnapshot: {
    routeConfig: {path: string};
    paramMap: ParamMap;
    queryParamMap: ParamMap;
    params: Record<string, string>;
  };
}

function createHarness(options?: {
  books?: Book[];
  totalElements?: number;
  booksError?: string | null;
  isBooksLoading?: boolean;
  translate?: (key: string) => string;
  visibleSortFields?: string[];
  allBooksFacets?: {key: string; values: {value: string; title: string; count?: number}[]}[];
  route?: {path: string; params?: Record<string, string>};
  infinitePageError?: Error;
  ids?: number[];
}): BookBrowserHarness {
  const books = signal<Book[]>(
    options?.books ?? [
      makeBook(2, 1, 'Zulu', '2024-02-01T00:00:00Z'),
      makeBook(1, 1, 'Alpha', '2024-01-01T00:00:00Z'),
      makeBook(3, 2, 'Bravo', '2024-03-01T00:00:00Z'),
    ]
  );
  const booksError = signal<string | null>(options?.booksError ?? null);
  const isBooksLoading = signal<boolean>(options?.isBooksLoading ?? false);
  const isFetchingNextPage = signal(false);
  const hasNextPage = signal(false);
  const currentUser = signal(makeCurrentUser({visibleSortFields: options?.visibleSortFields}));
  const showFilter = signal(false);
  const seriesCollapsed = signal(false);
  const routerEvents$ = new Subject<unknown>();
  const routeParams = options?.route?.params ?? {libraryId: '1'};
  const paramMap$ = new BehaviorSubject(convertToParamMap(routeParams));
  const queryParamMap$ = new BehaviorSubject(convertToParamMap({}));
  const url$ = new BehaviorSubject<unknown[]>([]);
  const booksSignalSpy = vi.fn(() => books());
  const infinitePageSpy = vi.fn((params: BookPageParams) => ({
    queryKey: ['books', 'query', 'collection', 'page', 'infinite', 'harness', JSON.stringify(params)] as const,
    queryFn: () => options?.infinitePageError
      ? Promise.reject(options.infinitePageError)
      : Promise.resolve({
          content: books(),
          page: {
            number: 0,
            size: books().length,
            totalElements: options?.totalElements ?? books().length,
            totalPages: 1,
            cursor: '',
          },
          links: [],
        }),
    initialPageParam: null as string | null,
    getNextPageParam: () => undefined,
    // The real service applies QUERY_DEFAULTS (retry: false for a non-HTTP error); this mock
    // bypasses that, so an error-state test needs the same guard or it burns through TanStack's
    // default multi-second retry backoff.
    retry: false,
  }));
  const idsSpy = vi.fn(() => ({
    queryKey: ['books', 'query', 'collection', 'ids', 'harness'] as const,
    queryFn: () => Promise.resolve(options?.ids ?? books().map(b => b.id)),
  }));
  const defaultSort: SortOption = {field: 'addedOn', direction: SortDirection.DESCENDING, label: 'Added On'};
  const queryParamsService = {
    parseQueryParams: vi.fn((_q, _u, _et, _ei, _sortOptions, defaultFilterMode) => ({
      viewMode: VIEW_MODES.GRID,
      sortOption: defaultSort,
      sortCriteria: [defaultSort],
      filters: {},
      filterMode: defaultFilterMode,
      viewModeFromToggle: false,
    })),
    syncQueryParams: vi.fn(),
    shouldForceExpandSeries: vi.fn(() => false),
    updateViewMode: vi.fn(),
    updateFilters: vi.fn(),
    updateFilterMode: vi.fn(),
    updateMultiSort: vi.fn(),
  };
  const routeSnapshot = {
    routeConfig: {path: options?.route?.path ?? 'library/:libraryId/books'},
    paramMap: paramMap$.value,
    queryParamMap: queryParamMap$.value,
    params: routeParams,
  };

  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      BookSelectionService,
      BookNavigationService,
      {
        provide: UserService,
        useValue: {
          currentUser: currentUser.asReadonly(),
          getCurrentUser: vi.fn(() => currentUser()),
          updateUserSetting: vi.fn(),
        },
      },
      {
        provide: CoverScalePreferenceService,
        useValue: {
          scaleFactor: vi.fn(() => 1),
          setScale: vi.fn(),
        },
      },
      {
        provide: TableColumnPreferenceService,
        useValue: {
          allColumns: [],
          visibleColumns: [],
          initPreferences: vi.fn(),
          saveVisibleColumns: vi.fn(),
        },
      },
      {
        provide: SidebarFilterTogglePrefService,
        useValue: {
          showFilter: showFilter.asReadonly(),
          toggle: vi.fn(() => showFilter.update(value => !value)),
        },
      },
      {
        provide: SeriesCollapseFilter,
        useValue: {
          seriesCollapsed: seriesCollapsed.asReadonly(),
          setContext: vi.fn(),
          collapseBooks: vi.fn((items: Book[]) => items),
          setCollapsed: vi.fn((value: boolean) => seriesCollapsed.set(value)),
        },
      },
      {
        provide: BookSelectionService,
        useValue: {
          selectedBooks: signal([]),
          selectedCount: signal(0),
          deselectAll: vi.fn(),
          setCurrentBooks: vi.fn(),
          selectAll: vi.fn(),
        },
      },
      {
        provide: BookNavigationService,
        useValue: {
          setAvailableBookIds: vi.fn(),
        },
      },
      {
        provide: RouteScrollPositionService,
        useValue: {
          createKey: vi.fn(() => 'test-key'),
          keyFor: vi.fn(() => 'test-key'),
          savePosition: vi.fn(),
          getPosition: vi.fn(() => 0),
          trackRoute: vi.fn(),
        },
      },
      {provide: ConfirmationService, useValue: {confirm: vi.fn()}},
      {provide: TaskHelperService, useValue: {}},
      {
        provide: BookCardOverlayPreferenceService,
        useValue: {
          showBookTypePill: vi.fn(() => true),
          setShowBookTypePill: vi.fn(),
        },
      },
      {provide: AppSettingsService, useValue: {appSettings: vi.fn(() => null)}},
      {provide: LayoutService, useValue: {isDesktop: signal(true), sidebarTransitioning: signal(false)}},
      {
        provide: ActivatedRoute,
        useValue: {
          url: url$.asObservable(),
          paramMap: paramMap$.asObservable(),
          queryParamMap: queryParamMap$.asObservable(),
          snapshot: routeSnapshot,
        },
      },
      {
        provide: Router,
        useValue: {
          events: routerEvents$.asObservable(),
          navigate: vi.fn(),
        },
      },
      {provide: MessageService, useValue: {add: vi.fn()}},
      {
        provide: BookService,
        useValue: {
          // Every book-browser route is server-paginated now; a call here would mean a route
          // fell back to the 132k-book full load.
          books: booksSignalSpy,
          isBooksLoading: isBooksLoading.asReadonly(),
          booksError: booksError.asReadonly(),
        },
      },
      // Every route is now server-paginated, so injectInfiniteQuery always fetches.
      provideTanStackQuery(new QueryClient()),
      {
        provide: BookQueryService,
        useValue: {
          infinitePage: infinitePageSpy,
          facets: vi.fn(() => ({
            queryKey: ['books', 'query', 'collection', 'facets', 'harness'] as const,
            queryFn: () => Promise.resolve(options?.allBooksFacets ?? []),
          })),
          ids: idsSpy,
        },
      },
      {provide: BookMetadataManageService, useValue: {}},
      {provide: BookDialogHelperService, useValue: {}},
      {
        provide: BookMenuService,
        useValue: {
          getMoreActionsMenu: vi.fn(() => []),
          getMetadataMenuItems: vi.fn(() => []),
        },
      },
      {provide: PageTitleService, useValue: {setPageTitle: vi.fn()}},
      {provide: LoadingService, useValue: {show: vi.fn(), hide: vi.fn()}},
      {provide: LocalStorageService, useValue: {get: vi.fn(), set: vi.fn()}},
      {
        provide: BookBrowserQueryParamsService,
        useValue: queryParamsService,
      },
      {
        provide: BookBrowserEntityService,
        useValue: {
          // Mirrors the real service: no library/shelf/magic-shelf id in the route means all-books.
          getEntityInfo: vi.fn((paramMap: ParamMap) => {
            const libraryId = paramMap.get('libraryId');
            if (libraryId) return {entityId: Number(libraryId), entityType: EntityType.LIBRARY};
            const shelfId = paramMap.get('shelfId');
            if (shelfId) return {entityId: Number(shelfId), entityType: EntityType.SHELF};
            const magicShelfId = paramMap.get('magicShelfId');
            if (magicShelfId) return {entityId: Number(magicShelfId), entityType: EntityType.MAGIC_SHELF};
            return {entityId: NaN, entityType: EntityType.ALL_BOOKS};
          }),
          getEntity: vi.fn((entityId: number) => ({id: entityId, name: `Library ${entityId}`})),
          getBooksByEntity: vi.fn((items: Book[], entityId: number) =>
            items.filter(book => book.libraryId === entityId)
          ),
          isLibrary: vi.fn(() => true),
          isMagicShelf: vi.fn(() => false),
        },
      },
      {
        provide: TranslocoService,
        useValue: {
          langChanges$: new Subject<string>().asObservable(),
          getActiveLang: vi.fn(() => 'en'),
          translate: vi.fn((key: string) => {
            if (options?.translate) {
              return options.translate(key);
            }

            return {
              'book.browser.tooltip.toggleView': 'Toggle between Grid and Table view',
              'book.browser.labels.allBooks': 'All Books',
              'book.browser.labels.unshelvedBooks': 'Unshelved Books',
            }[key] ?? key;
          }),
        },
      },
    ],
  });

  const component = TestBed.runInInjectionContext(() => new BookBrowserComponent());

  return {
    component,
    books,
    booksError,
    isBooksLoading,
    paramMap$,
    setHasNextPage: value => hasNextPage.set(value),
    setIsFetchingNextPage: value => isFetchingNextPage.set(value),
    bookQueryService: {infinitePage: infinitePageSpy, facets: vi.fn(), ids: idsSpy},
    booksSignalSpy,
    queryParamsService,
    routeSnapshot,
  };
}

describe('BookBrowserComponent', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal('ResizeObserver', class {
      observe = vi.fn();
      unobserve = vi.fn();
      disconnect = vi.fn();
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('switches view mode through the display settings control', () => {
    const {component, queryParamsService} = createHarness();

    component.currentViewMode.set(VIEW_MODES.GRID);

    component.onViewModeChange(VIEW_MODES.TABLE);

    expect(component.currentViewMode()).toBe(VIEW_MODES.TABLE);
    expect(queryParamsService.updateViewMode).toHaveBeenCalledWith(VIEW_MODES.TABLE);
  });

  it('requests the updated sort term from the server when sorting changes', async () => {
    const {component, bookQueryService} = createHarness();

    await resolveQueries();
    TestBed.flushEffects();

    component.applySortCriteria([
      {label: 'Title', field: 'title', direction: SortDirection.ASCENDING},
    ]);
    TestBed.flushEffects();

    const lastParams = vi.mocked(bookQueryService.infinitePage).mock.calls.at(-1)?.[0];
    expect(lastParams?.sort).toEqual([{key: 'title', direction: 'asc'}]);
  });

  it('updates books after a context change', async () => {
    const {component, books, paramMap$, routeSnapshot} = createHarness();

    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(component.books().map(book => book.id)).toEqual([2, 1, 3]);

    books.set([makeBook(3, 2, 'Bravo', '2024-03-01T00:00:00Z')]);
    routeSnapshot.paramMap = convertToParamMap({libraryId: '2'});
    routeSnapshot.params = {libraryId: '2'};
    paramMap$.next(routeSnapshot.paramMap);
    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(component.books().map(book => book.id)).toEqual([3]);
  });

  it('hides loading placeholders when the books query is in an error state', async () => {
    const {component} = createHarness({infinitePageError: new Error('Failed to load books')});

    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(component.booksError()).toBe('Failed to load books');
    expect(component.showBooksLoadingPlaceholder()).toBe(false);
    expect(component.showTableLoadingPlaceholder()).toBe(false);
  });

  it('keeps rendered books visible across a search term change (placeholderData)', async () => {
    const renderedBooks = Array.from({length: 30}, (_, index) =>
      makeBook(index + 1, 1, `Book ${index + 1}`, `2024-01-${String(index + 1).padStart(2, '0')}T00:00:00Z`)
    );
    const {component} = createHarness({books: renderedBooks});

    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(component.books()).toHaveLength(30);
    expect(component.virtualRowCount()).toBe(30);

    // A new query key (search term) is now in flight; keepPreviousData must keep the grid full.
    component.onSearchTermChange('book');
    vi.advanceTimersByTime(500);
    TestBed.flushEffects();

    expect(component.showBooksLoadingPlaceholder()).toBe(false);
    expect(component.virtualRowCount()).toBe(30);
  });

  it('sizes grid loading placeholders to fill the viewport', () => {
    const {component} = createHarness({books: [], isBooksLoading: true});
    const loadingGrid = component as unknown as {
      minimumLoadingGridItemCount(metrics: VirtualGridMetrics): number;
    };

    component.currentViewMode.set(VIEW_MODES.GRID);

    expect(loadingGrid.minimumLoadingGridItemCount({
      viewportWidth: 960,
      viewportHeight: 900,
      columns: 6,
      itemHeight: 200,
      gap: 20,
    })).toBe(36);
  });

  it('calls SeriesCollapseFilter.collapseBooks when computing books', () => {
    createHarness();
    const filter = TestBed.inject(SeriesCollapseFilter);
    const collapseBooksSpy = vi.spyOn(filter, 'collapseBooks');

    vi.runOnlyPendingTimers();
    TestBed.flushEffects();

    expect(collapseBooksSpy).toHaveBeenCalled();
  });

  it('offers only server-sortable fields in the all-books sort popover', () => {
    const {component, paramMap$} = createHarness({
      visibleSortFields: ['title', 'fileName', 'addedOn', 'locked', 'bookType'],
    });
    TestBed.flushEffects();

    paramMap$.next(convertToParamMap({}));
    TestBed.flushEffects();

    expect(component.visibleSortOptions().map(o => o.field)).toEqual(['title', 'addedOn']);
  });

  it('restricts the sort menu to server-sortable fields on a library route too', () => {
    const {component} = createHarness({
      visibleSortFields: ['title', 'fileName', 'addedOn'],
    });
    TestBed.flushEffects();

    expect(component.visibleSortOptions().map(o => o.field)).toEqual(['title', 'addedOn']);
  });

  it('passes the server facet total for a collapsed series on the all-books route, not the loaded-page count', async () => {
    const {paramMap$} = createHarness({
      allBooksFacets: [
        {key: 'series', values: [{value: 'Dune Saga', title: 'Dune Saga', count: 9}]},
      ],
    });
    paramMap$.next(convertToParamMap({}));
    TestBed.flushEffects();

    const filter = TestBed.inject(SeriesCollapseFilter);
    filter.setCollapsed(true);
    TestBed.flushEffects();

    // The mocked facets() queryFn resolves through TanStack's own scheduling, which needs a
    // real macrotask tick (fake timers never fire it) before the result signal updates.
    vi.useRealTimers();
    await new Promise(resolve => setTimeout(resolve, 10));
    TestBed.flushEffects();
    vi.useFakeTimers();

    const collapseBooksMock = vi.mocked(filter.collapseBooks);
    const seriesCounts = collapseBooksMock.mock.calls.at(-1)?.[3] as ReadonlyMap<string, number> | undefined;

    expect(seriesCounts?.get('Dune Saga')).toBe(9);
  });

  it.skip('uses the known total book count while more pages are available', () => {
    const {component, setHasNextPage} = createHarness({totalElements: 100});

    setHasNextPage(true);

    vi.runOnlyPendingTimers();
    TestBed.flushEffects();

    expect(component.virtualRowCount()).toBe(100);
    expect(component.virtualGrid.virtualizer.options().count).toBe(100);
  });

  it.skip('uses one unloaded slot for collapsed series while more pages are available', () => {
    const {component, setHasNextPage} = createHarness({totalElements: 100});
    const filter = TestBed.inject(SeriesCollapseFilter);
    filter.setCollapsed(true);
    setHasNextPage(true);

    vi.runOnlyPendingTimers();
    TestBed.flushEffects();

    expect(component.virtualRowCount()).toBe(component.books().length + 1);
    expect(component.virtualGrid.virtualizer.options().count).toBe(component.books().length + 1);
  });

  it('uses the rendered book count once pagination is exhausted', async () => {
    const {component} = createHarness();
    const filter = TestBed.inject(SeriesCollapseFilter);
    vi.mocked(filter.collapseBooks).mockImplementation((items: Book[]) => items.slice(0, 1));

    TestBed.flushEffects();
    await resolveQueries();
    TestBed.flushEffects();

    expect(component.books()).toHaveLength(1);
    expect(component.virtualRowCount()).toBe(1);
    expect(component.virtualGrid.virtualizer.options().count).toBe(1);
  });

  describe('server-paginated entity routes', () => {
    it('scopes the library route to a library facet and never touches bookService.books()', () => {
      const {bookQueryService, booksSignalSpy} = createHarness({
        route: {path: 'library/:libraryId/books', params: {libraryId: '7'}},
      });
      TestBed.flushEffects();

      const params = vi.mocked(bookQueryService.infinitePage).mock.calls.at(-1)?.[0];
      expect(params?.facets).toEqual({library: ['7']});
      expect(booksSignalSpy).not.toHaveBeenCalled();
    });

    it('scopes the shelf route to a shelf facet', () => {
      const {bookQueryService} = createHarness({
        route: {path: 'shelf/:shelfId/books', params: {shelfId: '9'}},
      });
      TestBed.flushEffects();

      const params = vi.mocked(bookQueryService.infinitePage).mock.calls.at(-1)?.[0];
      expect(params?.facets).toEqual({shelf: ['9']});
    });

    it('scopes the magic-shelf route to a magic-prefixed shelf facet', () => {
      const {bookQueryService} = createHarness({
        route: {path: 'magic-shelf/:magicShelfId/books', params: {magicShelfId: '3'}},
      });
      TestBed.flushEffects();

      const params = vi.mocked(bookQueryService.infinitePage).mock.calls.at(-1)?.[0];
      expect(params?.facets).toEqual({shelf: ['magic:3']});
    });

    it('scopes the unshelved-books route to shelf_status:unshelved', () => {
      const {bookQueryService} = createHarness({
        route: {path: 'unshelved-books', params: {}},
      });
      TestBed.flushEffects();

      const params = vi.mocked(bookQueryService.infinitePage).mock.calls.at(-1)?.[0];
      expect(params?.facets).toEqual({shelf_status: ['unshelved']});
    });
  });

  describe('selectAllBooks', () => {
    it('selects the ids /books/ids returns and never touches bookService.books()', async () => {
      const {component, bookQueryService, booksSignalSpy} = createHarness({
        ids: [10, 11, 12],
      });
      TestBed.flushEffects();
      await resolveQueries();
      TestBed.flushEffects();

      const selectionService = TestBed.inject(BookSelectionService);
      const pending = component.selectAllBooks();
      await resolveQueries();
      await pending;

      expect(bookQueryService.ids).toHaveBeenCalled();
      expect(vi.mocked(selectionService.selectAll)).toHaveBeenCalledWith([10, 11, 12]);
      expect(booksSignalSpy).not.toHaveBeenCalled();
      expect(component.selectAllLoading()).toBe(false);
    });

    it('ignores a re-entrant call while a select-all request is in flight', async () => {
      const {component, bookQueryService} = createHarness({ids: [1]});
      TestBed.flushEffects();
      await resolveQueries();
      TestBed.flushEffects();

      const first = component.selectAllBooks();
      const second = component.selectAllBooks();
      await resolveQueries();
      await Promise.all([first, second]);

      expect(bookQueryService.ids).toHaveBeenCalledTimes(1);
    });
  });
});
