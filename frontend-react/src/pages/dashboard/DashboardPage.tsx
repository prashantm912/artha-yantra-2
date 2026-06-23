import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { useSignals } from '../../api/signals.ts';
import { usePaperAccount, usePaperPnl, usePaperPositions } from '../../api/paper.ts';
import { JOB_ACTIVE_STATES, useRecentJobs, useSystemStatus } from '../../api/dashboard.ts';

// /dashboard cockpit page (master plan §20 parity, E-2): the at-a-glance grid — system/market/kite
// status strip, the newest live signals, the paper-P&L tile and the in-flight jobs. Reuses the
// signals + paper hooks; the full widget-shell (collapse + persisted layout) is a later polish.

const money = (v: string) => formatDecimal(v, 2);
const toneClass = (v: string) => (isNegative(v) ? 'text-bear' : 'text-bull');

function Pill({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <span className="rounded border border-ay-border bg-surface-1 px-2 py-1 text-xs">
      <span className="text-ay-muted">{label} </span>
      <span className={cn('font-semibold', tone)}>{value}</span>
    </span>
  );
}

function Card({ title, to, children }: { title: string; to?: string; children: React.ReactNode }) {
  return (
    <section className="rounded-lg border border-ay-border bg-surface-1 p-3">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-ay-text">{title}</h2>
        {to && (
          <Link to={to} className="text-xs text-accent hover:underline">
            Open →
          </Link>
        )}
      </div>
      {children}
    </section>
  );
}

const phaseTone: Record<string, string> = {
  OPEN: 'text-bull',
  PRE_OPEN: 'text-warn',
  CLOSED: 'text-ay-muted',
};

export function DashboardPage() {
  const status = useSystemStatus();
  const activeSignals = useSignals('ACTIVE');
  const account = usePaperAccount();
  const pnl = usePaperPnl();
  const positions = usePaperPositions();
  const jobs = useRecentJobs();

  const recentSignals = useMemo(() => (activeSignals.data?.items ?? []).slice(0, 7), [activeSignals.data]);
  const activeJobs = useMemo(
    () => (jobs.data?.items ?? []).filter((j) => JOB_ACTIVE_STATES.includes(j.status)),
    [jobs.data],
  );

  const s = status.data;
  const summary = pnl.data?.summary ?? null;

  return (
    <div>
      <h1 className="ay-sr-only">Dashboard</h1>

      {s && (
        <section className="mb-4 flex flex-wrap items-center gap-2">
          <Pill
            label="System"
            value={s.overall}
            tone={s.overall === 'UP' ? 'text-bull' : 'text-warn'}
          />
          <Pill label="Market" value={s.market.phase} tone={phaseTone[s.market.phase] ?? 'text-ay-text'} />
          <Pill
            label="Kite"
            value={s.kite.session}
            tone={s.kite.session === 'VALID' ? 'text-bull' : 'text-warn'}
          />
          <Pill label="Ticker" value={s.kite.ticker} />
          <Pill label="Jobs" value={`${s.jobs.running} running · ${s.jobs.queued} queued`} />
          <span className="ml-auto text-xs text-ay-muted">as of {s.asOf.slice(11, 19)}</span>
        </section>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card title="Active Signals" to="/signals">
          {recentSignals.length > 0 ? (
            <ul className="flex flex-col gap-1">
              {recentSignals.map((sig) => (
                <li key={sig.id} className="grid grid-cols-[3.2rem_1fr_auto] items-center gap-2 text-sm">
                  <span className="tabular-nums text-ay-muted">{sig.generatedAt.slice(11, 16)}</span>
                  <span className="truncate">{sig.tradingsymbol}</span>
                  <span className={cn('text-xs font-semibold', sig.signalType === 'ENTRY' ? 'text-bull' : 'text-warn')}>
                    {sig.signalType} {sig.side}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-ay-muted">No live signals yet — publish a strategy.</p>
          )}
        </Card>

        <Card title="Paper P&L" to="/paper">
          {summary ? (
            <>
              <div className={cn('mb-2 text-2xl font-bold tabular-nums', toneClass(summary.realizedTotal))}>
                {money(summary.realizedTotal)}
              </div>
              <dl className="grid grid-cols-2 gap-y-1 text-sm">
                <dt className="text-ay-muted">Day P&L</dt>
                <dd className={cn('text-right tabular-nums', account.data && toneClass(account.data.dayPnl))}>
                  {account.data ? money(account.data.dayPnl) : '—'}
                </dd>
                <dt className="text-ay-muted">Closed trades</dt>
                <dd className="text-right tabular-nums">{summary.trades}</dd>
                <dt className="text-ay-muted">Open positions</dt>
                <dd className="text-right tabular-nums">{positions.data?.items.length ?? 0}</dd>
                <dt className="text-ay-muted">Win rate</dt>
                <dd className="text-right tabular-nums">
                  {summary.winRate ? `${formatDecimal(summary.winRate, 4)}` : '—'}
                </dd>
              </dl>
            </>
          ) : (
            <p className="text-sm text-ay-muted">No paper activity yet.</p>
          )}
        </Card>

        <Card title="Jobs">
          {activeJobs.length > 0 ? (
            <ul className="flex flex-col gap-2">
              {activeJobs.map((j) => (
                <li key={j.jobId} className="text-sm">
                  <div className="flex items-center gap-2">
                    <span className="rounded bg-surface-2 px-1.5 py-0.5 text-xs text-ay-muted">
                      {j.kind ?? 'job'}
                    </span>
                    <span className="tabular-nums">{j.jobId.slice(0, 8)}</span>
                    <span className="text-xs text-accent">{j.status}</span>
                  </div>
                  <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-surface-2">
                    <div className="h-full bg-accent" style={{ width: `${Math.round(j.progress)}%` }} />
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-ay-muted">No jobs running.</p>
          )}
        </Card>
      </div>
    </div>
  );
}
