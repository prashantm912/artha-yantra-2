import { computed, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { ConflationBuffer } from '../core/conflation';
import { WsClientService } from '../core/ws-client.service';

/** Job kinds the jobs table renders (backtest-service jobs spine). */
export type JobKind = 'BACKTEST' | 'OPTIMIZATION' | 'TRIAL';

/** Lifecycle states; `cancelling` is the interim view after a DELETE on a running job. */
export type JobStatus = 'queued' | 'running' | 'completed' | 'failed' | 'cancelled' | 'cancelling';

/** One job row as the REST summary delivers it (extra fields fill from progress frames). */
export interface JobDto {
  jobId: string;
  kind: JobKind;
  status: JobStatus;
  progress: number;
  createdAt: string;
  parentJobId?: string | null;
  resultRef?: string | null;
  error?: string | null;
  bestSoFar?: number | string | null;
}

/** The `jobs.progress` frame relayed on `/topic/jobs/*` (gateway folds every job topic onto one channel). */
export interface JobProgressFrame {
  jobId: string;
  status: JobStatus;
  progress: number;
  parentJobId?: string | null;
  bestSoFar?: number | string | null;
}

/**
 * Any `/topic/jobs/*` destination maps onto the single `jobs.progress` Redis channel at the
 * gateway, so one subscription receives every job's frames; we fan out by `jobId` client-side.
 */
const JOBS_TOPIC = '/topic/jobs/stream';

const LIVE: JobStatus[] = ['queued', 'running', 'cancelling'];

interface JobsState {
  jobs: JobDto[];
  loading: boolean;
  statusFilter: string | null;
  limit: number;
  offset: number;
}

/**
 * JobsStore (E-1): the Postgres-backed job list from `/api/v1/backtests/jobs` plus live progress
 * from the `jobs.progress` topic — NO polling (D12). A frame for an unknown job heals the list with
 * one reload (event-driven, not a poll loop); reconnect re-fetches the snapshot.
 */
export const JobsStore = signalStore(
  { providedIn: 'root' },
  withState<JobsState>({ jobs: [], loading: false, statusFilter: null, limit: 50, offset: 0 }),
  withComputed((store) => ({
    active: computed(() => store.jobs().filter((j) => LIVE.includes(j.status))),
  })),
  withMethods((store, http = inject(HttpClient)) => ({
    /** REST snapshot (also the reconnect + unknown-job gap-healer). */
    load(): void {
      patchState(store, { loading: true });
      let params = new HttpParams().set('limit', store.limit()).set('offset', store.offset());
      const status = store.statusFilter();
      if (status) {
        params = params.set('status', status);
      }
      http.get<{ items: JobDto[] }>('/api/v1/backtests/jobs', { params }).subscribe({
        next: (page) => patchState(store, { jobs: page.items ?? [], loading: false }),
        error: () => patchState(store, { loading: false }),
      });
    },

    /** Apply a flushed batch of progress frames; unknown jobIds trigger exactly one reload. */
    ingestProgress(frames: JobProgressFrame[]): void {
      if (frames.length === 0) {
        return;
      }
      const next = store.jobs().slice();
      const index = new Map(next.map((j, i) => [j.jobId, i]));
      let changed = false;
      let unknown = false;
      for (const f of frames) {
        const i = index.get(f.jobId);
        if (i === undefined) {
          unknown = true;
          continue;
        }
        next[i] = {
          ...next[i],
          status: f.status,
          progress: f.progress,
          ...(f.bestSoFar != null ? { bestSoFar: f.bestSoFar } : {}),
        };
        changed = true;
      }
      if (changed) {
        patchState(store, { jobs: next });
      }
      if (unknown) {
        this.load();
      }
    },

    setStatusFilter(status: string | null): void {
      patchState(store, { statusFilter: status, offset: 0 });
      this.load();
    },

    /** DELETE: 204 ⇒ cancelled (was queued); 202 {status:cancelling} ⇒ cancelling (was running). */
    cancel(jobId: string): void {
      http.delete(`/api/v1/backtests/jobs/${jobId}`, { observe: 'response' }).subscribe({
        next: (res) => {
          const status: JobStatus = res.status === 204 ? 'cancelled' : 'cancelling';
          patchState(store, {
            jobs: store.jobs().map((j) => (j.jobId === jobId ? { ...j, status } : j)),
          });
        },
      });
    },
  })),
  withHooks({
    onInit(store) {
      const ws = inject(WsClientService);
      const pending: JobProgressFrame[] = [];
      const buffer = new ConflationBuffer<true>(() => {
        store.ingestProgress([...pending]);
        pending.length = 0;
      });
      ws.topic(JOBS_TOPIC).subscribe((body) => {
        try {
          pending.push(JSON.parse(body) as JobProgressFrame);
          buffer.set('jobs', true);
        } catch {
          // unparseable frame — dropped, the next load() heals
        }
      });
      ws.reconnects$.subscribe(() => store.load());
      store.load();
    },
  }),
);
