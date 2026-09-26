import {BrowseFacetResult, BrowsePage} from '../../../core/data/browse.models';
import {Book} from '../model/book.model';
import {BookSummary} from './book-response.models';

export type BookPage = BrowsePage<BookSummary>;

// The summary DTO and the UI model describe the same persisted book; only fileName/filePath/
// fileSizeKb sit at the top level on Book but nested under primaryFile on BookSummary.
export function bookSummaryToBook(summary: BookSummary): Book {
  const book = summary as unknown as Book;
  return {
    ...book,
    fileName: summary.primaryFile?.fileName,
    filePath: summary.primaryFile?.filePath,
    fileSizeKb: summary.primaryFile?.fileSizeKb,
  };
}

function facetValues(result: BrowseFacetResult | undefined, key: string) {
  return result?.facets.find(group => group.key === key)?.values ?? [];
}

// Sidebar per-library/per-shelf badge counts read one shared facets response, not the full collection.
export function toFacetNumericCountMap(result: BrowseFacetResult | undefined, key: string): Map<number, number> {
  const counts = new Map<number, number>();
  for (const {value, count} of facetValues(result, key)) {
    counts.set(Number(value), count);
  }
  return counts;
}

export function toFacetValueCount(result: BrowseFacetResult | undefined, key: string, value: string): number {
  return facetValues(result, key).find(v => v.value === value)?.count ?? 0;
}

export function toFacetTotalCount(result: BrowseFacetResult | undefined, key: string): number {
  return facetValues(result, key).reduce((total, v) => total + v.count, 0);
}

export function toFacetDistinctCount(result: BrowseFacetResult | undefined, key: string): number {
  return facetValues(result, key).length;
}

// Distinct values for a facet group (e.g. every genre in the library) - server-aggregated,
// never a client scan of the full book collection.
export function toFacetValues(result: BrowseFacetResult | undefined, key: string): string[] {
  return facetValues(result, key).map(v => v.value);
}
