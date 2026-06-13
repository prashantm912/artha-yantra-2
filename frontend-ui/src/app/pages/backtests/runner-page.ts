import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DatePickerModule } from 'primeng/datepicker';
import { InputNumberModule } from 'primeng/inputnumber';
import { SelectModule } from 'primeng/select';
import { TabsModule } from 'primeng/tabs';
import { BacktestsStore } from '../../stores/backtests.store';
import { StrategiesStore } from '../../stores/strategies.store';

/**
 * /backtests/run (Phase 38, E-8): the runner (full-parameter backtest) and the sweep launcher
 * (method, maxTrials, objective incl. fold_aggregation, constraints) → 202 {jobId} → the jobs
 * monitor. On the mock stack, windowed runs need benchmark history (see the guide).
 */
@Component({
  selector: 'ay-runner-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    TabsModule,
    ButtonModule,
    SelectModule,
    DatePickerModule,
    InputNumberModule,
  ],
  styles: `
    .grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.8rem;
      max-width: 40rem;
    }
    .field {
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
    }
    .fl {
      font-size: 0.8rem;
      color: var(--ay-text-muted);
    }
    .actions {
      margin-top: 1rem;
    }
  `,
  template: `
    <h1 class="ay-sr-only">Backtest runner</h1>
    <p-tabs value="backtest">
      <p-tablist>
        <p-tab value="backtest">Backtest</p-tab>
        <p-tab value="sweep">Sweep</p-tab>
      </p-tablist>
      <p-tabpanels>
        <p-tabpanel value="backtest">
          <div class="grid">
            <div class="field">
              <span class="fl">Strategy</span>
              <p-select
                [options]="strategies.list()"
                optionLabel="name"
                optionValue="id"
                [(ngModel)]="strategyId"
                placeholder="Strategy"
                ariaLabel="Strategy"
              />
            </div>
            <div class="field">
              <span class="fl">Interval</span>
              <p-select [options]="intervals" [(ngModel)]="interval" ariaLabel="Interval" />
            </div>
            <div class="field">
              <span class="fl">From</span>
              <p-datepicker
                [(ngModel)]="from"
                dateFormat="yy-mm-dd"
                [showIcon]="true"
                ariaLabel="From"
              />
            </div>
            <div class="field">
              <span class="fl">To</span>
              <p-datepicker
                [(ngModel)]="to"
                dateFormat="yy-mm-dd"
                [showIcon]="true"
                ariaLabel="To"
              />
            </div>
            <div class="field">
              <span class="fl">Initial capital (₹)</span>
              <p-inputnumber [(ngModel)]="capital" [min]="1000" ariaLabel="Initial capital" />
            </div>
            <div class="field">
              <span class="fl">Seed</span>
              <p-inputnumber [(ngModel)]="seed" ariaLabel="Seed" />
            </div>
          </div>
          <div class="actions">
            <p-button
              label="Run backtest"
              icon="pi pi-play"
              [disabled]="!strategyId"
              (onClick)="runBacktest()"
            />
          </div>
        </p-tabpanel>

        <p-tabpanel value="sweep">
          <div class="grid">
            <div class="field">
              <span class="fl">Strategy</span>
              <p-select
                [options]="strategies.list()"
                optionLabel="name"
                optionValue="id"
                [(ngModel)]="strategyId"
                placeholder="Strategy"
                ariaLabel="Sweep strategy"
              />
            </div>
            <div class="field">
              <span class="fl">Method</span>
              <p-select [options]="methods" [(ngModel)]="method" ariaLabel="Method" />
            </div>
            <div class="field">
              <span class="fl">Max trials</span>
              <p-inputnumber [(ngModel)]="maxTrials" [min]="2" ariaLabel="Max trials" />
            </div>
            <div class="field">
              <span class="fl">Objective metric</span>
              <p-select [options]="metrics" [(ngModel)]="objMetric" ariaLabel="Objective metric" />
            </div>
            <div class="field">
              <span class="fl">Direction</span>
              <p-select [options]="directions" [(ngModel)]="direction" ariaLabel="Direction" />
            </div>
            <div class="field">
              <span class="fl">Fold aggregation</span>
              <p-select
                [options]="aggregations"
                [(ngModel)]="foldAgg"
                ariaLabel="Fold aggregation"
              />
            </div>
            <div class="field">
              <span class="fl">Min trades</span>
              <p-inputnumber [(ngModel)]="minTrades" [min]="1" ariaLabel="Min trades" />
            </div>
          </div>
          <div class="actions">
            <p-button
              label="Launch sweep"
              icon="pi pi-sliders-h"
              [disabled]="!strategyId"
              (onClick)="launchSweep()"
            />
          </div>
        </p-tabpanel>
      </p-tabpanels>
    </p-tabs>
  `,
})
export class RunnerPage {
  protected readonly strategies = inject(StrategiesStore);
  private readonly backtests = inject(BacktestsStore);
  private readonly router = inject(Router);

  protected readonly intervals = ['1m', '5m', '15m', '1h', '1d', '1w'];
  protected readonly methods: string[] = ['grid', 'random', 'tpe', 'nsga2'];
  protected readonly metrics = [
    'sharpe',
    'cagr',
    'totalReturn',
    'sortino',
    'maxDrawdown',
    'winRate',
  ];
  protected readonly directions: string[] = ['maximize', 'minimize'];
  protected readonly aggregations: string[] = ['mean', 'min', 'mean_minus_std'];

  protected strategyId: string | null = null;
  protected interval = '1d';
  protected to = new Date();
  protected from = new Date(Date.now() - 60 * 86_400_000);
  protected capital = 100_000;
  protected seed = 42;
  protected method: 'grid' | 'random' | 'tpe' | 'nsga2' = 'tpe';
  protected maxTrials = 30;
  protected objMetric = 'sharpe';
  protected direction: 'maximize' | 'minimize' = 'maximize';
  protected foldAgg: 'mean' | 'min' | 'mean_minus_std' = 'mean';
  protected minTrades = 30;
  protected readonly submitted = signal(false);

  constructor() {
    this.strategies.loadList();
  }

  protected runBacktest(): void {
    if (!this.strategyId) {
      return;
    }
    this.backtests.submitRun(
      {
        strategyId: this.strategyId,
        from: this.from.toISOString(),
        to: this.to.toISOString(),
        interval: this.interval,
        initialCapital: String(this.capital),
        seed: this.seed,
      },
      () => this.router.navigate(['/backtests/jobs']),
    );
  }

  protected launchSweep(): void {
    if (!this.strategyId) {
      return;
    }
    this.backtests.submitSweep(
      {
        strategyId: this.strategyId,
        from: this.from.toISOString(),
        to: this.to.toISOString(),
        interval: this.interval,
        initialCapital: String(this.capital),
        method: this.method,
        maxTrials: this.maxTrials,
        objective: {
          metric: this.objMetric,
          direction: this.direction,
          fold_aggregation: this.foldAgg,
        },
        constraints: { min_trades: this.minTrades },
        seed: this.seed,
      },
      () => this.router.navigate(['/backtests/jobs']),
    );
  }
}
