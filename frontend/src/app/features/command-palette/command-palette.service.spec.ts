import { signal } from '@angular/core';
import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MessageService } from '@openng/optimus-ui/api';
import { getTranslocoModule } from '../../core/testing/transloco-testing';
import { createAuthServiceStub, createQueryClientHarness } from '../../core/testing/query-testing';
import { AuthService } from '../../shared/service/auth.service';
import { BookDialogHelperService } from '../book/components/book-browser/book-dialog-helper.service';
import type { BookSummary } from '../book/data/book-response.models';
import { LibraryService } from '../book/service/library.service';
import { ShelfService } from '../book/service/shelf.service';
import { MagicShelfService } from '../magic-shelf/service/magic-shelf.service';
import { UrlHelperService } from '../../shared/service/url-helper.service';
import { UserService } from '../settings/user-management/user.service';
import { CustomSvgService } from '../../shared/services/custom-svg.service';
import { DialogLauncherService } from '../../shared/services/dialog-launcher.service';

import { CommandPaletteService } from './command-palette.service';

function bookSummary(id: number, title: string, authors: string[] = [], overrides: Partial<BookSummary> = {}): BookSummary {
  return {
    id,
    libraryId: 1,
    libraryName: 'Library',
    ...overrides,
    metadata: {
      bookId: id,
      title,
      authors,
      allMetadataLocked: false,
      ...overrides.metadata,
    },
  } as BookSummary;
}

function pageResponse(content: BookSummary[]) {
  return {
    content,
    page: { number: 0, size: content.length, totalElements: content.length, totalPages: 1, cursor: '' },
    links: [],
  };
}

describe('CommandPaletteService', () => {
  let service: CommandPaletteService;
  let httpTestingController: HttpTestingController;
  let queryClientHarness: ReturnType<typeof createQueryClientHarness>;
  let urlHelper: {
    getThumbnailUrl: ReturnType<typeof vi.fn>;
    getAudiobookThumbnailUrl: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    vi.useFakeTimers();
  });

  beforeEach(() => {
    queryClientHarness = createQueryClientHarness();
    queryClientHarness.queryClient.setDefaultOptions({ queries: { retry: false } });
    urlHelper = {
      getThumbnailUrl: vi.fn(() => null),
      getAudiobookThumbnailUrl: vi.fn(() => null),
    };

    TestBed.configureTestingModule({
      imports: [getTranslocoModule()],
      providers: [
        ...queryClientHarness.providers,
        { provide: Router, useValue: { navigate: vi.fn(() => Promise.resolve(true)) } },
        { provide: AuthService, useValue: createAuthServiceStub() },
        { provide: ShelfService, useValue: { shelves: signal([]) } },
        { provide: MagicShelfService, useValue: { shelves: signal([]) } },
        { provide: LibraryService, useValue: { libraries: signal([]) } },
        { provide: UserService, useValue: { currentUser: signal({ permissions: {} }) } },
        { provide: MessageService, useValue: { add: vi.fn() } },
        { provide: UrlHelperService, useValue: urlHelper },
        { provide: CustomSvgService, useValue: { getSvgIconContent: vi.fn(() => of('')) } },
        {
          provide: DialogLauncherService,
          useValue: {
            openLibraryCreateDialog: vi.fn(() => Promise.resolve(null)),
            openMagicShelfCreateDialog: vi.fn(() => Promise.resolve(null)),
            openFileUploadDialog: vi.fn(() => Promise.resolve(null)),
          },
        },
        {
          provide: BookDialogHelperService,
          useValue: {
            openShelfCreatorDialog: vi.fn(() => Promise.resolve(null)),
          },
        },
      ],
    });

    service = TestBed.inject(CommandPaletteService);
    httpTestingController = TestBed.inject(HttpTestingController);
    TestBed.flushEffects();
  });

  afterEach(() => {
    httpTestingController?.verify();
    queryClientHarness?.queryClient.clear();
    TestBed.resetTestingModule();
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  // Lets a flushed HTTP response propagate through Angular Query's microtask chain under fake timers.
  async function settle(): Promise<void> {
    for (let i = 0; i < 5; i++) {
      TestBed.flushEffects();
      await Promise.resolve();
      await vi.advanceTimersByTimeAsync(0);
    }
    TestBed.flushEffects();
  }

  it('queries matching book groups from the server after the debounce window', async () => {
    service.query.set('tolkien');
    TestBed.flushEffects();
    await vi.advanceTimersByTimeAsync(200);
    TestBed.flushEffects();

    const req = httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page'));
    expect(req.request.params.get('query')).toBe('tolkien');
    req.flush(pageResponse([
      bookSummary(1, 'The Hobbit', ['J.R.R. Tolkien']),
      bookSummary(2, 'The Fellowship of the Ring', ['J.R.R. Tolkien']),
    ]));
    await settle();

    const bookGroup = service.groups().find((group) => group.kind === 'book');

    expect(bookGroup).toBeDefined();
    expect(bookGroup?.items.map((item) => item.title)).toEqual([
      'The Hobbit',
      'The Fellowship of the Ring',
    ]);
  });

  it('does not search the server for one-character searches', async () => {
    service.query.set('d');
    TestBed.flushEffects();
    await vi.advanceTimersByTimeAsync(200);
    TestBed.flushEffects();

    httpTestingController.expectNone(r => r.url.endsWith('/api/v1/books/page'));
    expect(service.groups().find((group) => group.kind === 'book')).toBeUndefined();
  });

  it('returns no groups when the query is empty', () => {
    service.query.set('');

    expect(service.groups()).toEqual([]);
    expect(service.visibleItems()).toEqual([]);
  });

  it('uses square audiobook metadata and audiobook thumbnails for audiobook results', async () => {
    urlHelper.getAudiobookThumbnailUrl.mockReturnValue('/audio-thumb.jpg');

    service.query.set('audio');
    TestBed.flushEffects();
    await vi.advanceTimersByTimeAsync(200);
    TestBed.flushEffects();

    const req = httpTestingController.expectOne(r => r.url.endsWith('/api/v1/books/page'));
    req.flush(pageResponse([
      bookSummary(4, 'Audio Sample', ['Narrator'], {
        primaryFile: { id: 4, bookId: 4, bookType: 'AUDIOBOOK', book: true, folderBased: false },
        metadata: {
          bookId: 4,
          title: 'Audio Sample',
          authors: ['Narrator'],
          audiobookCoverUpdatedOn: 'audio-updated',
          allMetadataLocked: false,
        },
      }),
    ]));
    await settle();

    const book = service.groups().find((group) => group.kind === 'book')?.items[0];

    expect(book?.bookMeta?.isAudiobook).toBe(true);
    expect(book?.bookMeta?.thumbnailUrl).toBe('/audio-thumb.jpg');
    expect(urlHelper.getAudiobookThumbnailUrl).toHaveBeenCalledWith(4, 'audio-updated');
    expect(urlHelper.getThumbnailUrl).not.toHaveBeenCalled();
  });
});
