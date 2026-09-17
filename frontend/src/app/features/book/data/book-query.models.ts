import {InfiniteData} from '@tanstack/angular-query-experimental';

import {BrowseFacetGroup, BrowsePage} from '../../../core/data/browse.models';
import {Book} from '../model/book.model';
import {BookSummary} from './book-response.models';

export type BookPage = BrowsePage<BookSummary>;
export type BookFacetGroup = BrowseFacetGroup;

// The summary DTO and the UI model describe the same persisted book; only the nullability of
// optional fields (null vs undefined) differs between the two, which the UI treats the same.
// Exception: Book expects fileName/filePath/fileSizeKb at the top level (table column, title
// fallback), but BookSummary only nests them under primaryFile - the cast alone leaves them out.
export function bookSummaryToBook(summary: BookSummary): Book {
  const book = summary as unknown as Book;
  return {
    ...book,
    fileName: summary.primaryFile?.fileName,
    filePath: summary.primaryFile?.filePath,
    fileSizeKb: summary.primaryFile?.fileSizeKb,
  };
}

export function flattenBookPages(
  data: InfiniteData<BookPage> | undefined,
): BookSummary[] {
  const books = data?.pages.flatMap(page => page.content) ?? [];
  const seen = new Set<number>();
  return books.filter(book => {
    if (seen.has(book.id)) {
      return false;
    }
    seen.add(book.id);
    return true;
  });
}

function findFacetGroup(facets: readonly BookFacetGroup[] | undefined, key: string): BookFacetGroup | undefined {
  return facets?.find(group => group.key === key);
}

/** Per-value counts for a facet group, e.g. library/shelf id -> book count for sidebar badges. The
 * library/shelf groups are never capped server-side (their domain is bounded by entity count, not
 * book count), so this map is exhaustive for them; a high-cardinality group (author, series, ...)
 * is capped at 100 values and a missing key there just means it fell outside the top 100. */
export function toFacetCountMap(facets: readonly BookFacetGroup[] | undefined, key: string): ReadonlyMap<string, number> {
  const group = findFacetGroup(facets, key);
  return new Map(group?.values.map(v => [v.value, v.count ?? 0]) ?? []);
}

// Safe only for an exhaustive, uncapped group (e.g. shelf_status) - an enumerated group
// (author, series, ...) is capped server-side at 100 values, so summing it would undercount.
export function toFacetTotalCount(facets: readonly BookFacetGroup[] | undefined, key: string): number {
  const group = findFacetGroup(facets, key);
  return group?.values.reduce((sum, v) => sum + (v.count ?? 0), 0) ?? 0;
}

// Exact up to the facet API's 100-value-per-group cap; beyond that it undercounts. Prefer
// toFacetDistinctCount for a group the server computes an uncapped total for (e.g. series).
export function toFacetValueCount(facets: readonly BookFacetGroup[] | undefined, key: string): number {
  return findFacetGroup(facets, key)?.values.length ?? 0;
}

// Exact, uncapped distinct-value count for a group the server computes one for; falls back to
// the capped value count where it doesn't (undercounts beyond 100 distinct values in that case).
export function toFacetDistinctCount(facets: readonly BookFacetGroup[] | undefined, key: string): number {
  const group = findFacetGroup(facets, key);
  return group?.distinctCount ?? group?.values.length ?? 0;
}
