import {Component, inject, OnInit} from '@angular/core';
import {FormBuilder, FormGroup, FormsModule, ReactiveFormsModule} from '@angular/forms';

import {InputText} from '@openng/optimus-ui/inputtext';
import {Button} from '@openng/optimus-ui/button';
import {Tooltip} from '@openng/optimus-ui/tooltip';
import {DatePicker} from '@openng/optimus-ui/datepicker';
import {DynamicDialogConfig, DynamicDialogRef} from '@openng/optimus-ui/dynamicdialog';
import {MessageService} from '@openng/optimus-ui/api';
import {BookService} from '../../../book/service/book.service';
import {BookMetadataManageService} from '../../../book/service/book-metadata-manage.service';
import {Book, BulkMetadataUpdateRequest} from '../../../book/model/book.model';
import {Checkbox} from '@openng/optimus-ui/checkbox';
import {AutoComplete} from '@openng/optimus-ui/autocomplete';
import {AutoCompleteSelectEvent} from '@openng/optimus-ui/autocomplete';
import {ProgressSpinner} from '@openng/optimus-ui/progressspinner';
import {metadataValueTypeahead} from '../metadata-manager/metadata-values.service';

@Component({
  selector: 'app-bulk-metadata-update-component',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    FormsModule,
    InputText,
    Button,
    Tooltip,
    DatePicker,
    Checkbox,
    ProgressSpinner,
    AutoComplete
],
  providers: [MessageService],
  templateUrl: './bulk-metadata-update-component.html',
  styleUrl: './bulk-metadata-update-component.scss'
})
export class BulkMetadataUpdateComponent implements OnInit {
  metadataForm!: FormGroup;
  bookIds: number[] = [];
  books: Book[] = [];
  showBookList = true;
  mergeCategories = true;
  mergeMoods = true;
  mergeTags = true;
  loading = false;
  selectedCoverFile: File | null = null;

  clearFields = {
    authors: false,
    publisher: false,
    language: false,
    seriesName: false,
    seriesTotal: false,
    publishedDate: false,
    genres: false,
    moods: false,
    tags: false,
  };

  private readonly config = inject(DynamicDialogConfig);
  readonly ref = inject(DynamicDialogRef);
  private readonly fb = inject(FormBuilder);
  private readonly bookService = inject(BookService);
  private readonly bookMetadataManageService = inject(BookMetadataManageService);
  private readonly messageService = inject(MessageService);

  // Server-side typeahead per field - never the full metadata vocabulary loaded up front.
  private readonly authorsTypeahead = metadataValueTypeahead('author');
  private readonly genresTypeahead = metadataValueTypeahead('genre');
  private readonly moodsTypeahead = metadataValueTypeahead('mood');
  private readonly tagsTypeahead = metadataValueTypeahead('tag');
  private readonly publishersTypeahead = metadataValueTypeahead('publisher');
  private readonly seriesTypeahead = metadataValueTypeahead('series');

  get filteredGenres(): string[] { return this.genresTypeahead.results(); }
  get filteredAuthors(): string[] { return this.authorsTypeahead.results(); }
  get filteredMoods(): string[] { return this.moodsTypeahead.results(); }
  get filteredTags(): string[] { return this.tagsTypeahead.results(); }
  get filteredPublishers(): string[] { return this.publishersTypeahead.results(); }
  get filteredSeries(): string[] { return this.seriesTypeahead.results(); }

  filterGenres(event: { query: string }) {
    this.genresTypeahead.filter(event);
  }

  filterAuthors(event: { query: string }) {
    this.authorsTypeahead.filter(event);
  }

  filterMoods(event: { query: string }) {
    this.moodsTypeahead.filter(event);
  }

  filterTags(event: { query: string }) {
    this.tagsTypeahead.filter(event);
  }

  filterPublishers(event: { query: string }) {
    this.publishersTypeahead.filter(event);
  }

  filterSeries(event: { query: string }) {
    this.seriesTypeahead.filter(event);
  }

  async ngOnInit(): Promise<void> {
    this.bookIds = this.config.data?.bookIds ?? [];

    this.metadataForm = this.fb.group({
      authors: [],
      publisher: [''],
      language: [''],
      seriesName: [''],
      seriesTotal: [''],
      publishedDate: [null],
      genres: [],
      moods: [],
      tags: []
    });

    // /books/batch fetches only the selection - never the full collection.
    this.books = await this.bookService.getBooksByIds(this.bookIds);
  }

