import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { EChartsCoreOption } from 'echarts/core';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { formatDecimal } from '../../core/decimal';
import { DegradationBadge } from '../../shared/degradation-badge';
import { EChartsComponent } from '../../shared/echarts-chart';
import type { FoldRow } from '../../stores/backtests.store';

/**
 * Walk-forward fold panel (E-12.4, BPC): a grouped train-vs-OOS bar chart (OOS-mean reference line)
 * above a fold table with date ranges, both metric sets, per-fold guard-7 degradation, and
 * regime-mix chips. `regimeMix` is nullable — the chips are omitted cleanly when it is null. Radar
 * charts are forbidden (BPC). Reused by the results page (Phase 38) and trial drill-down (Phase 39).
 */
@Component({
  selector: 'ay-fold-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TableModule, TagModule, DegradationBadge, EChartsComponent],
  styles: `
    .bars {
      height: 15rem;
    }
    .chips {
      display: flex;
      gap: 0.25rem;
      flex-wrap: wrap;
    }
    .mono {
      font-variant-numeric: tabular-nums;
    }
  `,
  template: `
    @if (folds().length) {
      <div class="bars">
        <ay-echart [option]="option()" />
      </div>
      <p-table [value]="rows()" dataKey="index">
        <ng-template #header>
          <tr>
            <th>Fold</th>
            <th>OOS window</th>
            <th>Train {{ metric() }}</th>
            <th>OOS {{ metric() }}</th>
            <th>Degradation</th>
            <th>Regime mix</th>
          </tr>
        </ng-template>
        <ng-template #body let-r>
          <tr>
            <td>F{{ r.index }}</td>
            <td>{{ r.window }}</td>
            <td class="mono">{{ r.train }}</td>
            <td class="mono">{{ r.oos }}</td>
            <td><ay-degradation-badge [value]="r.degradation" [trainSharpe]="r.trainRaw" /></td>
            <td>
              @if (r.regime.length) {
                <span class="chips">
                  @for (c of r.regime; track c) {
                    <p-tag [value]="c" severity="secondary" />
                  }
                </span>
              } @else {
                —
              }
            </td>
          </tr>
        </ng-template>
      </p-table>
    } @else {
      <p>No walk-forward folds for this run.</p>
    }
  `,
})
export class FoldPanel {
  readonly folds = input.required<FoldRow[]>();
  readonly metric = input<string>('sharpe');

  private val(m: Record<string, string | number | null>, key: string): number {
    const v = m[key];
    return v == null ? 0 : Number(v);
  }

  protected readonly rows = computed(() =>
    this.folds().map((f) => {
      const m = this.metric();
      const trainRaw = f.trainMetrics[m];
      const train = this.val(f.trainMetrics, m);
      const oos = this.val(f.oosMetrics, m);
      return {
        index: f.fold.index,
        window: `${f.fold.testFrom?.slice(0, 10)} → ${f.fold.testTo?.slice(0, 10)}`,
        train: formatDecimal(String(trainRaw ?? 0), 3),
        oos: formatDecimal(String(f.oosMetrics[m] ?? 0), 3),
        // degradation = train − OOS (a difference; guard-7 E-12.3)
        degradation: String(train - oos),
        trainRaw: trainRaw == null ? null : String(trainRaw),
        regime: f.regimeMix ? Object.entries(f.regimeMix).map(([k, v]) => `${k}: ${v}`) : [],
      };
    }),
  );

  protected readonly option = computed<EChartsCoreOption>(() => {
    const folds = this.folds();
    const m = this.metric();
    const x = folds.map((f) => `F${f.fold.index}`);
    const train = folds.map((f) => this.val(f.trainMetrics, m));
    const oos = folds.map((f) => this.val(f.oosMetrics, m));
    const oosMean = oos.length ? oos.reduce((a, b) => a + b, 0) / oos.length : 0;
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['train', 'OOS'] },
      grid: { left: 44, right: 16, top: 30, bottom: 24 },
      xAxis: { type: 'category', data: x },
      yAxis: { type: 'value', name: m },
      series: [
        { name: 'train', type: 'bar', data: train },
        {
          name: 'OOS',
          type: 'bar',
          data: oos,
          markLine: {
            symbol: 'none',
            data: [{ yAxis: oosMean, name: 'OOS mean' }],
          },
        },
      ],
    };
  });
}
