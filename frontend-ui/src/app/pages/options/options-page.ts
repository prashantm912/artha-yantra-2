import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { EChartsCoreOption } from 'echarts/core';
import { ButtonModule } from 'primeng/button';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { compareDecimal, formatDecimal } from '../../core/decimal';
import { EChartsComponent } from '../../shared/echarts-chart';
import { MarketStore } from '../../stores/market.store';
import { OptionsStore, type OptionStrikeRow } from '../../stores/options.store';

/**
 * /options (F-42): the live options workbench — wired underlying/expiry/strike-window filters, a
 * scrollable calls/strike/puts grid with ITM highlighting, PCR/IV-smile/OI-profile analytics
 * (ECharts, derived via `computed()`), an India-VIX header chip, off-hours staleness chips and a
 * snapshot-history mode. Fed by the `options.chain` topic + REST snapshot — never the tick firehose.
 */
@Component({
  selector: 'ay-options-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, TableModule, TagModule, ButtonModule, SelectModule, EChartsComponent],
  styles: `
    .filters {
      display: flex;
      gap: 0.6rem;
      align-items: center;
      flex-wrap: wrap;
      margin-bottom: 0.8rem;
    }
    .spacer {
      flex: 1;
    }
    .chip-row {
      display: flex;
      gap: 0.5rem;
      align-items: center;
    }
    .vix {
      font-variant-numeric: tabular-nums;
      font-size: 0.85rem;
      color: var(--ay-text-muted);
      border: 1px solid var(--ay-border);
      border-radius: 8px;
      padding: 0.2rem 0.55rem;
    }
    .num {
      font-variant-numeric: tabular-nums;
      text-align: right;
    }
    .strike {
      font-variant-numeric: tabular-nums;
      text-align: center;
      font-weight: 600;
      background: var(--ay-surface-2);
    }
    .itm {
      background: color-mix(in srgb, var(--ay-success, #16a34a) 14%, transparent);
    }
    .tabs {
      display: flex;
      gap: 0.4rem;
      margin: 0.8rem 0 0.4rem;
    }
    .analytics {
      height: 22rem;
    }
    .history {
      display: flex;
      gap: 0.5rem;
      align-items: center;
    }
    .meta {
      color: var(--ay-text-muted);
      font-size: 0.85rem;
      font-variant-numeric: tabular-nums;
      margin-bottom: 0.5rem;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Options chain</h1>
    <div class="filters">
      <p-select
        [options]="underlyings()"
        [ngModel]="store.underlying()"
        (ngModelChange)="store.setUnderlying($event)"
        [filter]="true"
        ariaLabel="Underlying"
      />
      <p-select
        [options]="store.expiries()"
        [ngModel]="store.expiry()"
        (ngModelChange)="store.setExpiry($event)"
        placeholder="Expiry"
        ariaLabel="Expiry"
      />
      <p-select
        [options]="windowOptions"
        optionLabel="label"
        optionValue="value"
        [ngModel]="store.strikeWindow()"
        (ngModelChange)="store.setStrikeWindow($event)"
        ariaLabel="Strike window"
      />
      <p-button [text]="true" icon="pi pi-refresh" ariaLabel="Reload" (onClick)="store.load()" />
      <span class="spacer"></span>
      <div class="chip-row">
        @if (store.ivHistory(); as iv) {
          <span class="vix" title="IV rank/percentile over the trailing 1y of own snapshots">
            @if (iv.insufficientHistory) {
              IV rank: insufficient ({{ iv.windowDays }}/{{ iv.floorDays }}d)
            } @else {
              IV rank {{ iv.rank }} · {{ iv.percentile }}%ile
            }
          </span>
        }
        @if (store.vixStat(); as vix) {
          <span class="vix" title="India VIX level + trailing 1-year percentile">
            VIX {{ price(vix.level) }}
            @if (vix.percentile !== null) {
              · {{ vix.percentile }}%ile
            }
          </span>
        }
        @if (store.stale()) {
          <p-tag severity="warn" value="STALE" pTooltip="Market closed — last stored chain" />
        }
        <span class="history">
          <p-button
            size="small"
            label="History"
            icon="pi pi-history"
            [outlined]="!store.historyMode()"
            [attr.aria-pressed]="store.historyMode()"
            (onClick)="store.setHistoryMode(!store.historyMode())"
          />
          @if (store.historyMode()) {
            <input
              type="datetime-local"
              [ngModel]="historyInput()"
              (ngModelChange)="onHistoryAt($event)"
              aria-label="Snapshot time"
            />
          }
        </span>
      </div>
    </div>

    <div class="meta" aria-live="polite">
      spot {{ store.spot() ? price(store.spot()!) : '—' }} · PCR {{ store.pcr() ?? '—' }} · ATM
      {{ store.atmStrike() ?? '—' }} · {{ store.windowedRows().length }} strikes
    </div>

    <p-table
      [value]="store.windowedRows()"
      [scrollable]="true"
      scrollHeight="60vh"
      dataKey="strike"
      [loading]="store.loading()"
    >
      <ng-template #header>
        <tr>
          <th class="num">CE OI</th>
          <th class="num">CE IV</th>
          <th class="num">CE LTP</th>
          <th style="text-align:center">Strike</th>
          <th class="num">PE LTP</th>
          <th class="num">PE IV</th>
          <th class="num">PE OI</th>
        </tr>
      </ng-template>
      <ng-template #body let-row>
        <tr style="height: 40px">
          <td class="num">{{ row.ce?.oi ?? '—' }}</td>
          <td class="num">{{ iv(row.ce?.iv) }}</td>
          <td class="num" [class.itm]="ceItm(row)">{{ row.ce?.ltp ? price(row.ce.ltp) : '—' }}</td>
          <td class="strike">{{ row.strike }}</td>
          <td class="num" [class.itm]="peItm(row)">{{ row.pe?.ltp ? price(row.pe.ltp) : '—' }}</td>
          <td class="num">{{ iv(row.pe?.iv) }}</td>
          <td class="num">{{ row.pe?.oi ?? '—' }}</td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td colspan="7">No chain — pick an underlying with a live (mock) options chain.</td>
        </tr>
      </ng-template>
    </p-table>

    <div class="tabs" role="tablist" aria-label="Options analytics">
      @for (tab of tabs; track tab.key) {
        <p-button
          size="small"
          [text]="store.activeTab() !== tab.key"
          [label]="tab.label"
          role="tab"
          [attr.aria-selected]="store.activeTab() === tab.key"
          (onClick)="store.setActiveTab(tab.key)"
        />
      }
    </div>
    <div class="analytics">
      <ay-echart [option]="chartOption()" />
    </div>
  `,
})
export class OptionsPage {
  protected readonly store = inject(OptionsStore);
  private readonly market = inject(MarketStore);

