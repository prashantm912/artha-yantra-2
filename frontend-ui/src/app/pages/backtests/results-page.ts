import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import type { EChartsCoreOption } from 'echarts/core';
import { ButtonModule } from 'primeng/button';
import { TableModule, type TableRowSelectEvent } from 'primeng/table';
import { TabsModule } from 'primeng/tabs';
import { TagModule } from 'primeng/tag';
import { MessageModule } from 'primeng/message';
import { formatDecimal } from '../../core/decimal';
import { BacktestsStore, type TradeRow } from '../../stores/backtests.store';
import { EquityCurveComponent } from '../../shared/equity-curve';
import { EChartsComponent } from '../../shared/echarts-chart';
import { FoldPanel } from './fold-panel';

interface MetricDef {
  key: string;
  label: string;
  suffix?: string;
  dp?: number;
}

const CORE_METRICS: MetricDef[] = [
  { key: 'totalReturn', label: 'Total return', suffix: '%' },
  { key: 'cagr', label: 'CAGR', suffix: '%' },
  { key: 'sharpe', label: 'Sharpe', dp: 2 },
  { key: 'sortino', label: 'Sortino', dp: 2 },
  { key: 'maxDrawdown', label: 'Max drawdown', suffix: '%' },
  { key: 'winRate', label: 'Win rate', suffix: '%', dp: 1 },
  { key: 'profitFactor', label: 'Profit factor', dp: 2 },
  { key: 'expectancy', label: 'Expectancy (₹)' },
  { key: 'averageTrade', label: 'Avg trade (₹)' },
  { key: 'exposure', label: 'Exposure', suffix: '%', dp: 1 },
  { key: 'tradeCount', label: 'Trades', dp: 0 },
];
const BENCH_METRICS: MetricDef[] = [
  { key: 'alpha', label: 'Alpha', dp: 4 },
  { key: 'beta', label: 'Beta', dp: 3 },
  { key: 'informationRatio', label: 'Information ratio', dp: 3 },
  { key: 'excessCagr', label: 'Excess CAGR', suffix: '%' },
];

/**
 * /backtests/:id (Phase 38, E-11 / E-12.5): metric panel (+ benchmark-relative columns when
 * present), scrollable trade table with per-trade contribution drill-down, persisted equity +
 * drawdown curves with the buy-and-hold benchmark overlay (never client-recomputed), the
 * walk-forward fold panel, and the Monte Carlo tab — all NULL-safe for runs predating Phase 32A.
 */
@Component({
  selector: 'ay-results-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    TabsModule,
    TableModule,
    TagModule,
    ButtonModule,
    MessageModule,
    EquityCurveComponent,
    EChartsComponent,
    FoldPanel,
  ],
  styles: `
    .metrics {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(9rem, 1fr));
      gap: 0.5rem 1rem;
      margin-bottom: 1rem;
    }
    .metrics .m {
      border: 1px solid var(--ay-border);
      border-radius: 6px;
      padding: 0.4rem 0.6rem;
      background: var(--ay-surface-1);
    }
    .metrics .lbl {
      font-size: 0.74rem;
      color: var(--ay-text-muted);
    }
    .metrics .val {
      font-variant-numeric: tabular-nums;
      font-weight: 600;
    }
    .curve {
      height: 20rem;
    }
    .caveats {
      margin: 0.5rem 0;
    }
    .detail {
      border: 1px solid var(--ay-border);
      border-radius: 8px;
      padding: 0.7rem;
      margin-top: 0.6rem;
      background: var(--ay-surface-1);
    }
    .kv {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 0.2rem 0.8rem;
      font-variant-numeric: tabular-nums;
      font-size: 0.85rem;
    }
    .mono {
      font-variant-numeric: tabular-nums;
    }
    .chartpair {
      height: 18rem;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Backtest results</h1>
    @if (store.viewResults(); as r) {
      <p-tabs value="overview">
        <p-tablist>
          <p-tab value="overview">Overview</p-tab>
          <p-tab value="trades">Trades</p-tab>
          @if (store.folds().length) {
            <p-tab value="folds">Folds</p-tab>
          }
          @if (store.montecarlo()) {
            <p-tab value="mc">Monte Carlo</p-tab>
          }
        </p-tablist>
        <p-tabpanels>
          <p-tabpanel value="overview">
            <div class="metrics">
              @for (m of coreMetrics; track m.key) {
                <div class="m">
                  <div class="lbl">{{ m.label }}</div>
                  <div class="val">{{ metric(r, m) }}</div>
                </div>
              }
              @for (m of benchMetrics; track m.key) {
                @if (r.metrics[m.key] !== null && r.metrics[m.key] !== undefined) {
                  <div class="m">
                    <div class="lbl">{{ m.label }}</div>
                    <div class="val">{{ metric(r, m) }}</div>
                  </div>
                }
              }
            </div>
            @if (r.premiumSource === 'SYNTHETIC') {
              <p-message
                class="caveats"
                severity="warn"
                text="Options premiums are SYNTHETIC_B76 (not snapshot-grade)."
              />
            }
            <div class="curve">
              <ay-equity-curve
                [equity]="r.equityCurve"
                [drawdown]="r.drawdownCurve ?? null"
                [benchmark]="r.benchmarkCurve ?? null"
              />
            </div>
            <p class="mono">dataHash {{ r.dataHash }} · seed {{ r.seed }}</p>
          </p-tabpanel>

          <p-tabpanel value="trades">
            <p-table
              [value]="store.trades()"
              [scrollable]="true"
              scrollHeight="calc(100vh - 18rem)"
              selectionMode="single"
              dataKey="seq"
              (onRowSelect)="select($event)"
            >
              <ng-template #header>
                <tr>
                  <th>#</th>
                  <th>Side</th>
                  <th>Entry</th>
                  <th>Exit</th>
                  <th>P&L</th>
                  <th>%</th>
                  <th>Reason</th>
                </tr>
              </ng-template>
              <ng-template #body let-t>
                <tr [pSelectableRow]="t" style="height: 40px">
                  <td>{{ t.seq }}</td>
                  <td>
                    <p-tag [value]="t.side" [severity]="t.side === 'LONG' ? 'success' : 'danger'" />
                  </td>
                  <td class="mono">{{ price(t.entryPrice) }}</td>
                  <td class="mono">{{ t.exitPrice ? price(t.exitPrice) : '—' }}</td>
                  <td class="mono">{{ price(t.pnl) }}</td>
                  <td class="mono">{{ price(t.pnlPct) }}</td>
                  <td>{{ t.exitReason }}</td>
                </tr>
              </ng-template>
            </p-table>
            @if (selected(); as t) {
              <div class="detail">
                <strong>Trade #{{ t.seq }}</strong> — entry {{ t.entryTs.slice(0, 16) }} · touch
                {{ t.touchBasis ?? '—' }}
                <p-button
                  size="small"
                  [text]="true"
                  label="View on chart"
                  icon="pi pi-chart-line"
                  [routerLink]="['/charts']"
                  [queryParams]="{ runId: runId(), tradeId: t.seq, interval: '1d' }"
                />
                <div class="kv">
                  @for (c of contributions(t); track c.k) {
                    <span>{{ c.k }}</span
                    ><span class="mono">{{ c.v }}</span>
                  }
                </div>
              </div>
            }
          </p-tabpanel>

          @if (store.folds().length) {
            <p-tabpanel value="folds">
              <ay-fold-panel [folds]="store.folds()" />
            </p-tabpanel>
          }

          @if (store.montecarlo(); as mc) {
            <p-tabpanel value="mc">
              @if (mc.insufficientSample) {
                <p-message
                  severity="warn"
                  text="Insufficient sample (< 30 trades) — extend the window."
                />
              }
              <div class="chartpair">
                <ay-echart [option]="mcFanOption()" />
              </div>
              <div class="chartpair">
                <ay-echart [option]="mcDrawdownOption()" />
              </div>
              <p class="mono">risk of ruin {{ mc.riskOfRuin }}</p>
            </p-tabpanel>
          }
        </p-tabpanels>
      </p-tabs>
    } @else {
      <p>Loading results…</p>
    }
  `,
})
export class ResultsPage {
  protected readonly store = inject(BacktestsStore);
  private readonly route = inject(ActivatedRoute);
  protected readonly coreMetrics = CORE_METRICS;
  protected readonly benchMetrics = BENCH_METRICS;
  protected readonly selected = signal<TradeRow | null>(null);
  protected readonly runId = signal<string>('');

