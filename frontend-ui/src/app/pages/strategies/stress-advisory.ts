import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { ProgressBarModule } from 'primeng/progressbar';
import { formatDecimal } from '../../core/decimal';
import { BacktestsStore } from '../../stores/backtests.store';
import { DegradationBadge } from '../../shared/degradation-badge';

/**
 * Stress-test advisory (E-13 / S1C): ADVISORY ONLY — publishing is never blocked. Suggests the
 * clean window from `GET /backtests/stress-window`, runs a `purpose: stress_test` backtest on it
 * (the backend 422s WINDOW_CONTAMINATED on overlap), and shows the guard-7 degradation badge from
 * the completed run. The historical "latest stress run + holdout-reuse count" is not exposed by the
 * backend, so the live run is what's surfaced; the clone-launder caveat is noted.
 */
@Component({
  selector: 'ay-stress-advisory',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonModule, MessageModule, ProgressBarModule, DegradationBadge],
  styles: `
    .advisory {
      border: 1px solid var(--ay-border);
      border-radius: 8px;
      padding: 0.8rem;
      background: var(--ay-surface-2);
      margin: 0.6rem 0;
    }
    .row {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      flex-wrap: wrap;
      margin: 0.4rem 0;
    }
    .foot {
      font-size: 0.78rem;
      color: var(--ay-text-muted);
      margin-top: 0.5rem;
    }
    .metrics {
      font-variant-numeric: tabular-nums;
      font-size: 0.85rem;
    }
  `,
  template: `
    <div class="advisory">
      <p-message severity="info" text="Advisory only — publishing is never blocked." />
      @if (store.results(); as r) {
        <div class="row metrics">
          <span>Sharpe {{ num(r.metrics['sharpe']) }}</span>
          <span>· return {{ num(r.metrics['totalReturn']) }}%</span>
          <span>· degradation</span>
          <ay-degradation-badge
            [value]="degradation(r)"
            [trainSharpe]="scalar(r.metrics['sharpe'])"
          />
        </div>
      } @else {
        <p>Never stress-tested on unseen data on this version.</p>
      }
      @if (running()) {
        <p-progressbar [value]="store.runProgress()" [showValue]="true" />
      }
      <div class="row">
        <p-button
          [label]="runLabel()"
          icon="pi pi-shield"
          size="small"
          [outlined]="true"
          [loading]="running()"
          (onClick)="runStress()"
        />
      </div>
      <p class="foot">
        Re-creating a strategy under a new id resets lineage tracking — this is honest accounting,
        not tamper-proof prevention.
      </p>
    </div>
  `,
})
export class StressAdvisory {
  readonly strategyId = input<string | null>(null);
  readonly version = input<string | null>(null);

  protected readonly store = inject(BacktestsStore);
  private readonly http = inject(HttpClient);
  private readonly window = signal<{ from?: string; to?: string } | null>(null);

  protected readonly running = computed(() => {
    const s = this.store.runStatus();
    return s === 'queued' || s === 'running';
  });

  protected readonly runLabel = computed(() => {
    const w = this.window();
    return w?.from && w.to
      ? `Run stress test (${w.from.slice(0, 10)} → ${w.to.slice(0, 10)})`
      : 'Run stress test';
  });

  constructor() {
    effect(() => {
      const id = this.strategyId();
      if (id) {
        this.http
          .get<{ from?: string; to?: string }>('/api/v1/backtests/stress-window', {
            params: new HttpParams().set('strategyId', id),
          })
          .subscribe({ next: (w) => this.window.set(w), error: () => this.window.set(null) });
      }
    });
  }

  protected num(value: unknown): string {
    return value == null || Array.isArray(value) ? '—' : formatDecimal(String(value), 2);
  }

  /** Coerce a metric value (which may be an array of caveats) to a scalar string or null. */
  protected scalar(value: unknown): string | null {
    return value == null || Array.isArray(value) ? null : String(value);
  }

  protected degradation(r: { metrics: Record<string, unknown> }): string | null {
    return this.scalar(r.metrics['sharpe_degradation']);
  }

  protected runStress(): void {
    const id = this.strategyId();
    if (!id) {
      return;
    }
    const w = this.window();
    const to = w?.to ?? new Date().toISOString();
    const from = w?.from ?? new Date(Date.now() - 30 * 86_400_000).toISOString();
    this.store.runQuick({
      strategyId: id,
      strategyVersion: this.version(),
      from,
      to,
      purpose: 'stress_test',
    });
  }
}
