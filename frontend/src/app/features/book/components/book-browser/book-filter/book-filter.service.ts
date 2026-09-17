import {computed, inject, Injectable, Signal} from '@angular/core';
import {injectQuery} from '@tanstack/angular-query-experimental';
import {ReadStatus} from '../../../model/book.model';
import {Library} from '../../../model/library.model';
import {Shelf} from '../../../model/shelf.model';
import {MagicShelf} from '../../../../magic-shelf/service/magic-shelf.service';
import {LibraryService} from '../../../service/library.service';
import {ShelfService} from '../../../service/shelf.service';
import {BookQueryService} from '../../../data/book-query.service';
import {BookFacetGroup} from '../../../data/book-query.models';
import {EntityType} from '../book-browser.component';
import {EntityScopeFacet, FILTER_TYPE_TO_FACET_KEY, toFacetLogic, toFacetValueMap} from '../all-books-query.mapper';
import {
  CONTENT_RATING_LABELS,
  Filter,
  FILTER_CONFIGS,
  FilterType,
  FilterValue,
  NUMERIC_ID_FILTER_TYPES,
  READ_STATUS_LABELS,
  registerLanguageDisplayName,
} from './book-filter.config';
import {BookFilterMode} from '../../../../settings/user-management/user.service';
import {LanguageResolverService} from '../../../../../shared/service/language-resolver.service';

const MAX_FILTER_ITEMS = 100;

// /books/facets groups by the exact stored value; these need range-bucketed (like the old
// client-side RangeConfig lists) or role-qualified grouping the backend doesn't compute yet.
// ponytail: stays empty until BookFacetService.FACETS grows a bucketed def for each - flagged
// for a backend slice rather than falling back to a full-collection scan.
const UNSUPPORTED_FACET_TYPES: ReadonlySet<FilterType> = new Set<FilterType>([
  'matchScore', 'fileSize', 'ageRating', 'amazonRating', 'goodreadsRating',
  'hardcoverRating', 'lubimyczytacRating', 'ranobedbRating', 'audibleRating', 'pageCount',
]);

type ValueResolver = (raw: string) => FilterValue;

const identityValue: ValueResolver = raw => ({id: raw, name: raw});

const readStatusValue: ValueResolver = raw => {
  const status = (raw in READ_STATUS_LABELS ? raw : ReadStatus.UNSET) as ReadStatus;
  return {id: status, name: READ_STATUS_LABELS[status]};
};

const contentRatingValue: ValueResolver = raw => ({id: raw, name: CONTENT_RATING_LABELS[raw] ?? raw});

const shelfStatusValue: ValueResolver = raw => ({id: raw, name: raw === 'shelved' ? 'Shelved' : 'Unshelved'});

// personal_rating is stored and faceted as an exact 1-10 integer, unlike the bucketed ratings.
const personalRatingValue: ValueResolver = raw => ({id: Number(raw), name: raw, sortIndex: Number(raw)});

@Injectable({providedIn: 'root'})
export class BookFilterService {
  private readonly bookQueryService = inject(BookQueryService);
  private readonly libraryService = inject(LibraryService);
  private readonly shelfService = inject(ShelfService);
  private readonly languageResolver = inject(LanguageResolverService);

  private readonly libraryNameById = computed(() =>
    new Map(this.libraryService.libraries().map(library => [String(library.id), library.name]))
  );
  private readonly shelfNameById = computed(() =>
    new Map(this.shelfService.shelves().map(shelf => [String(shelf.id), shelf.name]))
  );

  constructor() {
    registerLanguageDisplayName(raw => this.languageResolver.displayName(raw) || raw);
  }

  createFilterSignals(
    entity: Signal<Library | Shelf | MagicShelf | null>,
    entityType: Signal<EntityType>,
    activeFilters: Signal<Record<string, unknown[]> | null>,
    filterMode: Signal<BookFilterMode>
  ): Record<FilterType, Signal<Filter[]>> {
    const facetParams = computed(() => ({
      facets: toFacetValueMap(this.normalizeFilters(activeFilters()), this.entityScope(entity(), entityType())),
      facetLogic: toFacetLogic(filterMode()),
    }));

    // A single self-omitting facets call backs every group: the server excludes a group's own
    // selection from its own counts, so all 30 panels come from one request instead of one scan
    // of the full collection per panel.
    const facetsQuery = injectQuery(() => this.bookQueryService.facets(facetParams()));
    const facetGroups = computed(() => facetsQuery.data() ?? []);

    const signals = {} as Record<FilterType, Signal<Filter[]>>;
    for (const filterType of Object.keys(FILTER_TYPE_TO_FACET_KEY) as FilterType[]) {
      signals[filterType] = UNSUPPORTED_FACET_TYPES.has(filterType)
        ? computed(() => [])
        : computed(() => this.buildFilters(filterType, facetGroups()));
    }
    return signals;
  }

