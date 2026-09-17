// All fields below are computed server-side (AuthorMetadataService.getAllAuthors, bulk-aggregated
// per page) - the author browser never downloads the book collection to derive them.
export interface AuthorSummary {
  id: number;
  name: string;
  asin?: string;
  bookCount: number;
  hasPhoto: boolean;
  libraryNames: string[];
  categories: string[];
  seriesCount: number;
  latestAddedOn: string | null;
  lastReadTime: string | null;
  readCount: number;
  inProgressCount: number;
  avgPersonalRating: number | null;
}

export interface AuthorPageResponse {
  content: AuthorSummary[];
  totalElements: number;
}

export interface EnrichedAuthor extends AuthorSummary {
  readStatus: 'all-read' | 'some-read' | 'in-progress' | 'unread';
  hasSeries: boolean;
  readingProgress: number;
}

// Purely derived from counts the backend already computed - no Book[] input, so no
// per-request cost beyond the one paginated authors call.
export function enrichAuthor(author: AuthorSummary): EnrichedAuthor {
  const totalBooks = author.bookCount;
  let readStatus: EnrichedAuthor['readStatus'] = 'unread';
  if (totalBooks > 0) {
    if (author.readCount === totalBooks) {
      readStatus = 'all-read';
    } else if (author.inProgressCount > 0) {
      readStatus = 'in-progress';
    } else if (author.readCount > 0) {
      readStatus = 'some-read';
    }
  }

  return {
    ...author,
    readStatus,
    hasSeries: author.seriesCount > 0,
    readingProgress: totalBooks > 0 ? Math.round((author.readCount / totalBooks) * 100) : 0,
  };
}

export interface AuthorFilters {
  matchStatus: 'all' | 'matched' | 'unmatched';
  photoStatus: 'all' | 'has-photo' | 'no-photo';
  readStatus: 'all' | 'all-read' | 'some-read' | 'in-progress' | 'unread';
  bookCount: 'all' | '0' | '1' | '2' | '3' | '4' | '5' | '6-10' | '11-20' | '21-35' | '36+';
  library: string;
  genre: string;
}

export const DEFAULT_AUTHOR_FILTERS: AuthorFilters = {
  matchStatus: 'all',
  photoStatus: 'all',
  readStatus: 'all',
  bookCount: 'all',
  library: 'all',
  genre: 'all'
};

export interface AuthorDetails {
  id: number;
  name: string;
  description?: string;
  asin?: string;
  nameLocked: boolean;
  descriptionLocked: boolean;
  asinLocked: boolean;
  photoLocked: boolean;
}

export interface AuthorSearchResult {
  source: string;
  asin: string;
  name: string;
  description?: string;
  imageUrl?: string;
}

export interface AuthorMatchRequest {
  source: string;
  asin: string;
  region: string;
}

export interface AuthorPhotoResult {
  url: string;
  width: number;
  height: number;
  index: number;
}

export interface AuthorUpdateRequest {
  name?: string;
  description?: string;
  asin?: string;
  nameLocked?: boolean;
  descriptionLocked?: boolean;
  asinLocked?: boolean;
  photoLocked?: boolean;
}
