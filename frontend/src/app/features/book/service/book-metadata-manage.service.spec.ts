import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {QueryClient} from '@tanstack/angular-query-experimental';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {MessageService} from '@openng/optimus-ui/api';

import {Book, BookMetadata, MetadataUpdateWrapper} from '../model/book.model';
import {bookDetailQueryPrefix} from './book-query-keys';
import {BookMetadataManageService} from './book-metadata-manage.service';
import {TranslocoService} from '@jsverse/transloco';

function makeBook(id: number, metadata: Partial<BookMetadata> = {}): Book {
  return {
    id,
    libraryId: 1,
    libraryName: 'Library',
    metadata: {
      bookId: id,
      title: `Book ${id}`,
      titleLocked: false,
      authorsLocked: false,
      ...metadata,
    },
  };
}

describe('BookMetadataManageService', () => {
  let service: BookMetadataManageService;
  let httpTestingController: HttpTestingController;
  let queryClient: QueryClient;
  let messageService: {add: ReturnType<typeof vi.fn>};

  beforeEach(() => {
    queryClient = new QueryClient();
    vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue();
    messageService = {
      add: vi.fn(),
    };

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        BookMetadataManageService,
        {provide: QueryClient, useValue: queryClient},
        {provide: MessageService, useValue: messageService},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ],
    });

    service = TestBed.inject(BookMetadataManageService);
    httpTestingController = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTestingController.verify();
    queryClient.clear();
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('updates metadata with merge params and invalidates the cached book detail', () => {
    const wrapper: MetadataUpdateWrapper = {
      metadata: {bookId: 7, title: 'Updated Title'},
      clearFlags: {},
    };
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    service.updateBookMetadata(7, wrapper, true, 'REPLACE_WHEN_PROVIDED').subscribe(metadata => {
      expect(metadata.title).toBe('Updated Title');
    });

    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/7/metadata'));
    expect(request.request.method).toBe('PUT');
    expect(request.request.params.get('mergeCategories')).toBe('true');
    expect(request.request.params.get('replaceMode')).toBe('REPLACE_WHEN_PROVIDED');
    expect(request.request.body).toEqual(wrapper);
    request.flush({bookId: 7, title: 'Updated Title'});

    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(7)});
  });

  it('invalidates the cached book detail for successful bulk lock updates', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    service.toggleFieldLocks([1], {title: 'LOCK', authorsLocked: 'LOCK'}).subscribe();

    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/metadata/toggle-field-locks'));
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      bookIds: [1],
      fieldActions: {title: 'LOCK', authorsLocked: 'LOCK'},
    });
    request.flush(null);

    expect(invalidateSpy).toHaveBeenCalledWith({queryKey: bookDetailQueryPrefix(1)});
  });

  it('shows an error toast when toggleFieldLocks fails', () => {
    let thrown: unknown;

    service.toggleFieldLocks(new Set([2]), {title: 'UNLOCK'}).subscribe({
      error: error => {
        thrown = error;
      },
    });

    const request = httpTestingController.expectOne(req => req.url.endsWith('/api/v1/books/metadata/toggle-field-locks'));
    request.flush({message: 'nope'}, {status: 500, statusText: 'Server Error'});

    expect(thrown).toBeTruthy();
    expect(messageService.add).toHaveBeenCalledWith(expect.objectContaining({
      severity: 'error',
      summary: 'book.bookService.toast.fieldLockFailedSummary',
    }));
  });

  it('reports whether a book has both audiobook and ebook files', () => {
    const dualFormatBook = makeBook(3, {
      audiobookCoverUpdatedOn: '2026-03-01',
    });
    dualFormatBook.primaryFile = {id: 10, bookId: 3, bookType: 'EPUB'};
    dualFormatBook.alternativeFormats = [{id: 11, bookId: 3, bookType: 'AUDIOBOOK'}];

    const audiobookOnlyBook = makeBook(4);
    audiobookOnlyBook.primaryFile = {id: 12, bookId: 4, bookType: 'AUDIOBOOK'};

    expect(service.supportsDualCovers(dualFormatBook)).toBe(true);
    expect(service.supportsDualCovers(audiobookOnlyBook)).toBe(false);
  });

  it('returns stable upload URLs for ebook and audiobook cover endpoints', () => {
    expect(service.getUploadCoverUrl(9)).toBe('http://localhost:6060/api/v1/books/9/metadata/cover/upload');
    expect(service.getUploadAudiobookCoverUrl(9)).toBe('http://localhost:6060/api/v1/books/9/metadata/audiobook-cover/upload');
  });
});
