import {Component, DestroyRef, inject, OnInit} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {BaseChartDirective} from 'ng2-charts';
import {Tooltip} from '@openng/optimus-ui/tooltip';
import {BehaviorSubject, EMPTY, Observable} from 'rxjs';
import {catchError} from 'rxjs/operators';
import {ChartConfiguration, ChartData} from 'chart.js';
import {BookCompletionHeatmapResponse, UserStatsService} from '../../../../../settings/user-management/user-stats.service';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {AsyncPipe} from '@angular/common';
import {readStatsChartThemeColors} from '../../../shared/stats-chart-theme.service';

interface MatrixDataPoint {
  x: number; // month (0-11)
  y: number; // year index
  v: number; // book count
}

interface YearMonthData {
  year: number;
  month: number;
  count: number;
}

interface YScaleWithMax {
  max?: number;
}

const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

type HeatmapChartData = ChartData<'matrix', MatrixDataPoint[], string>;

@Component({
  selector: 'app-reading-heatmap-chart',
  standalone: true,
  imports: [
    AsyncPipe,BaseChartDirective, Tooltip, TranslocoDirective],
  templateUrl: './reading-heatmap-chart.component.html',
  styleUrls: ['./reading-heatmap-chart.component.scss']
})
export class ReadingHeatmapChartComponent implements OnInit {
  private readonly userStatsService = inject(UserStatsService);
  private readonly t = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  ngOnInit(): void {
    this.userStatsService.getBookCompletionHeatmap()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((error) => {
          console.error('Error loading book completion heatmap:', error);
          return EMPTY;
        })
      )
      .subscribe((data) => this.updateChartData(this.toYearMonthData(data)));
  }

  public readonly chartType = 'matrix' as const;

  private yearLabels: string[] = [];
  private maxBookCount = 1;

  public readonly chartOptions: ChartConfiguration['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    layout: {
      padding: {
        top: 20
      }
    },
    plugins: {
      legend: {display: false},
      tooltip: {
        enabled: true,
        borderColor: '#ef476f',
        borderWidth: 2,
        cornerRadius: 8,
        displayColors: false,
        padding: 16,
        titleFont: {size: 14, weight: 'bold'},
        bodyFont: {size: 13},
        callbacks: {
          title: (context) => {
            const point = context[0].raw as MatrixDataPoint;
            const year = this.yearLabels[point.y];
            const month = MONTH_NAMES[point.x];
            return `${month} ${year}`;
          },
          label: (context) => {
            const point = context.raw as MatrixDataPoint;
            const key = point.v === 1 ? 'statsUser.readingHeatmap.tooltipBook' : 'statsUser.readingHeatmap.tooltipBooks';
            return this.t.translate(key, {value: point.v});
          }
        }
      }
    },
    scales: {
      x: {
        type: 'linear',
        position: 'bottom',
        ticks: {
          stepSize: 1,
          callback: (value) => MONTH_NAMES[value as number] || '',
          font: {
            family: "'Inter', sans-serif",
            size: 11
          }
        },
        grid: {display: false},
      },
      y: {
        type: 'linear',
        offset: true,
        ticks: {
          stepSize: 1,
          callback: (value) => this.yearLabels[value as number] || '',
          font: {
            family: "'Inter', sans-serif",
            size: 11
          }
        },
        grid: {display: false},
      }
    }
  };

  private readonly chartDataSubject = new BehaviorSubject<HeatmapChartData>({
    labels: [],
    datasets: [{
      label: this.t.translate('statsUser.readingHeatmap.booksRead'),
      data: []
    }]
  });

  public readonly chartData$: Observable<HeatmapChartData> = this.chartDataSubject.asObservable();

  private updateChartData(yearMonthData: YearMonthData[]): void {
    const currentYear = new Date().getFullYear();
    const years = Array.from({length: 10}, (_, i) => currentYear - 9 + i);

    this.yearLabels = years.map(String);
    this.maxBookCount = Math.max(1, ...yearMonthData.map(d => d.count));

    const heatmapData: MatrixDataPoint[] = [];

    years.forEach((year, yearIndex) => {
      for (let month = 0; month <= 11; month++) {
        const dataPoint = yearMonthData.find(d => d.year === year && d.month === month + 1);
        heatmapData.push({
          x: month,
          y: yearIndex,
          v: dataPoint?.count || 0
        });
      }
    });

    if (this.chartOptions?.scales?.['y']) {
      (this.chartOptions.scales['y'] as YScaleWithMax).max = years.length - 1;
    }

    this.chartDataSubject.next({
      labels: [],
      datasets: [{
        label: this.t.translate('statsUser.readingHeatmap.booksRead'),
        data: heatmapData,
        backgroundColor: (context) => {
          const point = context.raw as MatrixDataPoint;
          if (!point?.v) return readStatsChartThemeColors().grid;

          const intensity = point.v / this.maxBookCount;
          const alpha = Math.max(0.2, Math.min(1.0, intensity * 0.8 + 0.2));
          return `rgba(239, 71, 111, ${alpha})`;
        },
        borderWidth: 1,
        width: ({chart}) => (chart.chartArea?.width || 0) / 12 - 1,
        height: ({chart}) => (chart.chartArea?.height || 0) / years.length - 1
      }]
    });
  }

  private toYearMonthData(data: BookCompletionHeatmapResponse[]): YearMonthData[] {
    return data.map(d => ({year: d.year, month: d.month, count: d.count}));
  }
}
