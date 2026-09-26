import {Component, computed, inject} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {Tooltip} from '@openng/optimus-ui/tooltip';
import {catchError, forkJoin, map, of} from 'rxjs';
import {LibraryStatsService, type LibraryAggregateBucket, type LibraryHistogramBucket, type LibrarySummary, type LibraryTimelineResponse} from '../../../library-stats/service/library-stats.service';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';

interface ReadingDNAProfile {
  adventurous: number;
  perfectionist: number;
  intellectual: number;
  emotional: number;
  patient: number;
  social: number;
  nostalgic: number;
  ambitious: number;
}

interface PersonalityInsight {
  trait: string;
  score: number;
  description: string;
  color: string;
}

// Primitives combined to reconstruct the eight-trait profile without a per-book payload.
interface DnaSignals {
  summary: LibrarySummary;
  categories: LibraryAggregateBucket[];
  languages: LibraryAggregateBucket[];
  readStatus: LibraryAggregateBucket[];
  personalRating: LibraryAggregateBucket[];
  pageCount: LibraryHistogramBucket[];
  publishedYear: LibraryTimelineResponse;
  series: LibraryAggregateBucket[];
  progressPercent: LibraryAggregateBucket[];
}

type ReadingDNAChartData = ChartData<'radar', number[], string>;

const INTELLECTUAL_GENRES = [
  'philosophy', 'history', 'biography', 'politics', 'psychology',
  'economics', 'mathematics', 'engineering', 'medicine', 'law',
  'education', 'sociology', 'nonfiction', 'non-fiction', 'academic'
];
const EMOTIONAL_GENRES = ['romance', 'memoir', 'poetry', 'drama', 'self-help', 'autobiography', 'literary fiction', 'coming of age'];
const MAINSTREAM_GENRES = ['thriller', 'mystery', 'crime', 'suspense', 'horror', 'fantasy', 'science fiction', 'adventure', 'true crime', 'humor', 'graphic novel', 'manga', 'comic'];
const CLASSIC_GENRES = ['classic', 'mythology', 'folklore', 'fairy tale', 'ancient', 'medieval', 'victorian', 'gothic'];

