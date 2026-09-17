import { Component, inject, OnInit, OnDestroy, AfterViewInit, ElementRef, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DynamicDialogRef, DynamicDialogConfig } from '@openng/optimus-ui/dynamicdialog';
import { AutoComplete, AutoCompleteSelectEvent } from '@openng/optimus-ui/autocomplete';
import { Button } from '@openng/optimus-ui/button';
import { Checkbox } from '@openng/optimus-ui/checkbox';
import { Subject, takeUntil } from 'rxjs';
import { QueryClient } from '@tanstack/angular-query-experimental';
import { BookQueryService } from '../../data/book-query.service';
import { BookPageParams, DEFAULT_BOOK_SORT_TERMS } from '../../data/book-query-params';
import { bookSummaryToBook } from '../../data/book-query.models';
import { BookFileService } from '../../service/book-file.service';
import { Book } from '../../model/book.model';
import { MessageService } from '@openng/optimus-ui/api';
import { TranslocoDirective, TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { AppSettingsService } from '../../../../shared/service/app-settings.service';

// A candidate window wide enough that excluding the source books afterwards still leaves
// (at most sourceBooks.length fewer than) a full page of 20 results.
const CANDIDATE_PAGE_SIZE = 20;

@Component({
  selector: 'app-book-file-attacher',
  standalone: true,
  imports: [
    FormsModule,
    AutoComplete,
    Button,
    Checkbox,
    TranslocoDirective,
    TranslocoPipe,
  ],
  templateUrl: './book-file-attacher.component.html',
  styleUrls: ['./book-file-attacher.component.scss']
})
export class BookFileAttacherComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('autocompleteWrapper') autocompleteWrapper!: ElementRef;

  sourceBooks: Book[] = [];
  targetBook: Book | null = null;
  moveFiles = false;
  isAttaching = false;
  searchQuery = '';
  filteredBooks: Book[] = [];
  autocomplePanelStyle: Record<string, string> = {};

  private destroy$ = new Subject<void>();
  private libraryId: number | null = null;
  private sourceBookIds = new Set<number>();

  private readonly t = inject(TranslocoService);
  private readonly appSettingsService = inject(AppSettingsService);
  private dialogRef = inject(DynamicDialogRef);
  private config = inject(DynamicDialogConfig);
  private bookQueryService = inject(BookQueryService);
  private queryClient = inject(QueryClient);
  private bookFileService = inject(BookFileService);
  private messageService = inject(MessageService);

  ngAfterViewInit(): void {
    setTimeout(() => {
      const width = this.autocompleteWrapper?.nativeElement?.offsetWidth;
      if (width) {
        this.autocomplePanelStyle = { 'width': `${width}px`, 'max-width': `${width}px` };
      }
    });
  }

  ngOnInit(): void {
    // Support both single book and multiple books
    if (this.config.data.sourceBook) {
      this.sourceBooks = [this.config.data.sourceBook];
    } else if (this.config.data.sourceBooks) {
      this.sourceBooks = this.config.data.sourceBooks;
    }

    if (this.sourceBooks.length === 0) {
      this.closeDialog();
      return;
    }

    const settings = this.appSettingsService.appSettings();
    if (settings) {
      this.moveFiles = settings.metadataPersistenceSettings?.moveFilesToLibraryPattern ?? false;
    }

    // Get the library ID from first source book (all should be same library)
    this.libraryId = this.sourceBooks[0].libraryId;
    this.sourceBookIds = new Set(this.sourceBooks.map(b => b.id));

    void this.searchCandidates('').then(books => this.filteredBooks = books);
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get isBulkMode(): boolean {
    return this.sourceBooks.length > 1;
  }

  filterBooks(event: { query: string; }): void {
    void this.searchCandidates(event.query.trim()).then(books => this.filteredBooks = books);
  }

  // Server-side search scoped to the source books' library - never the full collection.
  // Overfetches by sourceBookIds.size so excluding the source books afterwards still leaves
  // a full page of candidates.
  private async searchCandidates(query: string): Promise<Book[]> {
    const params: BookPageParams = {
      ...(query ? {query} : {}),
      facets: this.libraryId != null ? {library: [String(this.libraryId)]} : {},
      facetLogic: 'and',
      sort: DEFAULT_BOOK_SORT_TERMS,
      size: CANDIDATE_PAGE_SIZE + this.sourceBookIds.size,
    };

    const page = await this.queryClient.fetchQuery(this.bookQueryService.page(params));
    return page.content
      .map(bookSummaryToBook)
      .filter(book => !this.sourceBookIds.has(book.id))
      .slice(0, CANDIDATE_PAGE_SIZE);
  }

  onBookSelect(event: AutoCompleteSelectEvent): void {
    this.targetBook = event.value as Book;
  }

  onBookClear(): void {
    this.targetBook = null;
  }

  getBookDisplayName(book: Book): string {
    const title = book.metadata?.title || `Book #${book.id}`;
    const authors = book.metadata?.authors?.join(', ');
    return authors ? `${title} - ${authors}` : title;
  }

  getSourceFileInfo(book: Book): string {
    const file = book.primaryFile;
    if (!file) return this.t.translate('book.fileAttacher.unknownFile');
    const format = file.extension?.toUpperCase() || file.bookType || this.t.translate('book.fileAttacher.unknownFormat');
    return `${format} - ${file.fileName || this.t.translate('book.fileAttacher.unknownFilename')}`;
  }

  canAttach(): boolean {
    return !!this.targetBook && !this.isAttaching;
  }

  attach(): void {
    if (!this.targetBook) return;

    this.isAttaching = true;

    const sourceBookIds = this.sourceBooks.map(b => b.id);

    this.bookFileService.attachBookFiles(
      this.targetBook.id,
      sourceBookIds,
      this.moveFiles
    ).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: () => {
        this.dialogRef.close({ success: true });
      },
      error: () => {
        this.isAttaching = false;
      }
    });
  }

  closeDialog(): void {
    this.dialogRef.close();
  }
}
