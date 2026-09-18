import {AfterViewInit, ChangeDetectionStrategy, Component, DestroyRef, ElementRef, HostListener, computed, effect, inject, signal, untracked, viewChild} from '@angular/core';
import {takeUntilDestroyed, toObservable, toSignal} from '@angular/core/rxjs-interop';
import {ActivatedRoute} from '@angular/router';
import {ConfirmationService, MenuItem, MessageService} from '@openng/optimus-ui/api';
import {injectInfiniteQuery, injectQuery, keepPreviousData, QueryClient} from '@tanstack/angular-query-experimental';
import {PageTitleService} from '../../../../shared/service/page-title.service';
import {BookService} from '../../service/book.service';
import {BookQueryService} from '../../data/book-query.service';
import {bookSummaryToBook, flattenBookPages} from '../../data/book-query.models';
import {EntityScopeFacet, isServerSortField, toAllBooksQueryParams, toSeriesCountMap} from './all-books-query.mapper';
import {BookMetadataManageService} from '../../service/book-metadata-manage.service';
import {debounceTime, distinctUntilChanged, filter, map, skip, take} from 'rxjs/operators';
import {combineLatest, finalize} from 'rxjs';
import {DynamicDialogRef} from '@openng/optimus-ui/dynamicdialog';
import {Library} from '../../model/library.model';
import {SortDirection, SortOption} from '../../model/sort.model';
import {Book} from '../../model/book.model';
import {
  LibraryShelfMenuComponent,
  type LibraryShelfMenuTarget,
} from '../library-shelf-menu/library-shelf-menu.component';
import {BookTableComponent} from './book-table/book-table.component';
import {Button, ButtonDirective} from '@openng/optimus-ui/button';
import {NgClass} from '@angular/common';
import {BookCardComponent} from './book-card/book-card.component';

import {InputText} from '@openng/optimus-ui/inputtext';
import {FormsModule} from '@angular/forms';
import {BookFilterComponent} from './book-filter/book-filter.component';
import {Tooltip} from '@openng/optimus-ui/tooltip';
import {BookFilterMode, DEFAULT_VISIBLE_SORT_FIELDS, EntityViewPreferences, SortCriterion, UserService} from '../../../settings/user-management/user.service';
import {SeriesCollapseFilter} from './filters/SeriesCollapseFilter';
import {CoverScalePreferenceService} from './cover-scale-preference.service';
import {BookSorter} from './sorting/BookSorter';
import {BookDialogHelperService} from './book-dialog-helper.service';
import {Checkbox} from '@openng/optimus-ui/checkbox';
import {Popover} from '@openng/optimus-ui/popover';
import {Divider} from '@openng/optimus-ui/divider';
import {MultiSelect} from '@openng/optimus-ui/multiselect';
import {TableColumnPreferenceService} from './table-column-preference.service';
import {TieredMenu} from '@openng/optimus-ui/tieredmenu';
import {Badge} from '@openng/optimus-ui/badge';
import {BookMenuService} from '../../service/book-menu.service';
import {SidebarFilterTogglePrefService} from './filters/sidebar-filter-toggle-pref.service';
import {MetadataRefreshType} from '../../../metadata/model/request/metadata-refresh-type.enum';
import {TaskHelperService} from '../../../settings/task-management/task-helper.service';
import {FilterLabelHelper} from './filter-label.helper';
import {LoadingService} from '../../../../core/services/loading.service';
import {LocalStorageService} from '../../../../shared/service/local-storage.service';
import {LanguageResolverService} from '../../../../shared/service/language-resolver.service';
import {BookNavigationService} from '../../service/book-navigation.service';
import {BookCardOverlayPreferenceService} from './book-card-overlay-preference.service';
import {BookSelectionService, CheckboxClickEvent} from './book-selection.service';
import {BookBrowserQueryParamsService, VIEW_MODES} from './book-browser-query-params.service';
import {BookBrowserEntityService, EntityInfo} from './book-browser-entity.service';
import {RouteScrollPositionService} from '../../../../shared/service/route-scroll-position.service';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {MultiSortPopoverComponent} from './sorting/multi-sort-popover/multi-sort-popover.component';
import {TranslocoDirective, TranslocoPipe, TranslocoService} from '@jsverse/transloco';

import {createVirtualGrid, type VirtualGridMetrics} from '../../../../shared/util/virtual-grid.util';
import {GridDensityButtonsComponent, type GridDensityDirection} from '../../../../shared/components/grid-density-buttons/grid-density-buttons.component';
import {LayoutService} from '../../../../shared/layout/layout.service';
import {createGridDensity} from '../../../../shared/util/grid-density.util';
import {DeferredRenderState} from './deferred-render-state';
import {AppMenuTriggerDirective} from '../../../../shared/ui/menu/app-menu-trigger.directive';

export enum EntityType {
  LIBRARY = 'Library',
  SHELF = 'Shelf',
  MAGIC_SHELF = 'Magic Shelf',
  ALL_BOOKS = 'All Books',
  UNSHELVED = 'Unshelved Books',
}

const INITIAL_LOADING_ROW_COUNT = 24;
const DEFAULT_MOBILE_GRID_COLUMNS = 3;
const MIN_MOBILE_GRID_COLUMNS = 2;
const MAX_MOBILE_GRID_COLUMNS = 4;
const MOBILE_COLUMNS_STORAGE_KEY = 'mobileColumnsPreference';

@Component({
  selector: 'app-book-browser',
  standalone: true,
  templateUrl: './book-browser.component.html',
  styleUrls: ['./book-browser.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    Button, ButtonDirective, BookCardComponent, InputText, FormsModule,
    BookTableComponent, BookFilterComponent, Tooltip, NgClass, Popover,
    Checkbox, Divider, MultiSelect, TieredMenu, Badge, MultiSortPopoverComponent, TranslocoDirective, TranslocoPipe, GridDensityButtonsComponent,
    LibraryShelfMenuComponent, AppMenuTriggerDirective,
  ],
  providers: [SeriesCollapseFilter],
})
export class BookBrowserComponent implements AfterViewInit {
  protected userService = inject(UserService);
  protected coverScalePreferenceService = inject(CoverScalePreferenceService);
  protected columnPreferenceService = inject(TableColumnPreferenceService);
  protected sidebarFilterTogglePrefService = inject(SidebarFilterTogglePrefService);
  protected seriesCollapseFilter = inject(SeriesCollapseFilter);
  protected confirmationService = inject(ConfirmationService);
  protected taskHelperService = inject(TaskHelperService);
  protected bookCardOverlayPreferenceService = inject(BookCardOverlayPreferenceService);
  protected bookSelectionService = inject(BookSelectionService);
  protected appSettingsService = inject(AppSettingsService);

