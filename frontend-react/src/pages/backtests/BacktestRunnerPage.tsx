import { useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { m } from 'motion/react';
import { cn } from '../../lib/cn.ts';
import { Button } from '../../components/atoms/Button.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { useStrategies, useStrategyVersions } from '../../api/strategies.ts';
import {
  DIRECTIONS,
  FOLD_AGGREGATIONS,
  INTERVALS,
  OBJECTIVE_METRICS,
  SWEEP_METHODS,
  useSubmitRun,
  useSubmitSweep,
  type RunRequest,
  type SweepRequest,
} from '../../api/backtests.ts';

// /backtests/run (master plan §20 parity, E-8): the runner (full-parameter backtest) and the sweep
// launcher (method / max-trials / objective incl. fold aggregation / constraints) → 202 {jobId} → the
// jobs monitor. On the mock stack, windowed runs need benchmark history (see the guide).

const dayISO = (offsetDays = 0) => new Date(Date.now() - offsetDays * 86_400_000).toISOString().slice(0, 10);
const dateInput = (value?: string) => value?.slice(0, 10);

type PendingSubmission =
  | { kind: 'backtest'; request: RunRequest }
  | { kind: 'sweep'; request: SweepRequest };

interface RunnerLocationState {
  clone?: Partial<RunRequest>;
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-ay-muted">{label}</span>
      {children}
    </label>
  );
}

const numberCls = 'h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text';

