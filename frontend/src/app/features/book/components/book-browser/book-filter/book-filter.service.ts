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
import {BrowseFacetValue} from '../../../../../core/data/browse.models';
import {EntityType} from '../book-browser.component';
import {EntityScopeFacet, FILTER_TYPE_TO_FACET_KEY, toFacetLogic, toFacetValueMap} from '../all-books-query.mapper';
import {
  AGE_RATING_OPTIONS,
  COMIC_ROLE_DISPLAY_LABELS,
  CONTENT_RATING_LABELS,
  Filter,
  FILE_SIZE_RANGES,
  FILTER_CONFIGS,
  FilterType,
  FilterValue,
  MATCH_SCORE_RANGES,
  NUMERIC_ID_FILTER_TYPES,
  PAGE_COUNT_RANGES,
  RangeConfig,
  RATING_RANGES_5,
  READ_STATUS_LABELS,
  registerLanguageDisplayName,
} from './book-filter.config';
import {BookFilterMode} from '../../../../settings/user-management/user.service';
import {LanguageResolverService} from '../../../../../shared/service/language-resolver.service';

const MAX_FILTER_ITEMS = 100;

type ValueResolver = (fv: BrowseFacetValue) => FilterValue;

const identityValue: ValueResolver = fv => ({id: fv.value, name: fv.value});

const readStatusValue: ValueResolver = fv => {
  const status = (fv.value in READ_STATUS_LABELS ? fv.value : ReadStatus.UNSET) as ReadStatus;
  return {id: status, name: READ_STATUS_LABELS[status]};
};

const contentRatingValue: ValueResolver = fv => ({id: fv.value, name: CONTENT_RATING_LABELS[fv.value] ?? fv.value});

const shelfStatusValue: ValueResolver = fv => ({id: fv.value, name: fv.value === 'shelved' ? 'Shelved' : 'Unshelved'});

// personal_rating is stored and faceted as an exact 1-10 integer, unlike the bucketed ratings.
const personalRatingValue: ValueResolver = fv => ({id: Number(fv.value), name: fv.value, sortIndex: Number(fv.value)});

// The 10 numeric filters bucket server-side into the same ranges book-filter.config's RangeConfig
// tables already describe - the facet value is the bucket id, so reuse the label/order from there
// instead of asking the server to repeat "0 to 1" style text it has no other use for.
const bucketValue = (ranges: readonly RangeConfig[]): ValueResolver => fv => {
  const id = Number(fv.value);
  const range = ranges.find(r => r.id === id);
  return {id, name: range?.label ?? fv.value, sortIndex: range?.sortIndex ?? 999};
};

// comic_creator values arrive as "name:role" (role in COMIC_ROLE_DISPLAY_LABELS' camelCase keys);
// expand it into the same "Name (Role)" shape the old client-side extractor produced.
const comicCreatorValue: ValueResolver = fv => {
  const separator = fv.value.lastIndexOf(':');
  if (separator < 0) return {id: fv.value, name: fv.value};
  const name = fv.value.slice(0, separator);
  const role = fv.value.slice(separator + 1);
  const roleLabel = COMIC_ROLE_DISPLAY_LABELS[role] ?? role;
  return {id: fv.value, name: `${name} (${roleLabel})`};
};

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

  // expandedFilterTypes gates the network fetch per panel: a group is only ever requested once
  // the user opens its accordion panel, never all ~30 groups on mount.
  createFilterSignals(
    entity: Signal<Library | Shelf | MagicShelf | null>,
    entityType: Signal<EntityType>,
    activeFilters: Signal<Record<string, unknown[]> | null>,
    filterMode: Signal<BookFilterMode>,
    expandedFilterTypes: Signal<ReadonlySet<FilterType>>,
  ): Record<FilterType, Signal<Filter[]>> {
    const baseParams = computed(() => ({
      facets: toFacetValueMap(this.normalizeFilters(activeFilters()), this.entityScope(entity(), entityType())),
      facetLogic: toFacetLogic(filterMode()),
    }));

    const signals = {} as Record<FilterType, Signal<Filter[]>>;
    for (const filterType of Object.keys(FILTER_TYPE_TO_FACET_KEY) as FilterType[]) {
      const groupKey = FILTER_TYPE_TO_FACET_KEY[filterType];
      const facetQuery = injectQuery(() => ({
        ...this.bookQueryService.facets({...baseParams(), group: [groupKey]}),
        enabled: expandedFilterTypes().has(filterType),
      }));
      signals[filterType] = computed(() => this.buildFilters(filterType, facetQuery.data() ?? []));
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
    const filters = group.values.map(v => ({value: resolve(v), bookCount: v.count ?? 0}));

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
        return fv => ({id: fv.value, name: this.languageResolver.displayName(fv.value) || fv.value});
      case 'library':
        return fv => ({id: Number(fv.value), name: this.libraryNameById().get(fv.value) ?? `Library ${fv.value}`});
      case 'shelf':
        return fv => ({id: Number(fv.value), name: this.shelfNameById().get(fv.value) ?? `Shelf ${fv.value}`});
      case 'matchScore':
        return bucketValue(MATCH_SCORE_RANGES);
      case 'fileSize':
        return bucketValue(FILE_SIZE_RANGES);
      case 'pageCount':
        return bucketValue(PAGE_COUNT_RANGES);
      case 'ageRating':
        return bucketValue(AGE_RATING_OPTIONS);
      case 'amazonRating':
      case 'goodreadsRating':
      case 'hardcoverRating':
      case 'lubimyczytacRating':
      case 'ranobedbRating':
      case 'audibleRating':
        return bucketValue(RATING_RANGES_5);
      case 'comicCreator':
        return comicCreatorValue;
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