  processFilterValue(key: string, value: unknown): unknown {
    if (NUMERIC_ID_FILTER_TYPES.has(key as FilterType) && typeof value === 'string') {
      return Number(value);
    }
    return value;
  }

  isNumericFilter(filterType: string): boolean {
    return NUMERIC_ID_FILTER_TYPES.has(filterType as FilterType);
  }

  // Mirrors BookBrowserComponent's entityScopeFacet: the route entity (library/shelf/
  // magic-shelf/unshelved) rides along as one more mandatory facet, regardless of facet_logic.
  private entityScope(entity: Library | Shelf | MagicShelf | null, entityType: EntityType): EntityScopeFacet | null {
    const entityId = entity?.id;
    switch (entityType) {
      case EntityType.LIBRARY:
        return entityId == null ? null : {key: 'library', value: String(entityId)};
      case EntityType.SHELF:
        return entityId == null ? null : {key: 'shelf', value: String(entityId)};
      case EntityType.MAGIC_SHELF:
        return entityId == null ? null : {key: 'shelf', value: `magic:${entityId}`};
      case EntityType.UNSHELVED:
        return {key: 'shelf_status', value: 'unshelved'};
      default:
        return null;
    }
  }

  private normalizeFilters(filters: Record<string, unknown[]> | null): Record<string, string[]> | null {
    if (!filters) return null;
    return Object.fromEntries(
      Object.entries(filters).map(([key, values]) => [key, values.map(value => String(value))])
    );
  }

  private buildFilters(filterType: FilterType, groups: readonly BookFacetGroup[]): Filter[] {
    const group = groups.find(g => g.key === FILTER_TYPE_TO_FACET_KEY[filterType]);
    if (!group) return [];

    const resolve = this.resolverFor(filterType);
    const filters = group.values.map(v => ({value: resolve(v.value), bookCount: v.count ?? 0}));

    const sortMode = FILTER_CONFIGS[filterType as Exclude<FilterType, 'library'>]?.sortMode;
    const sorted = sortMode === 'sortIndex'
      ? this.sortFiltersBySortIndex(filters)
      : this.sortFiltersByCount(filters);

    return sorted.slice(0, MAX_FILTER_ITEMS);
  }

  private resolverFor(filterType: FilterType): ValueResolver {
    switch (filterType) {
      case 'readStatus':
        return readStatusValue;
      case 'contentRating':
        return contentRatingValue;
      case 'shelfStatus':
        return shelfStatusValue;
      case 'personalRating':
        return personalRatingValue;
      case 'language':
        return raw => ({id: raw, name: this.languageResolver.displayName(raw) || raw});
      case 'library':
        return raw => ({id: Number(raw), name: this.libraryNameById().get(raw) ?? `Library ${raw}`});
      case 'shelf':
        return raw => ({id: Number(raw), name: this.shelfNameById().get(raw) ?? `Shelf ${raw}`});
      default:
        return identityValue;
    }
  }

  private sortFiltersByCount(filters: Filter[]): Filter[] {
    return filters.sort((a, b) => {
      if (b.bookCount !== a.bookCount) return b.bookCount - a.bookCount;
      return this.compareNames(a, b);
    });
  }

  private sortFiltersBySortIndex(filters: Filter[]): Filter[] {
    return filters.sort((a, b) => {
      const aIndex = (a.value as {sortIndex?: number}).sortIndex ?? 999;
      const bIndex = (b.value as {sortIndex?: number}).sortIndex ?? 999;
      if (aIndex !== bIndex) return aIndex - bIndex;
      return this.compareNames(a, b);
    });
  }

  private compareNames(a: Filter, b: Filter): number {
    const aName = String((a.value as {name?: string}).name ?? '');
    const bName = String((b.value as {name?: string}).name ?? '');
    return aName.localeCompare(bName);
  }
}
