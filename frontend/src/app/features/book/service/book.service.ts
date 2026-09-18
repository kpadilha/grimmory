import {computed, inject, Injectable} from '@angular/core';
import {from, lastValueFrom, Observable, of, throwError} from 'rxjs';
import {HttpClient, HttpParams} from '@angular/common/http';
import {catchError, tap} from 'rxjs/operators';
import {Book, BookDeletionResponse, BookRecommendation, BookSetting, BookStatusUpdateResponse, BookType, CreatePhysicalBookRequest, PersonalRatingUpdateResponse, ReadStatus} from '../model/book.model';
import {API_CONFIG} from '../../../core/config/api-config';
import {MessageService} from '@openng/optimus-ui/api';
import {ResetProgressType} from '../../../shared/constants/reset-progress-type';
import {AuthService} from '../../../shared/service/auth.service';
import {Router} from '@angular/router';
import {BookSocketService} from './book-socket.service';
import {BookPatchService} from './book-patch.service';
import {TranslocoService} from '@jsverse/transloco';
import {injectQuery, queryOptions, QueryClient} from '@tanstack/angular-query-experimental';
import {
  bookDetailQueryKey,
  bookRecommendationsQueryKey,
} from './book-query-keys';
import {
  invalidateAppBooksQueries,
  invalidateBooksQuery,
  patchBooksInCache,
  removeBookQueries,
} from './book-query-cache';
import {BookQueryService} from '../data/book-query.service';
import {BookCollectionFilterParams, EMPTY_FACET_SELECTION} from '../data/book-query-params';
import {bookSummaryToBook, toFacetTotalCount} from '../data/book-query.models';

// Only the 'shelf_status' group - not the all-groups scan GLOBAL_FACET_PARAMS used to trigger.
const TOTAL_COUNT_FACET_PARAMS: BookCollectionFilterParams = {
  facets: EMPTY_FACET_SELECTION,
  facetLogic: 'and',
  group: ['shelf_status'],
};

