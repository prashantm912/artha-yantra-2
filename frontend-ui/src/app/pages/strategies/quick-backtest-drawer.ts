import { ChangeDetectionStrategy, Component, inject, input, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { DatePickerModule } from 'primeng/datepicker';
import { DrawerModule } from 'primeng/drawer';
import { InputNumberModule } from 'primeng/inputnumber';
import { ProgressBarModule } from 'primeng/progressbar';
import { SelectModule } from 'primeng/select';
import { formatDecimal } from '../../core/decimal';
import { BacktestsStore } from '../../stores/backtests.store';
import { EquityCurveComponent } from '../../shared/equity-curve';

/**
 * Quick-backtest drawer (E-11 screen 3): docked in the editor, prefilled from a recent window,
 * fires `POST /backtests/run` against the saved draft version, shows live progress over the
 * `jobs.progress` topic, then headline metrics + an inline equity/drawdown curve — the
 * validate-tweak-rerun loop without leaving the editor. (A draft must be saved first: the run is
 * keyed by strategyId+version, not inline config.)
 */
@Component({
  selector: 'ay-quick-backtest-drawer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    DrawerModule,
    ButtonModule,
    SelectModule,
    DatePickerModule,
    InputNumberModule,
    ProgressBarModule,
    EquityCurveComponent,
  ],
  styles: `
    .form {
      display: grid;
      gap: 0.7rem;
      margin-bottom: 1rem;
    }
    .fl {
      font-size: 0.8rem;
      color: var(--ay-text-muted);
      display: block;
      margin-bottom: 0.2rem;
    }
    .metrics {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 0.4rem 1rem;
      margin: 0.8rem 0;
      font-variant-numeric: tabular-nums;
    }
    .metrics dt {
      color: var(--ay-text-muted);
      font-size: 0.8rem;
    }
    .metrics dd {
      margin: 0 0 0.4rem;
      font-weight: 600;
    }
    .curve {
      height: 16rem;
    }
    .hint {
      color: var(--ay-warn);
    }
  `,
  template: `
    <p-drawer
      [(visible)]="open"
      position="right"
      header="Quick backtest"
      [style]="{ width: '34rem' }"
    >
      @if (!strategyId()) {
        <p class="hint">
          Save the draft first — a quick backtest runs against the saved draft version.
        </p>
      } @else {
        <div class="form">
          <div>
            <span class="fl">From</span>
            <p-datepicker
              [(ngModel)]="from"
              dateFormat="yy-mm-dd"
              [showIcon]="true"
              ariaLabel="From date"
            />
          </div>
          <div>
            <span class="fl">To</span>
            <p-datepicker
              [(ngModel)]="to"
              dateFormat="yy-mm-dd"
              [showIcon]="true"
              ariaLabel="To date"
            />
          </div>
          <div>
            <span class="fl">Interval</span>
            <p-select [options]="intervals" [(ngModel)]="interval" ariaLabel="Interval" />
          </div>
          <div>
            <span class="fl">Initial capital (₹)</span>
            <p-inputnumber [(ngModel)]="capital" [min]="1000" ariaLabel="Initial capital" />
          </div>
          <p-button
            label="Run quick backtest"
            icon="pi pi-play"
            [loading]="running()"
            (onClick)="run()"
          />
        </div>

        @if (store.runStatus(); as status) {
          <p-progressbar [value]="store.runProgress()" [showValue]="true" />
          <p>{{ status }}</p>
        }

        @if (store.results(); as r) {
          <dl class="metrics">
            <dt>Sharpe</dt>
            <dd>{{ num(r.metrics['sharpe'], 2) }}</dd>
            <dt>Total return</dt>
            <dd>{{ num(r.metrics['totalReturn'], 2) }}%</dd>
            <dt>Max drawdown</dt>
            <dd>{{ num(r.metrics['maxDrawdown'], 2) }}%</dd>
            <dt>Win rate</dt>
            <dd>{{ num(r.metrics['winRate'], 1) }}%</dd>
            <dt>Trades</dt>
            <dd>{{ r.metrics['tradeCount'] }}</dd>
          </dl>
          <div class="curve">
            <ay-equity-curve [equity]="r.equityCurve" [drawdown]="r.drawdownCurve ?? null" />
          </div>
        }
      }
    </p-drawer>
  `,
})
export class QuickBacktestDrawer {
  readonly open = model<boolean>(false);
  readonly strategyId = input<string | null>(null);
  readonly version = input<string | null>(null);

  protected readonly store = inject(BacktestsStore);
  protected readonly intervals = ['1m', '5m', '15m', '1h', '1d', '1w'];
  protected interval = '1d';
  protected capital = 100_000;
  // a recent default window (mock candles are rolling — never hardcode absolute dates)
  protected to = new Date();
  protected from = new Date(Date.now() - 30 * 86_400_000);

  protected running(): boolean {
    const s = this.store.runStatus();
    return s === 'queued' || s === 'running';
  }

  protected num(value: string | number | null | string[] | undefined, dp: number): string {
    if (value == null || Array.isArray(value)) {
      return '—';
    }
    return formatDecimal(String(value), dp);
  }

  protected run(): void {
    const id = this.strategyId();
    if (!id) {
      return;
    }
    this.store.runQuick({
      strategyId: id,
      strategyVersion: this.version(),
      from: this.from.toISOString(),
      to: this.to.toISOString(),
      interval: this.interval,
      initialCapital: String(this.capital),
    });
  }
}
