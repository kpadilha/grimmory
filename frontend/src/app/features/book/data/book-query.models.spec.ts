import {describe, expect, it} from 'vitest';

import {BookSummary} from './book-response.models';
import {
  BookFacetGroup,
  BookPage,
  bookSummaryToBook,
  flattenBookPages,
  toFacetCountMap,
  toFacetDistinctCount,
  toFacetTotalCount,
  toFacetValueCount,
} from './book-query.models';

function facetGroup(key: string, values: readonly [string, number][], distinctCount?: number): BookFacetGroup {
  return {
    rel: 'facet',
    key,
    title: key,
    values: values.map(([value, count]) => ({value, title: value, count, selected: false})),
    ...(distinctCount === undefined ? {} : {distinctCount}),
  };
}

function summary(overrides: Partial<BookSummary> = {}): BookSummary {
  return {id: 1, libraryId: 1, libraryName: 'Library', ...overrides};
}

function page(ids: number[]): BookPage {
  return {
    content: ids.map(id => ({id, libraryId: 1, libraryName: 'Library'})),
    page: {
      number: 0,
      size: ids.length,
      totalElements: ids.length,
      totalPages: 1,
      cursor: 'opaque-cursor',
    },
    links: [],
  };
}

describe('flattenBookPages', () => {
  it('returns an empty list without data', () => {
    expect(flattenBookPages(undefined)).toEqual([]);
  });

  it('flattens pages in order', () => {
    const flattened = flattenBookPages({
      pages: [page([1, 2]), page([3, 4])],
      pageParams: [null, 'next'],
    });

    expect(flattened.map(book => book.id)).toEqual([1, 2, 3, 4]);
  });

  it('keeps the first occurrence when an offset shift re-serves a book', () => {
    const flattened = flattenBookPages({
      pages: [page([1, 2]), page([2, 3])],
      pageParams: [null, 'next'],
    });

    expect(flattened.map(book => book.id)).toEqual([1, 2, 3]);
  });
});

describe('bookSummaryToBook', () => {
  it('lifts fileName/filePath/fileSizeKb from primaryFile to the top level', () => {
    const book = bookSummaryToBook(summary({
      primaryFile: {
        id: 9,
        bookId: 1,
        book: true,
        folderBased: false,
        fileName: 'dune.epub',
        filePath: '/library/dune.epub',
        fileSizeKb: 2048,
      },
    }));

    expect(book.fileName).toBe('dune.epub');
    expect(book.filePath).toBe('/library/dune.epub');
    expect(book.fileSizeKb).toBe(2048);
    // primaryFile itself is preserved, unrelated fields pass through the cast untouched.
    expect(book.primaryFile?.fileName).toBe('dune.epub');
  });

  it('leaves the top-level file fields undefined without a primaryFile', () => {
    const book = bookSummaryToBook(summary());

    expect(book.fileName).toBeUndefined();
    expect(book.filePath).toBeUndefined();
    expect(book.fileSizeKb).toBeUndefined();
  });
});

describe('toFacetCountMap', () => {
  it('maps each value to its count for a matching group', () => {
    const facets = [facetGroup('library', [['8', 2], ['9', 5]])];

    expect(toFacetCountMap(facets, 'library')).toEqual(new Map([['8', 2], ['9', 5]]));
  });

  it('returns an empty map without a matching group or data', () => {
    expect(toFacetCountMap([facetGroup('shelf', [['1', 3]])], 'library')).toEqual(new Map());
    expect(toFacetCountMap(undefined, 'library')).toEqual(new Map());
  });
});

describe('toFacetTotalCount', () => {
  it('sums every value in the group - the exhaustive shelf_status split totals the whole library', () => {
    const facets = [facetGroup('shelf_status', [['shelved', 8], ['unshelved', 3]])];

    expect(toFacetTotalCount(facets, 'shelf_status')).toBe(11);
  });

  it('returns zero without a matching group', () => {
    expect(toFacetTotalCount([], 'shelf_status')).toBe(0);
  });
});

describe('toFacetValueCount', () => {
  it('counts distinct values in the group', () => {
    const facets = [facetGroup('series', [['Earthsea', 3], ['Discworld', 41]])];

    expect(toFacetValueCount(facets, 'series')).toBe(2);
  });

  it('returns zero without a matching group', () => {
    expect(toFacetValueCount([], 'series')).toBe(0);
  });
});

describe('toFacetDistinctCount', () => {
  it('reads the exact server-computed total, above the 100-value cap', () => {
    const capped = Array.from({length: 100}, (_, i) => [`Series${i}`, 1] as [string, number]);
    const facets = [facetGroup('series', capped, 23731)];

    expect(facets[0].values).toHaveLength(100);
    expect(toFacetDistinctCount(facets, 'series')).toBe(23731);
  });

  it('falls back to the value count when the server did not compute a distinct count', () => {
    const facets = [facetGroup('genre', [['Horror', 3], ['Romance', 1]])];

    expect(toFacetDistinctCount(facets, 'genre')).toBe(2);
  });

  it('returns zero without a matching group', () => {
    expect(toFacetDistinctCount([], 'series')).toBe(0);
  });
});
