import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import type { EChartsCoreOption } from 'echarts/core';
import { MessageModule } from 'primeng/message';
import { TableModule } from 'primeng/table';
import { formatDecimal } from '../../core/decimal';
import { BacktestsStore } from '../../stores/backtests.store';
import { EChartsComponent } from '../../shared/echarts-chart';

interface Row {
  key: string;
  label: string;
  higherBetter: boolean;
  suffix?: string;
  dp?: number;
}

const ROWS: Row[] = [
  { key: 'totalReturn', label: 'Total return', higherBetter: true, suffix: '%' },
  { key: 'cagr', label: 'CAGR', higherBetter: true, suffix: '%' },
  { key: 'sharpe', label: 'Sharpe', higherBetter: true },
  { key: 'sortino', label: 'Sortino', higherBetter: true },
  { key: 'maxDrawdown', label: 'Max drawdown', higherBetter: false, suffix: '%' },
  { key: 'winRate', label: 'Win rate', higherBetter: true, suffix: '%', dp: 1 },
  { key: 'profitFactor', label: 'Profit factor', higherBetter: true },
  { key: 'alpha', label: 'Alpha', higherBetter: true, dp: 4 },
  { key: 'beta', label: 'Beta', higherBetter: true, dp: 3 },
  { key: 'informationRatio', label: 'Information ratio', higherBetter: true, dp: 3 },
];

/**
 * /backtests/compare (Phase 38, E-11 screen 5): up to 6 runs — metric matrix (best per row
 * highlighted, incl. benchmark-relative columns), overlaid normalized equity curves, and a
 * `dataHash` mismatch banner when the runs are not like-for-like. Run ids come via `?ids=a,b,c`
 * (leaderboard rows / strategy history link here). Trade-distribution histograms are deferred
 * (need per-run trade fetches; noted in PHASE_GATES).
 */
@Component({
  selector: 'ay-compare-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TableModule, MessageModule, EChartsComponent],
  styles: `
    .best {
      color: var(--ay-bull);
      font-weight: 700;
    }
    .mono {
      font-variant-numeric: tabular-nums;
    }
    .curve {
      height: 20rem;
      margin-top: 1rem;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Compare backtests</h1>
    @if (store.compareResults().length) {
      @if (dataHashMismatch()) {
        <p-message
          severity="warn"
          text="Runs have differing dataHash — not a like-for-like comparison."
        />
      }
      <p-table [value]="matrix()" dataKey="key">
        <ng-template #header>
          <tr>
            <th>Metric</th>
            @for (c of store.compareResults(); track c.id) {
              <th>{{ c.id.slice(0, 8) }}</th>
            }
          </tr>
        </ng-template>
        <ng-template #body let-row>
          <tr>
            <td>{{ row.label }}</td>
            @for (cell of row.cells; track cell.id) {
              <td class="mono" [class.best]="cell.best">{{ cell.text }}</td>
            }
          </tr>
        </ng-template>
      </p-table>
      <div class="curve">
        <ay-echart [option]="equityOption()" />
      </div>
    } @else {
      <p>Add runs to compare (e.g. /backtests/compare?ids=run1,run2).</p>
    }
  `,
})
export class ComparePage {
  protected readonly store = inject(BacktestsStore);
  private readonly route = inject(ActivatedRoute);

  constructor() {
    this.route.queryParamMap.subscribe((q) => {
      const ids = (q.get('ids') ?? '')
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean);
      if (ids.length) {
        this.store.loadCompare(ids);
      }
    });
  }

  protected readonly dataHashMismatch = computed(() => {
    const hashes = new Set(this.store.compareResults().map((c) => c.results.dataHash));
    return hashes.size > 1;
  });

  protected readonly matrix = computed(() => {
    const runs = this.store.compareResults();
    return ROWS.map((row) => {
      const vals = runs.map((r) => {
        const raw = r.results.metrics[row.key];
        return raw == null || Array.isArray(raw) ? null : Number(raw);
      });
      const present = vals.filter((v): v is number => v != null);
      const best = present.length
        ? row.higherBetter
          ? Math.max(...present)
          : Math.min(...present)
        : null;
      return {
        key: row.key,
        label: row.label,
        cells: runs.map((r, i) => {
          const v = vals[i];
          return {
            id: r.id,
            text: v == null ? '—' : `${formatDecimal(String(v), row.dp ?? 2)}${row.suffix ?? ''}`,
            best: v != null && best != null && v === best && present.length > 1,
          };
        }),
      };
    });
  });

  /** Overlaid equity curves normalized to a 100 start, so different capital bases compare. */
  protected readonly equityOption = computed<EChartsCoreOption>(() => {
    const runs = this.store.compareResults();
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: runs.map((r) => r.id.slice(0, 8)) },
      grid: { left: 48, right: 16, top: 30, bottom: 24 },
      xAxis: { type: 'category', boundaryGap: false },
      yAxis: { type: 'value', name: 'normalized' },
      series: runs.map((r) => {
        const curve = r.results.equityCurve ?? [];
        const base = curve.length ? Number(curve[0].value) : 1;
        return {
          name: r.id.slice(0, 8),
          type: 'line',
          showSymbol: false,
          data: curve.map((p) => (base ? (Number(p.value) / base) * 100 : 0)),
        };
      }),
    };
  });
}
