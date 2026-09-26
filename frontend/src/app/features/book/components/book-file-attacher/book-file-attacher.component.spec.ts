import {TestBed} from '@angular/core/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {TranslocoService} from '@jsverse/transloco';
import {MessageService} from '@openng/optimus-ui/api';
import {DynamicDialogConfig, DynamicDialogRef} from '@openng/optimus-ui/dynamicdialog';
import {describe, expect, it, vi} from 'vitest';
import {Observable, of, throwError} from 'rxjs';
import {createAuthServiceStub, createQueryClientHarness, flushQueryAsync} from '../../../../core/testing/query-testing';
import {AuthService} from '../../../../shared/service/auth.service';
import {Book} from '../../model/book.model';
import {BookFileService} from '../../service/book-file.service';
import {BookFileAttacherComponent} from './book-file-attacher.component';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';

interface DialogData {
  sourceBook?: Book;
  sourceBooks?: Book[];
}

type AppSettingsLike = {
  metadataPersistenceSettings?: {
    moveFilesToLibraryPattern?: boolean;
  };
} | null;

function buildBook(overrides: Partial<Book> = {}): Book {
  const id = overrides.id ?? 1;
  return {
    id,
    libraryId: overrides.libraryId ?? 10,
    libraryName: overrides.libraryName ?? 'Main Library',
    metadata: overrides.metadata ?? {
      bookId: id,
      title: `Book ${id}`,
      authors: [`Author ${id}`],
    },
    primaryFile: overrides.primaryFile,
    ...overrides,
  };
}

function pageResponse(content: Book[]) {
  return {
    content,
    page: {number: 0, size: content.length, totalElements: content.length, totalPages: 1, cursor: ''},
    links: [],
  };
}

function setup(options: {
  dialogData?: DialogData;
  appSettings?: AppSettingsLike;
  attachResult?: Observable<{updatedBook: Book; deletedSourceBookIds: number[]}>;
} = {}) {
  const dialogRef = {
    close: vi.fn(),
  };
  const config = {
    data: options.dialogData ?? {},
  };
  const bookFileService = {
    attachBookFiles: vi.fn().mockReturnValue(
      options.attachResult ?? of({updatedBook: buildBook({id: 999}), deletedSourceBookIds: []})
    ),
  };
  const appSettingsService = {
    appSettings: vi.fn(() => options.appSettings ?? null),
  };
  const translocoService = {
    translate: vi.fn((key: string) => key),
  };
  const messageService = {
    add: vi.fn(),
  };

  const queryClientHarness = createQueryClientHarness();
  queryClientHarness.queryClient.setDefaultOptions({queries: {retry: false}});

  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      ...queryClientHarness.providers,
      {provide: AuthService, useValue: createAuthServiceStub()},
      {provide: DynamicDialogRef, useValue: dialogRef},
      {provide: DynamicDialogConfig, useValue: config},
      {provide: BookFileService, useValue: bookFileService},
      {provide: AppSettingsService, useValue: appSettingsService},
      {provide: TranslocoService, useValue: translocoService},
      {provide: MessageService, useValue: messageService},
    ],
  });

  const component = TestBed.runInInjectionContext(() => new BookFileAttacherComponent());
  const httpTestingController = TestBed.inject(HttpTestingController);

  return {
    component,
    dialogRef,
    bookFileService,
    appSettingsService,
    translocoService,
    httpTestingController,
    queryClientHarness,
  };
}