@Injectable({
  providedIn: 'root',
})
export class BookService {

  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/books`;

  private http = inject(HttpClient);
  private messageService = inject(MessageService);
  private authService = inject(AuthService);
  private router = inject(Router);
  private bookSocketService = inject(BookSocketService);
  private bookPatchService = inject(BookPatchService);
  private queryClient = inject(QueryClient);
  private readonly t = inject(TranslocoService);
  private readonly token = this.authService.token;
  private readonly bookQueryService = inject(BookQueryService);

  // Sidebar badge counts and the boot-time "all books" total come from server-side facet
  // counts - never the full collection (132k books, ~100 columns each).
  private readonly globalFacetsQuery = injectQuery(() => ({
    ...this.bookQueryService.facets(TOTAL_COUNT_FACET_PARAMS),
    enabled: !!this.token(),
  }));

  readonly totalBookCount = computed(() => toFacetTotalCount(this.globalFacetsQuery.data(), 'shelf_status'));

  bookDetailQueryOptions(bookId: number, withDescription: boolean) {
    return queryOptions({
      queryKey: bookDetailQueryKey(bookId, withDescription),
      queryFn: () => lastValueFrom(this.http.get<Book>(`${this.url}/${bookId}`, {
        params: {
          withDescription: withDescription.toString()
        }
      }))
    });
  }

  ensureBookDetail(bookId: number, withDescription: boolean): Promise<Book> {
    return this.queryClient.ensureQueryData(this.bookDetailQueryOptions(bookId, withDescription));
  }

  /**
   * Always fetches book detail from the server, bypassing the cache.
   *
   * Used by reader components on initialization to guarantee the latest reading position.
   * While optimistic cache updates keep progress current within a single session,
   * this network request is necessary to pick up progress saved on another device or browser.
   */
  fetchFreshBookDetail(bookId: number, withDescription: boolean): Promise<Book> {
    return this.queryClient.fetchQuery({
      ...this.bookDetailQueryOptions(bookId, withDescription),
      staleTime: 0,
    });
  }

  bookRecommendationsQueryOptions(bookId: number, limit: number) {
    return queryOptions({
      queryKey: bookRecommendationsQueryKey(bookId, limit),
      queryFn: () => lastValueFrom(this.http.get<BookRecommendation[]>(`${this.url}/${bookId}/recommendations`, {
        params: {limit: limit.toString()}
      }))
    });
  }

  removeBooksFromShelf(shelfId: number): void {
    invalidateAppBooksQueries(this.queryClient);
  }

  /*------------------ Book Retrieval ------------------*/

  // /books/batch fetches only the requested ids - a selection editor must never wait on the
  // full collection just to resolve the few books it was opened with.
  getBooksByIds(bookIds: number[]): Promise<Book[]> {
    if (bookIds.length === 0) return Promise.resolve([]);
    const ids = new Set(bookIds.map(id => +id));
    const params = new HttpParams().set('ids', Array.from(ids).join(','));
    return lastValueFrom(this.http.get<Book[]>(`${this.url}/batch`, {params}));
  }

  // Pages /books/page scoped to the series facet to exhaustion, instead of filtering the full
  // collection client-side - a series is a handful of books, not 132k.
  getBooksInSeries(seriesName: string): Observable<Book[]> {
    if (!seriesName) {
      return of([]);
    }

    return new Observable<Book[]>(subscriber => {
      const controller = new AbortController();
      this.bookQueryService.fetchAllPages({
        facets: {series: [seriesName]},
        facetLogic: 'and',
        sort: [],
        size: 100,
      }, controller.signal).then(
        summaries => {
          subscriber.next(summaries.map(bookSummaryToBook));
          subscriber.complete();
        },
        error => subscriber.error(error),
      );
      return () => controller.abort();
    });
  }

  getBookRecommendations(bookId: number, limit: number = 20): Observable<BookRecommendation[]> {
    return from(this.queryClient.ensureQueryData(this.bookRecommendationsQueryOptions(bookId, limit)));
  }

  /*------------------ Book Operations ------------------*/

  deleteBooks(ids: Set<number>): Observable<BookDeletionResponse> {
    const idList = Array.from(ids);
    const params = new HttpParams().set('ids', idList.join(','));

    return this.http.delete<BookDeletionResponse>(this.url, {params}).pipe(
      tap(response => {
        const deletedIds = response.deleted.length > 0 ? response.deleted : idList;
        invalidateBooksQuery(this.queryClient);
        removeBookQueries(this.queryClient, deletedIds);

        if (response.failedFileDeletions?.length > 0) {
          this.messageService.add({
            severity: 'warn',
            summary: this.t.translate('book.bookService.toast.someFilesNotDeletedSummary'),
            detail: this.t.translate('book.bookService.toast.someFilesNotDeletedDetail', {fileNames: response.failedFileDeletions.join(', ')}),
          });
        } else {
          this.messageService.add({
            severity: 'success',
            summary: this.t.translate('book.bookService.toast.booksDeletedSummary'),
            detail: this.t.translate('book.bookService.toast.booksDeletedDetail', {count: idList.length}),
          });
        }
      }),
      catchError(error => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('book.bookService.toast.deleteFailedSummary'),
          detail: error?.error?.message || error?.message || this.t.translate('book.bookService.toast.deleteFailedDetail'),
        });
        return throwError(() => error);
      })
    );
  }

  updateBookShelves(bookIds: Set<number | undefined>, shelvesToAssign: Set<number | null | undefined>, shelvesToUnassign: Set<number | null | undefined>): Observable<Book[]> {
    return this.bookPatchService.updateBookShelves(bookIds, shelvesToAssign, shelvesToUnassign);
  }

  createPhysicalBook(request: CreatePhysicalBookRequest): Observable<Book> {
    return this.http.post<Book>(`${this.url}/physical`, request).pipe(
      tap(newBook => {
        invalidateBooksQuery(this.queryClient);
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('book.bookService.toast.physicalBookCreatedSummary'),
          detail: this.t.translate('book.bookService.toast.physicalBookCreatedDetail', {title: newBook.metadata?.title || 'Book'})
        });
      }),
      catchError(error => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('book.bookService.toast.creationFailedSummary'),
          detail: error?.error?.message || error?.message || this.t.translate('book.bookService.toast.creationFailedDetail')
        });
        return throwError(() => error);
      })
    );
  }

  togglePhysicalFlag(bookId: number, physical: boolean): Observable<Book> {
    return this.http.patch<Book>(`${this.url}/${bookId}/physical`, null, {params: {physical}}).pipe(
      tap(updatedBook => {
        patchBooksInCache(this.queryClient, [updatedBook]);
      })
    );
  }

  /*------------------ Reading & Viewer Settings ------------------*/

  readBook(bookId: number, reader?: 'epub-streaming', explicitBookType?: BookType): void {
    this.ensureBookDetail(bookId, false).then(detail => {
      this.navigateToReader(detail, bookId, reader, explicitBookType);
    }).catch(() => {
      console.error('Book not found:', bookId);
    });
  }

  private navigateToReader(book: Book, bookId: number, reader?: 'epub-streaming', explicitBookType?: BookType): void {

    const bookType: BookType | undefined = explicitBookType ?? book.primaryFile?.bookType;
    const isAlternativeFormat = explicitBookType && explicitBookType !== book.primaryFile?.bookType;

    let baseUrl: string | null = null;
    const queryParams: Partial<{streaming: true; bookType: BookType}> = {};

    switch (bookType) {
      case 'PDF':
        baseUrl = 'pdf-reader';
        break;

      case 'EPUB':
        baseUrl = 'ebook-reader';
        if (reader === 'epub-streaming') {
          queryParams['streaming'] = true;
        }
        break;

      case 'FB2':
      case 'MOBI':
      case 'AZW3':
        baseUrl = 'ebook-reader';
        break;

      case 'CBX':
        baseUrl = 'cbx-reader';
        break;

      case 'AUDIOBOOK':
        baseUrl = 'audiobook-player';
        break;
    }

    if (!baseUrl) {
      console.error('Unsupported book type:', bookType);
      return;
    }

    if (isAlternativeFormat) {
      queryParams['bookType'] = bookType;
    }

    const hasQueryParams = Object.keys(queryParams).length > 0;
    this.router.navigate([`/${baseUrl}/book/${book.id}`], hasQueryParams ? {queryParams} : undefined);

    this.updateLastReadTime(book.id);
  }

  getBookSetting(bookId: number, bookFileId: number): Observable<BookSetting> {
    return this.http.get<BookSetting>(`${this.url}/${bookId}/viewer-setting?bookFileId=${bookFileId}`);
  }

  updateViewerSetting(bookSetting: BookSetting, bookId: number): Observable<void> {
    return this.http.put<void>(`${this.url}/${bookId}/viewer-setting`, bookSetting);
  }

  /*------------------ Progress & Status Tracking ------------------*/

  updateLastReadTime(bookId: number): void {
    this.bookPatchService.updateLastReadTime(bookId);
  }

  savePdfProgress(bookId: number, page: number, percentage: number, bookFileId?: number): Observable<void> {
    return this.bookPatchService.savePdfProgress(bookId, page, percentage, bookFileId);
  }

  saveCbxProgress(bookId: number, page: number, percentage: number, bookFileId?: number): Observable<void> {
    return this.bookPatchService.saveCbxProgress(bookId, page, percentage, bookFileId);
  }

  updateDateFinished(bookId: number, dateFinished: string | null): Observable<void> {
    return this.bookPatchService.updateDateFinished(bookId, dateFinished);
  }

  resetProgress(bookIds: number | number[], type: ResetProgressType): Observable<BookStatusUpdateResponse[]> {
    return this.bookPatchService.resetProgress(bookIds, type);
  }

  updateBookReadStatus(bookIds: number | number[], status: ReadStatus): Observable<BookStatusUpdateResponse[]> {
    return this.bookPatchService.updateBookReadStatus(bookIds, status);
  }

  /*------------------ Personal Rating ------------------*/

  resetPersonalRating(bookIds: number | number[]): Observable<PersonalRatingUpdateResponse[]> {
    return this.bookPatchService.resetPersonalRating(bookIds);
  }

  updatePersonalRating(bookIds: number | number[], rating: number): Observable<PersonalRatingUpdateResponse[]> {
    return this.bookPatchService.updatePersonalRating(bookIds, rating);
  }

  /*------------------ Websocket Handlers ------------------*/

  handleNewlyCreatedBook(book: Book): void {
    this.bookSocketService.handleNewlyCreatedBook(book);
  }

  handleRemovedBookIds(removedBookIds: number[]): void {
    this.bookSocketService.handleRemovedBookIds(removedBookIds);
  }

  handleBookUpdate(updatedBook: Book): void {
    this.bookSocketService.handleBookUpdate(updatedBook);
  }

  handleMultipleBookUpdates(updatedBooks: Book[]): void {
    this.bookSocketService.handleMultipleBookUpdates(updatedBooks);
  }

  handleBookMetadataUpdate(bookId: number): void {
    this.bookSocketService.handleBookMetadataUpdate(bookId);
  }

  handleMultipleBookCoverPatches(patches: { id: number; coverUpdatedOn: string }[]): void {
    this.bookSocketService.handleMultipleBookCoverPatches(patches);
  }
}
