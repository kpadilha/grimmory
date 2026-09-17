import {BookFilterMode} from '../../../settings/user-management/user.service';
import {SortDirection, SortOption} from '../../model/sort.model';
import {
  BookPageParams,
  BookQueryFacetKey,
  BookQuerySortKey,
  BookSortTerm,
  DEFAULT_BOOK_SORT_TERMS,
  FacetLogic,
  FacetValueMap,
  isBookQueryFacetKey,
  isBookQuerySortKey,
} from '../../data/book-query-params';
import {BookFacetGroup} from '../../data/book-query.models';

/** Batch size for the all-books infinite scroll; balances request count against payload. */
export const ALL_BOOKS_PAGE_SIZE = 60;

// The sidebar filter component predates the server facet vocabulary; only the key name differs.
// Exported so BookFilterService can request the sidebar's own facet groups by the same keys.
export const FILTER_TYPE_TO_FACET_KEY: Readonly<Record<string, BookQueryFacetKey>> = {
  author: 'author',
  category: 'genre',
  series: 'series',
  bookType: 'file_type',
  readStatus: 'read_status',
  personalRating: 'personal_rating',
  publisher: 'publisher',
  matchScore: 'match_score',
  library: 'library',
  shelf: 'shelf',
  shelfStatus: 'shelf_status',
  tag: 'tag',
  publishedDate: 'published_year',
  fileSize: 'file_size',
  amazonRating: 'amazon_rating',
  goodreadsRating: 'goodreads_rating',
  hardcoverRating: 'hardcover_rating',
  lubimyczytacRating: 'lubimyczytac_rating',
  ranobedbRating: 'ranobedb_rating',
  audibleRating: 'audible_rating',
  language: 'language',
  pageCount: 'page_count',
  mood: 'mood',
  ageRating: 'age_rating',
  contentRating: 'content_rating',
  narrator: 'narrator',
  comicCharacter: 'comic_character',
  comicTeam: 'comic_team',
  comicLocation: 'comic_location',
  comicCreator: 'comic_creator',
};

// BookSortRegistry backs both endpoints; fields it has no column for (file name/path/size,
// lock state, book type) are dropped here rather than sent as an invalid sort key.
const SORT_FIELD_TO_SERVER_KEY: Readonly<Record<string, BookQuerySortKey>> = {
  title: 'title',
  author: 'authorName',
  authorSurnameVorname: 'authorSortName',
  seriesName: 'seriesName',
  seriesNumber: 'seriesNumber',
  lastReadTime: 'lastReadTime',
  personalRating: 'personalRating',
  addedOn: 'addedOn',
  publisher: 'publisher',
  publishedDate: 'publishedDate',
  readStatus: 'readStatus',
  dateFinished: 'dateFinished',
  readingProgress: 'readingProgress',
  amazonRating: 'amazonRating',
  amazonReviewCount: 'amazonReviewCount',
  goodreadsRating: 'goodreadsRating',
  goodreadsReviewCount: 'goodreadsReviewCount',
  hardcoverRating: 'hardcoverRating',
  hardcoverReviewCount: 'hardcoverReviewCount',
  ranobedbRating: 'ranobedbRating',
  narrator: 'narrator',
  pageCount: 'pageCount',
  random: 'random',
};

// Backs the all-books sort popover: an unsupported field must never be offered there, since
// toBookSortTerms would silently drop it rather than send an invalid server sort key.
export function isServerSortField(field: string): boolean {
  return field in SORT_FIELD_TO_SERVER_KEY;
}

// A library/shelf/magic-shelf/unshelved route scope, expressed in the server facet vocabulary.
// It always ANDs against the sidebar's own facets, regardless of the chosen facet logic.
export interface EntityScopeFacet {
  readonly key: BookQueryFacetKey;
  readonly value: string;
}

export function toFacetValueMap(
  filters: Record<string, string[]> | null,
  scope?: EntityScopeFacet | null,
): FacetValueMap {
  const facets: Partial<Record<BookQueryFacetKey, readonly string[]>> = {};
  if (filters) {
    for (const [filterType, values] of Object.entries(filters)) {
      const facetKey = FILTER_TYPE_TO_FACET_KEY[filterType];
      if (facetKey && isBookQueryFacetKey(facetKey) && values.length > 0 && facetKey !== scope?.key) {
        facets[facetKey] = values;
      }
    }
  }

  // The route scope wins outright on a same-key collision (e.g. a 'shelf' sidebar filter while
  // already browsing a shelf): the server has one and/or/not mode per facet key, so merging the
  // two would let 'or'/'not' silently drop the mandatory scope restriction.
  if (scope) {
    facets[scope.key] = [scope.value];
  }

  return facets;
}

// Only 'or' and 'not' change value combination; 'and' and the UI-only 'single' mode both AND.
export function toFacetLogic(mode: BookFilterMode): FacetLogic {
  return mode === 'or' || mode === 'not' ? mode : 'and';
}

export function toBookSortTerms(criteria: readonly SortOption[]): readonly BookSortTerm[] {
  const terms = criteria
    .map((criterion): BookSortTerm | null => {
      const key = SORT_FIELD_TO_SERVER_KEY[criterion.field];
      if (!key || !isBookQuerySortKey(key)) return null;
      return {key, direction: criterion.direction === SortDirection.ASCENDING ? 'asc' : 'desc'};
    })
    .filter((term): term is BookSortTerm => term !== null);

  return terms.length > 0 ? terms : DEFAULT_BOOK_SORT_TERMS;
}

// Backs every book-browser route (all-books, library, shelf, magic-shelf, unshelved): the
// entity scope is what tells apart an all-books query from a scoped one.
export interface AllBooksQueryInput {
  search: string;
  filters: Record<string, string[]> | null;
  filterMode: BookFilterMode;
  sort: readonly SortOption[];
  scope?: EntityScopeFacet | null;
}

export function toAllBooksQueryParams(input: AllBooksQueryInput): BookPageParams {
  return {
    query: input.search,
    facets: toFacetValueMap(input.filters, input.scope),
    facetLogic: toFacetLogic(input.filterMode),
    sort: toBookSortTerms(input.sort),
    size: ALL_BOOKS_PAGE_SIZE,
  };
}

// The 'series' facet totals each series under the CURRENT filters, server-side - unlike a
// collapsed card's local group.length, this doesn't undercount a series split across pages.
export function toSeriesCountMap(facets: readonly BookFacetGroup[] | undefined): ReadonlyMap<string, number> {
  const seriesFacet = facets?.find(group => group.key === 'series');
  return new Map(seriesFacet?.values.map(value => [value.value, value.count ?? 0]) ?? []);
}
