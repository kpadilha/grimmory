import {Component, computed, inject} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {BaseChartDirective} from 'ng2-charts';
import {Tooltip} from '@openng/optimus-ui/tooltip';
import {ChartConfiguration, ChartData} from 'chart.js';
import {UserStatsService} from '../../../../../settings/user-management/user-stats.service';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {catchError, of} from 'rxjs';

interface ReadingProgressStats {
  progressRange: string;
  count: number;
  description: string;
}

const CHART_COLORS = [
  '#6c757d', // Gray - Not Started (0%)
  '#ffc107', // Yellow - Just Started (1-25%)
  '#fd7e14', // Orange - Getting Into It (26-50%)
  '#17a2b8', // Cyan - Halfway Through (51-75%)
  '#6f42c1', // Purple - Almost Finished (76-99%)
  '#28a745'  // Green - Completed (100%)
] as const;

const PROGRESS_RANGES = [
  {range: '0%', min: 0, max: 0, desc: 'Not Started'},
  {range: '1-25%', min: 0.1, max: 25, desc: 'Just Started'},
  {range: '26-50%', min: 26, max: 50, desc: 'Getting Into It'},
  {range: '51-75%', min: 51, max: 75, desc: 'Halfway Through'},
  {range: '76-99%', min: 76, max: 99, desc: 'Almost Finished'},
  {range: '100%', min: 100, max: 100, desc: 'Completed'}
] as const;

type ProgressChartData = ChartData<'doughnut', number[], string>;

// Reproduces the fixed axis labels ('0%', '1-25%', ...) from the server bucket's min/max.
function formatProgressRange(min: number, max: number): string {
  if (min === max) return `${min}%`;
  return `${min}-${max}%`;
}

@Component({
  selector: 'app-reading-progress-chart',
  standalone: true,
  imports: [BaseChartDirective, Tooltip, TranslocoDirective],
  templateUrl: './reading-progress-chart.component.html',
  styleUrls: ['./reading-progress-chart.component.scss']
})
export class ReadingProgressChartComponent {
  private readonly userStatsService = inject(UserStatsService);
  private readonly t = inject(TranslocoService);
  private readonly distributions = toSignal(
    this.userStatsService.getBookDistributions().pipe(catchError(() => of(undefined))),
    {initialValue: undefined}
  );
  private readonly progressStats = computed<ReadingProgressStats[]>(() => {
    const data = this.distributions();
    if (!data) {
      return [];
    }

    // Server buckets arrive in the same fixed order as PROGRESS_RANGES.
    return data.progressDistribution.map((bucket, i) => ({
      progressRange: formatProgressRange(bucket.min, bucket.max),
      count: bucket.count,
      description: PROGRESS_RANGES[i]?.desc ?? bucket.range
    }));
  });

  public readonly chartType = 'doughnut' as const;

  public readonly chartOptions: ChartConfiguration<'doughnut'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    layout: {
      padding: {top: 15}
    },
    plugins: {
      legend: {
        display: true,
        position: 'bottom',
        labels: {
          font: {
            family: "'Inter', sans-serif",
            size: 12
          },
          padding: 15,
          usePointStyle: true,
          pointStyle: 'circle',
          generateLabels: (chart) => {
            const data = chart.data;
            if (data.labels?.length && data.datasets?.length) {
              return data.labels.map((label, i) => {
                const value = data.datasets[0].data[i] as number;
                const isVisible = typeof chart.getDataVisibility === 'function'
                  ? chart.getDataVisibility(i)
                  : true;

                return {
                  text: `${label}: ${value}`,
                  fillStyle: (data.datasets[0].backgroundColor as string[])[i],
                  lineWidth: 1,
                  hidden: !isVisible,
                  index: i,
                };
              });
            }
            return [];
          }
        }
      },
      tooltip: {
        enabled: true,
        borderWidth: 1,
        cornerRadius: 6,
        displayColors: true,
        padding: 12,
        titleFont: {size: 14, weight: 'bold'},
        bodyFont: {size: 13},
        callbacks: {
          title: (context) => context[0].label,
          label: (context) => {
            const value = context.parsed;
            const total = (context.dataset.data as number[]).reduce((a, b) => a + b, 0);
            const percentage = ((value / total) * 100).toFixed(1);
            const label = context.label;
            const rangeInfo = PROGRESS_RANGES.find(r => r.range === label);
            const descKeys: Record<string, string> = {
              'Not Started': 'notStarted', 'Just Started': 'justStarted',
              'Getting Into It': 'gettingIntoIt', 'Halfway Through': 'halfwayThrough',
              'Almost Finished': 'almostFinished', 'Completed': 'completed'
            };
            const description = rangeInfo ? this.t.translate(`statsUser.readingProgress.${descKeys[rangeInfo.desc] || 'notStarted'}`) : '';
            const plural = value === 1 ? '' : 's';
            return this.t.translate('statsUser.readingProgress.tooltipLabel', {value, plural, description, percentage});
          }
        }
      }
    },
    interaction: {
      intersect: false,
      mode: 'point'
    }
  };

  public readonly chartData = computed<ProgressChartData>(() => {
    try {
      const stats = this.progressStats();
      const labels = stats.map(s => s.progressRange);
      const dataValues = stats.map(s => s.count);

      return {
        labels,
        datasets: [{
          label: this.t.translate('statsUser.readingProgress.booksByProgress'),
          data: dataValues,
          backgroundColor: [...CHART_COLORS],
        }]
      };
    } catch (error) {
      console.error('Error updating reading progress chart data:', error);
      return {
        labels: [],
        datasets: [{
          label: this.t.translate('statsUser.readingProgress.booksByProgress'),
          data: [],
          backgroundColor: [...CHART_COLORS]
        }]
      };
    }
  });

}
