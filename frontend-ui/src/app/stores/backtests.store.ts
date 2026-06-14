import { inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { WsClientService } from '../core/ws-client.service';
import type { JobStatus } from './jobs.store';

/** `POST /api/v1/backtests/run` body (BigDecimal-as-string for money). */
export interface BacktestRunRequest {
  strategyId: string;
  strategyVersion?: string | null;
  from: string;
  to: string;
  interval?: string;
  universeOverride?: unknown;
  initialCapital?: string;
  costs?: unknown;
  seed?: number;
  purpose?: 'backtest' | 'stress_test';
}

/** One downsampled curve point (`ts` ISO, `value` decimal string). */
export interface CurvePoint {
  ts: string;
  value: string;
}

/** `GET /api/v1/backtests/{id}/results` — extended in Phase 38 (trades/folds/Monte Carlo). */
export interface BacktestResults {
  metrics: Record<string, string | number | null | string[]>;
  equityCurve: CurvePoint[];
  drawdownCurve?: CurvePoint[];
  benchmarkCurve?: CurvePoint[] | null;
  dataHash?: string;
  seed?: number;
  premiumSource?: string;
  universeChecksum?: string | null;
  caveats?: string[];
  // the run's instrument — lets "View on chart" deep-link the right symbol
  exchange?: string | null;
  tradingsymbol?: string | null;
}

/** One closed/open replay trade row (`GET /{id}/trades`). `contributions` is a per-indicator map. */
export interface TradeRow {
  seq: number;
  side: 'LONG' | 'SHORT';
  qty: number;
  entryTs: string;
  entryPrice: string;
  exitTs?: string | null;
  exitPrice?: string | null;
  pnl: string;
  pnlPct: string;
  exitReason: string;
  barsHeld: number;
  touchBasis?: string | null;
  contributions?: Record<string, unknown> | null;
  // denormalized run instrument + entry-time protective levels (null when no SL/TP rule)
  exchange?: string | null;
  tradingsymbol?: string | null;
  stopLoss?: string | null;
  takeProfit?: string | null;
}

/** One walk-forward fold (`GET /{id}/folds`); `regimeMix` is nullable (BPC degrades cleanly). */
export interface FoldRow {
  fold: { index: number; trainFrom: string; trainTo: string; testFrom: string; testTo: string };
  trainMetrics: Record<string, string | number | null>;
  oosMetrics: Record<string, string | number | null>;
  regimeMix?: Record<string, number> | null;
}

/** Monte Carlo summary (`GET /{id}/montecarlo`). */
export interface MonteCarloSummary {
  mcSeed?: number;
  n: number;
  trades: number;
  insufficientSample: boolean;
  equityBands: { step: number[]; p5: string[]; p50: string[]; p95: string[] };
  drawdownDistribution: { p5: string; p50: string; p95: string; mean: string };
  riskOfRuin: string;
  ci?: { cagr?: Record<string, string>; sharpe?: Record<string, string> };
}

/** A backtest-run request for the optimizer sweep (`POST /api/v1/optimizations/run`). */
export interface SweepRunRequest {
  strategyId: string;
  strategyVersion?: string | null;
  from: string;
  to: string;
  interval?: string;
  initialCapital?: string;
  method: 'grid' | 'random' | 'tpe' | 'nsga2';
  maxTrials: number;
  objective: {
    metric?: string;
    direction?: 'maximize' | 'minimize';
    fold_aggregation?: 'mean' | 'min' | 'mean_minus_std';
    objectives?: { metric: string; direction: string }[];
  };
  constraints?: { min_trades?: number };
  seed?: number;
}

interface JobProgressFrame {
  jobId: string;
  status: JobStatus;
  progress: number;
}

interface BacktestsState {
  runningJobId: string | null;
  runStatus: JobStatus | null;
  runProgress: number;
  results: BacktestResults | null;
  resultsLoading: boolean;
  runError: string | null;
  // results-page slice
  viewResults: BacktestResults | null;
  trades: TradeRow[];
  folds: FoldRow[];
  montecarlo: MonteCarloSummary | null;
  // comparison slice (up to 6 runs — §7.7 screen 5)
  compareResults: { id: string; results: BacktestResults }[];
}

const JOBS_TOPIC = '/topic/jobs/stream';

/**
 * BacktestsStore (E-1, runner slice): fires the quick-backtest, tracks ITS job over the
 * `jobs.progress` topic, and on completion fetches the run's results via the job's `resultRef`
 * (results are keyed by the run id, not the jobId). Extended in Phase 38 for the full results page.
 */
export const BacktestsStore = signalStore(
  { providedIn: 'root' },
  withState<BacktestsState>({
    runningJobId: null,
    runStatus: null,
    runProgress: 0,
    results: null,
    resultsLoading: false,
    runError: null,
    viewResults: null,
    trades: [],
    folds: [],
    montecarlo: null,
    compareResults: [],
  }),
  withMethods((store, http = inject(HttpClient)) => ({
    /** Submit a backtest against the given request; tracks the returned jobId. */
    runQuick(req: BacktestRunRequest): void {
      patchState(store, {
        runStatus: 'queued',
        runProgress: 0,
        results: null,
        runError: null,
        runningJobId: null,
      });
      http.post<{ jobId: string; status: JobStatus }>('/api/v1/backtests/run', req).subscribe({
        next: (res) => patchState(store, { runningJobId: res.jobId, runStatus: res.status }),
        error: () => patchState(store, { runStatus: 'failed', runError: 'submit failed' }),
      });
    },

    applyProgress(frame: JobProgressFrame): void {
      if (frame.jobId !== store.runningJobId()) {
        return;
      }
      patchState(store, { runStatus: frame.status, runProgress: frame.progress });
      if (frame.status === 'completed') {
        this.loadResultsForJob(frame.jobId);
      } else if (frame.status === 'failed') {
        patchState(store, { runError: 'run failed' });
      }
    },

    /** Resolve the run id from the job, then fetch results (keyed by the run id / resultRef). */
    loadResultsForJob(jobId: string): void {
      patchState(store, { resultsLoading: true });
      http.get<{ resultRef?: string | null }>(`/api/v1/backtests/jobs/${jobId}`).subscribe({
        next: (job) => {
          const ref = job.resultRef ?? jobId;
          http.get<BacktestResults>(`/api/v1/backtests/${ref}/results`).subscribe({
            next: (results) => patchState(store, { results, resultsLoading: false }),
            error: () => patchState(store, { resultsLoading: false }),
          });
        },
        error: () => patchState(store, { resultsLoading: false }),
      });
    },

    // --- results page slice ---
    loadResults(id: string): void {
      patchState(store, { resultsLoading: true });
      http.get<BacktestResults>(`/api/v1/backtests/${id}/results`).subscribe({
        next: (viewResults) => patchState(store, { viewResults, resultsLoading: false }),
        error: () => patchState(store, { resultsLoading: false }),
      });
    },

    loadTrades(id: string): void {
      http.get<{ items: TradeRow[] }>(`/api/v1/backtests/${id}/trades`).subscribe({
        next: (page) => patchState(store, { trades: page.items ?? [] }),
        error: () => patchState(store, { trades: [] }),
      });
    },

    loadFolds(id: string): void {
      http.get<FoldRow[]>(`/api/v1/backtests/${id}/folds`).subscribe({
        next: (folds) => patchState(store, { folds: folds ?? [] }),
        error: () => patchState(store, { folds: [] }),
      });
    },

    loadMonteCarlo(id: string): void {
      http.get<MonteCarloSummary>(`/api/v1/backtests/${id}/montecarlo`).subscribe({
        next: (montecarlo) => patchState(store, { montecarlo }),
        error: () => patchState(store, { montecarlo: null }),
      });
    },

    /** Load up to 6 runs' results for the comparison matrix. */
    loadCompare(ids: string[]): void {
      patchState(store, { compareResults: [] });
      for (const id of ids.slice(0, 6)) {
        http.get<BacktestResults>(`/api/v1/backtests/${id}/results`).subscribe({
          next: (results) =>
            patchState(store, { compareResults: [...store.compareResults(), { id, results }] }),
          error: () => undefined,
        });
      }
    },

    /** Fire a full backtest from the runner; yields the jobId (the runner navigates to the monitor). */
    submitRun(req: BacktestRunRequest, then?: (jobId: string) => void): void {
      http.post<{ jobId: string }>('/api/v1/backtests/run', req).subscribe({
        next: (res) => then?.(res.jobId),
      });
    },

    /** Fire a sweep from the runner; yields the sweep jobId. */
    submitSweep(req: SweepRunRequest, then?: (jobId: string) => void): void {
      http.post<{ jobId: string }>('/api/v1/optimizations/run', req).subscribe({
        next: (res) => then?.(res.jobId),
      });
    },

    reset(): void {
      patchState(store, {
        runningJobId: null,
        runStatus: null,
        runProgress: 0,
        results: null,
        runError: null,
      });
    },
  })),
  withHooks({
    onInit(store) {
      const ws = inject(WsClientService);
      ws.topic(JOBS_TOPIC).subscribe((body) => {
        try {
          const frame = JSON.parse(body) as JobProgressFrame;
          if (frame.jobId === store.runningJobId()) {
            store.applyProgress(frame);
          }
        } catch {
          // unparseable frame — dropped
        }
      });
    },
  }),
);
