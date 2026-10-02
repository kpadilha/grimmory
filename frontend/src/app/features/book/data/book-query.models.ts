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

// Distinct values for a facet group (e.g. every genre in the library) - server-aggregated,
// never a client scan of the full book collection.
export function toFacetValues(result: BrowseFacetResult | undefined, key: string): string[] {
  return facetValues(result, key).map(v => v.value);
}
