import {describe, expect, it, vi} from 'vitest';

import {AuthorSummary} from '../model/author.model';
import {fetchAllAuthorPages} from './author-page-loader';

function author(id: number): AuthorSummary {
  return {
    id,
    name: `Author ${id}`,
    bookCount: 1,
    hasPhoto: false,
    libraryNames: [],
    categories: [],
    seriesCount: 0,
    latestAddedOn: null,
    lastReadTime: null,
    readCount: 0,
    inProgressCount: 0,
    avgPersonalRating: null
  };
}

describe('fetchAllAuthorPages', () => {
  it('stitches successive pages into one array using page/size, not a full collection fetch', async () => {
    const fetchPage = vi.fn()
      .mockResolvedValueOnce({content: [author(1), author(2)], totalElements: 3})
      .mockResolvedValueOnce({content: [author(3)], totalElements: 3});

    const authors = await fetchAllAuthorPages(fetchPage, 2);

    expect(authors.map(a => a.id)).toEqual([1, 2, 3]);
    expect(fetchPage).toHaveBeenNthCalledWith(1, 0, 2);
    expect(fetchPage).toHaveBeenNthCalledWith(2, 1, 2);
  });

  it('stops after a single request when the first page already covers the total', async () => {
    const fetchPage = vi.fn().mockResolvedValueOnce({content: [author(1)], totalElements: 1});

    const authors = await fetchAllAuthorPages(fetchPage, 500);

    expect(authors).toHaveLength(1);
    expect(fetchPage).toHaveBeenCalledTimes(1);
  });

  it('stops on an empty page instead of looping forever against a stale total', async () => {
    const fetchPage = vi.fn().mockResolvedValueOnce({content: [], totalElements: 10});

    const authors = await fetchAllAuthorPages(fetchPage, 2);

    expect(authors).toEqual([]);
    expect(fetchPage).toHaveBeenCalledTimes(1);
  });
});