  private activatedRoute = inject(ActivatedRoute);
  private messageService = inject(MessageService);
  private bookService = inject(BookService);
  private bookQueryService = inject(BookQueryService);
  private queryClient = inject(QueryClient);
  private bookMetadataManageService = inject(BookMetadataManageService);
  private dialogHelperService = inject(BookDialogHelperService);
  private bookMenuService = inject(BookMenuService);
  private pageTitle = inject(PageTitleService);
  private loadingService = inject(LoadingService);
  private bookNavigationService = inject(BookNavigationService);
  private queryParamsService = inject(BookBrowserQueryParamsService);
  private entityService = inject(BookBrowserEntityService);
  private localStorageService = inject(LocalStorageService);
  private scrollService = inject(RouteScrollPositionService);
  private layoutService = inject(LayoutService);
  private readonly t = inject(TranslocoService);
  private readonly languageResolver = inject(LanguageResolverService);
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    this.setupRouteChangeHandlers();
    this.setupQueryParamSubscription();
    this.scrollService.trackRoute({
      scrollElement: this.scrollElement,
      route: this.activatedRoute,
      destroyRef: this.destroyRef,
      keySuffix: 'grid',
      dismissOverlaysBeforeSave: true,
    });
    this.destroyRef.onDestroy(() => this.bookSelectionService.deselectAll());
  }

  private readonly defaultSortCriteria: SortOption[] = [{
    field: 'addedOn',
    direction: SortDirection.DESCENDING,
    label: 'Added On'
  }];
  private readonly routePath = toSignal(
    this.activatedRoute.url.pipe(
      map(() => this.activatedRoute.snapshot.routeConfig?.path ?? '')
    ),
    {initialValue: this.activatedRoute.snapshot.routeConfig?.path ?? ''}
  );
  private readonly routeParamMap = toSignal(this.activatedRoute.paramMap, {
    initialValue: this.activatedRoute.snapshot.paramMap
  });
  private readonly queryParamMap = toSignal(this.activatedRoute.queryParamMap, {
    initialValue: this.activatedRoute.snapshot.queryParamMap
  });
  private readonly searchTerm = signal('');
  private readonly debouncedSearchTerm = toSignal(
    toObservable(this.searchTerm).pipe(
      debounceTime(500),
      distinctUntilChanged()
    ),
    {initialValue: this.searchTerm()}
  );
  private readonly selectedFilter = signal<Record<string, string[]> | null>(null);
  private readonly selectedFilterMode = signal<BookFilterMode>('and');
  private readonly sortCriteria = signal<SortOption[]>(this.defaultSortCriteria);

  readonly screenWidth = signal(typeof window !== 'undefined' ? window.innerWidth : 1024);
  readonly currentViewMode = signal<string | undefined>(undefined);
  readonly bookTitle = signal('');
  readonly visibleColumns = signal<{ field: string; header: string }[]>([]);
  readonly visibleSortOptions = signal<SortOption[]>([]);
  readonly currentFilterLabel = signal<string | null>(null);
  readonly rawFilterParamFromUrl = signal<string | null>(null);
  private readonly seriesCollapsed = this.seriesCollapseFilter.seriesCollapsed;
  readonly selectedBooks = this.bookSelectionService.selectedBooks;
  readonly selectAllLoading = signal(false);
  readonly selectedCount = this.bookSelectionService.selectedCount;
  readonly showFilter = this.sidebarFilterTogglePrefService.showFilter;
  private readonly currentUser$ = toObservable(this.userService.currentUser).pipe(filter(u => !!u));
  readonly entityInfo = computed<EntityInfo>(() => {
    const routePath = this.routePath();
    if (routePath === 'all-books') {
      return {entityId: NaN, entityType: EntityType.ALL_BOOKS};
    }
    if (routePath === 'unshelved-books') {
      return {entityId: NaN, entityType: EntityType.UNSHELVED};
    }
    return this.entityService.getEntityInfo(this.routeParamMap());
  });
  readonly entityType = computed(() => this.entityInfo().entityType);
  readonly entity = computed(() => {
    const {entityId, entityType} = this.entityInfo();
    return this.entityService.getEntity(entityId, entityType);
  });
  readonly entityMenuTarget = computed<LibraryShelfMenuTarget | null>(() => {
    const entity = this.entity();
    if (entity?.id == null) return null;

    if (this.entityService.isLibrary(entity)) {
      return {type: 'library', entity: {...entity, id: entity.id}};
    }
    if (this.entityService.isMagicShelf(entity)) {
      return {type: 'magicShelf', entity: {...entity, id: entity.id}};
    }
    return {type: 'shelf', entity: {...entity, id: entity.id}};
  });

  // Every book-browser route is server-paginated - the library is too large to hold
  // client-side, so search/sort/facet run server-side via BookQueryService. The entity scope
  // (library/shelf/magic-shelf/unshelved) rides along as one more mandatory facet; all-books
  // has none.
  private readonly entityScopeFacet = computed<EntityScopeFacet | null>(() => {
    const {entityId, entityType} = this.entityInfo();
    switch (entityType) {
      case EntityType.LIBRARY:
        return Number.isNaN(entityId) ? null : {key: 'library', value: String(entityId)};
      case EntityType.SHELF:
        return Number.isNaN(entityId) ? null : {key: 'shelf', value: String(entityId)};
      case EntityType.MAGIC_SHELF:
        // Server-side magic shelf rule evaluation lives behind the 'shelf' facet's 'magic:' prefix.
        return Number.isNaN(entityId) ? null : {key: 'shelf', value: `magic:${entityId}`};
      case EntityType.UNSHELVED:
        return {key: 'shelf_status', value: 'unshelved'};
      default:
        return null;
    }
  });
  private readonly booksQueryParams = computed(() => toAllBooksQueryParams({
    search: this.debouncedSearchTerm(),
    filters: this.selectedFilter(),
    filterMode: this.selectedFilterMode(),
    sort: this.sortCriteria(),
    scope: this.entityScopeFacet(),
  }));
  private readonly booksInfiniteQuery = injectInfiniteQuery(() => ({
    ...this.bookQueryService.infinitePage(this.booksQueryParams()),
    // Keeps the previous page's books on screen while a filter/sort/route change re-keys the query.
    placeholderData: keepPreviousData,
  }));
  private readonly fetchedBooks = computed<Book[]>(() =>
    flattenBookPages(this.booksInfiniteQuery.data()).map(bookSummaryToBook)
  );
  private readonly booksTotalElements = computed(() =>
    this.booksInfiniteQuery.data()?.pages[0]?.page.totalElements
  );

  // Deferred pipeline: heavy filter/sort runs in a setTimeout so the page chrome
  // and skeletons paint first, then real books replace them on the next task.
  private readonly forceExpandSeries = computed(() =>
    this.queryParamsService.shouldForceExpandSeries(this.queryParamMap())
  );

  // Collapsed series cards need the REAL per-series count, not the count among pages fetched
  // so far (which grows as the user scrolls) - the 'series' facet already totals it server-side
  // under the current filters/scope, so fetch it only when a collapsed card would actually show it.
  private readonly seriesFacetQuery = injectQuery(() => ({
    ...this.bookQueryService.facets({...this.booksQueryParams(), group: ['series']}),
    enabled: this.seriesCollapsed() && !this.forceExpandSeries(),
  }));
  private readonly seriesCounts = computed(() =>
    toSeriesCountMap(this.seriesFacetQuery.data())
  );
  private readonly booksContextKey = computed(() => {
    const {entityId, entityType} = this.entityInfo();
    return Number.isNaN(entityId) ? entityType : `${entityType}:${entityId}`;
  });
  private lastBooksContextKey: string | null = null;
  private readonly booksRenderState = new DeferredRenderState<Book[]>();
  readonly books = computed(() => this.booksRenderState.value() ?? []);
  readonly hasRenderedBooks = this.booksRenderState.hasValue;
  readonly isBooksRefreshing = this.booksRenderState.isRefreshing;

  private readonly computeBooksEffect = effect(() => {
    const contextKey = this.booksContextKey();
    const collapsedFlag = this.seriesCollapsed();
    const forceExpand = this.forceExpandSeries();

    const sameContext = contextKey === this.lastBooksContextKey;
    const shouldRefresh = sameContext && untracked(() => this.hasRenderedBooks());
    const requestId = this.booksRenderState.begin(shouldRefresh ? 'refresh' : 'reset');
    this.lastBooksContextKey = contextKey;

    // Server already applied search/sort/facet/scope; only the (cheap) series collapse runs here.
    const fetched = this.fetchedBooks();
    const seriesCounts = this.seriesCounts();
    const collapsed = this.seriesCollapseFilter.collapseBooks(fetched, forceExpand, collapsedFlag, seriesCounts);
    this.booksRenderState.commit(requestId, collapsed);
  });

  readonly isBooksLoading = computed(() => this.booksInfiniteQuery.isPending());
  readonly booksError = computed<string | null>(() => {
    if (!this.booksInfiniteQuery.isError()) return null;
    const error = this.booksInfiniteQuery.error();
    return error instanceof Error ? error.message : 'Failed to load books';
  });

  private readonly GRID_GAP = 21;
  private readonly CARD_ASPECT_RATIO = 7 / 5;
  private readonly MOBILE_TITLE_BAR_HEIGHT = 32;
  private readonly DESKTOP_CARD_BASE_WIDTH = 135;
  private readonly DESKTOP_CARD_BASE_HEIGHT = 220;
  private readonly DESKTOP_MIN_SCALE = 0.5;
  private readonly DESKTOP_MAX_SCALE = 1.5;
  private readonly AUDIOBOOK_TITLE_BAR_HEIGHT = 31;
  private readonly scrollElement = viewChild<ElementRef<HTMLElement>>('scrollElement');
  private readonly initialScrollOffset = () => this.scrollService.getPosition(this.scrollService.keyFor(this.activatedRoute, 'grid')) ?? 0;
  readonly isMobile = computed(() => !this.layoutService.isDesktop());
  private readonly desktopBaseCardWidth = computed(() =>
    this.isAudiobookOnlyLibrary()
      ? this.DESKTOP_CARD_BASE_WIDTH * 1.1
      : this.DESKTOP_CARD_BASE_WIDTH
  );
  private readonly gridDensity = createGridDensity(this.localStorageService, {
    useFixedColumns: this.isMobile,
    screenWidth: this.screenWidth,
    storageKey: MOBILE_COLUMNS_STORAGE_KEY,
    defaultColumns: DEFAULT_MOBILE_GRID_COLUMNS,
    minColumns: MIN_MOBILE_GRID_COLUMNS,
    maxColumns: MAX_MOBILE_GRID_COLUMNS,
    scale: this.coverScalePreferenceService.scaleFactor,
    minScale: this.DESKTOP_MIN_SCALE,
    maxScale: this.DESKTOP_MAX_SCALE,
    gap: this.GRID_GAP,
    baseWidth: this.desktopBaseCardWidth,
    setScale: scale => this.coverScalePreferenceService.setScale(scale),
  });
  private readonly minCardWidth = computed(() =>
    this.isMobile()
      ? 1
      : Math.round(this.desktopBaseCardWidth() * this.coverScalePreferenceService.scaleFactor())
  );
  readonly virtualRowCount = computed(() => this.bookCountIncludingUnloadedPages(this.books().length));
  private readonly hasUnloadedBooks = computed(() => this.books().length < this.virtualRowCount());
  readonly loadedBookCount = computed(() => this.books().length);
  readonly virtualGrid = createVirtualGrid({
    items: this.books,
    scrollElement: this.scrollElement,
    minItemWidth: this.minCardWidth,
    gap: this.gridDensity.gap,
    columns: this.gridDensity.columns,
    count: this.virtualRowCount,
    minimumCount: metrics => this.minimumLoadingGridItemCount(metrics),
    initialOffset: this.initialScrollOffset,
    fillItemWidth: true,
    deferViewportUpdates: this.layoutService.sidebarTransitioning,
    estimateItemHeight: itemWidth => this.isMobile()
      ? this.mobileCardSizeForWidth(itemWidth).height
      : this.cardSizeForWidth(itemWidth).height,
  });
  readonly bookQueryToken = computed(() => ({
    entity: this.entityInfo(),
    search: this.debouncedSearchTerm(),
    filter: this.selectedFilter(),
    filterMode: this.selectedFilterMode(),
    sort: this.sortCriteria(),
  }));
  private gridLastLoadRequestLoadedBookCount: number | undefined;
  private gridLastSeenQueryToken: unknown;
  private readonly gridPaginatorEffect = effect(() => {
    if (this.currentViewMode() !== VIEW_MODES.GRID) return;

    const queryToken = this.bookQueryToken();
    if (queryToken !== this.gridLastSeenQueryToken) {
      this.gridLastLoadRequestLoadedBookCount = undefined;
      this.gridLastSeenQueryToken = queryToken;
    }

    const items = this.books();
    const loadedBookCount = this.loadedBookCount();
    const lastVirtualItem = this.virtualGrid.virtualizer.getVirtualItems().at(-1);
    if (!lastVirtualItem || items.length === 0) return;
    if (lastVirtualItem.index < items.length - 1) return;
    if (!this.hasUnloadedBooks()) return;
    if (this.gridLastLoadRequestLoadedBookCount === loadedBookCount) {
      this.gridLastLoadRequestLoadedBookCount = undefined;
      return;
    }

    this.gridLastLoadRequestLoadedBookCount = loadedBookCount;
    this.loadNextBooksPage();
  });
  readonly isFetchingNextBooksPage = computed(() => this.booksInfiniteQuery.isFetchingNextPage());

  parsedFilters: Record<string, string[]> = {};
  dynamicDialogRef: DynamicDialogRef | undefined | null;
  EntityType = EntityType;
  private readonly activeLang = toSignal(this.t.langChanges$, {
    initialValue: this.t.getActiveLang()
  });

  readonly computedFilterLabel = computed(() => {
    this.activeLang();
    const filters = this.selectedFilter();

    if (!filters || Object.keys(filters).length === 0) {
      return this.t.translate('book.browser.labels.allBooks');
    }

    const filterEntries = Object.entries(filters);

    if (filterEntries.length === 1) {
      const [filterType, values] = filterEntries[0];
      const filterName = FilterLabelHelper.getFilterTypeName(filterType);

      if (values.length === 1) {
        const displayValue = filterType === 'language'
          ? this.languageResolver.displayName(String(values[0]))
          : FilterLabelHelper.getFilterDisplayValue(filterType, values[0]);
        return `${filterName}: ${displayValue}`;
      }

      return `${filterName} (${values.length})`;
    }

    const filterSummary = filterEntries
      .map(([type, values]) => `${FilterLabelHelper.getFilterTypeName(type)} (${values.length})`)
      .join(', ');

    return filterSummary.length > 50
      ? this.t.translate('book.browser.labels.activeFilters', {count: filterEntries.length})
      : filterSummary;
  });
  entityViewPreferences: EntityViewPreferences | undefined;
  lastAppliedSortCriteria: SortOption[] = [];

  private settingFiltersFromUrl = false;
  protected metadataMenuItems: MenuItem[] | undefined;
  protected moreActionsMenuItems: MenuItem[] | undefined;
  protected readonly onBookCardSelect = (book: Book, selected: boolean): void => {
    this.handleBookSelect(book, selected);
  };

  protected bookSorter = new BookSorter(
    sortCriteria => this.onMultiSortChange(sortCriteria),
    this.t
  );
  private readonly syncBrowserStateEffect = effect(() => {
    this.activeLang();
    const entityType = this.entityType();
    const entity = this.entity();

    if (entityType === EntityType.ALL_BOOKS) {
      this.pageTitle.setPageTitle(this.t.translate('book.browser.labels.allBooks'));
      this.seriesCollapseFilter.setContext(null, null);
      return;
    }

    if (entityType === EntityType.UNSHELVED) {
      this.pageTitle.setPageTitle(this.t.translate('book.browser.labels.unshelvedBooks'));
      this.seriesCollapseFilter.setContext(null, null);
      return;
    }

    if (entity) {
      this.pageTitle.setPageTitle(entity.name);
    }

    if (!entity) {
      this.seriesCollapseFilter.setContext(null, null);
      return;
    }

    switch (entityType) {
      case EntityType.LIBRARY:
        this.seriesCollapseFilter.setContext('LIBRARY', entity.id ?? 0);
        break;
      case EntityType.SHELF:
        this.seriesCollapseFilter.setContext('SHELF', entity.id ?? 0);
        break;
      case EntityType.MAGIC_SHELF:
        this.seriesCollapseFilter.setContext('MAGIC_SHELF', entity.id ?? 0);
        break;
      default:
        this.seriesCollapseFilter.setContext(null, null);
    }
  });
  private readonly syncBooksEffect = effect(() => {
    const books = this.books();
    this.bookSelectionService.setCurrentBooks(books);
    this.bookNavigationService.setAvailableBookIds(books.map(book => book.id));
  });
  private readonly syncMoreActionsMenuEffect = effect(() => {
    this.moreActionsMenuItems = this.bookMenuService.getMoreActionsMenu(
      this.selectedBooks(),
      this.userService.currentUser()
    );
  });

  private readonly bookTableComponent = viewChild(BookTableComponent);
  private readonly bookFilterComponent = viewChild(BookFilterComponent);

  @HostListener('window:resize')
  onResize(): void {
    this.screenWidth.set(window.innerWidth);
  }

  readonly gridDensitySmallerDisabled = this.gridDensity.smallerDisabled;
  readonly gridDensityLargerDisabled = this.gridDensity.largerDisabled;

  private cardSizeForWidth(width: number): { width: number; height: number } {
    const cardWidth = Math.round(width);
    if (this.isAudiobookOnlyLibrary()) {
      return {width: cardWidth, height: cardWidth + this.AUDIOBOOK_TITLE_BAR_HEIGHT};
    }
    return {
      width: cardWidth,
      height: Math.round(cardWidth * (this.DESKTOP_CARD_BASE_HEIGHT / this.DESKTOP_CARD_BASE_WIDTH)),
    };
  }

  private mobileCardSizeForWidth(width: number): { width: number; height: number } {
    const cardWidth = Math.round(width);
    const coverHeight = this.isAudiobookOnlyLibrary()
      ? cardWidth
      : Math.floor(cardWidth * this.CARD_ASPECT_RATIO);
    return {width: cardWidth, height: coverHeight + this.MOBILE_TITLE_BAR_HEIGHT};
  }

  readonly showBooksLoadingPlaceholder = computed(() =>
    !this.booksError() && (!this.hasRenderedBooks() || (this.isBooksLoading() && this.books().length === 0))
  );

  readonly showTableLoadingPlaceholder = computed(() =>
    this.showBooksLoadingPlaceholder() && this.currentViewMode() === VIEW_MODES.TABLE
  );

  private bookCountIncludingUnloadedPages(renderedBookCount: number): number {
    if (this.showBooksLoadingPlaceholder()) {
      return INITIAL_LOADING_ROW_COUNT;
    }
    if (this.booksInfiniteQuery.hasNextPage()) {
      // Series collapsing makes the eventual rendered total unpredictable; reserve one slot
      // instead. Uncollapsed, the server's total gives the virtualizer its real final size.
      const total = this.booksTotalElements();
      return !this.seriesCollapsed() && total !== undefined ? total : renderedBookCount + 1;
    }
    return renderedBookCount;
  }

  private minimumLoadingGridItemCount({viewportHeight, columns, itemHeight, gap}: VirtualGridMetrics): number {
    if (!this.showBooksLoadingPlaceholder() || this.currentViewMode() !== VIEW_MODES.GRID) {
      return 0;
    }
    if (viewportHeight <= 0 || itemHeight <= 0) {
      return INITIAL_LOADING_ROW_COUNT;
    }

    const visibleRows = Math.ceil((viewportHeight + gap) / (itemHeight + gap));
    return Math.max(INITIAL_LOADING_ROW_COUNT, (visibleRows + 1) * columns);
  }

  readonly viewIcon = computed(() =>
    this.currentViewMode() === VIEW_MODES.TABLE ? 'pi pi-table' : 'pi pi-objects-column'
  );

  readonly isFilterActive = computed(() => {
    const selectedFilter = this.selectedFilter();
    return !!selectedFilter && Object.keys(selectedFilter).length > 0;
  });

  readonly isAudiobookOnlyLibrary = computed(() => {
    const entity = this.entity();
    if (!entity || this.entityType() !== EntityType.LIBRARY) return false;
    const library = entity as Library;
    return !!library.allowedFormats && library.allowedFormats.length === 1 && library.allowedFormats[0] === 'AUDIOBOOK';
  });

  readonly seriesViewEnabled = computed(() => Boolean(this.userService.getCurrentUser()?.userSettings?.enableSeriesView));

  readonly hasMetadataMenuItems = computed(() => (this.metadataMenuItems?.length ?? 0) > 0);

  readonly hasMoreActionsItems = computed(() => (this.moreActionsMenuItems?.length ?? 0) > 0);

  readonly canSaveSort = computed(() => {
    const entityType = this.entityType();
    return entityType === EntityType.LIBRARY ||
           entityType === EntityType.SHELF ||
           entityType === EntityType.MAGIC_SHELF ||
           entityType === EntityType.ALL_BOOKS ||
           entityType === EntityType.UNSHELVED;
  });

  readonly hasSearchTerm = computed(() => this.searchTerm().trim().length > 0);

  readonly sortCriteriaCount = computed(() => this.bookSorter.selectedSortCriteria.length);

  ngAfterViewInit(): void {
    const bookFilterComponent = this.bookFilterComponent();
    if (bookFilterComponent) {
      bookFilterComponent.setFilters(this.parsedFilters);
      bookFilterComponent.onFilterModeChange(this.selectedFilterMode());
    }
  }

  private setupRouteChangeHandlers(): void {
    this.activatedRoute.paramMap.pipe(
      skip(1),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(() => {
      this.searchTerm.set('');
      this.bookTitle.set('');
      this.bookSelectionService.deselectAll();
      this.clearFilter();
      this.scrollToTop();
    });
  }

  private scrollToTop(): void {
    const scrollElement = this.scrollElement()?.nativeElement;
    if (scrollElement) {
      scrollElement.scrollTop = 0;
    }
    this.bookTableComponent()?.scrollToTop();
    this.virtualGrid.virtualizer.scrollToOffset(0);
  }

  private readonly syncMetadataMenuEffect = effect(() => {
    const user = this.userService.currentUser();
    if (!user) return;

    this.metadataMenuItems = this.bookMenuService.getMetadataMenuItems(
      () => this.autoFetchMetadata(),
      () => this.fetchMetadata(),
      () => this.bulkEditMetadata(),
      () => this.multiBookEditMetadata(),
      () => this.regenerateCoversForSelected(),
      () => this.generateCustomCoversForSelected(),
      user
    );
  });

  private setupQueryParamSubscription(): void {
    combineLatest([
      this.activatedRoute.paramMap.pipe(map(() => this.entityInfo())),
      this.activatedRoute.queryParamMap,
      this.currentUser$,
    ]).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(([entityInfo, queryParamMap, currentUser]) => {
      const parseResult = this.queryParamsService.parseQueryParams(
        queryParamMap,
        currentUser.userSettings?.entityViewPreferences,
        entityInfo.entityType,
        entityInfo.entityId,
        this.bookSorter.sortOptions,
        currentUser.userSettings?.filterMode ?? 'and'
      );

      if (parseResult.filterMode !== this.selectedFilterMode()) {
        this.selectedFilterMode.set(parseResult.filterMode);
        this.bookFilterComponent()?.onFilterModeChange(parseResult.filterMode);
      }

      const filterParams = queryParamMap.get('filter');

      if (filterParams) {
        this.settingFiltersFromUrl = true;
        this.selectedFilter.set(parseResult.filters);

        this.bookFilterComponent()?.setFilters?.(parseResult.filters);

        if (Object.keys(parseResult.filters).length > 0) {
          this.currentFilterLabel.set(this.computedFilterLabel());
        }

        this.rawFilterParamFromUrl.set(filterParams);
        this.settingFiltersFromUrl = false;
      } else {
        this.clearFilter();
        this.rawFilterParamFromUrl.set(null);
      }

      this.parsedFilters = parseResult.filters;

      this.entityViewPreferences = currentUser.userSettings?.entityViewPreferences;
      this.columnPreferenceService.initPreferences(currentUser.userSettings?.tableColumnPreference);
      this.visibleColumns.set(this.columnPreferenceService.visibleColumns);

      const visibleFields = currentUser.userSettings?.visibleSortFields ?? DEFAULT_VISIBLE_SORT_FIELDS;
      const sortOptionsByField = new Map(this.bookSorter.sortOptions.map(o => [o.field, o]));
      const resolvedSortOptions = visibleFields.map(f => sortOptionsByField.get(f)).filter((o): o is SortOption => !!o);
      // Every route sorts server-side now; only offer fields BookSortRegistry can actually
      // apply, so a chosen sort is never silently ignored.
      this.visibleSortOptions.set(resolvedSortOptions.filter(o => isServerSortField(o.field)));

      if (!this.areSortCriteriaEqual(this.bookSorter.selectedSortCriteria, parseResult.sortCriteria)) {
        this.bookSorter.setSortCriteria(parseResult.sortCriteria);
      }
      this.currentViewMode.set(parseResult.viewMode);

      this.applySortCriteria(this.bookSorter.selectedSortCriteria);

      this.queryParamsService.syncQueryParams(
        this.currentViewMode()!,
        this.selectedFilterMode(),
        this.parsedFilters
      );
    });
  }

  onFilterSelected(filters: Record<string, unknown> | null): void {
    if (this.settingFiltersFromUrl) return;

    const normalizedFilters = filters
      ? Object.fromEntries(
          Object.entries(filters).map(([key, value]) => [
            key,
            (Array.isArray(value) ? value : [value]).map(filterValue => String(filterValue))
          ])
        )
      : null;

    this.selectedFilter.set(normalizedFilters);
    this.rawFilterParamFromUrl.set(null);

    const hasSidebarFilters = !!normalizedFilters && Object.keys(normalizedFilters).length > 0;
    this.currentFilterLabel.set(hasSidebarFilters ? this.computedFilterLabel() : this.t.translate('book.browser.labels.allBooks'));
    this.queryParamsService.updateFilters(normalizedFilters);
  }

  onFilterModeChanged(mode: BookFilterMode): void {
    if (this.settingFiltersFromUrl || mode === this.selectedFilterMode()) return;

    this.selectedFilterMode.set(mode);
    this.queryParamsService.updateFilterMode(mode, this.parsedFilters);
  }

  toggleSidebar(): void {
    this.sidebarFilterTogglePrefService.toggle();
  }

  onVisibleColumnsChange(selected: { field: string; header: string }[]): void {
    const allFields = this.columnPreferenceService.allColumns.map(column => column.field);
    this.visibleColumns.set(selected.sort(
      (a, b) => allFields.indexOf(a.field) - allFields.indexOf(b.field)
    ));
  }

  onCheckboxClicked(event: CheckboxClickEvent): void {
    this.bookSelectionService.handleCheckboxClick(event);
  }

  handleBookSelect(book: Book, selected: boolean): void {
    this.bookSelectionService.handleBookSelection(book, selected);
  }

  async selectAllBooks(): Promise<void> {
    if (this.selectAllLoading()) return;
    this.selectAllLoading.set(true);
    try {
      // The current route/search/filter/sort scope, same as the page query - /books/ids
      // returns just the matching ids instead of downloading the whole collection to select it.
      const ids = await this.queryClient.fetchQuery(this.bookQueryService.ids(this.booksQueryParams()));
      this.bookSelectionService.selectAll(ids);
    } finally {
      this.selectAllLoading.set(false);
    }
  }

  loadNextBooksPage(): void {
    if (this.booksInfiniteQuery.isFetchingNextPage() || !this.booksInfiniteQuery.hasNextPage()) return;
    void this.booksInfiniteQuery.fetchNextPage();
  }

  deselectAllBooks(): void {
    this.bookSelectionService.deselectAll();
  }

  confirmDeleteBooks(): void {
    const selectedBooks = this.selectedBooks();
    this.confirmationService.confirm({
      message: this.t.translate('book.browser.confirm.deleteMessage', {count: selectedBooks.size}),
      header: this.t.translate('book.browser.confirm.deleteHeader'),
      icon: 'pi pi-exclamation-triangle',
      acceptIcon: 'pi pi-trash',
      rejectIcon: 'pi pi-times',
      acceptLabel: this.t.translate('common.delete'),
      rejectLabel: this.t.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-danger',
      rejectButtonStyleClass: 'p-button-outlined',
      accept: () => {
        const count = selectedBooks.size;
        const loader = this.loadingService.show(this.t.translate('book.browser.loading.deleting', {count}));

        this.bookService.deleteBooks(selectedBooks)
          .pipe(finalize(() => this.loadingService.hide(loader)))
          .subscribe(() => {
            this.bookSelectionService.deselectAll();
          });
      }
    });
  }

  onSeriesCollapseCheckboxChange(value: boolean): void {
    this.seriesCollapseFilter.setCollapsed(value);
  }

  onMultiSortChange(sortCriteria: SortOption[]): void {
    this.applySortCriteria(sortCriteria);
    this.queryParamsService.updateMultiSort(sortCriteria);
  }

  // Backward compatibility wrapper
  onManualSortChange(sortOption: SortOption): void {
    this.onMultiSortChange([sortOption]);
  }

  applySortCriteria(sortCriteria: SortOption[]): void {
    this.sortCriteria.set(sortCriteria.length > 0 ? sortCriteria : this.defaultSortCriteria);
  }

  // Backward compatibility wrapper
  applySortOption(sortOption: SortOption): void {
    this.applySortCriteria([sortOption]);
  }

  private areSortCriteriaEqual(a: SortOption[], b: SortOption[]): boolean {
    if (a.length !== b.length) return false;
    return a.every((criterion, index) =>
      criterion.field === b[index].field && criterion.direction === b[index].direction
    );
  }

  onSortCriteriaChange(criteria: SortOption[]): void {
    this.bookSorter.setSortCriteria(criteria);
    this.onMultiSortChange(criteria);
  }

  onSaveSortConfig(criteria: SortOption[]): void {
    const entityType = this.entityType();
    if (!entityType) return;

    const user = this.userService.getCurrentUser();
    if (!user) return;
    const entity = this.entity();

    const sortCriteria: SortCriterion[] = criteria.map(c => ({
      field: c.field,
      direction: c.direction === SortDirection.ASCENDING ? 'ASC' as const : 'DESC' as const
    }));

    const prefs: EntityViewPreferences = structuredClone(
      user.userSettings.entityViewPreferences ?? {global: {sortKey: 'title', sortDir: 'ASC', view: 'GRID', coverSize: 1.0, seriesCollapsed: false, overlayBookType: true}, overrides: []}
    );

    if (entityType === EntityType.ALL_BOOKS || entityType === EntityType.UNSHELVED) {
      prefs.global = {
        ...prefs.global,
        sortKey: sortCriteria[0]?.field ?? 'title',
        sortDir: sortCriteria[0]?.direction ?? 'ASC',
        sortCriteria
      };
    } else {
      if (!entity) return;
      if (!prefs.overrides) prefs.overrides = [];

      let overrideEntityType: 'LIBRARY' | 'SHELF' | 'MAGIC_SHELF';
      switch (entityType) {
        case EntityType.LIBRARY: overrideEntityType = 'LIBRARY'; break;
        case EntityType.SHELF: overrideEntityType = 'SHELF'; break;
        case EntityType.MAGIC_SHELF: overrideEntityType = 'MAGIC_SHELF'; break;
        default: return;
      }

      const existingIndex = prefs.overrides.findIndex(
        o => o.entityType === overrideEntityType && o.entityId === entity.id
      );

      if (existingIndex >= 0) {
        prefs.overrides[existingIndex].preferences = {
          ...prefs.overrides[existingIndex].preferences,
          sortKey: sortCriteria[0]?.field ?? 'title',
          sortDir: sortCriteria[0]?.direction ?? 'ASC',
          sortCriteria
        };
      } else {
        prefs.overrides.push({
          entityType: overrideEntityType,
          entityId: entity.id!,
          preferences: {
            sortKey: sortCriteria[0]?.field ?? 'title',
            sortDir: sortCriteria[0]?.direction ?? 'ASC',
            sortCriteria,
            view: 'GRID',
            coverSize: 1.0,
            seriesCollapsed: false,
            overlayBookType: true
          }
        });
      }
    }

    this.userService.updateUserSetting(user.id, 'entityViewPreferences', prefs);
    this.messageService.add({
      severity: 'success',
      summary: this.t.translate('book.browser.toast.sortSavedSummary'),
      detail: entityType === EntityType.ALL_BOOKS || entityType === EntityType.UNSHELVED
        ? this.t.translate('book.browser.toast.sortSavedGlobalDetail')
        : this.t.translate('book.browser.toast.sortSavedEntityDetail', {entityType: entityType.toLowerCase()})
    });
  }

  onSearchTermChange(term: string): void {
    this.searchTerm.set(term);
  }

  clearSearch(): void {
    this.bookTitle.set('');
    this.onSearchTermChange('');
    this.resetFilters();
  }

  resetFilters(): void {
    this.bookFilterComponent()?.clearActiveFilter();
  }

  clearFilter(): void {
    if (this.selectedFilter() !== null) {
      this.selectedFilter.set(null);
    }
    this.clearSearch();
  }

  toggleTableGrid(): void {
    const newMode = this.currentViewMode() === VIEW_MODES.GRID ? VIEW_MODES.TABLE : VIEW_MODES.GRID;
    this.currentViewMode.set(newMode);
    this.queryParamsService.updateViewMode(newMode as 'grid' | 'table');
  }

  onViewModeChange(mode: string): void {
    if (mode && mode !== this.currentViewMode()) {
      this.currentViewMode.set(mode);
      this.queryParamsService.updateViewMode(mode as 'grid' | 'table');
    }
  }

  unshelfBooks(): void {
    const entity = this.entity();
    if (!entity) return;
    const selectedBooks = this.selectedBooks();
    const count = selectedBooks.size;
    const loader = this.loadingService.show(this.t.translate('book.browser.loading.unshelving', {count}));

    this.bookService.updateBookShelves(selectedBooks, new Set(), new Set([entity.id!]))
      .pipe(finalize(() => this.loadingService.hide(loader)))
      .subscribe({
        next: () => {
          this.messageService.add({severity: 'info', summary: this.t.translate('common.success'), detail: this.t.translate('book.browser.toast.unshelveSuccessDetail')});
          this.bookSelectionService.deselectAll();
        },
        error: () => {
          this.messageService.add({severity: 'error', summary: this.t.translate('common.error'), detail: this.t.translate('book.browser.toast.unshelveFailedDetail')});
        }
      });
  }

  async openShelfAssigner() {
    this.dynamicDialogRef = await this.dialogHelperService.openShelfAssignerDialog(null, this.selectedBooks());
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.pipe(take(1)).subscribe(result => {
        if (result?.assigned) {
          this.bookSelectionService.deselectAll();
        }
      });
    }
  }

  async lockUnlockMetadata() {
    this.dynamicDialogRef = await this.dialogHelperService.openLockUnlockMetadataDialog(this.selectedBooks());
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.pipe(take(1)).subscribe(() => {
        this.bookSelectionService.deselectAll();
      });
    }
  }

  autoFetchMetadata(): void {
    const selectedBooks = this.selectedBooks();
    if (selectedBooks.size === 0) return;
    this.taskHelperService.refreshMetadataTask({
      refreshType: MetadataRefreshType.BOOKS,
      bookIds: Array.from(selectedBooks),
    }).subscribe();
  }

  async fetchMetadata() {
    await this.dialogHelperService.openMetadataRefreshDialog(this.selectedBooks());
  }

  async bulkEditMetadata() {
    this.dynamicDialogRef = await this.dialogHelperService.openBulkMetadataEditDialog(this.selectedBooks());
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.pipe(take(1)).subscribe(() => {
        this.bookSelectionService.deselectAll();
      });
    }
  }

  async multiBookEditMetadata() {
    this.dynamicDialogRef = await this.dialogHelperService.openMultibookMetadataEditorDialog(this.selectedBooks());
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.pipe(take(1)).subscribe(() => {
        this.bookSelectionService.deselectAll();
      });
    }
  }

  regenerateCoversForSelected(): void {
    const selectedBooks = this.selectedBooks();
    if (selectedBooks.size === 0) return;
    const count = selectedBooks.size;
    this.confirmationService.confirm({
      message: this.t.translate('book.browser.confirm.regenCoverMessage', {count}),
      header: this.t.translate('book.browser.confirm.regenCoverHeader'),
      icon: 'pi pi-image',
      acceptLabel: this.t.translate('common.yes'),
      rejectLabel: this.t.translate('common.no'),
      acceptButtonProps: {
        label: this.t.translate('common.yes'),
        severity: 'success'
      },
      rejectButtonProps: {
        label: this.t.translate('common.no'),
        severity: 'secondary'
      },
      accept: () => {
        this.bookMetadataManageService.regenerateCoversForBooks(Array.from(selectedBooks)).subscribe({
          next: () => {
            this.messageService.add({
              severity: 'success',
              summary: this.t.translate('book.browser.toast.regenCoverStartedSummary'),
              detail: this.t.translate('book.browser.toast.regenCoverStartedDetail', {count}),
              life: 3000
            });
          },
          error: () => {
            this.messageService.add({
              severity: 'error',
              summary: this.t.translate('book.browser.toast.failedSummary'),
              detail: this.t.translate('book.browser.toast.regenCoverFailedDetail'),
              life: 3000
            });
          }
        });
      }
    });
  }

  generateCustomCoversForSelected(): void {
    const selectedBooks = this.selectedBooks();
    if (selectedBooks.size === 0) return;
    const count = selectedBooks.size;
    this.confirmationService.confirm({
      message: this.t.translate('book.browser.confirm.customCoverMessage', {count}),
      header: this.t.translate('book.browser.confirm.customCoverHeader'),
      icon: 'pi pi-palette',
      acceptLabel: this.t.translate('common.yes'),
      rejectLabel: this.t.translate('common.no'),
      acceptButtonProps: {
        label: this.t.translate('common.yes'),
        severity: 'success'
      },
      rejectButtonProps: {
        label: this.t.translate('common.no'),
        severity: 'secondary'
      },
      accept: () => {
        this.bookMetadataManageService.generateCustomCoversForBooks(Array.from(selectedBooks)).subscribe({
          next: () => {
            this.messageService.add({
              severity: 'success',
              summary: this.t.translate('book.browser.toast.customCoverStartedSummary'),
              detail: this.t.translate('book.browser.toast.customCoverStartedDetail', {count}),
              life: 3000
            });
          },
          error: () => {
            this.messageService.add({
              severity: 'error',
              summary: this.t.translate('book.browser.toast.failedSummary'),
              detail: this.t.translate('book.browser.toast.customCoverFailedDetail'),
              life: 3000
            });
          }
        });
      }
    });
  }

  async moveFiles() {
    await this.dialogHelperService.openFileMoverDialog(this.selectedBooks());
  }

  async attachFilesToBook() {
    const selectedBookIds = Array.from(this.selectedBooks());
    const sourceBooks = this.books().filter(book =>
      selectedBookIds.includes(book.id)
    );

    if (sourceBooks.length === 0) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('book.browser.toast.noEligibleBooksSummary'),
        detail: this.t.translate('book.browser.toast.noEligibleBooksDetail')
      });
      return;
    }

    // Check if all books are from the same library
    const libraryIds = new Set(sourceBooks.map(b => b.libraryId));
    if (libraryIds.size > 1) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('book.browser.toast.multipleLibrariesSummary'),
        detail: this.t.translate('book.browser.toast.multipleLibrariesDetail')
      });
      return;
    }

    this.dynamicDialogRef = await this.dialogHelperService.openBulkBookFileAttacherDialog(sourceBooks);
    if (this.dynamicDialogRef) {
      this.dynamicDialogRef.onClose.pipe(take(1)).subscribe(result => {
        if (result?.success) {
          this.bookSelectionService.deselectAll();
        }
      });
    }
  }

  canAttachFiles(): boolean {
    const selectedBookIds = Array.from(this.selectedBooks());
    if (selectedBookIds.length === 0) return false;

    const selectedBooks = this.books().filter(book =>
      selectedBookIds.includes(book.id)
    );

    if (selectedBooks.length === 0) return false;

    const libraryIds = new Set(selectedBooks.map(b => b.libraryId));
    return libraryIds.size === 1;
  }

  adjustGridDensity(direction: GridDensityDirection): void {
    this.gridDensity.adjust(direction, this.virtualGrid);
  }
}
