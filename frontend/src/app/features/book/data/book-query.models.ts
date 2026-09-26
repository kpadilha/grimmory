import {BrowseFacetResult, BrowsePage} from '../../../core/data/browse.models';
import {BookSummary} from './book-response.models';

export type BookPage = BrowsePage<BookSummary>;

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
