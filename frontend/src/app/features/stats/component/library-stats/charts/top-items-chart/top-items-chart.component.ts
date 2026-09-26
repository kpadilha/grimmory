import {Component, DestroyRef, inject, Input, OnInit} from '@angular/core';
import {takeUntilDestroyed, toObservable} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData, TooltipItem} from 'chart.js';
import {BehaviorSubject, catchError, EMPTY, Observable, switchMap} from 'rxjs';
import {Select} from '@openng/optimus-ui/select';
import {LibraryFilterService} from '../../service/library-filter.service';
import {LibraryStatsService, type LibraryAggregateBucket} from '../../service/library-stats.service';
import {ReadStatus} from '../../../../../book/model/book.model';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {NgClass, AsyncPipe} from '@angular/common';

interface ItemStats {
  name: string;
  count: number;
  statusBreakdown: Record<ReadStatus, number>;
}

interface DataTypeOption {
  label: string;
  value: DataType;
  icon: string;
  color: string;
}

type DataType = 'authors' | 'categories' | 'publishers' | 'tags' | 'moods' | 'series';
type ItemChartData = ChartData<'bar', number[], string>;

const DATA_TYPE_DEFS: { key: string; value: DataType; icon: string; color: string }[] = [
  {key: 'authors', value: 'authors', icon: 'pi-th-large', color: '#2563EB'},
  {key: 'categories', value: 'categories', icon: 'pi-user', color: '#0D9488'},
  {key: 'series', value: 'series', icon: 'pi-tag', color: '#DB2777'},
  {key: 'publishers', value: 'publishers', icon: 'pi-building', color: '#7C3AED'},
  {key: 'tags', value: 'tags', icon: 'pi-bookmark', color: '#EAB308'},
  {key: 'moods', value: 'moods', icon: 'pi-heart', color: '#EA580C'}
];

// The aggregate endpoint's field names are singular for publisher; every other DataType matches.
const AGGREGATE_FIELD_BY_TYPE: Record<DataType, string> = {
  authors: 'authors',
  categories: 'categories',
  series: 'series',
  publishers: 'publisher',
  tags: 'tags',
  moods: 'moods'
};

const READ_STATUS_KEYS: Record<ReadStatus, string> = {
  [ReadStatus.READ]: 'read',
  [ReadStatus.READING]: 'reading',
  [ReadStatus.RE_READING]: 'reReading',
  [ReadStatus.UNREAD]: 'unread',
  [ReadStatus.PARTIALLY_READ]: 'partiallyRead',
  [ReadStatus.PAUSED]: 'paused',
  [ReadStatus.WONT_READ]: 'wontRead',
  [ReadStatus.ABANDONED]: 'abandoned',
  [ReadStatus.UNSET]: 'notSet'
};

const READ_STATUS_COLORS: Record<ReadStatus, string> = {
  [ReadStatus.READ]: '#22c55e',
  [ReadStatus.READING]: '#3b82f6',
  [ReadStatus.RE_READING]: '#8b5cf6',
  [ReadStatus.UNREAD]: '#6b7280',
  [ReadStatus.PARTIALLY_READ]: '#f59e0b',
  [ReadStatus.PAUSED]: '#eab308',
  [ReadStatus.WONT_READ]: '#ef4444',
  [ReadStatus.ABANDONED]: '#dc2626',
  [ReadStatus.UNSET]: '#9ca3af'
};

const READ_STATUS_ORDER: ReadStatus[] = [
  ReadStatus.READ,
  ReadStatus.READING,
  ReadStatus.RE_READING,
  ReadStatus.PARTIALLY_READ,
  ReadStatus.PAUSED,
  ReadStatus.UNREAD,
  ReadStatus.WONT_READ,
  ReadStatus.ABANDONED,
  ReadStatus.UNSET
];

@Component({
  selector: 'app-top-items-chart',
  standalone: true,
  imports: [
    AsyncPipe,
    NgClass,FormsModule, BaseChartDirective, Select, TranslocoDirective],
  templateUrl: './top-items-chart.component.html',
  styleUrls: ['./top-items-chart.component.scss']
})
export class TopItemsChartComponent implements OnInit {
  @Input() initialDataType: DataType | null = null;

  public readonly chartType = 'bar' as const;
  public readonly chartData$: Observable<ItemChartData>;
  public chartOptions: ChartConfiguration<'bar'>['options'];
  public dataTypeOptions: DataTypeOption[];
  public selectedDataType: DataTypeOption;

  public totalItems = 0;
  public totalBooks = 0;
  public insights: { icon: string; label: string; value: string }[] = [];

