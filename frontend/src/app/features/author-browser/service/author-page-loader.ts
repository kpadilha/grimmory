import {AuthorPageResponse, AuthorSummary} from '../model/author.model';

export type FetchAuthorPage = (page: number, size: number) => Promise<AuthorPageResponse>;

// Drains the paginated /api/v1/authors endpoint into one array - each request is a lean
// AuthorSummary page, never the full book collection the author browser used to load.
export async function fetchAllAuthorPages(fetchPage: FetchAuthorPage, pageSize: number): Promise<AuthorSummary[]> {
  const authors: AuthorSummary[] = [];
  let page = 0;

  while (true) {
    const response = await fetchPage(page, pageSize);
    authors.push(...response.content);
    if (response.content.length === 0 || authors.length >= response.totalElements) {
      break;
    }
    page++;
  }

  return authors;
}
