import { inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { ConflationBuffer } from '../core/conflation';
import { WsClientService } from '../core/ws-client.service';
import type { FoldRow } from './backtests.store';
import type { JobStatus } from './jobs.store';

export type SortMode = 'plateau' | 'raw';

/** One leaderboard row (`GET /optimizations/{id}/best`). */
export interface BestRow {
  trialNumber: number;
  params: Record<string, unknown>;
  objective: number;
  objectiveValues?: Record<string, number> | null;
  backtestRunId?: string | null;
  plateauObjective?: number;
  neighborCount?: number;
}

export type TrialState = 'RUNNING' | 'COMPLETE' | 'PRUNED' | 'FAILED';

/** One trial (`GET /optimizations/{id}/trials`) — all states; pruned/failed are flagged, not hidden. */
export interface TrialRow {
  trialNumber: number;
  params: Record<string, unknown>;
  objectiveValues?: Record<string, number> | null;
  state: TrialState;
  backtestRunId?: string | null;
}

interface SweepProgressFrame {
  jobId: string;
  status: JobStatus;
  progress: number;
  trialsCompleted?: number;
  trialsTotal?: number;
  bestSoFar?: number | null;
}

interface OptState {
  sweepId: string | null;
  metric: string;
  sort: SortMode;
  best: BestRow[];
  trials: TrialRow[];
  trialFolds: FoldRow[];
  bestSoFar: number | null;
  trialsCompleted: number;
  trialsTotal: number;
  status: JobStatus | null;
}

const JOBS_TOPIC = '/topic/jobs/stream';

/**
 * OptimizationsStore (Phase 39): the sweep leaderboard (`/best`, plateau|raw sort), trials (all
 * states, for the explorer + flagging pruned/failed), trial fold drill-down, and promote. Live from
 * the sweep's `jobs.progress` frames — a frame updates best-so-far / counts and triggers a debounced
 * reload of trials + leaderboard (event-driven, no polling).
 */
export const OptimizationsStore = signalStore(
  { providedIn: 'root' },
  withState<OptState>({
    sweepId: null,
    metric: 'sharpe',
    sort: 'plateau',
    best: [],
    trials: [],
    trialFolds: [],
    bestSoFar: null,
    trialsCompleted: 0,
    trialsTotal: 0,
    status: null,
  }),
  withMethods((store, http = inject(HttpClient)) => ({
    loadBest(sweepId: string, sort: SortMode = store.sort()): void {
      patchState(store, { sweepId, sort });
      const params = new HttpParams().set('sort', sort).set('top', 50);
      http
        .get<{
          metric?: string;
          items: BestRow[];
        }>(`/api/v1/optimizations/${sweepId}/best`, { params })
        .subscribe({
          next: (res) =>
            patchState(store, { best: res.items ?? [], metric: res.metric ?? store.metric() }),
          error: () => undefined,
        });
    },

    loadTrials(sweepId: string): void {
      patchState(store, { sweepId });
      http.get<{ items: TrialRow[] }>(`/api/v1/optimizations/${sweepId}/trials`).subscribe({
        next: (res) => patchState(store, { trials: res.items ?? [] }),
        error: () => undefined,
      });
    },

    loadTrialFolds(sweepId: string, trialId: number): void {
      http.get<FoldRow[]>(`/api/v1/optimizations/${sweepId}/trials/${trialId}/folds`).subscribe({
        next: (folds) => patchState(store, { trialFolds: folds ?? [] }),
        error: () => patchState(store, { trialFolds: [] }),
      });
    },

    setSort(sort: SortMode): void {
      const id = store.sweepId();
      if (id) {
        this.loadBest(id, sort);
      }
    },

    /** Promote a trial → a new draft (minor bump) with provenance; yields the new version. */
    promote(sweepId: string, trialId: number, then?: (newVersion: string) => void): void {
      http
        .post<{
          newVersion: string;
          strategyId: string;
        }>(`/api/v1/optimizations/${sweepId}/promote`, { trialId })
        .subscribe({ next: (res) => then?.(res.newVersion) });
    },

    applyProgress(frame: SweepProgressFrame): void {
      patchState(store, {
        status: frame.status,
        bestSoFar: frame.bestSoFar ?? store.bestSoFar(),
        trialsCompleted: frame.trialsCompleted ?? store.trialsCompleted(),
        trialsTotal: frame.trialsTotal ?? store.trialsTotal(),
      });
    },
  })),
  withHooks({
    onInit(store) {
      const ws = inject(WsClientService);
      const buffer = new ConflationBuffer<true>(() => {
        const id = store.sweepId();
        if (id) {
          store.loadTrials(id);
          store.loadBest(id, store.sort());
        }
      });
      ws.topic(JOBS_TOPIC).subscribe((body) => {
        try {
          const frame = JSON.parse(body) as SweepProgressFrame;
          if (frame.jobId === store.sweepId()) {
            store.applyProgress(frame);
            buffer.set('reload', true);
          }
        } catch {
          // unparseable frame — dropped
        }
      });
    },
  }),
);
