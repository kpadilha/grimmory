import {Component, DestroyRef, ElementRef, HostListener, computed, effect, inject, OnInit, signal, viewChild} from '@angular/core';
import {toObservable, toSignal} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {debounceTime, distinctUntilChanged} from 'rxjs/operators';
import {injectInfiniteQuery, keepPreviousData} from '@tanstack/angular-query-experimental';
import {ProgressSpinner} from '@openng/optimus-ui/progressspinner';
import {InputText} from '@openng/optimus-ui/inputtext';
import {Select} from '@openng/optimus-ui/select';
import {Popover} from '@openng/optimus-ui/popover';
import {TranslocoDirective, TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {SeriesDataService, SeriesPage} from '../../service/series-data.service';
import {SeriesSummary} from '../../model/series.model';
import {SeriesCardComponent} from '../series-card/series-card.component';
import {PageTitleService} from '../../../../shared/service/page-title.service';
import {ActivatedRoute, Router} from '@angular/router';
import {createVirtualGrid} from '../../../../shared/util/virtual-grid.util';
import {RouteScrollPositionService} from '../../../../shared/service/route-scroll-position.service';
import {GridDensityButtonsComponent, type GridDensityDirection} from '../../../../shared/components/grid-density-buttons/grid-density-buttons.component';
import {LocalStorageService} from '../../../../shared/service/local-storage.service';
import {ScalePreference} from '../../../../shared/util/scale-preference.util';
import {LayoutService} from '../../../../shared/layout/layout.service';
import {createGridDensity} from '../../../../shared/util/grid-density.util';
import type {InfiniteData} from '@tanstack/angular-query-experimental';

interface FilterOption {
  label: string;
  value: string;
}

interface SortOption {
  label: string;
  value: string;
}

function flattenSeriesPages(data: InfiniteData<SeriesPage> | undefined): SeriesSummary[] {
  const pages = data?.pages ?? [];
  const seen = new Set<string>();
  const result: SeriesSummary[] = [];
  for (const page of pages) {
    for (const series of page.content) {
      const key = series.seriesName.trim().toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      result.push(series);
    }
  }
  return result;
}

@Component({
  selector: 'app-series-browser',
  standalone: true,
  templateUrl: './series-browser.component.html',
  styleUrls: ['./series-browser.component.scss'],
  imports: [
    FormsModule,
    ProgressSpinner,
    InputText,
    Select,
    Popover,
    TranslocoDirective,
    TranslocoPipe,
    GridDensityButtonsComponent,
    SeriesCardComponent,
  ]
})
export class SeriesBrowserComponent implements OnInit {

  private static readonly BASE_WIDTH = 230;
  private static readonly BASE_HEIGHT = 285;
  private static readonly MOBILE_BASE_WIDTH = 180;
  private static readonly MOBILE_BASE_HEIGHT = 250;
  private static readonly GRID_GAP = 20;
  private static readonly DEFAULT_MOBILE_GRID_COLUMNS = 2;
  private static readonly MIN_MOBILE_GRID_COLUMNS = 2;
  private static readonly MAX_MOBILE_GRID_COLUMNS = 3;
  private static readonly SCALE_STORAGE_KEY = 'seriesScalePreference';
  private static readonly MOBILE_COLUMNS_STORAGE_KEY = 'seriesMobileColumnsPreference';
  private static readonly MIN_SCALE = 0.7;
  private static readonly MAX_SCALE = 1.3;

  private seriesDataService = inject(SeriesDataService);
  private pageTitle = inject(PageTitleService);
  private t = inject(TranslocoService);
  private router = inject(Router);
  private activatedRoute = inject(ActivatedRoute);
  private destroyRef = inject(DestroyRef);
  private scrollService = inject(RouteScrollPositionService);
  private localStorageService = inject(LocalStorageService);
  private layoutService = inject(LayoutService);

  private readonly searchTerm = signal('');
  private readonly debouncedSearchTerm = toSignal(
    toObservable(this.searchTerm).pipe(
      debounceTime(500),
      distinctUntilChanged()
    ),
    {initialValue: this.searchTerm()}
  );
  private readonly statusFilter = signal('all');
  private readonly sortBy = signal('name-asc');

  // Search/sort/status all run server-side (SeriesSummaryService) - the client only ever holds
  // the pages it has scrolled, not the whole series collection.
  private readonly seriesQueryParams = computed(() => ({
    sort: this.sortBy(),
    query: this.debouncedSearchTerm(),
    status: this.statusFilter(),
  }));
  private readonly seriesInfiniteQuery = injectInfiniteQuery(() => ({
    ...this.seriesDataService.infinitePage(this.seriesQueryParams()),
    placeholderData: keepPreviousData,
  }));
  readonly seriesList = computed(() => flattenSeriesPages(this.seriesInfiniteQuery.data()));
  private readonly seriesTotalElements = computed(() => this.seriesInfiniteQuery.data()?.pages[0]?.page.totalElements);

  readonly isBooksLoading = computed(() => this.seriesInfiniteQuery.isPending());

  private readonly scrollElement = viewChild<ElementRef<HTMLElement>>('scrollElement');
  private readonly initialScrollOffset = () => this.scrollService.getPosition(this.scrollService.keyFor(this.activatedRoute)) ?? 0;
  private readonly scalePreference = new ScalePreference(this.localStorageService, {
    storageKey: SeriesBrowserComponent.SCALE_STORAGE_KEY,
    minScale: SeriesBrowserComponent.MIN_SCALE,
    maxScale: SeriesBrowserComponent.MAX_SCALE,
  });
  private readonly scaleFactor = this.scalePreference.scaleFactor;
  readonly screenWidth = signal(typeof window !== 'undefined' ? window.innerWidth : 1024);
  readonly isMobile = computed(() => !this.layoutService.isDesktop());
  private readonly baseCardWidth = computed(() => this.isMobile()
    ? SeriesBrowserComponent.MOBILE_BASE_WIDTH
    : SeriesBrowserComponent.BASE_WIDTH
  );
  private readonly gridDensity = createGridDensity(this.localStorageService, {
    useFixedColumns: this.isMobile,
    screenWidth: this.screenWidth,
    storageKey: SeriesBrowserComponent.MOBILE_COLUMNS_STORAGE_KEY,
    defaultColumns: SeriesBrowserComponent.DEFAULT_MOBILE_GRID_COLUMNS,
    minColumns: SeriesBrowserComponent.MIN_MOBILE_GRID_COLUMNS,
    maxColumns: SeriesBrowserComponent.MAX_MOBILE_GRID_COLUMNS,
    scale: this.scaleFactor,
    minScale: SeriesBrowserComponent.MIN_SCALE,
    maxScale: SeriesBrowserComponent.MAX_SCALE,
    gap: SeriesBrowserComponent.GRID_GAP,
    baseWidth: this.baseCardWidth,
    setScale: scale => this.scalePreference.setScale(scale),
  });
  readonly gridDensitySmallerDisabled = this.gridDensity.smallerDisabled;
  readonly gridDensityLargerDisabled = this.gridDensity.largerDisabled;
  filterOptions: FilterOption[] = [];
  sortOptions: SortOption[] = [];

  @HostListener('window:resize')
  onResize(): void {
    this.screenWidth.set(window.innerWidth);
  }

  private readonly cardAspectRatio = computed(() => {
    const baseHeight = this.isMobile()
      ? SeriesBrowserComponent.MOBILE_BASE_HEIGHT
      : SeriesBrowserComponent.BASE_HEIGHT;
    return baseHeight / this.baseCardWidth();
  });
  private readonly minCardWidth = computed(() => this.isMobile()
    ? 1
    : Math.round(this.baseCardWidth() * this.scaleFactor())
  );
  // Server reports the exact total up front (no client-side collapsing to make it drift), so the
  // virtualizer can reserve the real final row count instead of growing it one page at a time.
  private readonly virtualRowCount = computed(() => {
    const total = this.seriesTotalElements();
    return total !== undefined ? total : this.seriesList().length;
  });
  readonly virtualGrid = createVirtualGrid({
    items: this.seriesList,
    scrollElement: this.scrollElement,
    minItemWidth: this.minCardWidth,
    gap: this.gridDensity.gap,
    columns: this.gridDensity.columns,
    count: this.virtualRowCount,
    initialOffset: this.initialScrollOffset,
    fillItemWidth: true,
    estimateItemHeight: itemWidth => Math.round(itemWidth * this.cardAspectRatio()),
  });
  readonly currentCardScale = computed(() => this.virtualGrid.itemWidth() / this.baseCardWidth());

  private lastLoadRequestSeriesCount: number | undefined;
  private readonly gridPaginatorEffect = effect(() => {
    const items = this.seriesList();
    const lastVirtualItem = this.virtualGrid.virtualizer.getVirtualItems().at(-1);
    if (!lastVirtualItem || items.length === 0) return;
    if (lastVirtualItem.index < items.length - 1) return;
    if (!this.seriesInfiniteQuery.hasNextPage()) return;
    if (this.lastLoadRequestSeriesCount === items.length) return;

    this.lastLoadRequestSeriesCount = items.length;
    this.loadNextSeriesPage();
  });

  get searchValue(): string {
    return this.searchTerm();
  }

  get statusFilterValue(): string {
    return this.statusFilter();
  }

  get sortByValue(): string {
    return this.sortBy();
  }

  ngOnInit(): void {
    this.pageTitle.setPageTitle(this.t.translate('seriesBrowser.pageTitle'));
    this.scrollService.trackRoute({
      scrollElement: this.scrollElement,
      route: this.activatedRoute,
      destroyRef: this.destroyRef,
    });

    this.filterOptions = [
      {label: this.t.translate('seriesBrowser.filters.all'), value: 'all'},
      {label: this.t.translate('seriesBrowser.filters.notStarted'), value: 'not-started'},
      {label: this.t.translate('seriesBrowser.filters.inProgress'), value: 'in-progress'},
      {label: this.t.translate('seriesBrowser.filters.completed'), value: 'completed'},
      {label: this.t.translate('seriesBrowser.filters.abandoned'), value: 'abandoned'}
    ];

    this.sortOptions = [
      {label: this.t.translate('seriesBrowser.sort.nameAsc'), value: 'name-asc'},
      {label: this.t.translate('seriesBrowser.sort.nameDesc'), value: 'name-desc'},
      {label: this.t.translate('seriesBrowser.sort.bookCount'), value: 'book-count'},
      {label: this.t.translate('seriesBrowser.sort.progress'), value: 'progress'},
      {label: this.t.translate('seriesBrowser.sort.recentlyRead'), value: 'recently-read'},
      {label: this.t.translate('seriesBrowser.sort.recentlyAdded'), value: 'recently-added'}
    ];
  }

  onSearchChange(value: string): void {
    this.searchTerm.set(value);
  }

  onStatusFilterChange(value: string): void {
    this.statusFilter.set(value);
  }

  onSortChange(value: string): void {
    this.sortBy.set(value);
  }

  loadNextSeriesPage(): void {
    if (this.seriesInfiniteQuery.isFetchingNextPage() || !this.seriesInfiniteQuery.hasNextPage()) return;
    void this.seriesInfiniteQuery.fetchNextPage();
  }

  adjustGridDensity(direction: GridDensityDirection): void {
    this.gridDensity.adjust(direction, this.virtualGrid);
  }

  navigateToSeries(series: SeriesSummary): void {
    this.router.navigate(['/series', series.seriesName]);
  }
}
