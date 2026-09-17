import {Component, computed, inject} from '@angular/core';
import {toObservable, toSignal} from '@angular/core/rxjs-interop';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {catchError, of, switchMap} from 'rxjs';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService} from '../../service/library-stats.service';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';

interface ScoreStats {
  range: string;
  count: number;
  percentage: number;
  color: string;
}

type ScoreChartData = ChartData<'doughnut', number[], string>;
type ScoreRangeKey = 'excellent' | 'good' | 'fair' | 'poor' | 'veryPoor';

const SCORE_RANGE_DEFS: { key: ScoreRangeKey; min: number; max: number; color: string }[] = [
  {key: 'excellent', min: 90, max: 100, color: '#16A34A'},
  {key: 'good', min: 70, max: 89, color: '#22C55E'},
  {key: 'fair', min: 50, max: 69, color: '#F59E0B'},
  {key: 'poor', min: 25, max: 49, color: '#F97316'},
  {key: 'veryPoor', min: 0, max: 24, color: '#DC2626'}
];

@Component({
  selector: 'app-metadata-score-chart',
  standalone: true,
  imports: [BaseChartDirective, TranslocoDirective],
  templateUrl: './metadata-score-chart.component.html',
  styleUrls: ['./metadata-score-chart.component.scss']
})
export class MetadataScoreChartComponent {
  private readonly libraryStatsService = inject(LibraryStatsService);
  private readonly libraryFilterService = inject(LibraryFilterService);
  private readonly t = inject(TranslocoService);
  private readonly buckets = toSignal(
    toObservable(this.libraryFilterService.selectedLibrary).pipe(
      switchMap(libraryId => this.libraryStatsService.histogram('metadata_score', libraryId).pipe(catchError(() => of([]))))
    ),
    {initialValue: []}
  );

  public readonly chartType = 'doughnut' as const;
  public readonly scoreStats = computed(() => this.calculateScoreStats());
  public readonly totalBooks = computed(() => this.scoreStats().reduce((sum, s) => sum + s.count, 0));
  public readonly averageScore = computed(() => this.calculateAverageScore());

  public readonly chartOptions: ChartConfiguration<'doughnut'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    cutout: '60%',
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
            size: 11
          },
          usePointStyle: true,
          pointStyle: 'circle',
          padding: 12,
          boxWidth: 8
        }
      },
      tooltip: {
        enabled: true,
        borderColor: '#16A34A',
        borderWidth: 2,
        cornerRadius: 8,
        padding: 12,
        titleFont: {size: 13, weight: 'bold'},
        bodyFont: {size: 11},
        callbacks: {
          label: (context) => {
            const value = context.parsed;
            const total = context.dataset.data.reduce((a: number, b: number) => a + b, 0);
            const percentage = ((value / total) * 100).toFixed(1);
            return this.t.translate('statsLibrary.metadataScore.tooltipLabel', {value, percentage});
          }
        }
      }
    }
  };

  public readonly chartData = computed<ScoreChartData>(() => {
    const stats = this.scoreStats();
    if (stats.length === 0) {
      return {labels: [], datasets: []};
    }

    const labels = stats.map(s => s.range);
    const data = stats.map(s => s.count);
    const colors = stats.map(s => s.color);

    return {
      labels,
      datasets: [{
        data,
        backgroundColor: colors
      }]
    };
  });

  private calculateScoreStats(): ScoreStats[] {
    const byKey = new Map(this.buckets().map(b => [b.range, b.count]));
    const total = this.buckets().reduce((sum, b) => sum + b.count, 0);
    if (total === 0) {
      return [];
    }

    return SCORE_RANGE_DEFS
      .map(range => {
        const count = byKey.get(range.key) ?? 0;
        return {
          range: this.t.translate(`statsLibrary.metadataScore.${range.key}`),
          count,
          percentage: (count / total) * 100,
          color: range.color
        };
      })
      .filter(stat => stat.count > 0);
  }

  // ponytail: bucket-midpoint approximation (histogram gives counts per range, not raw scores);
  // add a dedicated backend average if exact precision ever matters here.
  private calculateAverageScore(): number {
    const buckets = this.buckets();
    const total = buckets.reduce((sum, b) => sum + b.count, 0);
    if (total === 0) {
      return 0;
    }
    const weightedSum = buckets.reduce((sum, b) => sum + b.count * ((b.min + b.max) / 2), 0);
    return Math.round(weightedSum / total);
  }
}
