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
  caveats?: string[];
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
