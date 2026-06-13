import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import type { ECharts, EChartsCoreOption } from 'echarts/core';
import { EChartsComponent } from '../../shared/echarts-chart';
import type { TrialRow } from '../../stores/optimizations.store';

/**
 * Live sweep explorer (Phase 39, E-11 screen 4): ECharts trial scatter (objective vs trial #,
 * best-so-far step line, pruned greyed), parallel-coordinates (params coloured by objective; brush
 * filters the trial table), and a heatmap for 2-parameter grids. nsga2 renders a Pareto FRONT
 * scatter (never a single winner — guard 5). All update live as the parent reloads trials.
 */
@Component({
  selector: 'ay-sweep-explorer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EChartsComponent],
  styles: `
    .chart {
      height: 16rem;
      margin-bottom: 0.8rem;
    }
    h3 {
      font-size: 0.82rem;
      color: var(--ay-text-muted);
      margin: 0 0 0.3rem;
    }
  `,
  template: `
    @if (objectiveMetrics().length >= 2) {
      <h3>Pareto front ({{ objectiveMetrics()[0] }} vs {{ objectiveMetrics()[1] }})</h3>
      <div class="chart"><ay-echart [option]="paretoOption()" /></div>
    } @else {
      <h3>Trial objective ({{ metric() }})</h3>
      <div class="chart"><ay-echart [option]="scatterOption()" /></div>
    }
    @if (numericParams().length >= 2) {
      <h3>Parameter parallel-coordinates (brush to filter the table)</h3>
      <div class="chart">
        <ay-echart [option]="parallelOption()" (chartInit)="wireBrush($event)" />
      </div>
    }
    @if (numericParams().length === 2) {
      <h3>2-parameter heatmap</h3>
      <div class="chart"><ay-echart [option]="heatmapOption()" /></div>
    }
  `,
})
export class SweepExplorer {
  readonly trials = input.required<TrialRow[]>();
  readonly metric = input<string>('sharpe');
  readonly method = input<string>('tpe');
  /** Trial numbers selected by the parallel-coordinates brush ([] = no filter). */
  readonly selection = output<number[]>();

  private readonly complete = computed(() =>
    this.trials().filter((t) => t.state === 'COMPLETE' && t.objectiveValues),
  );

  protected readonly numericParams = computed(() => {
    const first = this.complete()[0];
    if (!first) {
      return [] as string[];
    }
    return Object.keys(first.params).filter((k) => typeof first.params[k] === 'number');
  });

  protected readonly objectiveMetrics = computed(() => {
    const first = this.complete()[0];
    return first?.objectiveValues ? Object.keys(first.objectiveValues) : [];
  });

  private obj(t: TrialRow): number {
    return Number(t.objectiveValues?.[this.metric()] ?? 0);
  }

  protected readonly scatterOption = computed<EChartsCoreOption>(() => {
    const all = this.trials();
    const done = all.filter((t) => t.state === 'COMPLETE' && t.objectiveValues);
    const pruned = all.filter((t) => t.state !== 'COMPLETE');
    // best-so-far running max over completed trials in trial order
    const ordered = [...done].sort((a, b) => a.trialNumber - b.trialNumber);
    let best = -Infinity;
    const bestLine = ordered.map((t) => {
      best = Math.max(best, this.obj(t));
      return [t.trialNumber, best];
    });
    return {
      tooltip: { trigger: 'item' },
      legend: { data: ['trials', 'pruned', 'best so far'] },
      grid: { left: 48, right: 16, top: 30, bottom: 24 },
      xAxis: { type: 'value', name: 'trial #' },
      yAxis: { type: 'value', name: this.metric() },
      series: [
        { name: 'trials', type: 'scatter', data: done.map((t) => [t.trialNumber, this.obj(t)]) },
        {
          name: 'pruned',
          type: 'scatter',
          itemStyle: { color: '#64748b', opacity: 0.4 },
          data: pruned.map((t) => [t.trialNumber, Number(t.objectiveValues?.[this.metric()] ?? 0)]),
        },
        { name: 'best so far', type: 'line', step: 'end', showSymbol: false, data: bestLine },
      ],
    };
  });

  protected readonly parallelOption = computed<EChartsCoreOption>(() => {
    const keys = this.numericParams();
    const done = this.complete();
    const objs = done.map((t) => this.obj(t));
    const min = Math.min(...objs, 0);
    const max = Math.max(...objs, 1);
    return {
      parallelAxis: [
        ...keys.map((k, i) => ({ dim: i, name: k })),
        { dim: keys.length, name: this.metric() },
      ],
      visualMap: {
        min,
        max,
        dimension: keys.length,
        inRange: { color: ['#64748b', '#38bdf8', '#22c55e'] },
        calculable: true,
        right: 0,
      },
      parallel: { left: 60, right: 80, top: 30, bottom: 24 },
      brush: { brushLink: 'all' },
      series: [
        {
          type: 'parallel',
          data: done.map((t) => [...keys.map((k) => Number(t.params[k])), this.obj(t)]),
        },
      ],
    };
  });

  protected readonly heatmapOption = computed<EChartsCoreOption>(() => {
    const [px, py] = this.numericParams();
    const done = this.complete();
    const xs = [...new Set(done.map((t) => Number(t.params[px])))].sort((a, b) => a - b);
    const ys = [...new Set(done.map((t) => Number(t.params[py])))].sort((a, b) => a - b);
    const data = done.map((t) => [
      xs.indexOf(Number(t.params[px])),
      ys.indexOf(Number(t.params[py])),
      this.obj(t),
    ]);
    const objs = done.map((t) => this.obj(t));
    return {
      tooltip: {},
      grid: { left: 60, right: 16, top: 16, bottom: 40 },
      xAxis: { type: 'category', data: xs.map(String), name: px },
      yAxis: { type: 'category', data: ys.map(String), name: py },
      visualMap: {
        min: Math.min(...objs, 0),
        max: Math.max(...objs, 1),
        calculable: true,
        orient: 'horizontal',
        left: 'center',
        bottom: 0,
      },
      series: [{ type: 'heatmap', data }],
    };
  });

  protected readonly paretoOption = computed<EChartsCoreOption>(() => {
    const [m1, m2] = this.objectiveMetrics();
    const done = this.complete();
    return {
      tooltip: { trigger: 'item' },
      grid: { left: 56, right: 16, top: 20, bottom: 36 },
      xAxis: { type: 'value', name: m1 },
      yAxis: { type: 'value', name: m2 },
      series: [
        {
          type: 'scatter',
          symbolSize: 10,
          data: done.map((t) => [
            Number(t.objectiveValues?.[m1] ?? 0),
            Number(t.objectiveValues?.[m2] ?? 0),
          ]),
        },
      ],
    };
  });

  /** Wire parallel-coordinates brushing → emit the selected trial numbers (empty = no filter). */
  protected wireBrush(chart: ECharts): void {
    const done = this.complete();
    chart.on('brushSelected', (params: unknown) => {
      const batch = (params as { batch?: { selected?: { dataIndex?: number[] }[] }[] }).batch ?? [];
      const indices = batch[0]?.selected?.[0]?.dataIndex ?? [];
      this.selection.emit(
        indices.map((i) => done[i]?.trialNumber).filter((n): n is number => n != null),
      );
    });
  }
}
