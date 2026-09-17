import {Component, computed, inject} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {Tooltip} from '@openng/optimus-ui/tooltip';
import {catchError, forkJoin, map, of} from 'rxjs';
import {LibraryStatsService, type LibraryAggregateBucket, type LibraryAuthorStat, type LibraryHistogramBucket, type LibrarySummary, type LibraryTimelineResponse} from '../../../library-stats/service/library-stats.service';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';

interface ReadingHabitsProfile {
  consistency: number;
  multitasking: number;
  completionism: number;
  exploration: number;
  organization: number;
  intensity: number;
  methodology: number;
  momentum: number;
}

interface HabitInsight {
  habit: string;
  score: number;
  description: string;
  color: string;
}

// Primitives combined to reconstruct the eight-habit profile without a per-book payload.
interface HabitSignals {
  summary: LibrarySummary;
  categories: LibraryAggregateBucket[];
  languages: LibraryAggregateBucket[];
  readStatus: LibraryAggregateBucket[];
  personalRating: LibraryAggregateBucket[];
  progressPercent: LibraryAggregateBucket[];
  pageCount: LibraryHistogramBucket[];
  publishedYear: LibraryTimelineResponse;
  finishedByMonth: LibraryTimelineResponse;
  authors: LibraryAuthorStat[];
  series: LibraryAggregateBucket[];
}

type ReadingHabitsChartData = ChartData<'radar', number[], string>;

@Component({
  selector: 'app-reading-habits-chart',
  standalone: true,
  imports: [BaseChartDirective, Tooltip, TranslocoDirective],
  templateUrl: './reading-habits-chart.component.html',
  styleUrls: ['./reading-habits-chart.component.scss']
})
export class ReadingHabitsChartComponent {
  private readonly libraryStatsService = inject(LibraryStatsService);
  private readonly t = inject(TranslocoService);
  private readonly signals = toSignal(
    forkJoin({
      summary: this.libraryStatsService.summary(null),
      categories: this.libraryStatsService.aggregate('categories', null),
      languages: this.libraryStatsService.aggregate('language', null),
      readStatus: this.libraryStatsService.aggregate('read_status', null),
      personalRating: this.libraryStatsService.aggregate('personal_rating', null),
      progressPercent: this.libraryStatsService.aggregate('progress_percent', null),
      pageCount: this.libraryStatsService.histogram('page_count', null),
      publishedYear: this.libraryStatsService.timeline('published_date', 'year', null),
      finishedByMonth: this.libraryStatsService.timeline('date_finished', 'month', null),
      authors: this.libraryStatsService.authors(200, null),
      series: this.libraryStatsService.aggregate('series', null)
    }).pipe(map(s => s as HabitSignals), catchError(() => of(null))),
    {initialValue: null}
  );
  private readonly profile = computed(() => {
    const signals = this.signals();
    return signals && signals.summary.totalBooks > 0 ? this.analyzeReadingHabits(signals) : null;
  });

  private readonly habitKeys = ['consistency', 'multitasking', 'completionism', 'exploration', 'organization', 'intensity', 'methodology', 'momentum'];

  public readonly chartType = 'radar' as const;