  private readonly libraryStatsService = inject(LibraryStatsService);
  private readonly libraryFilterService = inject(LibraryFilterService);
  private readonly t = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dataTypeSubject: BehaviorSubject<DataType>;
  private readonly chartDataSubject: BehaviorSubject<ItemChartData>;
  private lastCalculatedStats: ItemStats[] = [];
  // ponytail: proxy denominator for the "top 5 coverage" insight - the true filtered-library book
  // count would need a separate summary() call; occurrence totals inflate for multi-valued fields
  // (authors/categories/tags/moods) where one book contributes more than once.
  private allItemOccurrences = 0;

  constructor() {
    this.dataTypeOptions = DATA_TYPE_DEFS.map(def => ({
      label: this.t.translate(`statsLibrary.topItems.dataTypes.${def.key}`),
      value: def.value,
      icon: def.icon,
      color: def.color
    }));
    this.selectedDataType = this.dataTypeOptions[0];
    this.dataTypeSubject = new BehaviorSubject<DataType>(this.selectedDataType.value);
    this.chartDataSubject = new BehaviorSubject<ItemChartData>({
      labels: [],
      datasets: []
    });
    this.chartData$ = this.chartDataSubject.asObservable();
    this.initChartOptions();

    toObservable(this.libraryFilterService.selectedLibrary)
      .pipe(
        switchMap(libraryId => this.dataTypeSubject.pipe(
          switchMap(dataType => this.libraryStatsService
            .aggregate(AGGREGATE_FIELD_BY_TYPE[dataType], libraryId, 'read_status')
            .pipe(catchError(() => EMPTY)))
        )),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(buckets => this.processData(buckets));
  }

  ngOnInit(): void {
    if (this.initialDataType) {
      const initialOption = this.dataTypeOptions.find(opt => opt.value === this.initialDataType);
      if (initialOption) {
        this.selectedDataType = initialOption;
        this.initChartOptions();
        this.dataTypeSubject.next(initialOption.value);
      }
    }
  }

  onDataTypeChange(): void {
    this.initChartOptions();
    this.dataTypeSubject.next(this.selectedDataType.value);
  }

  private initChartOptions(): void {
    const currentColor = this.selectedDataType.color;
    this.chartOptions = {
      responsive: true,
      maintainAspectRatio: false,
      indexAxis: 'y',
      layout: {
        padding: {top: 10, right: 20, bottom: 10, left: 10}
      },
      scales: {
        x: {
          stacked: true,
          beginAtZero: true,
          ticks: {
            font: {
              family: "'Inter', sans-serif",
              size: 11
            },
            precision: 0,
            stepSize: 1
          },
          grid: {
          },
          border: {display: false},
          title: {
            display: true,
            text: this.t.translate('statsLibrary.topItems.axisNumberOfBooks'),
            font: {
              family: "'Inter', sans-serif",
              size: 12,
              weight: 500
            }
          }
        },
        y: {
          stacked: true,
          ticks: {
            font: {
              family: "'Inter', sans-serif",
              size: 11
            },
            maxTicksLimit: 25
          },
          grid: {
            display: false
          },
          border: {display: false}
        }
      },
      plugins: {
        legend: {
          display: true,
          position: 'bottom',
          labels: {
            font: {
              family: "'Inter', sans-serif",
              size: 11
            },
            padding: 15,
            usePointStyle: true,
            pointStyle: 'rectRounded'
          }
        },
        tooltip: {
          enabled: true,
          borderColor: currentColor,
          borderWidth: 2,
          cornerRadius: 8,
          displayColors: true,
          padding: 12,
          titleFont: {size: 14, weight: 'bold'},
          bodyFont: {size: 12},
          callbacks: {
            title: (context) => {
              const dataIndex = context[0].dataIndex;
              return this.lastCalculatedStats[dataIndex]?.name || 'Unknown';
            },
            label: this.formatTooltipLabel.bind(this)
          }
        }
      },
      interaction: {
        intersect: true,
        mode: 'nearest',
        axis: 'y'
      }
    };
  }

  private processData(buckets: LibraryAggregateBucket[]): void {
    this.allItemOccurrences = buckets.reduce((sum, b) => sum + b.count, 0);
    const stats = this.calculateStats(buckets);
    this.updateChartData(stats);
  }

  private updateChartData(stats: ItemStats[]): void {
    try {
      this.lastCalculatedStats = stats;
      this.totalItems = stats.length;
      this.totalBooks = stats.reduce((sum, s) => sum + s.count, 0);

      const labels = stats.map(s => this.truncateTitle(s.name, 30));

      const datasets = READ_STATUS_ORDER
        .filter(status => stats.some(s => s.statusBreakdown[status] > 0))
        .map(status => {
          const statusKey = READ_STATUS_KEYS[status];
          const color = READ_STATUS_COLORS[status];
          return {
            label: this.t.translate(`statsLibrary.topItems.readStatus.${statusKey}`),
            data: stats.map(s => s.statusBreakdown[status]),
            backgroundColor: color,
            borderColor: color,
            borderWidth: 1,
            borderRadius: 4,
            barPercentage: 0.85,
            categoryPercentage: 0.8,
            hoverBorderWidth: 2,
          };
        });

      this.chartDataSubject.next({
        labels,
        datasets
      });

      this.generateInsights(stats);
    } catch (error) {
      console.error('Error updating items chart data:', error);
    }
  }

  private generateInsights(stats: ItemStats[]): void {
    this.insights = [];
    if (stats.length === 0) return;

    const typeName = this.selectedDataType.label.toLowerCase().slice(0, -1); // Remove 's' for singular

    // 1. Top item
    const top = stats[0];
    this.insights.push({
      icon: 'pi-trophy',
      label: this.t.translate('statsLibrary.topItems.insightTop', {type: typeName}),
      value: this.t.translate('statsLibrary.topItems.insightTopValue', {name: top.name, count: top.count})
    });

    // 2. Most completed - highest read percentage
    const withReads = stats.filter(s => s.count >= 2);
    if (withReads.length > 0) {
      const mostRead = withReads.reduce((best, curr) => {
        const bestPct = best.statusBreakdown[ReadStatus.READ] / best.count;
        const currPct = curr.statusBreakdown[ReadStatus.READ] / curr.count;
        return currPct > bestPct ? curr : best;
      });
      const readPct = Math.round((mostRead.statusBreakdown[ReadStatus.READ] / mostRead.count) * 100);
      if (readPct > 0) {
        this.insights.push({
          icon: 'pi-check-circle',
          label: this.t.translate('statsLibrary.topItems.insightMostCompleted'),
          value: this.t.translate('statsLibrary.topItems.insightMostCompletedValue', {name: mostRead.name, percent: readPct})
        });
      }
    }

    // 3. Top 5 concentration
    if (stats.length >= 5) {
      const top5Books = stats.slice(0, 5).reduce((sum, s) => sum + s.count, 0);
      if (this.allItemOccurrences > 0) {
        const concentration = Math.round((top5Books / this.allItemOccurrences) * 100);
        this.insights.push({
          icon: 'pi-chart-pie',
          label: this.t.translate('statsLibrary.topItems.insightTop5Coverage'),
          value: this.t.translate('statsLibrary.topItems.insightTop5CoverageValue', {percent: concentration})
        });
      }
    }

    // 4. Average books per item
    if (stats.length > 0) {
      const avgBooks = (this.totalBooks / stats.length).toFixed(1);
      this.insights.push({
        icon: 'pi-book',
        label: this.t.translate('statsLibrary.topItems.insightAvgPer', {type: typeName}),
        value: this.t.translate('statsLibrary.topItems.insightAvgPerValue', {avg: avgBooks})
      });
    }
  }

  private calculateStats(buckets: LibraryAggregateBucket[]): ItemStats[] {
    return buckets
      .map(bucket => ({
        name: bucket.value,
        count: bucket.count,
        statusBreakdown: this.toStatusBreakdown(bucket.breakdown ?? [])
      }))
      .sort((a, b) => b.count - a.count)
      .slice(0, 15);
  }

  private toStatusBreakdown(breakdown: LibraryAggregateBucket[]): Record<ReadStatus, number> {
    const empty = this.createEmptyStatusBreakdown();
    for (const entry of breakdown) {
      const status = Object.values(ReadStatus).includes(entry.value as ReadStatus) ? (entry.value as ReadStatus) : ReadStatus.UNSET;
      empty[status] += entry.count;
    }
    return empty;
  }

  private createEmptyStatusBreakdown(): Record<ReadStatus, number> {
    return {
      [ReadStatus.READ]: 0,
      [ReadStatus.READING]: 0,
      [ReadStatus.RE_READING]: 0,
      [ReadStatus.UNREAD]: 0,
      [ReadStatus.PARTIALLY_READ]: 0,
      [ReadStatus.PAUSED]: 0,
      [ReadStatus.WONT_READ]: 0,
      [ReadStatus.ABANDONED]: 0,
      [ReadStatus.UNSET]: 0
    };
  }

  private formatTooltipLabel(context: TooltipItem<'bar'>): string {
    const value = context.parsed.x;
    if (value === 0) {
      return '';
    }

    const statusLabel = context.dataset.label || this.t.translate('statsLibrary.pageCount.axisBooks');
    return value === 1
      ? this.t.translate('statsLibrary.topItems.tooltipBook', {status: statusLabel, value})
      : this.t.translate('statsLibrary.topItems.tooltipBooks', {status: statusLabel, value});
  }

  private truncateTitle(title: string, maxLength: number): string {
    return title.length > maxLength ? title.substring(0, maxLength) + '...' : title;
  }
}