  /** All synced F&O underlyings (NSE/BSE), falling back to the index defaults until loaded. */
  protected readonly underlyings = computed(() =>
    this.market.underlyings().length ? this.market.underlyings() : ['NIFTY 50', 'NIFTY BANK'],
  );

  constructor() {
    this.market.loadUnderlyings();
  }
  protected readonly windowOptions = [
    { label: '±5 strikes', value: 5 },
    { label: '±10 strikes', value: 10 },
    { label: '±20 strikes', value: 20 },
    { label: 'All strikes', value: 0 },
  ];
  protected readonly tabs = [
    { key: 'smile' as const, label: 'IV smile' },
    { key: 'oi' as const, label: 'OI profile' },
    { key: 'pcr' as const, label: 'PCR trend' },
    { key: 'iv' as const, label: 'IV history' },
  ];

  /** datetime-local needs `YYYY-MM-DDTHH:mm`; strip the offset/seconds off the stored ISO. */
  protected readonly historyInput = computed(() => this.store.historyAt()?.slice(0, 16) ?? '');

  protected readonly chartOption = computed<EChartsCoreOption>(() => {
    const rows = this.store.windowedRows();
    const strikes = rows.map((r) => r.strike);
    switch (this.store.activeTab()) {
      case 'oi':
        return this.barOption(
          strikes,
          'CE OI',
          rows.map((r) => r.ce?.oi ?? null),
          'PE OI',
          rows.map((r) => r.pe?.oi ?? null),
        );
      case 'pcr': {
        const trend = this.store.pcrTrend();
        return {
          tooltip: { trigger: 'axis' },
          xAxis: { type: 'category', data: trend.map((p) => p.ts.slice(11, 19)), name: 'time' },
          yAxis: { type: 'value', name: 'PCR' },
          series: [
            { name: 'PCR', type: 'line', smooth: true, data: trend.map((p) => Number(p.pcr)) },
          ],
        };
      }
      case 'iv': {
        const points = this.store.ivHistory()?.series ?? [];
        return {
          tooltip: { trigger: 'axis' },
          xAxis: { type: 'category', data: points.map((p) => p.date), name: 'date' },
          yAxis: { type: 'value', name: 'IV (30d / ATM)' },
          series: [
            {
              name: 'IV',
              type: 'line',
              smooth: true,
              data: points.map((p) => this.numOrNull(p.iv)),
            },
          ],
        };
      }
      default:
        return {
          tooltip: { trigger: 'axis' },
          legend: { data: ['CE IV', 'PE IV'] },
          xAxis: { type: 'category', data: strikes, name: 'strike' },
          yAxis: { type: 'value', name: 'IV' },
          series: [
            { name: 'CE IV', type: 'line', data: rows.map((r) => this.numOrNull(r.ce?.iv)) },
            { name: 'PE IV', type: 'line', data: rows.map((r) => this.numOrNull(r.pe?.iv)) },
          ],
        };
    }
  });

  protected onHistoryAt(value: string): void {
    // datetime-local is offset-naive; treat the picked wall-clock as IST.
    this.store.setHistoryAt(value ? `${value}:00+05:30` : null);
  }

  protected price(value: string): string {
    return formatDecimal(value, 2);
  }

  protected iv(value: string | null | undefined): string {
    return value ? formatDecimal(value, 4) : '—';
  }

  protected ceItm(row: OptionStrikeRow): boolean {
    const spot = this.store.spot();
    return !!spot && compareDecimal(row.strike, spot) < 0;
  }

  protected peItm(row: OptionStrikeRow): boolean {
    const spot = this.store.spot();
    return !!spot && compareDecimal(row.strike, spot) > 0;
  }

  /** Decimal string → chart number (charts are visual, not money math); null preserves gaps. */
  private numOrNull(value: string | null | undefined): number | null {
    return value ? Number(value) : null;
  }

  private barOption(
    strikes: string[],
    nameA: string,
    a: (number | null)[],
    nameB: string,
    b: (number | null)[],
  ): EChartsCoreOption {
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: [nameA, nameB] },
      xAxis: { type: 'category', data: strikes, name: 'strike' },
      yAxis: { type: 'value', name: 'OI' },
      series: [
        { name: nameA, type: 'bar', data: a },
        { name: nameB, type: 'bar', data: b },
      ],
    };
  }
}