  onFieldClearToggle(field: keyof typeof this.clearFields): void {
    const control = this.metadataForm.get(field);
    if (!control) return;

    if (this.clearFields[field]) {
      control.disable();
      control.setValue(null);
    } else {
      control.enable();
    }
  }

  onAutoCompleteSelect(fieldName: string, event: AutoCompleteSelectEvent) {
    const values = (this.metadataForm.get(fieldName)?.value as string[]) || [];
    if (!values.includes(event.value as string)) {
      this.metadataForm.get(fieldName)?.setValue([...values, event.value as string]);
    }
    (event.originalEvent.target as HTMLInputElement).value = "";
  }

  onAutoCompleteKeyUp(fieldName: string, event: KeyboardEvent) {
    if (event.key === "Enter") {
      const input = event.target as HTMLInputElement;
      const value = input.value?.trim();
      if (value) {
        const values = this.metadataForm.get(fieldName)?.value || [];
        if (!values.includes(value)) {
          this.metadataForm.get(fieldName)?.setValue([...values, value]);
        }
        input.value = "";
      }
    }
  }

  onFormKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      if ((event.target as HTMLElement)?.tagName === 'BUTTON' &&
        (event.target as HTMLButtonElement)?.type === 'submit') {
        return;
      }
      event.preventDefault();
    }
  }

  onSubmit(): void {
    if (!this.metadataForm.valid) return;

    const formValue = this.metadataForm.value;

    const payload: BulkMetadataUpdateRequest = {
      bookIds: this.bookIds,

      authors: this.clearFields.authors ? [] : (formValue.authors?.length ? formValue.authors : undefined),
      clearAuthors: this.clearFields.authors,

      publisher: this.clearFields.publisher ? '' : (formValue.publisher?.trim() || undefined),
      clearPublisher: this.clearFields.publisher,

      language: this.clearFields.language ? '' : (formValue.language?.trim() || undefined),
      clearLanguage: this.clearFields.language,

      seriesName: this.clearFields.seriesName ? '' : (formValue.seriesName?.trim() || undefined),
      clearSeriesName: this.clearFields.seriesName,

      seriesTotal: this.clearFields.seriesTotal ? null : (formValue.seriesTotal || undefined),
      clearSeriesTotal: this.clearFields.seriesTotal,

      publishedDate: this.clearFields.publishedDate
        ? null
        : (formValue.publishedDate ? new Date(formValue.publishedDate).toISOString().split('T')[0] : undefined),
      clearPublishedDate: this.clearFields.publishedDate,

      genres: this.clearFields.genres ? [] : (formValue.genres?.length ? formValue.genres : undefined),
      clearGenres: this.clearFields.genres,

      moods: this.clearFields.moods ? [] : (formValue.moods?.length ? formValue.moods : undefined),
      clearMoods: this.clearFields.moods,

      tags: this.clearFields.tags ? [] : (formValue.tags?.length ? formValue.tags : undefined),
      clearTags: this.clearFields.tags,

      mergeCategories: this.mergeCategories,
      mergeMoods: this.mergeMoods,
      mergeTags: this.mergeTags
    };

    this.loading = true;
    this.bookMetadataManageService.updateBooksMetadata(payload).subscribe({
      next: () => {
        if (this.selectedCoverFile) {
          this.bookMetadataManageService.bulkUploadCover(this.bookIds, this.selectedCoverFile).subscribe({
            next: () => {
              this.loading = false;
              this.messageService.add({
                severity: 'success',
                summary: 'Metadata & Cover Updated',
                detail: 'Books updated and cover upload started. Refresh the page when complete.'
              });
              this.ref.close(true);
            },
            error: err => {
              console.error('Bulk cover upload failed:', err);
              this.loading = false;
              this.messageService.add({
                severity: 'warn',
                summary: 'Partial Success',
                detail: 'Metadata updated but cover upload failed'
              });
              this.ref.close(true);
            }
          });
        } else {
          this.loading = false;
          this.messageService.add({
            severity: 'success',
            summary: 'Metadata Updated',
            detail: 'Books updated successfully'
          });
          this.ref.close(true);
        }
      },
      error: err => {
        console.error('Bulk metadata update failed:', err);
        this.loading = false;
        this.messageService.add({
          severity: 'error',
          summary: 'Update Failed',
          detail: 'An error occurred while updating book metadata'
        });
      }
    });
  }

  onCoverFileSelect(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.selectedCoverFile = input.files[0];
    }
  }

  clearCoverFile(): void {
    this.selectedCoverFile = null;
  }
}
