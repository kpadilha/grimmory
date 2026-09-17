import { AfterViewChecked, Component, computed, effect, ElementRef, inject, OnInit, signal, viewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NgClass } from '@angular/common';
import { Tab, TabList, TabPanel, TabPanels, Tabs } from '@openng/optimus-ui/tabs';
import { ProgressSpinner } from '@openng/optimus-ui/progressspinner';
import { Button } from '@openng/optimus-ui/button';
import { Tag } from '@openng/optimus-ui/tag';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { MessageService } from '@openng/optimus-ui/api';
import { Tooltip } from '@openng/optimus-ui/tooltip';
import { injectInfiniteQuery } from '@tanstack/angular-query-experimental';
import { AuthorService } from '../../service/author.service';
import { AuthorDetails } from '../../model/author.model';
import { BookQueryService } from '../../../book/data/book-query.service';
import { BookPageParams } from '../../../book/data/book-query-params';
import { bookSummaryToBook, flattenBookPages } from '../../../book/data/book-query.models';
import { BookCardComponent } from '../../../book/components/book-browser/book-card/book-card.component';
import { CoverScalePreferenceService } from '../../../book/components/book-browser/cover-scale-preference.service';
import { BookCardOverlayPreferenceService } from '../../../book/components/book-browser/book-card-overlay-preference.service';
import { UserService } from '../../../settings/user-management/user.service';
import { AuthorMatchComponent } from '../author-match/author-match.component';
import { AuthorEditorComponent } from '../author-editor/author-editor.component';
import { PageTitleService } from '../../../../shared/service/page-title.service';
import { createVirtualGrid } from '../../../../shared/util/virtual-grid.util';

@Component({
  selector: 'app-author-detail',
  standalone: true,
  templateUrl: './author-detail.component.html',
  styleUrls: ['./author-detail.component.scss'],
  imports: [
    NgClass,
    Tabs,
    TabList,
    Tab,
    TabPanels,
    TabPanel,
    ProgressSpinner,
    Button,
    Tag,
    TranslocoDirective,
    Tooltip,
    BookCardComponent,
    AuthorMatchComponent,
    AuthorEditorComponent
  ]
})
export class AuthorDetailComponent implements OnInit, AfterViewChecked {

  private static readonly GRID_GAP = 21;
  // Max page size the server allows (see application.yaml); an author past this needs more
  // than one round trip, which drainAuthorBooksPagesEffect below handles.
  private static readonly AUTHOR_BOOKS_PAGE_SIZE = 100;

  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private authorService = inject(AuthorService);
  private bookQueryService = inject(BookQueryService);
  private messageService = inject(MessageService);
  protected coverScalePreferenceService = inject(CoverScalePreferenceService);
  protected bookCardOverlayPreferenceService = inject(BookCardOverlayPreferenceService);
  protected userService = inject(UserService);
  private pageTitle = inject(PageTitleService);
  private t = inject(TranslocoService);

  readonly descriptionContentRef = viewChild<ElementRef<HTMLElement>>('descriptionContent');
  private readonly scrollElement = viewChild<ElementRef<HTMLElement>>('scrollElement');

  loading = signal(true);
  tab = 'books';
  isExpanded = false;
  isOverflowing = false;
  hasPhoto = true;
  photoTimestamp = Date.now();
  quickMatching = false;
  private authorState = signal<AuthorDetails | null>(null);
  author = this.authorState.asReadonly();

  // Server-scoped to this one author via the 'author' facet - never the full collection.
  private readonly authorBooksQueryParams = computed<BookPageParams>(() => {
    const authorName = this.author()?.name ?? '';
    return {
      facets: authorName ? {author: [authorName]} : {},
      facetLogic: 'and',
      sort: [{key: 'title', direction: 'asc'}],
      size: AuthorDetailComponent.AUTHOR_BOOKS_PAGE_SIZE,
    };
  });
  private readonly authorBooksQuery = injectInfiniteQuery(() => ({
    ...this.bookQueryService.infinitePage(this.authorBooksQueryParams()),
    enabled: !!this.author()?.name,
  }));
  // The page renders every book by the author at once (virtualized, but not scroll-paged), so
  // drain pages automatically - the 100-per-page cap (application.yaml) keeps this to a single
  // request for the overwhelming majority of authors.
  private readonly drainAuthorBooksPagesEffect = effect(() => {
    if (this.authorBooksQuery.hasNextPage() && !this.authorBooksQuery.isFetchingNextPage()) {
      void this.authorBooksQuery.fetchNextPage();
    }
  });

  authorBooks = computed(() =>
    flattenBookPages(this.authorBooksQuery.data()).map(bookSummaryToBook)
  );

  get currentCardSize() {
    return this.coverScalePreferenceService.currentCardSize();
  }

  readonly virtualGrid = createVirtualGrid({
    items: this.authorBooks,
    scrollElement: this.scrollElement,
    minItemWidth: computed(() => this.currentCardSize.width),
    estimateItemHeight: () => this.currentCardSize.height,
    gap: AuthorDetailComponent.GRID_GAP,
  });

  get photoUrl(): string {
    const author = this.author();
    if (!author) return '';
    return this.authorService.getAuthorPhotoUrl(author.id) + '&t=' + this.photoTimestamp;
  }

  get canEditMetadata(): boolean {
    const user = this.userService.getCurrentUser();
    return !!user?.permissions?.admin || !!user?.permissions?.canEditMetadata;
  }

  ngOnInit(): void {
    const authorId = Number(this.route.snapshot.paramMap.get('authorId'));
    const tabParam = this.route.snapshot.queryParamMap.get('tab');
    if (tabParam) {
      this.tab = tabParam;
    }
    this.loadAuthor(authorId);
  }

  ngAfterViewChecked(): void {
    const descriptionContent = this.descriptionContentRef();
    this.updateDescriptionOverflow(descriptionContent?.nativeElement);
  }

  updateDescriptionOverflow(element?: Pick<HTMLElement, 'scrollHeight' | 'clientHeight'>): void {
    if (!this.isExpanded && element) {
      this.isOverflowing = element.scrollHeight > element.clientHeight;
    }
  }

  toggleExpand(): void {
    this.isExpanded = !this.isExpanded;
  }

  onPhotoError(): void {
    this.hasPhoto = false;
  }

  onAuthorUpdated(updatedAuthor: AuthorDetails): void {
    this.authorState.set(updatedAuthor);
    this.hasPhoto = true;
    this.photoTimestamp = Date.now();
    this.authorService.patchAuthorInCache(updatedAuthor.id, {
      name: updatedAuthor.name,
      asin: updatedAuthor.asin,
      hasPhoto: true,
    });
  }

  quickMatch(): void {
    const author = this.author();
    if (!author || this.quickMatching) return;
    this.quickMatching = true;
    this.authorService.quickMatchAuthor(author.id).subscribe({
      next: (matched) => {
        this.onAuthorUpdated(matched);
        this.quickMatching = false;
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('authorBrowser.toast.quickMatchSuccessSummary'),
          detail: this.t.translate('authorBrowser.toast.quickMatchSuccessDetail')
        });
      },
      error: () => {
        this.quickMatching = false;
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('authorBrowser.toast.quickMatchFailedSummary'),
          detail: this.t.translate('authorBrowser.toast.quickMatchFailedDetail')
        });
      }
    });
  }

  private loadAuthor(authorId: number): void {
    this.authorService.getAuthorDetails(authorId).subscribe({
      next: (author) => {
        this.authorState.set(author);
        this.loading.set(false);
        this.pageTitle.setPageTitle(author.name);
      },
      error: () => {
        this.loading.set(false);
      }
    });
  }
}