  public readonly chartOptions: ChartConfiguration<'radar'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    layout: {
      padding: {top: 15}
    },
    scales: {
      r: {
        beginAtZero: true,
        min: 0,
        max: 100,
        ticks: {
          stepSize: 20,
          font: {
            family: "'Inter', sans-serif",
            size: 12
          },
          backdropColor: 'transparent',
          showLabelBackdrop: false
        },
        grid: {
          circular: true
        },
        angleLines: {
        },
        pointLabels: {
          font: {
            family: "'Inter', sans-serif",
            size: 12
          },
          padding: 25,
          callback: (label: string) => {
            const icons = ['📅', '📚', '✅', '🔍', '📋', '⚡', '🎯', '🔥'];
            const translatedLabels = this.habitKeys.map(k => this.t.translate(`statsUser.readingHabits.habits.${k}`));
            const idx = translatedLabels.indexOf(label);
            return [idx >= 0 ? icons[idx] : '', label];
          }
        }
      }
    },
    plugins: {
      legend: {
        display: false
      },
      tooltip: {
        enabled: true,
        borderColor: '#9c27b0',
        borderWidth: 2,
        cornerRadius: 8,
        padding: 16,
        titleFont: {size: 14, weight: 'bold'},
        bodyFont: {size: 12},
        callbacks: {
          title: (context) => {
            const label = context[0]?.label || '';
            return this.t.translate('statsUser.readingHabits.tooltipHabit', {label});
          },
          label: (context) => {
            const score = context.parsed.r;
            const insight = this.habitInsights().find(i => i.habit === context.label);

            return [
              this.t.translate('statsUser.readingHabits.tooltipScore', {score}),
              '',
              insight ? insight.description : this.t.translate('statsUser.readingHabits.tooltipDefaultDescription')
            ];
          }
        }
      }
    },
    interaction: {
      intersect: false,
      mode: 'point'
    },
    elements: {
      line: {
        borderWidth: 3,
        tension: 0.1
      },
      point: {
        radius: 5,
        hoverRadius: 8,
        borderWidth: 3
      }
    }
  };
  public readonly chartData = computed<ReadingHabitsChartData>(() => {
    const profile = this.profile();
    if (!profile) {
      return {labels: [], datasets: []};
    }

    const data = [
      profile.consistency,
      profile.multitasking,
      profile.completionism,
      profile.exploration,
      profile.organization,
      profile.intensity,
      profile.methodology,
      profile.momentum
    ];

    const habitColors = [
      '#9c27b0', '#e91e63', '#ff5722', '#ff9800',
      '#ffc107', '#4caf50', '#2196f3', '#673ab7'
    ];

    const translatedLabels = this.habitKeys.map(k => this.t.translate(`statsUser.readingHabits.habits.${k}`));

    return {
      labels: translatedLabels,
      datasets: [{
        label: this.t.translate('statsUser.readingHabits.readingHabitsProfile'),
        data,
        backgroundColor: 'rgba(156, 39, 176, 0.2)',
        borderColor: '#9c27b0',
        borderWidth: 3,
        pointBackgroundColor: habitColors,
        pointBorderWidth: 3,
        pointRadius: 5,
        pointHoverRadius: 8,
        fill: true
      }]
    };
  });
  public readonly habitInsights = computed(() => {
    const profile = this.profile();
    return profile ? this.buildHabitInsights(profile) : [];
  });

  private analyzeReadingHabits(s: HabitSignals): ReadingHabitsProfile {
    return {
      consistency: this.calculateConsistencyScore(s),
      multitasking: this.calculateMultitaskingScore(s),
      completionism: this.calculateCompletionismScore(s),
      exploration: this.calculateExplorationScore(s),
      organization: this.calculateOrganizationScore(s),
      intensity: this.calculateIntensityScore(s),
      methodology: this.calculateMethodologyScore(s),
      momentum: this.calculateMomentumScore(s)
    };
  }

  private statusCount(readStatus: LibraryAggregateBucket[], ...statuses: string[]): number {
    return readStatus.filter(b => statuses.includes(b.value)).reduce((sum, b) => sum + b.count, 0);
  }

  // ponytail: regularity from the coefficient of variation across MONTHLY finish counts, not
  // exact per-book gap days - the timeline primitive only exposes monthly granularity.
  private calculateConsistencyScore(s: HabitSignals): number {
    const counts = s.finishedByMonth.buckets.map(b => b.count).filter(c => c > 0);
    const completedCount = this.statusCount(s.readStatus, 'READ');
    if (counts.length < 3) {
      return Math.min(20, completedCount * 10);
    }

    const meanCount = counts.reduce((a, b) => a + b, 0) / counts.length;
    if (meanCount === 0) return 50;
    const variance = counts.reduce((sum, c) => sum + Math.pow(c - meanCount, 2), 0) / counts.length;
    const cv = Math.sqrt(variance) / meanCount;

    const regularityScore = Math.max(0, Math.min(70, (1 - cv / 2) * 70));
    const volumeBonus = Math.min(30, completedCount * 1.5);
    return Math.min(100, Math.round(regularityScore + volumeBonus));
  }

  // ponytail: "partial-progress, not currently reading" needs a progress x read-status crosstab
  // that isn't allow-listed - the partial component here is the progress band alone.
  private calculateMultitaskingScore(s: HabitSignals): number {
    const activeBooks = this.statusCount(s.readStatus, 'READING', 'RE_READING');
    const activeScore = Math.min(75, activeBooks <= 1 ? activeBooks * 10 : 10 + (activeBooks - 1) * 20);

    const midProgress = s.progressPercent
      .filter(b => ['10', '25', '50', '75'].includes(b.value))
      .reduce((sum, b) => sum + b.count, 0);
    const partialBooks = Math.max(0, midProgress - activeBooks);
    const partialScore = Math.min(25, partialBooks * 5);

    return Math.min(100, Math.round(activeScore + partialScore));
  }

  private calculateCompletionismScore(s: HabitSignals): number {
    const started = this.statusCount(s.readStatus, 'READ', 'ABANDONED', 'READING', 'RE_READING');
    if (started === 0) return 0;

    const completed = this.statusCount(s.readStatus, 'READ');
    const abandoned = this.statusCount(s.readStatus, 'ABANDONED');
    const completionRate = completed / started;
    const abandonmentRate = abandoned / started;

    return Math.min(100, Math.round(completionRate * 75 + (1 - abandonmentRate) * 25));
  }

  private calculateExplorationScore(s: HabitSignals): number {
    const authorRatio = s.authors.length / Math.max(1, s.summary.totalBooks);
    const diversityScore = Math.min(60, authorRatio * 60);

    const years = s.publishedYear.buckets.map(b => Number(b.period));
    let temporalScore = 0;
    if (years.length >= 2) {
      const yearSpread = Math.max(...years) - Math.min(...years);
      temporalScore = Math.min(25, yearSpread * 0.5);
    }

    const languageScore = Math.min(15, Math.max(0, s.languages.length - 1) * 7.5);
    return Math.min(100, Math.round(diversityScore + temporalScore + languageScore));
  }

  // ponytail: "rated among completed" needs a rating x read-status crosstab that isn't
  // allow-listed - rating discipline uses the overall rated fraction instead.
  private calculateOrganizationScore(s: HabitSignals): number {
    const ratedTotal = s.personalRating.reduce((sum, b) => sum + b.count, 0);
    const ratingRate = s.summary.totalBooks > 0 ? ratedTotal / s.summary.totalBooks : 0;
    const ratingScore = ratingRate * 40;

    const unsetCount = this.statusCount(s.readStatus, 'UNSET');
    const statusRate = s.summary.totalBooks > 0 ? (s.summary.totalBooks - unsetCount) / s.summary.totalBooks : 0;
    const statusScore = statusRate * 35;

    const seriesScore = 25; // no per-book series-number completeness signal exposed server-side
    return Math.min(100, Math.round(ratingScore + statusScore + seriesScore));
  }

  private calculateIntensityScore(s: HabitSignals): number {
    const totalWithPages = s.pageCount.reduce((sum, b) => sum + b.count, 0);
    if (totalWithPages === 0) return 0;

    const weightedSum = s.pageCount.reduce((sum, b) => sum + b.count * ((b.min + b.max) / 2), 0);
    const avgPages = weightedSum / totalWithPages;
    const lengthScore = Math.min(60, avgPages / 10);

    const deepReaders = s.progressPercent.filter(b => ['75', '90', '100'].includes(b.value)).reduce((sum, b) => sum + b.count, 0);
    const progressScore = Math.min(40, (deepReaders / s.summary.totalBooks) * 40);
    return Math.min(100, Math.round(lengthScore + progressScore));
  }

  // ponytail: true series-order discipline needs per-book series-number/finish-date pairs -
  // approximated here by how many multi-book series exist at all.
  private calculateMethodologyScore(s: HabitSignals): number {
    const multiBookSeries = s.series.filter(b => b.count >= 2).length;
    const orderScore = s.series.length > 0 ? Math.min(50, (multiBookSeries / s.series.length) * 50) : 25;

    const deepDiveAuthors = s.authors.filter(a => a.bookCount >= 3).length;
    const authorDepthScore = Math.min(30, deepDiveAuthors * 10);

    const focusedGenres = s.categories.filter(c => c.count >= 5).length;
    const genreDepthScore = Math.min(20, focusedGenres * 5);

    return Math.min(100, Math.round(orderScore + authorDepthScore + genreDepthScore));
  }

  private calculateMomentumScore(s: HabitSignals): number {
    const now = new Date();
    const sixMonthsAgo = new Date(now.getFullYear(), now.getMonth() - 6, 1);
    const sixMonthsKey = `${sixMonthsAgo.getFullYear()}-${String(sixMonthsAgo.getMonth() + 1).padStart(2, '0')}`;

    const recentCompletions = s.finishedByMonth.buckets
      .filter(b => b.period >= sixMonthsKey)
      .reduce((sum, b) => sum + b.count, 0);
    const recentScore = Math.min(45, recentCompletions * 7.5);

    const activeBooks = this.statusCount(s.readStatus, 'READING', 'RE_READING');
    const activeScore = Math.min(30, activeBooks * 10);

    const almostDone = s.progressPercent.filter(b => ['75', '90'].includes(b.value)).reduce((sum, b) => sum + b.count, 0);
    const progressScore = Math.min(25, almostDone * 8);

    return Math.min(100, Math.round(recentScore + activeScore + progressScore));
  }

  private getHabitDescription(habitKey: string, score: number): string {
    const level = score < 33 ? 'low' : score < 67 ? 'mid' : 'high';
    return this.t.translate(`statsUser.readingHabits.descriptions.${habitKey}.${level}`);
  }

  private buildHabitInsights(profile: ReadingHabitsProfile): HabitInsight[] {
    const habitColors = ['#9c27b0', '#e91e63', '#ff5722', '#ff9800', '#ffc107', '#4caf50', '#2196f3', '#673ab7'];

    return this.habitKeys.map((key, i) => ({
      habit: this.t.translate(`statsUser.readingHabits.habits.${key}`),
      score: profile[key as keyof ReadingHabitsProfile],
      description: this.getHabitDescription(key, profile[key as keyof ReadingHabitsProfile]),
      color: habitColors[i]
    }));
  }
}
