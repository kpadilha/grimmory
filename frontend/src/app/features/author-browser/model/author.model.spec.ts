import {describe, expect, expectTypeOf, it} from 'vitest';

import {
  AuthorDetails,
  AuthorFilters,
  AuthorMatchRequest,
  AuthorSummary,
  AuthorUpdateRequest,
  DEFAULT_AUTHOR_FILTERS,
  enrichAuthor,
  EnrichedAuthor
} from './author.model';

function baseAuthorSummary(overrides: Partial<AuthorSummary> = {}): AuthorSummary {
  return {
    id: 7,
    name: 'Ursula K. Le Guin',
    bookCount: 12,
    hasPhoto: true,
    libraryNames: ['Main', 'Archive'],
    categories: ['Fantasy', 'Sci-Fi'],
    seriesCount: 2,
    latestAddedOn: '2026-03-26',
    lastReadTime: '2026-03-25T20:15:00Z',
    readCount: 9,
    inProgressCount: 0,
    avgPersonalRating: 4.5,
    ...overrides
  };
}

describe('author.model', () => {
  it('provides all-author defaults for author filtering', () => {
    expect(DEFAULT_AUTHOR_FILTERS).toEqual({
      matchStatus: 'all',
      photoStatus: 'all',
      readStatus: 'all',
      bookCount: 'all',
      library: 'all',
      genre: 'all'
    } satisfies AuthorFilters);
  });

  it('supports enriched authors extending the summary shape', () => {
    const enriched: EnrichedAuthor = {
      ...baseAuthorSummary(),
      readStatus: 'some-read',
      hasSeries: true,
      readingProgress: 75
    };

    expect(enriched.readStatus).toBe('some-read');
    expect(enriched.seriesCount).toBe(2);
    expectTypeOf(enriched).toMatchTypeOf<AuthorSummary>();
  });

  describe('enrichAuthor', () => {
    it('takes only an AuthorSummary - never a Book[] - to derive enrichment', () => {
      // The whole point: enrichment is a pure function of AuthorSummary counts, never Book[] -
      // the author browser no longer needs bookService.books() to derive these fields.
      expectTypeOf(enrichAuthor).parameter(0).toEqualTypeOf<AuthorSummary>();
      expectTypeOf(enrichAuthor).toBeCallableWith(baseAuthorSummary());
    });

    it('marks all-read when every book is read', () => {
      const author = baseAuthorSummary({bookCount: 3, readCount: 3, inProgressCount: 0});
      expect(enrichAuthor(author).readStatus).toBe('all-read');
    });

    it('marks in-progress when some books are being read', () => {
      const author = baseAuthorSummary({bookCount: 3, readCount: 1, inProgressCount: 1});
      expect(enrichAuthor(author).readStatus).toBe('in-progress');
    });

    it('marks some-read when books are read but none in progress', () => {
      const author = baseAuthorSummary({bookCount: 3, readCount: 1, inProgressCount: 0});
      expect(enrichAuthor(author).readStatus).toBe('some-read');
    });

    it('marks unread when the author has no books or no progress', () => {
      expect(enrichAuthor(baseAuthorSummary({bookCount: 0, readCount: 0, inProgressCount: 0})).readStatus).toBe('unread');
      expect(enrichAuthor(baseAuthorSummary({bookCount: 3, readCount: 0, inProgressCount: 0})).readStatus).toBe('unread');
    });

    it('computes readingProgress and hasSeries from the raw counts', () => {
      const enriched = enrichAuthor(baseAuthorSummary({bookCount: 4, readCount: 3, seriesCount: 0}));
      expect(enriched.readingProgress).toBe(75);
      expect(enriched.hasSeries).toBe(false);
    });
  });

  it('keeps author request and update payloads structurally typed', () => {
    const matchRequest: AuthorMatchRequest = {
      source: 'hardcover',
      asin: 'B001234',
      region: 'US'
    };
    const updateRequest: AuthorUpdateRequest = {
      name: 'New Name',
      descriptionLocked: true
    };
    const details: AuthorDetails = {
      id: 4,
      name: 'Author',
      nameLocked: false,
      descriptionLocked: true,
      asinLocked: false,
      photoLocked: false
    };

    expect(matchRequest.region).toBe('US');
    expect(updateRequest.descriptionLocked).toBe(true);
    expect(details.nameLocked).toBe(false);
    expectTypeOf(updateRequest.name).toEqualTypeOf<string | undefined>();
  });
});
