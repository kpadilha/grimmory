import {Component, computed, inject} from '@angular/core';
import {toObservable, toSignal} from '@angular/core/rxjs-interop';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {catchError, of, switchMap} from 'rxjs';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService} from '../../service/library-stats.service';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';

interface PageRange {
  label: string;
  min: number;
  max: number;
  color: string;
}

interface PageStats {
  range: string;
  count: number;
  color: string;
}

type PageChartData = ChartData<'bar', number[], string>;

const PAGE_RANGES: PageRange[] = [
  {label: '0-100', min: 0, max: 100, color: '#06B6D4'},
  {label: '101-200', min: 101, max: 200, color: '#0EA5E9'},
  {label: '201-300', min: 201, max: 300, color: '#3B82F6'},
  {label: '301-500', min: 301, max: 500, color: '#6366F1'},
  {label: '501-750', min: 501, max: 750, color: '#8B5CF6'},
  {label: '751-1000', min: 751, max: 1000, color: '#A855F7'},
  {label: '1000+', min: 1001, max: Infinity, color: '#D946EF'}
];

@Component({
  selector: 'app-page-count-chart',
  standalone: true,
  imports: [BaseChartDirective, TranslocoDirective],
  templateUrl: './page-count-chart.component.html',
  styleUrls: ['./page-count-chart.component.scss']
})
export class PageCountChartComponent {
  private readonly libraryStatsService = inject(LibraryStatsService);
  private readonly libraryFilterService = inject(LibraryFilterService);
  private readonly t = inject(TranslocoService);
  private readonly buckets = toSignal(
    toObservable(this.libraryFilterService.selectedLibrary).pipe(
      switchMap(libraryId => this.libraryStatsService.histogram('page_count', libraryId).pipe(catchError(() => of([]))))
    ),
    {initialValue: []}
  );

  public readonly chartType = 'bar' as const;
  public readonly totalBooks = computed(() => this.buckets().reduce((sum, b) => sum + b.count, 0));

  public readonly chartOptions: ChartConfiguration<'bar'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    layout: {
      padding: {top: 10, bottom: 10}
    },
    plugins: {
      legend: {display: false},
      tooltip: {
        enabled: true,
        borderColor: '#8B5CF6',
        borderWidth: 2,
        cornerRadius: 8,
        padding: 12,
        titleFont: {size: 13, weight: 'bold'},
        bodyFont: {size: 11},
        callbacks: {
          title: (context) => this.t.translate('statsLibrary.pageCount.tooltipTitle', {label: context[0].label}),
          label: (context) => {
            const value = context.parsed.y;
            return value === 1
              ? this.t.translate('statsLibrary.pageCount.tooltipLabel', {value})
              : this.t.translate('statsLibrary.pageCount.tooltipLabelPlural', {value});
          }
        }
      }
    },
    scales: {
      x: {
        title: {
          display: true,
          text: this.t.translate('statsLibrary.pageCount.axisPageCount'),
          font: {
            family: "'Inter', sans-serif",
            size: 11
          }
        },
        ticks: {
          font: {
            family: "'Inter', sans-serif",
            size: 10
          }
        },
        grid: {display: false},
        border: {display: false}
      },
      y: {
        title: {
          display: true,
          text: this.t.translate('statsLibrary.pageCount.axisBooks'),
          font: {
            family: "'Inter', sans-serif",
            size: 11
          }
        },
        beginAtZero: true,
        ticks: {
          font: {
            family: "'Inter', sans-serif",
            size: 10
          },
          stepSize: 1,
          maxTicksLimit: 6
        },
        grid: {
        },
        border: {display: false}
      }
    }
  };

  public readonly chartData = computed<PageChartData>(() => {
    if (this.totalBooks() === 0) {
      return {labels: [], datasets: []};
    }

    const stats = this.calculatePageStats();
    const labels = stats.map(s => s.range);
    const data = stats.map(s => s.count);
    const colors = stats.map(s => s.color);

    return {
      labels,
      datasets: [{
        data,
        backgroundColor: colors,
        borderWidth: 1,
        borderRadius: 4,
        barPercentage: 0.8,
        categoryPercentage: 0.7
      }]
    };
  });

  private calculatePageStats(): PageStats[] {
    const byLabel = new Map(this.buckets().map(b => [b.range, b.count]));
    return PAGE_RANGES.map(range => ({
      range: range.label,
      count: byLabel.get(range.label) ?? 0,
      color: range.color
    }));
  }
}
