import {Component, computed, inject} from '@angular/core';
import {toObservable, toSignal} from '@angular/core/rxjs-interop';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {catchError, of, switchMap} from 'rxjs';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService} from '../../service/library-stats.service';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {LanguageResolverService} from '../../../../../../shared/service/language-resolver.service';

interface LanguageStats {
  language: string;
  displayName: string;
  count: number;
  percentage: number;
}

type LanguageChartData = ChartData<'pie', number[], string>;

// Professional color palette for languages
const LANGUAGE_COLORS = [
  '#2563EB', // Blue
  '#0D9488', // Teal
  '#7C3AED', // Violet
  '#DC2626', // Red
  '#F59E0B', // Amber
  '#16A34A', // Green
  '#EC4899', // Pink
  '#8B5CF6', // Purple
  '#06B6D4', // Cyan
  '#EA580C', // Orange
  '#6366F1', // Indigo
  '#14B8A6', // Teal-500
  '#F43F5E', // Rose
  '#84CC16', // Lime
  '#A855F7'  // Purple-500
] as const;

@Component({
  selector: 'app-language-chart',
  standalone: true,
  imports: [BaseChartDirective, TranslocoDirective],
  templateUrl: './language-chart.component.html',
  styleUrls: ['./language-chart.component.scss']
})
export class LanguageChartComponent {
  private readonly libraryStatsService = inject(LibraryStatsService);
  private readonly libraryFilterService = inject(LibraryFilterService);
  private readonly t = inject(TranslocoService);
  private readonly languageResolver = inject(LanguageResolverService);
  private readonly buckets = toSignal(
    toObservable(this.libraryFilterService.selectedLibrary).pipe(
      switchMap(libraryId => this.libraryStatsService.aggregate('language', libraryId).pipe(catchError(() => of([]))))
    ),
    {initialValue: []}
  );

  public readonly chartType = 'pie' as const;
  public readonly languageStats = computed(() => this.calculateLanguageStats());
  public readonly totalBooks = computed(() => this.buckets().reduce((sum, b) => sum + b.count, 0));
  public readonly booksWithLanguage = computed(() => this.languageStats().reduce((sum, s) => sum + s.count, 0));

  public readonly chartOptions: ChartConfiguration<'pie'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    layout: {
      padding: {top: 10, bottom: 10}
    },
    plugins: {
      legend: {
        display: true,
        position: 'right',
        labels: {
          font: {
            family: "'Inter', sans-serif",
            size: 12
          },
          usePointStyle: true,
          pointStyle: 'circle',
          padding: 15
        }
      },
      tooltip: {
        enabled: true,
        borderColor: '#2563EB',
        borderWidth: 2,
        cornerRadius: 8,
        padding: 12,
        titleFont: {size: 14, weight: 'bold'},
        bodyFont: {size: 12},
        callbacks: {
          label: (context) => {
            const value = context.parsed;
            const total = context.dataset.data.reduce((a: number, b: number) => a + b, 0);
            const percentage = ((value / total) * 100).toFixed(1);
            return this.t.translate('statsLibrary.language.tooltipLabel', {label: context.label, value, percentage});
          }
        }
      }
    }
  };

  public readonly chartData = computed<LanguageChartData>(() => {
    const stats = this.languageStats();
    if (stats.length === 0) {
      return {labels: [], datasets: []};
    }

    const labels = stats.map(s => s.displayName);
    const data = stats.map(s => s.count);
    const colors = stats.map((_, index) => LANGUAGE_COLORS[index % LANGUAGE_COLORS.length]);

    return {
      labels,
      datasets: [{
        data,
        backgroundColor: colors
      }]
    };
  });

  // The server groups by raw metadata.language; re-merge equivalent tags (e.g. "en"/"eng"/"English")
  // client-side the same way the pre-migration per-book loop did, via the resolver's tag.
  private calculateLanguageStats(): LanguageStats[] {
    const languageCounts = new Map<string, number>();

    this.buckets().forEach(bucket => {
      const language = bucket.value?.trim();
      if (language) {
        const resolved = this.languageResolver.resolve(language);
        const normalizedKey = resolved?.tag ?? language.toLowerCase();
        languageCounts.set(normalizedKey, (languageCounts.get(normalizedKey) || 0) + bucket.count);
      }
    });

    const total = Array.from(languageCounts.values()).reduce((a, b) => a + b, 0);

    return Array.from(languageCounts.entries())
      .map(([language, count]) => ({
        language,
        displayName: this.languageResolver.displayName(language),
        count,
        percentage: (count / total) * 100
      }))
      .sort((a, b) => b.count - a.count)
      .slice(0, 15); // Show top 15 languages
  }
}