@Component({
  selector: 'app-reading-dna-chart',
  standalone: true,
  imports: [BaseChartDirective, Tooltip, TranslocoDirective],
  templateUrl: './reading-dna-chart.component.html',
  styleUrls: ['./reading-dna-chart.component.scss']
})
export class ReadingDNAChartComponent {
  private readonly libraryStatsService = inject(LibraryStatsService);
  private readonly t = inject(TranslocoService);
  private readonly signals = toSignal(
    forkJoin({
      summary: this.libraryStatsService.summary(null),
      categories: this.libraryStatsService.aggregate('categories', null),
      languages: this.libraryStatsService.aggregate('language', null),
      readStatus: this.libraryStatsService.aggregate('read_status', null),
      personalRating: this.libraryStatsService.aggregate('personal_rating', null),
      pageCount: this.libraryStatsService.histogram('page_count', null),
      publishedYear: this.libraryStatsService.timeline('published_date', 'year', null),
      series: this.libraryStatsService.aggregate('series', null),
      progressPercent: this.libraryStatsService.aggregate('progress_percent', null)
    }).pipe(map(s => s as DnaSignals), catchError(() => of(null))),
    {initialValue: null}
  );
  private readonly profile = computed(() => {
    const signals = this.signals();
    return signals && signals.summary.totalBooks > 0 ? this.analyzeReadingDNA(signals) : null;
  });

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
            const traitKeys = ['adventurous', 'perfectionist', 'intellectual', 'emotional', 'patient', 'social', 'nostalgic', 'ambitious'];
            const icons = ['🌟', '💎', '🧠', '💖', '🕰️', '👥', '📚', '🚀'];
            const translatedLabels = traitKeys.map(k => this.t.translate(`statsUser.readingDna.traits.${k}`));
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
        borderColor: '#e91e63',
        borderWidth: 2,
        cornerRadius: 8,
        padding: 16,
        titleFont: {size: 14, weight: 'bold'},
        bodyFont: {size: 12},
        callbacks: {
          title: (context) => {
            const label = context[0]?.label || '';
            return this.t.translate('statsUser.readingDna.tooltipPersonality', {label});
          },
          label: (context) => {
            const score = context.parsed.r;
            const insight = this.personalityInsights().find(i => i.trait === context.label);

            return [
              this.t.translate('statsUser.readingDna.tooltipScore', {score}),
              '',
              insight ? insight.description : this.t.translate('statsUser.readingDna.tooltipDefaultDescription')
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

  private readonly traitKeys = ['adventurous', 'perfectionist', 'intellectual', 'emotional', 'patient', 'social', 'nostalgic', 'ambitious'];
  public readonly chartData = computed<ReadingDNAChartData>(() => {
    const profile = this.profile();
    if (!profile) {
      return {labels: [], datasets: []};
    }

    const data = [
      profile.adventurous,
      profile.perfectionist,
      profile.intellectual,
      profile.emotional,
      profile.patient,
      profile.social,
      profile.nostalgic,
      profile.ambitious
    ];

    const gradientColors = [
      '#e91e63', '#2196f3', '#00bcd4', '#ff9800',
      '#9c27b0', '#3f51b5', '#673ab7', '#009688'
    ];

    const translatedLabels = this.traitKeys.map(k => this.t.translate(`statsUser.readingDna.traits.${k}`));

    return {
      labels: translatedLabels,
      datasets: [{
        label: this.t.translate('statsUser.readingDna.readingDnaProfile'),
        data,
        backgroundColor: 'rgba(233, 30, 99, 0.2)',
        borderColor: '#e91e63',
        borderWidth: 3,
        pointBackgroundColor: gradientColors,
        pointBorderWidth: 3,
        pointRadius: 5,
        pointHoverRadius: 8,
        fill: true
      }]
    };
  });
  public readonly personalityInsights = computed(() => {
    const profile = this.profile();
    return profile ? this.buildPersonalityInsights(profile) : [];
  });

  private analyzeReadingDNA(signals: DnaSignals): ReadingDNAProfile {
    return {
      adventurous: this.calculateAdventurousScore(signals),
      perfectionist: this.calculatePerfectionistScore(signals),
      intellectual: this.calculateIntellectualScore(signals),
      emotional: this.calculateEmotionalScore(signals),
      patient: this.calculatePatienceScore(signals),
      social: this.calculateSocialScore(signals),
      nostalgic: this.calculateNostalgicScore(signals),
      ambitious: this.calculateAmbitiousScore(signals)
    };
  }

  private bucketFraction(buckets: LibraryAggregateBucket[], total: number, matches: (value: string) => boolean): number {
    if (total === 0) return 0;
    const matched = buckets.filter(b => matches(b.value)).reduce((sum, b) => sum + b.count, 0);
    return matched / total;
  }

  private genreMatches(keywords: string[]): (value: string) => boolean {
    return value => keywords.some(k => value.toLowerCase().includes(k));
  }

  // Genre diversity + language variety - both read directly off the aggregate bucket counts.
  private calculateAdventurousScore(s: DnaSignals): number {
    const diversityRatio = s.categories.length / Math.max(1, s.summary.totalBooks * 0.4);
    const genreScore = Math.min(75, diversityRatio * 75);
    const languageScore = Math.min(25, Math.max(0, s.languages.length - 1) * 12.5);
    return Math.min(100, Math.round(genreScore + languageScore));
  }

  // Completion rate + high personal ratings, both from aggregate buckets.
  private calculatePerfectionistScore(s: DnaSignals): number {
    const completionRate = this.bucketFraction(s.readStatus, s.summary.totalBooks, v => v === 'READ');
    const ratedTotal = s.personalRating.reduce((sum, b) => sum + b.count, 0);
    const highRatedCount = s.personalRating.filter(b => Number(b.value) >= 4).reduce((sum, b) => sum + b.count, 0);
    const highRatingRate = ratedTotal > 0 ? highRatedCount / ratedTotal : 0;
    return Math.min(100, Math.round(completionRate * 60 + highRatingRate * 40));
  }

  // Non-fiction/academic genre proportion + long books, via category and page-count buckets.
  private calculateIntellectualScore(s: DnaSignals): number {
    const intellectualRate = this.bucketFraction(s.categories, s.summary.totalBooks, this.genreMatches(INTELLECTUAL_GENRES));
    const longBookRate = this.longBookFraction(s.pageCount, s.summary.totalBooks, 400);
    return Math.min(100, Math.round(intellectualRate * 70 + longBookRate * 30));
  }

  // Emotionally-driven genre proportion + rating engagement.
  private calculateEmotionalScore(s: DnaSignals): number {
    const emotionalRate = this.bucketFraction(s.categories, s.summary.totalBooks, this.genreMatches(EMOTIONAL_GENRES));
    const ratedTotal = s.personalRating.reduce((sum, b) => sum + b.count, 0);
    const ratingEngagement = s.summary.totalBooks > 0 ? ratedTotal / s.summary.totalBooks : 0;
    return Math.min(100, Math.round(emotionalRate * 70 + ratingEngagement * 30));
  }

  // Long books + series membership + deep progress, via histogram/category/progress buckets.
  private calculatePatienceScore(s: DnaSignals): number {
    const longBookRate = this.longBookFraction(s.pageCount, s.summary.totalBooks, 500);
    const seriesRate = this.bucketFraction(s.series, s.summary.totalBooks, () => true);
    const progressRate = this.bucketFraction(s.progressPercent, s.summary.totalBooks, v => Number(v) >= 50);
    return Math.min(100, Math.round(longBookRate * 40 + seriesRate * 35 + progressRate * 25));
  }

  // Popular/mainstream genre proportion (review-count popularity is not exposed by any endpoint).
  private calculateSocialScore(s: DnaSignals): number {
    const mainstreamRate = this.bucketFraction(s.categories, s.summary.totalBooks, this.genreMatches(MAINSTREAM_GENRES));
    return Math.min(100, Math.round(mainstreamRate * 100));
  }

  // Old publication dates + classic genre proportion, via the published_date timeline.
  private calculateNostalgicScore(s: DnaSignals): number {
    const currentYear = new Date().getFullYear();
    const classicThreshold = currentYear - 30;
    const totalWithYear = s.publishedYear.buckets.reduce((sum, b) => sum + b.count, 0);
    const oldCount = s.publishedYear.buckets
      .filter(b => Number(b.period) < classicThreshold)
      .reduce((sum, b) => sum + b.count, 0);
    const oldBookRate = totalWithYear > 0 ? oldCount / totalWithYear : 0;
    const classicRate = this.bucketFraction(s.categories, s.summary.totalBooks, this.genreMatches(CLASSIC_GENRES));
    return Math.min(100, Math.round(oldBookRate * 60 + classicRate * 40));
  }

  // Library volume + challenging (600+ page) book proportion (completion-of-challenging is not
  // separately exposed, so the completion term folds into the overall READ rate instead).
  private calculateAmbitiousScore(s: DnaSignals): number {
    const volumeScore = Math.min(40, s.summary.totalBooks * 0.4);
    const challengingRate = this.longBookFraction(s.pageCount, s.summary.totalBooks, 600);
    const completionRate = this.bucketFraction(s.readStatus, s.summary.totalBooks, v => v === 'READ');
    return Math.min(100, Math.round(volumeScore + challengingRate * 35 + completionRate * 25));
  }

  // Sums histogram buckets whose lower edge is >= threshold as a proxy for "pages > threshold".
  private longBookFraction(buckets: LibraryHistogramBucket[], total: number, threshold: number): number {
    if (total === 0) return 0;
    const matched = buckets.filter(b => b.min >= threshold).reduce((sum, b) => sum + b.count, 0);
    return matched / total;
  }

  private getTraitDescription(traitKey: string, score: number): string {
    const level = score < 33 ? 'low' : score < 67 ? 'mid' : 'high';
    return this.t.translate(`statsUser.readingDna.descriptions.${traitKey}.${level}`);
  }

  private buildPersonalityInsights(profile: ReadingDNAProfile): PersonalityInsight[] {
    const traitColors = ['#e91e63', '#2196f3', '#00bcd4', '#ff9800', '#9c27b0', '#3f51b5', '#673ab7', '#009688'];

    return this.traitKeys.map((key, i) => ({
      trait: this.t.translate(`statsUser.readingDna.traits.${key}`),
      score: profile[key as keyof ReadingDNAProfile],
      description: this.getTraitDescription(key, profile[key as keyof ReadingDNAProfile]),
      color: traitColors[i]
    }));
  }
}
