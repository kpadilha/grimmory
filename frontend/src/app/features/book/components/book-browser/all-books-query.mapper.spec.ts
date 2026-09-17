import {describe, expect, it} from 'vitest';

import {SortDirection} from '../../model/sort.model';
import {
  isServerSortField,
  toAllBooksQueryParams,
  toBookSortTerms,
  toFacetLogic,
  toFacetValueMap,
  toSeriesCountMap,
} from './all-books-query.mapper';

describe('all-books query mapper', () => {
  it('translates sidebar filter types to the server facet vocabulary', () => {
    expect(toFacetValueMap({
      category: ['Fantasy'],
      bookType: ['EPUB'],
      lubimyczytacRating: ['4'],
    })).toEqual({
      genre: ['Fantasy'],
      file_type: ['EPUB'],
      lubimyczytac_rating: ['4'],
    });
  });

  it('drops unknown filter types and empty value lists', () => {
    expect(toFacetValueMap({unknownFilter: ['x'], author: []})).toEqual({});
  });

  it('returns an empty facet map for no active filters', () => {
    expect(toFacetValueMap(null)).toEqual({});
  });

  it('adds the entity scope facet alongside the sidebar facets', () => {
    expect(toFacetValueMap({category: ['Fantasy']}, {key: 'library', value: '7'})).toEqual({
      genre: ['Fantasy'],
      library: ['7'],
    });
  });

  it('lets the entity scope win over a same-key sidebar selection', () => {
    expect(toFacetValueMap({shelf: ['9']}, {key: 'shelf', value: 'magic:3'})).toEqual({
      shelf: ['magic:3'],
    });
  });

  it('maps and and single modes to the AND facet logic, passing or/not through', () => {
    expect(toFacetLogic('and')).toBe('and');
    expect(toFacetLogic('single')).toBe('and');
    expect(toFacetLogic('or')).toBe('or');
    expect(toFacetLogic('not')).toBe('not');
  });

  it('translates supported sort fields and drops fields with no server column', () => {
    const terms = toBookSortTerms([
      {label: 'Author', field: 'author', direction: SortDirection.ASCENDING},
      {label: 'File Name', field: 'fileName', direction: SortDirection.DESCENDING},
      {label: 'Added On', field: 'addedOn', direction: SortDirection.DESCENDING},
    ]);

    expect(terms).toEqual([
      {key: 'authorName', direction: 'asc'},
      {key: 'addedOn', direction: 'desc'},
    ]);
  });

  it('falls back to the default sort when every requested field is unsupported', () => {
    const terms = toBookSortTerms([
      {label: 'File Size', field: 'fileSizeKb', direction: SortDirection.ASCENDING},
    ]);

    expect(terms).toEqual([{key: 'title', direction: 'asc'}]);
  });

  it('builds full page params from component state', () => {
    expect(toAllBooksQueryParams({
      search: 'dune',
      filters: {author: ['Frank Herbert']},
      filterMode: 'or',
      sort: [{label: 'Title', field: 'title', direction: SortDirection.ASCENDING}],
    })).toEqual({
      query: 'dune',
      facets: {author: ['Frank Herbert']},
      facetLogic: 'or',
      sort: [{key: 'title', direction: 'asc'}],
      size: 60,
    });
  });

  it('folds a route scope into the page params for a scoped route (library/shelf/magic-shelf/unshelved)', () => {
    expect(toAllBooksQueryParams({
      search: '',
      filters: null,
      filterMode: 'and',
      sort: [],
      scope: {key: 'shelf_status', value: 'unshelved'},
    })).toEqual({
      query: '',
      facets: {shelf_status: ['unshelved']},
      facetLogic: 'and',
      sort: [{key: 'title', direction: 'asc'}],
      size: 60,
    });
  });

  it('reports server-sortable fields and rejects the rest', () => {
    expect(isServerSortField('title')).toBe(true);
    expect(isServerSortField('authorSurnameVorname')).toBe(true);
    expect(isServerSortField('fileName')).toBe(false);
    expect(isServerSortField('filePath')).toBe(false);
    expect(isServerSortField('fileSizeKb')).toBe(false);
    expect(isServerSortField('locked')).toBe(false);
    expect(isServerSortField('bookType')).toBe(false);
  });

  it('looks up the real per-series count from the series facet group', () => {
    const counts = toSeriesCountMap([
      {
        rel: 'series', key: 'series', title: 'Series',
        values: [
          {value: 'Dune Saga', title: 'Dune Saga', count: 9, selected: false},
          {value: 'Foundation', title: 'Foundation', selected: false},
        ],
      },
      {rel: 'genre', key: 'genre', title: 'Genre', values: []},
    ]);

    expect(counts.get('Dune Saga')).toBe(9);
    expect(counts.get('Foundation')).toBe(0);
    expect(counts.get('Unknown Series')).toBeUndefined();
  });

  it('returns an empty map without a series facet group', () => {
    expect(toSeriesCountMap(undefined).size).toBe(0);
    expect(toSeriesCountMap([{rel: 'genre', key: 'genre', title: 'Genre', values: []}]).size).toBe(0);
  });
});