  constructor() {
    this.route.paramMap.subscribe((p) => {
      const id = p.get('id');
      if (id) {
        this.runId.set(id);
        this.store.loadResults(id);
        this.store.loadTrades(id);
        this.store.loadFolds(id);
        this.store.loadMonteCarlo(id);
      }
    });
  }

  protected price(v: string | null | undefined): string {
    return v == null ? '—' : formatDecimal(v, 2);
  }

  protected metric(r: { metrics: Record<string, unknown> }, m: MetricDef): string {
    const v = r.metrics[m.key];
    if (v == null || Array.isArray(v)) {
      return '—';
    }
    return `${formatDecimal(String(v), m.dp ?? 2)}${m.suffix ?? ''}`;
  }

  protected select(event: TableRowSelectEvent): void {
    this.selected.set((event.data as TradeRow) ?? null);
  }

  protected contributions(t: TradeRow): { k: string; v: string }[] {
    const c = t.contributions;
    if (!c || typeof c !== 'object') {
      return [];
    }
    return Object.entries(c).map(([k, v]) => ({ k, v: String(v) }));
  }

  protected readonly mcFanOption = computed<EChartsCoreOption>(() => {
    const mc = this.store.montecarlo();
    const step = mc?.equityBands.step ?? [];
    const num = (a: string[]) => a.map((s) => Number(s));
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['p5', 'p50', 'p95'] },
      grid: { left: 56, right: 16, top: 30, bottom: 24 },
      xAxis: { type: 'category', data: step.map((s) => String(s)), name: 'trade #' },
      yAxis: { type: 'value', name: 'equity (₹)' },
      series: [
        {
          name: 'p95',
          type: 'line',
          data: num(mc?.equityBands.p95 ?? []),
          smooth: true,
          showSymbol: false,
        },
        {
          name: 'p50',
          type: 'line',
          data: num(mc?.equityBands.p50 ?? []),
          smooth: true,
          showSymbol: false,
        },
        {
          name: 'p5',
          type: 'line',
          data: num(mc?.equityBands.p5 ?? []),
          smooth: true,
          showSymbol: false,
        },
      ],
    };
  });

  protected readonly mcDrawdownOption = computed<EChartsCoreOption>(() => {
    const d = this.store.montecarlo()?.drawdownDistribution;
    return {
      title: { text: 'Drawdown distribution (%)', textStyle: { fontSize: 12 } },
      tooltip: {},
      grid: { left: 44, right: 16, top: 36, bottom: 24 },
      xAxis: { type: 'category', data: ['p5', 'p50', 'p95', 'mean'] },
      yAxis: { type: 'value' },
      series: [
        {
          type: 'bar',
          data: [
            Number(d?.p5 ?? 0),
            Number(d?.p50 ?? 0),
            Number(d?.p95 ?? 0),
            Number(d?.mean ?? 0),
          ],
        },
      ],
    };
  });
}
