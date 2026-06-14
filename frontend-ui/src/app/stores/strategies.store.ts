import { inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';

/** Strategy list-row summary (`GET /api/v1/strategies`). */
export interface StrategySummary {
  id: string;
  slug: string;
  name: string;
  currentVersion?: string;
  publishedVersion?: string | null;
  currentVersionId?: string | null;
  publishedVersionId?: string | null;
  status: string;
  tags: string[];
  author?: string;
  enabled?: boolean;
  updatedAt?: string;
  notificationsEnabled?: boolean;
  notificationChannel?: string | null;
}

/**
 * Latest-backtest summary for a strategy version (`GET /api/v1/backtests/summary`). `equity` is a
 * downsampled value series (decimal strings) for the list sparkline; metrics are decimal strings.
 */
export interface BacktestSummary {
  strategyVersionId: string;
  runId: string;
  sharpe: string | null;
  totalReturn: string | null;
  maxDrawdown: string | null;
  completedAt?: string;
  equity: string[];
}

/** Full strategy detail (`GET /api/v1/strategies/{id}`) — `configYaml` is the Monaco buffer source. */
export interface StrategyDetail {
  id: string;
  slug?: string;
  name: string;
  description?: string;
  tags?: string[];
  enabled?: boolean;
  version: string;
  status: string;
  config?: unknown;
  configYaml: string;
  checksum?: string;
  notes?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

/** One semantic validation issue (`POST /validate`): a JSON-pointer path + message. */
export interface ValidationIssue {
  path: string;
  message: string;
}
export interface ValidationResult {
  valid: boolean;
  errors: ValidationIssue[];
  warnings: ValidationIssue[];
}

export type VersionBump = 'patch' | 'minor' | 'major';

/** One row of the immutable version timeline (`GET /{id}/versions`). */
export interface VersionRow {
  version: string;
  status: string;
  checksum: string;
  author?: string;
  notes?: string | null;
  createdAt?: string;
}

/** One structured diff op (`GET /{id}/diff`): `path: before → after`. */
export interface DiffOp {
  path: string;
  op: 'add' | 'change' | 'remove';
  before: string | null;
  after: string | null;
}
export interface DiffResult {
  structured: DiffOp[];
  yamlFrom: string;
  yamlTo: string;
}

/** The Phase-44 resolved universe label (`index_constituents` / `futures_of_underlying`). */
export interface UniverseInfo {
  mode: string;
  asOf: string | null;
  constituentCount: number;
  checksum: string;
  survivorshipCaveat: string | null;
}

interface StrategiesState {
  list: StrategySummary[];
  summaries: Record<string, BacktestSummary>;
  loading: boolean;
  q: string;
  statusFilter: string | null;
  current: StrategyDetail | null;
  draft: string;
  dirty: boolean;
  schema: object | null;
  validation: ValidationResult | null;
  validating: boolean;
  saving: boolean;
  lastSaved: { version: string; checksum?: string } | null;
  createdId: string | null;
  versions: VersionRow[];
  diff: DiffResult | null;
  universe: UniverseInfo | null;
}

/**
 * StrategiesStore (E-1): the strategy list + the Monaco editor draft buffer (dirty flag, server
 * validation markers, the `strategy-schema/v1` JSON Schema). Server `POST /validate` is the
 * authoritative semantic check (E-8); monaco-yaml provides the inline schema squiggles.
 */
export const StrategiesStore = signalStore(
  { providedIn: 'root' },
  withState<StrategiesState>({
    list: [],
    summaries: {},
    loading: false,
    q: '',
    statusFilter: null,
    current: null,
    draft: '',
    dirty: false,
    schema: null,
    validation: null,
    validating: false,
    saving: false,
    lastSaved: null,
    createdId: null,
    versions: [],
    diff: null,
    universe: null,
  }),
  withMethods((store, http = inject(HttpClient)) => ({
    loadList(): void {
      patchState(store, { loading: true });
      let params = new HttpParams();
      if (store.q()) {
        params = params.set('q', store.q());
      }
      if (store.statusFilter()) {
        params = params.set('status', store.statusFilter()!);
      }
      http.get<{ items: StrategySummary[] }>('/api/v1/strategies', { params }).subscribe({
        next: (page) => {
          patchState(store, { list: page.items ?? [], loading: false });
          this.loadSummaries();
        },
        error: () => patchState(store, { loading: false }),
      });
    },

    /**
     * Enrich the list with each strategy's last-backtest summary (Stage F follow-on). Joins the
     * backtest schema client-side: collect the published-or-current version UUID per row, ask
     * backtest-service for the latest run per version, and key the result map by version id. Best
     * effort — a failure leaves the list rendering without the summary column.
     */
    loadSummaries(): void {
      const versionIds = [
        ...new Set(
          store
            .list()
            .map((s) => s.publishedVersionId ?? s.currentVersionId)
            .filter((v): v is string => !!v),
        ),
      ];
      if (versionIds.length === 0) {
        patchState(store, { summaries: {} });
        return;
      }
      const params = new HttpParams().set('strategyVersionIds', versionIds.join(','));
      http.get<{ items: BacktestSummary[] }>('/api/v1/backtests/summary', { params }).subscribe({
        next: (page) => {
          const map: Record<string, BacktestSummary> = {};
          for (const item of page.items ?? []) {
            map[item.strategyVersionId] = item;
          }
          patchState(store, { summaries: map });
        },
        error: () => patchState(store, { summaries: {} }),
      });
    },

    setQuery(q: string): void {
      patchState(store, { q });
      this.loadList();
    },

    setStatusFilter(status: string | null): void {
      patchState(store, { statusFilter: status });
      this.loadList();
    },

    /** Load a strategy into the editor: seeds the draft buffer from `configYaml`, clean. */
    loadDetail(id: string): void {
      http.get<StrategyDetail>(`/api/v1/strategies/${id}`).subscribe({
        next: (detail) =>
          patchState(store, {
            current: detail,
            draft: detail.configYaml ?? '',
            dirty: false,
            validation: null,
            lastSaved: null,
          }),
        error: () => undefined,
      });
      this.loadUniverse(id);
    },

    /** Resolve the strategy's pinned universe (Phase 44 editor label); null for explicit universes. */
    loadUniverse(id: string): void {
      http.get<UniverseInfo>(`/api/v1/strategies/${id}/universe`).subscribe({
        next: (universe) =>
          patchState(store, { universe: universe.mode === 'explicit' ? null : universe }),
        error: () => patchState(store, { universe: null }),
      });
    },

    loadSchema(): void {
      http.get<object>('/api/v1/strategies/schema/v1').subscribe({
        next: (schema) => patchState(store, { schema }),
        error: () => undefined,
      });
    },

    /** A new draft template for "Create strategy" (no id yet). */
    startNewDraft(template: string): void {
      patchState(store, {
        current: null,
        draft: template,
        dirty: false,
        validation: null,
        lastSaved: null,
        createdId: null,
      });
    },

    setDraft(yaml: string): void {
      patchState(store, { draft: yaml, dirty: true });
    },

    /** Server-authoritative validation of the current draft (debounced by the caller). */
    validate(): void {
      patchState(store, { validating: true });
      http
        .post<ValidationResult>('/api/v1/strategies/validate', { config: store.draft() })
        .subscribe({
          next: (result) => patchState(store, { validation: result, validating: false }),
          error: () => patchState(store, { validating: false }),
        });
    },

    /** Persist an existing strategy's draft (auto-bumped version). */
    saveDraft(id: string, versionBump: VersionBump, notes?: string): void {
      patchState(store, { saving: true });
      http
        .put<{ id: string; version: string; checksum?: string }>(`/api/v1/strategies/${id}`, {
          config: store.draft(),
          versionBump,
          notes,
        })
        .subscribe({
          next: (res) =>
            patchState(store, {
              saving: false,
              dirty: false,
              lastSaved: { version: res.version, checksum: res.checksum },
              current: store.current()
                ? { ...store.current()!, version: res.version, checksum: res.checksum }
                : store.current(),
            }),
          error: () => patchState(store, { saving: false }),
        });
    },

    /** Toggle notification opt-in (Phase 41) — never mints a version; updates the list row. */
    setNotifications(id: string, enabled: boolean, channel: string | null): void {
      http
        .patch<{
          notificationsEnabled: boolean;
          notificationChannel: string | null;
        }>(`/api/v1/strategies/${id}/notifications`, { enabled, channel })
        .subscribe({
          next: (res) =>
            patchState(store, {
              list: store.list().map((s) =>
                s.id === id
                  ? {
                      ...s,
                      notificationsEnabled: res.notificationsEnabled,
                      notificationChannel: res.notificationChannel,
                    }
                  : s,
              ),
            }),
        });
    },

    /** Fire a test push (422 toasts via the interceptor when disabled/unconfigured). */
    testNotification(id: string, then?: () => void): void {
      http
        .post(`/api/v1/strategies/${id}/notifications/test`, {})
        .subscribe({ next: () => then?.() });
    },

    loadVersions(id: string): void {
      http.get<{ items: VersionRow[] }>(`/api/v1/strategies/${id}/versions`).subscribe({
        next: (page) => patchState(store, { versions: page.items ?? [] }),
        error: () => undefined,
      });
    },

    loadDiff(id: string, from: string, to: string): void {
      const params = new HttpParams().set('from', from).set('to', to);
      http.get<DiffResult>(`/api/v1/strategies/${id}/diff`, { params }).subscribe({
        next: (diff) => patchState(store, { diff }),
        error: () => patchState(store, { diff: null }),
      });
    },

    /** Publish a draft (advisory stress state never blocks — E-13); yields the published version. */
    publish(
      id: string,
      body: { targetVersion?: string; notes?: string },
      then?: (version: string) => void,
    ): void {
      http
        .post<{ version: string; status: string }>(`/api/v1/strategies/${id}/publish`, body)
        .subscribe({
          next: (res) => {
            this.loadVersions(id);
            then?.(res.version);
          },
        });
    },

    /** Roll back to a prior version (copy-forward draft); yields the new draft version. */
    rollback(
      id: string,
      body: { version: string; andPublish?: boolean },
      then?: (newVersion: string) => void,
    ): void {
      http
        .post<{
          newVersion: string;
          copiedFrom: string;
          status: string;
        }>(`/api/v1/strategies/${id}/rollback`, body)
        .subscribe({
          next: (res) => {
            this.loadVersions(id);
            then?.(res.newVersion);
          },
        });
    },

    /** Create a new strategy from the draft; yields the new id to the caller (to navigate). */
    create(
      req: { name: string; config: string; tags?: string[] },
      then?: (id: string) => void,
    ): void {
      patchState(store, { saving: true });
      http
        .post<{ id: string; version: string; checksum?: string }>('/api/v1/strategies', req)
        .subscribe({
          next: (res) => {
            patchState(store, {
              saving: false,
              dirty: false,
              createdId: res.id,
              lastSaved: { version: res.version, checksum: res.checksum },
            });
            then?.(res.id);
          },
          error: () => patchState(store, { saving: false }),
        });
    },
  })),
);
