import {ChangeDetectionStrategy, Component, computed, DestroyRef, EventEmitter, inject, Input, OnChanges, OnInit, Output, signal, SimpleChanges} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {forkJoin} from 'rxjs';
import {FormsModule} from '@angular/forms';
import {Button} from '@openng/optimus-ui/button';
import {Select} from '@openng/optimus-ui/select';
import {MessageService} from '@openng/optimus-ui/api';
import {Tooltip} from '@openng/optimus-ui/tooltip';
import {
  AGE_RATING_OPTIONS,
  CONTENT_RATINGS,
  ContentRestriction,
  ContentRestrictionMode,
  ContentRestrictionType
} from '../content-restriction.model';
import {ContentRestrictionService} from '../content-restriction.service';
import {MetadataValuesService} from '../../../metadata/component/metadata-manager/metadata-values.service';
import {TranslocoDirective, TranslocoPipe, TranslocoService} from '@jsverse/transloco';

// Enumerates the picker's own vocabulary, not a typeahead - large enough for a Select dropdown
// without repeating the exhaustive /facets/values load a boot-time query used to make.
const AVAILABLE_VALUES_LIMIT = 200;

@Component({
  selector: 'app-content-restrictions-editor',
  standalone: true,
  imports: [
    FormsModule,
    Button,
    Select,
    Tooltip,
    TranslocoDirective,
    TranslocoPipe
  ],
  templateUrl: './content-restrictions-editor.component.html',
  styleUrls: ['./content-restrictions-editor.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ContentRestrictionsEditorComponent implements OnInit, OnChanges {
  @Input() userId!: number;
  @Input() isEditing = false;
  @Output() restrictionsChanged = new EventEmitter<ContentRestriction[]>();

  private contentRestrictionService = inject(ContentRestrictionService);
  private metadataValuesService = inject(MetadataValuesService);
  private messageService = inject(MessageService);
  private t = inject(TranslocoService);
  private destroyRef = inject(DestroyRef);

  // Loaded once this editor mounts (never on app boot) via the capped typeahead endpoint -
  // not the exhaustive value/book-id list a boot-time query used to fetch for every user.
  private readonly availableValues = signal({categories: [] as string[], tags: [] as string[], moods: [] as string[]});

  readonly restrictions = signal<ContentRestriction[]>([]);
  private readonly excludeRestrictions = computed(() => this.restrictions().filter(r => r.mode === ContentRestrictionMode.EXCLUDE));
  private readonly allowOnlyRestrictions = computed(() => this.restrictions().filter(r => r.mode === ContentRestrictionMode.ALLOW_ONLY));

  get availableCategories(): string[] { return this.availableValues().categories; }
  get availableTags(): string[] { return this.availableValues().tags; }
  get availableMoods(): string[] { return this.availableValues().moods; }

  newRestriction: Partial<ContentRestriction> = {
    restrictionType: ContentRestrictionType.CATEGORY,
    mode: ContentRestrictionMode.EXCLUDE,
    value: ''
  };

  restrictionTypes = [
    {label: 'Category/Genre', value: ContentRestrictionType.CATEGORY, translationKey: 'settingsUsers.contentRestrictions.types.category'},
    {label: 'Tag', value: ContentRestrictionType.TAG, translationKey: 'settingsUsers.contentRestrictions.types.tag'},
    {label: 'Mood', value: ContentRestrictionType.MOOD, translationKey: 'settingsUsers.contentRestrictions.types.mood'},
    {label: 'Age Rating', value: ContentRestrictionType.AGE_RATING, translationKey: 'settingsUsers.contentRestrictions.types.ageRating'},
    {label: 'Content Rating', value: ContentRestrictionType.CONTENT_RATING, translationKey: 'settingsUsers.contentRestrictions.types.contentRating'}
  ];

  restrictionModes = [
    {label: 'Exclude (Hide matching)', value: ContentRestrictionMode.EXCLUDE, translationKey: 'settingsUsers.contentRestrictions.modes.exclude'},
    {label: 'Allow Only (Show only matching)', value: ContentRestrictionMode.ALLOW_ONLY, translationKey: 'settingsUsers.contentRestrictions.modes.allowOnly'}
  ];

  ageRatingOptions = AGE_RATING_OPTIONS;
  contentRatingOptions = CONTENT_RATINGS.map(r => ({label: r, value: r}));

  ngOnInit() {
    this.loadRestrictions();
    this.loadAvailableValues();
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['userId'] && !changes['userId'].firstChange) {
      this.loadRestrictions();
    }
  }

  private loadAvailableValues() {
    forkJoin({
      categories: this.metadataValuesService.search('genre', '', AVAILABLE_VALUES_LIMIT),
      tags: this.metadataValuesService.search('tag', '', AVAILABLE_VALUES_LIMIT),
      moods: this.metadataValuesService.search('mood', '', AVAILABLE_VALUES_LIMIT),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(values => this.availableValues.set(values));
  }

  loadRestrictions() {
    if (!this.userId) return;
    const requestedUserId = this.userId;

    this.contentRestrictionService.getUserRestrictions(requestedUserId).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: (restrictions) => {
        if (this.userId !== requestedUserId) return;
        this.restrictions.set(restrictions);
        this.restrictionsChanged.emit(restrictions);
      },
      error: () => {
        if (this.userId !== requestedUserId) return;
        const restrictions: ContentRestriction[] = [];
        this.restrictions.set(restrictions);
        this.restrictionsChanged.emit(restrictions);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: this.t.translate('settingsUsers.contentRestrictions.loadError')
        });
      }
    });
  }

  getValueOptions(): {label: string, value: string}[] {
    switch (this.newRestriction.restrictionType) {
      case ContentRestrictionType.CATEGORY:
        return this.availableCategories.map(c => ({label: c, value: c}));
      case ContentRestrictionType.TAG:
        return this.availableTags.map(t => ({label: t, value: t}));
      case ContentRestrictionType.MOOD:
        return this.availableMoods.map(m => ({label: m, value: m}));
      case ContentRestrictionType.AGE_RATING:
        return this.ageRatingOptions.map(o => ({label: o.label, value: o.value}));
      case ContentRestrictionType.CONTENT_RATING:
        return this.contentRatingOptions;
      default:
        return [];
    }
  }

  addRestriction() {
    if (!this.newRestriction.value || !this.newRestriction.restrictionType || !this.newRestriction.mode) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('common.error'),
        detail: this.t.translate('settingsUsers.contentRestrictions.selectAllFields')
      });
      return;
    }

    const exists = this.restrictions().some(r =>
      r.restrictionType === this.newRestriction.restrictionType &&
      r.value === this.newRestriction.value
    );

    if (exists) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('common.error'),
        detail: this.t.translate('settingsUsers.contentRestrictions.alreadyExists')
      });
      return;
    }

    const restriction: ContentRestriction = {
      userId: this.userId,
      restrictionType: this.newRestriction.restrictionType!,
      mode: this.newRestriction.mode!,
      value: this.newRestriction.value!
    };
    const requestedUserId = this.userId;

    this.contentRestrictionService.addRestriction(requestedUserId, restriction).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: (added) => {
        if (this.userId !== requestedUserId) return;
        const restrictions = [...this.restrictions(), added];
        this.restrictions.set(restrictions);
        this.restrictionsChanged.emit(restrictions);
        this.newRestriction.value = '';
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('settingsUsers.contentRestrictions.addSuccess')
        });
      },
      error: () => {
        if (this.userId !== requestedUserId) return;
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: this.t.translate('settingsUsers.contentRestrictions.addError')
        });
      }
    });
  }

  removeRestriction(restriction: ContentRestriction) {
    if (!restriction.id) return;
    const requestedUserId = this.userId;

    this.contentRestrictionService.deleteRestriction(requestedUserId, restriction.id).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: () => {
        if (this.userId !== requestedUserId) return;
        const restrictions = this.restrictions().filter(r => r.id !== restriction.id);
        this.restrictions.set(restrictions);
        this.restrictionsChanged.emit(restrictions);
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('settingsUsers.contentRestrictions.removeSuccess')
        });
      },
      error: () => {
        if (this.userId !== requestedUserId) return;
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: this.t.translate('settingsUsers.contentRestrictions.removeError')
        });
      }
    });
  }

  getRestrictionTypeLabel(type: ContentRestrictionType): string {
    const found = this.restrictionTypes.find(t => t.value === type);
    return found ? this.t.translate(found.translationKey) : type;
  }

  getModeLabel(mode: ContentRestrictionMode): string {
    return mode === ContentRestrictionMode.EXCLUDE
      ? this.t.translate('settingsUsers.contentRestrictions.modes.exclude')
      : this.t.translate('settingsUsers.contentRestrictions.modes.allowOnly');
  }

  getModeClass(mode: ContentRestrictionMode): string {
    return mode === ContentRestrictionMode.EXCLUDE ? 'mode-exclude' : 'mode-allow';
  }

  getExcludeRestrictions(): ContentRestriction[] {
    return this.excludeRestrictions();
  }

  getAllowOnlyRestrictions(): ContentRestriction[] {
    return this.allowOnlyRestrictions();
  }
}
