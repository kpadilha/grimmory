import {ReadStatus} from '../../book/model/book.model';

/** One cover candidate for a series card - only what SeriesCardComponent needs to render it. */
export interface SeriesCoverBook {
  id: number;
  bookType?: string;
  coverUpdatedOn?: string;
  audiobookCoverUpdatedOn?: string;
}

export interface SeriesSummary {
  seriesName: string;
  authors: string[];
  categories: string[];
  bookCount: number;
  readCount: number;
  progress: number;
  seriesStatus: ReadStatus;
  nextUnreadBookId: number | null;
  lastReadTime: string | null;
  coverBooks: SeriesCoverBook[];
  addedOn: string | null;
}