describe('BookFileAttacherComponent', () => {
  it('bootstraps a single source book, searches candidates scoped to its library, and applies the moveFiles setting', async () => {
    const sourceBook = buildBook({id: 1, libraryId: 7, metadata: {bookId: 1, title: 'Source', authors: ['Origin']}});
    const {component, httpTestingController} = setup({
      dialogData: {sourceBook},
      appSettings: {
        metadataPersistenceSettings: {
          moveFilesToLibraryPattern: true,
        },
      },
    });

    component.ngOnInit();

    expect(component.sourceBooks).toEqual([sourceBook]);
    expect(component.isBulkMode).toBe(false);
    expect(component.moveFiles).toBe(true);

    const req = httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page'));
    expect(req.request.params.getAll('facet')).toEqual(['library:7']);
    expect(req.request.params.has('query')).toBe(false);
    const siblingCandidate = buildBook({id: 2, libraryId: 7, metadata: {bookId: 2, title: 'Sibling', authors: ['Match']}});
    req.flush(pageResponse([sourceBook, siblingCandidate]));
    await flushQueryAsync();

    expect(component.filteredBooks).toEqual([siblingCandidate]);
    httpTestingController.verify();
  });

  it('bootstraps bulk mode from sourceBooks and defaults moveFiles to false when settings are unavailable', async () => {
    const sourceA = buildBook({id: 10, libraryId: 4});
    const sourceB = buildBook({id: 11, libraryId: 4});
    const target = buildBook({id: 12, libraryId: 4});
    const {component, httpTestingController} = setup({
      dialogData: {sourceBooks: [sourceA, sourceB]},
      appSettings: null,
    });

    component.ngOnInit();

    expect(component.sourceBooks).toEqual([sourceA, sourceB]);
    expect(component.isBulkMode).toBe(true);
    expect(component.moveFiles).toBe(false);

    const req = httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page'));
    // Overfetches by the source-book count (2) so excluding them still leaves a full page.
    expect(req.request.params.get('size')).toBe('22');
    req.flush(pageResponse([target]));
    await flushQueryAsync();

    expect(component.filteredBooks).toEqual([target]);
    httpTestingController.verify();
  });

  it('closes immediately when no source payload is provided', () => {
    const {component, dialogRef, appSettingsService, httpTestingController} = setup({
      dialogData: {},
    });

    component.ngOnInit();

    expect(dialogRef.close).toHaveBeenCalledOnce();
    expect(dialogRef.close).toHaveBeenCalledWith();
    expect(appSettingsService.appSettings).not.toHaveBeenCalled();
    httpTestingController.expectNone(r => r.url.endsWith('/api/v1/books/page'));
  });

  it('re-searches the server on each typed query, trimmed, scoped to the library', async () => {
    const sourceBook = buildBook({id: 1, libraryId: 5, metadata: {bookId: 1, title: 'Source', authors: ['Keeper']}});
    const {component, httpTestingController} = setup({
      dialogData: {sourceBook},
    });

    component.ngOnInit();
    httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page')).flush(pageResponse([]));
    await flushQueryAsync();

    component.filterBooks({query: ' dune '});
    const matchReq = httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page'));
    expect(matchReq.request.params.get('query')).toBe('dune');
    const matchingByTitle = buildBook({id: 2, libraryId: 5, metadata: {bookId: 2, title: 'Dune Messiah', authors: ['Frank Herbert']}});
    matchReq.flush(pageResponse([matchingByTitle]));
    await flushQueryAsync();
    expect(component.filteredBooks).toEqual([matchingByTitle]);

    // Same (blank) query as the initial ngOnInit search - the query cache serves it without
    // a second round trip, so this asserts the cached result rather than a new request.
    component.filterBooks({query: '   '});
    await flushQueryAsync();
    expect(component.filteredBooks).toEqual([]);

    httpTestingController.verify();
  });

  it('excludes the source books from search results even when the server returns them', async () => {
    const sourceBook = buildBook({id: 1, libraryId: 5});
    const {component, httpTestingController} = setup({
      dialogData: {sourceBook},
    });

    component.ngOnInit();
    const other = buildBook({id: 2, libraryId: 5});
    httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page'))
      .flush(pageResponse([sourceBook, other]));
    await flushQueryAsync();

    expect(component.filteredBooks).toEqual([other]);
    httpTestingController.verify();
  });

  it('handles lightweight selection events and formats display helpers with fallbacks', () => {
    const selectedBook = buildBook({
      id: 30,
      metadata: {bookId: 30, title: 'The Left Hand of Darkness', authors: ['Ursula K. Le Guin', 'Guest Author']},
      primaryFile: {id: 301, bookId: 30, extension: 'epub', fileName: 'left-hand.epub'},
    });
    const untitledBook = buildBook({id: 31, metadata: undefined, primaryFile: undefined});
    const {component, translocoService} = setup();

    component.onBookSelect({value: selectedBook} as never);
    expect(component.targetBook).toEqual(selectedBook);

    component.onBookClear();
    expect(component.targetBook).toBeNull();

    expect(component.getBookDisplayName(selectedBook)).toBe('The Left Hand of Darkness - Ursula K. Le Guin, Guest Author');
    expect(component.getBookDisplayName(untitledBook)).toBe('Book #31');
    expect(component.getSourceFileInfo(selectedBook)).toBe('EPUB - left-hand.epub');
    expect(component.getSourceFileInfo(untitledBook)).toBe('book.fileAttacher.unknownFile');
    expect(translocoService.translate).toHaveBeenCalledWith('book.fileAttacher.unknownFile');
  });

  it('uses the target selection and defaulted moveFiles value when attach succeeds', () => {
    const sourceBook = buildBook({id: 40, libraryId: 8});
    const targetBook = buildBook({id: 41, libraryId: 8});
    const {component, bookFileService, dialogRef, httpTestingController} = setup({
      dialogData: {sourceBook},
      appSettings: null,
    });

    component.ngOnInit();
    component.targetBook = targetBook;

    component.attach();

    expect(bookFileService.attachBookFiles).toHaveBeenCalledWith(41, [40], false);
    expect(dialogRef.close).toHaveBeenCalledWith({success: true});
    httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page')).flush(pageResponse([]));
  });

  it('resets attaching state when attach fails and preserves the dialog', () => {
    const sourceBook = buildBook({id: 50, libraryId: 9});
    const targetBook = buildBook({id: 51, libraryId: 9});
    const {component, bookFileService, dialogRef, httpTestingController} = setup({
      dialogData: {sourceBook},
      appSettings: {
        metadataPersistenceSettings: {
          moveFilesToLibraryPattern: true,
        },
      },
      attachResult: throwError(() => new Error('attach failed')),
    });

    component.ngOnInit();
    component.targetBook = targetBook;

    component.attach();

    expect(bookFileService.attachBookFiles).toHaveBeenCalledWith(51, [50], true);
    expect(component.isAttaching).toBe(false);
    expect(dialogRef.close).not.toHaveBeenCalled();
    httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page')).flush(pageResponse([]));
  });

  it('does not attach without a target book and exposes direct close behavior', () => {
    const sourceBook = buildBook({id: 60});
    const {component, bookFileService, dialogRef, httpTestingController} = setup({
      dialogData: {sourceBook},
    });

    component.ngOnInit();
    component.attach();
    component.closeDialog();

    expect(component.canAttach()).toBe(false);
    expect(bookFileService.attachBookFiles).not.toHaveBeenCalled();
    expect(dialogRef.close).toHaveBeenCalledOnce();
    expect(dialogRef.close).toHaveBeenCalledWith();
    httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page')).flush(pageResponse([]));
  });
});