function ConfirmRunDialog({
  submission,
  strategyName,
  isPending,
  onCancel,
  onConfirm,
  returnFocusTo,
}: {
  submission: PendingSubmission;
  strategyName: string;
  isPending: boolean;
  onCancel: () => void;
  onConfirm: () => void;
  returnFocusTo: HTMLElement | null;
}) {
  const request = submission.request;
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onCancel(); }}>
      <DialogContent
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          returnFocusTo?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>Confirm {submission.kind}</DialogTitle>
          <DialogDescription>Review the inputs before this research job is submitted.</DialogDescription>
        </DialogHeader>
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 rounded border border-ay-border bg-surface-1 p-3 text-sm">
          <dt className="text-ay-muted">Strategy</dt>
          <dd>{strategyName}</dd>
          <dt className="text-ay-muted">Window</dt>
          <dd className="tabular-nums">{request.from.slice(0, 10)} to {request.to.slice(0, 10)}</dd>
          <dt className="text-ay-muted">Interval</dt>
          <dd>{request.interval}</dd>
          <dt className="text-ay-muted">Initial capital</dt>
          <dd className="tabular-nums">{request.initialCapital}</dd>
          <dt className="text-ay-muted">Seed</dt>
          <dd className="tabular-nums">{request.seed}</dd>
          {submission.kind === 'sweep' && (
            <>
              <dt className="text-ay-muted">Method</dt>
              <dd>{submission.request.method}</dd>
              <dt className="text-ay-muted">Max trials</dt>
              <dd className="tabular-nums">{submission.request.maxTrials}</dd>
              <dt className="text-ay-muted">Objective</dt>
              <dd>
                {submission.request.objective.metric} / {submission.request.objective.direction} /{' '}
                {submission.request.objective.fold_aggregation}
              </dd>
            </>
          )}
        </dl>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onCancel}>Cancel</Button>
          <Button type="button" onClick={onConfirm} loading={isPending}>Confirm &amp; run</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function BacktestRunnerPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const clone = (location.state as RunnerLocationState | null)?.clone;
  const strategies = useStrategies('', null);
  const submitRun = useSubmitRun();
  const submitSweep = useSubmitSweep();
  const confirmTriggerRef = useRef<HTMLElement | null>(null);

  const [tab, setTab] = useState<'backtest' | 'sweep'>('backtest');
  const [strategyId, setStrategyId] = useState<string | null>(clone?.strategyId ?? null);
  // '' = latest (the backend pins latest published, else latest draft); a version string runs that version.
  const [strategyVersion, setStrategyVersion] = useState(clone?.strategyVersion ?? '');
  const [interval, setInterval] = useState(clone?.interval ?? '1d');
  const [from, setFrom] = useState(dateInput(clone?.from) ?? dayISO(60));
  const [to, setTo] = useState(dateInput(clone?.to) ?? dayISO(0));
  const [capital, setCapital] = useState(clone?.initialCapital ?? '100000');
  const [seed, setSeed] = useState(String(clone?.seed ?? 42));
  const [pendingSubmission, setPendingSubmission] = useState<PendingSubmission | null>(null);
  // sweep-only
  const [method, setMethod] = useState('tpe');
  const [maxTrials, setMaxTrials] = useState('30');
  const [objMetric, setObjMetric] = useState('sharpe');
  const [direction, setDirection] = useState('maximize');
  const [foldAgg, setFoldAgg] = useState('mean');
  const [minTrades, setMinTrades] = useState('30');

  const strategyOptions = useMemo(
    () => (strategies.data?.items ?? []).map((s) => ({ value: s.id, label: s.name })),
    [strategies.data],
  );

  // Version dropdown for the selected strategy: "Latest" (omit the version) + every minted version.
  const versions = useStrategyVersions(strategyId ?? '');
  const versionOptions = useMemo(
    () => [
      { value: '', label: 'Latest (default)' },
      ...(versions.data?.items ?? []).map((v) => ({ value: v.version, label: `v${v.version} · ${v.status}` })),
    ],
    [versions.data],
  );

  const iso = (d: string) => new Date(`${d}T00:00:00`).toISOString();
  const versionField = strategyVersion ? { strategyVersion } : {};

  const runBacktest = () => {
    if (!strategyId) return;
    setPendingSubmission({
      kind: 'backtest',
      request: { strategyId, ...versionField, from: iso(from), to: iso(to), interval, initialCapital: capital, seed: Number(seed) },
    });
  };

  const launchSweep = () => {
    if (!strategyId) return;
    setPendingSubmission({
      kind: 'sweep',
      request: {
        strategyId,
        ...versionField,
        from: iso(from),
        to: iso(to),
        interval,
        initialCapital: capital,
        method,
        maxTrials: Number(maxTrials),
        objective: { metric: objMetric, direction, fold_aggregation: foldAgg },
        constraints: { min_trades: Number(minTrades) },
        seed: Number(seed),
      },
    });
  };

  const confirmSubmission = () => {
    if (!pendingSubmission) return;
    const submission = pendingSubmission;
    setPendingSubmission(null);
    if (submission.kind === 'backtest') {
      submitRun.mutate(submission.request, { onSuccess: () => navigate('/backtests/jobs') });
    } else {
      submitSweep.mutate(submission.request, { onSuccess: () => navigate('/backtests/jobs') });
    }
  };

  const selectedStrategyName =
    strategyOptions.find((option) => option.value === strategyId)?.label ?? strategyId ?? 'Unknown strategy';

  return (
    <LoadBeat>
      <PageHeader title="Backtest runner" subtitle="Run a full-parameter backtest or launch a parameter sweep" help="Run a strategy over a past window to see how it would have performed, or launch a sweep that tries many parameter sets to find the best." />
      <div role="tablist" className="mb-4 flex gap-1 border-b border-ay-border">
        {(['backtest', 'sweep'] as const).map((t) => (
          <button
            key={t}
            type="button"
            role="tab"
            aria-selected={tab === t}
            onClick={() => setTab(t)}
            className={cn(
              '-mb-px border-b-2 px-4 py-2 text-sm font-medium capitalize',
              tab === t ? 'border-accent text-accent' : 'border-transparent text-ay-muted hover:text-ay-text',
            )}
          >
            {t}
          </button>
        ))}
      </div>

      <m.div key={tab} initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.15 }}>
      <BeatBlock className="grid max-w-2xl grid-cols-1 gap-3 sm:grid-cols-2">
        <Field label="Strategy">
          <Select
            value={strategyId}
            options={strategyOptions}
            onChange={(v) => {
              setStrategyId(v);
              setStrategyVersion(''); // a stale version string would not exist on the new strategy
            }}
            ariaLabel="Strategy"
            placeholder="Select strategy"
            title="The strategy to backtest (its latest published, else latest draft, version is used)."
          />
        </Field>
        <Field label="Version">
          <Select
            value={strategyVersion}
            options={versionOptions}
            onChange={setStrategyVersion}
            ariaLabel="Version"
            title="Which version to run. Latest (default) pins the latest published, else latest draft; pick a specific version to backtest an older one."
          />
        </Field>
        <Field label="Interval">
          <Select value={interval} options={[...INTERVALS]} onChange={setInterval} ariaLabel="Interval" title="The candle timeframe the strategy runs on (e.g. 1m, 5m, 1d)." />
        </Field>
        <Field label="From">
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From" title="Start date of the backtest window." className={numberCls} />
        </Field>
        <Field label="To">
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="To" title="End date of the backtest window." className={numberCls} />
        </Field>
        <Field label="Initial capital (₹)">
          <input type="number" min={1000} value={capital} onChange={(e) => setCapital(e.target.value)} aria-label="Initial capital" title="Starting cash for the run; returns are measured against this base." className={numberCls} />
        </Field>
        <Field label="Seed">
          <input type="number" value={seed} onChange={(e) => setSeed(e.target.value)} aria-label="Seed" title="Random seed — fixing it makes the run reproducible (same inputs give the same result)." className={numberCls} />
        </Field>

        {tab === 'sweep' && (
          <>
            <Field label="Method">
              <Select value={method} options={[...SWEEP_METHODS]} onChange={setMethod} ariaLabel="Method" title="How the sweep searches the parameter space (e.g. TPE Bayesian search, random, grid)." />
            </Field>
            <Field label="Max trials">
              <input type="number" min={2} value={maxTrials} onChange={(e) => setMaxTrials(e.target.value)} aria-label="Max trials" title="How many parameter combinations the sweep will test — more trials search harder but take longer." className={numberCls} />
            </Field>
            <Field label="Objective metric">
              <Select value={objMetric} options={[...OBJECTIVE_METRICS]} onChange={setObjMetric} ariaLabel="Objective metric" title="The metric the sweep optimizes for (e.g. Sharpe, total return) when picking the best parameters." />
            </Field>
            <Field label="Direction">
              <Select value={direction} options={[...DIRECTIONS]} onChange={setDirection} ariaLabel="Direction" title="Whether to maximize or minimize the objective metric (maximize Sharpe, minimize drawdown)." />
            </Field>
            <Field label="Fold aggregation">
              <Select value={foldAgg} options={[...FOLD_AGGREGATIONS]} onChange={setFoldAgg} ariaLabel="Fold aggregation" title="How per-fold scores are combined into one number across walk-forward out-of-sample folds (e.g. mean)." />
            </Field>
            <Field label="Min trades">
              <input type="number" min={1} value={minTrades} onChange={(e) => setMinTrades(e.target.value)} aria-label="Min trades" title="A trial is rejected if it produced fewer than this many trades — guards against curve-fit results on too few trades." className={numberCls} />
            </Field>
          </>
        )}
      </BeatBlock>

      <div className="mt-4">
        {tab === 'backtest' ? (
          <button
            type="button"
            onClick={(event) => {
              confirmTriggerRef.current = event.currentTarget;
              runBacktest();
            }}
            disabled={!strategyId || submitRun.isPending}
            title="Submit the backtest and jump to the Jobs monitor to watch its progress."
            className="h-9 rounded-md bg-accent px-4 text-sm font-medium text-surface-0 hover:opacity-90 disabled:opacity-50"
          >
            ▶ Run backtest
          </button>
        ) : (
          <button
            type="button"
            onClick={(event) => {
              confirmTriggerRef.current = event.currentTarget;
              launchSweep();
            }}
            disabled={!strategyId || submitSweep.isPending}
            title="Submit the parameter sweep and jump to the Jobs monitor to watch its progress."
            className="h-9 rounded-md bg-accent px-4 text-sm font-medium text-surface-0 hover:opacity-90 disabled:opacity-50"
          >
            ⚙ Launch sweep
          </button>
        )}
      </div>
      </m.div>
      {pendingSubmission && (
        <ConfirmRunDialog
          submission={pendingSubmission}
          strategyName={selectedStrategyName}
          isPending={pendingSubmission.kind === 'backtest' ? submitRun.isPending : submitSweep.isPending}
          onCancel={() => setPendingSubmission(null)}
          onConfirm={confirmSubmission}
          returnFocusTo={confirmTriggerRef.current}
        />
      )}
    </LoadBeat>
  );
}
