import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import {
  Activity,
  ArrowDownRight,
  ArrowUpRight,
  CalendarClock,
  Cog,
  HeartPulse,
  KeyRound,
  Radar,
  Radio,
  Wallet,
  type LucideIcon,
} from 'lucide-react';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { useSignals } from '../../api/signals.ts';
import { usePaperAccount, usePaperPnl, usePaperPositions } from '../../api/paper.ts';
import { JOB_ACTIVE_STATES, useRecentJobs, useSystemStatus } from '../../api/dashboard.ts';
import { useDataHealth } from '../../api/health.ts';
import { PageHeader, LiveDot } from '../../components/PageHeader.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { FocusPanel } from '../../components/insights/FocusPanel.tsx';

// /dashboard cockpit page (master plan §20 parity, E-2): the at-a-glance grid — system/market/kite
// status strip, the newest live signals, the paper-P&L tile and the in-flight jobs. Reuses the
// signals + paper hooks; the full widget-shell (collapse + persisted layout) is a later polish.
// Revamp Phase 5 (§4.1): visible title + signature lockup, elevated status/KPI tiles (realized P&L
// hoisted into the KPI strip — rendered in exactly ONE node), lucide-iconed section cards, a real
// role="progressbar", and cold-load card skeletons in the same grid.

const money = (v: string) => formatDecimal(v, 2);
const toneClass = (v: string) => (isNegative(v) ? 'text-bear' : 'text-bull');

function StatusTile({ icon: Icon, label, value, tone }: { icon: LucideIcon; label: string; value: string; tone?: string }) {
  return (
    <div className="card shadow-e1 flex items-center gap-2.5">
      <Icon aria-hidden="true" className="size-4 shrink-0 text-ay-muted" />
      <div className="min-w-0">
        <div className="text-caption uppercase tracking-wide text-ay-muted">{label}</div>
        <div className={cn('nums font-semibold leading-tight', tone)}>{value}</div>
      </div>
    </div>
  );
}

function Kpi({ label, value, sub, tone, arrow }: { label: string; value: string; sub?: string; tone?: string; arrow?: 'up' | 'down' }) {
  const Arrow = arrow === 'up' ? ArrowUpRight : arrow === 'down' ? ArrowDownRight : null;
  return (
    <div className="card shadow-e1">
      <div className="text-caption uppercase tracking-wide text-ay-muted">{label}</div>
      <div className={cn('mt-1 flex items-center gap-1', tone)}>
        {Arrow && <Arrow aria-hidden="true" className="size-4" />}
        <span className="text-h3 nums font-semibold">{value}</span>
      </div>
      {sub && <div className="mt-0.5 text-caption text-ay-muted">{sub}</div>}
    </div>
  );
}

function SectionCard({ icon: Icon, title, to, children }: { icon: LucideIcon; title: string; to?: string; children: React.ReactNode }) {
  return (
    <section className="card shadow-e1">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="flex items-center gap-1.5 text-h3 text-ay-text">
          <Icon aria-hidden="true" className="size-4 text-ay-muted" />
          {title}
        </h2>
        {to && (
          <Link to={to} className="text-caption text-accent hover:underline">
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
  const dataHealth = useDataHealth();
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
  const health = dataHealth.data;
  const healthValue = !health
    ? '—'
    : !health.marketOpen
      ? 'CLOSED'
      : health.status === 'GREEN'
        ? `OK · ${health.tickedTokens} live`
        : `${health.status} (${health.problems.length})`;
  const healthTone = !health || !health.marketOpen
    ? 'text-ay-muted'
    : health.status === 'GREEN'
      ? 'text-bull'
      : health.status === 'AMBER'
        ? 'text-warn'
        : 'text-bear';
  const healthDetail = health?.problems.map((p) => `${p.check} ${p.key}: ${p.detail}`).join('\n');

  return (
    <LoadBeat>
      <PageHeader
        title="Dashboard"
        subtitle="Cockpit — system health, signals, paper P&L and in-flight jobs"
        help="A one-glance cockpit: system/market/broker status, the newest live signals, your paper P&L and any running jobs."
        right={<LiveDot detail={s ? `${s.asOf.slice(11, 19)} IST` : undefined} />}
      />

      {/* B. Elevated status strip */}
      {s ? (
        <BeatStrip
          data-testid="dashboard-status"
          className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6"
        >
          <BeatItem>
            <StatusTile icon={Activity} label="System" value={s.overall} tone={s.overall === 'UP' ? 'text-bull' : 'text-warn'} />
          </BeatItem>
          <BeatItem>
            <div title={healthDetail || undefined}>
              <StatusTile icon={HeartPulse} label="Data health" value={healthValue} tone={healthTone} />
            </div>
          </BeatItem>
          <BeatItem>
            <StatusTile icon={CalendarClock} label="Market" value={s.market.phase} tone={phaseTone[s.market.phase] ?? 'text-ay-text'} />
          </BeatItem>
          <BeatItem>
            <StatusTile icon={KeyRound} label="Kite" value={s.kite.session} tone={s.kite.session === 'VALID' ? 'text-bull' : 'text-warn'} />
          </BeatItem>
          <BeatItem>
            <StatusTile icon={Radio} label="Ticker" value={s.kite.ticker} />
          </BeatItem>
          <BeatItem>
            <StatusTile icon={Cog} label="Jobs" value={`${s.jobs.running} running · ${s.jobs.queued} queued`} />
          </BeatItem>
        </BeatStrip>
      ) : (
        <Skeleton variant="metric-strip" cols={5} className="mb-4" />
      )}

      {/* C. KPI strip — Paper P&L hero numbers (Realized rendered HERE, in exactly one node) */}
      {summary ? (
        <BeatStrip data-testid="dashboard-kpis" className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
          <BeatItem>
            <Kpi
              label="Realized P&L"
              value={money(summary.realizedTotal)}
              sub="₹ · all sessions"
              tone={toneClass(summary.realizedTotal)}
              arrow={isNegative(summary.realizedTotal) ? 'down' : 'up'}
            />
          </BeatItem>
          <BeatItem>
            <Kpi
              label="Day P&L"
              value={account.data ? money(account.data.dayPnl) : '—'}
              sub="₹ · today"
              tone={account.data ? toneClass(account.data.dayPnl) : undefined}
              arrow={account.data ? (isNegative(account.data.dayPnl) ? 'down' : 'up') : undefined}
            />
          </BeatItem>
          <BeatItem>
            <Kpi
              label="Open / Closed"
              value={`${positions.data?.items.length ?? 0} / ${summary.trades}`}
              sub="positions / trades"
            />
          </BeatItem>
          <BeatItem>
            <Kpi
              label="Win rate"
              value={summary.winRate ? formatDecimal(summary.winRate, 4) : '—'}
              sub="closed trades"
            />
          </BeatItem>
        </BeatStrip>
      ) : (
        <Skeleton variant="metric-strip" cols={4} className="mb-4" />
      )}

      {/* D. Focus panel (INT-I1, shadow mode) — an additive preview of the priority-ranked decision
          surface that will replace the passive "Active Signals" card below once the layer is trusted
          (§10.3 Stage 0). Both render for now. */}
      <div className="mb-4">
        <FocusPanel />
      </div>

      {/* E. Section cards */}
      <BeatBlock className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <SectionCard icon={Radar} title="Active Signals" to="/signals">
          {recentSignals.length > 0 ? (
            <ul className="flex flex-col gap-1">
              {recentSignals.map((sig) => (
                <li key={sig.id} className="grid grid-cols-[3.2rem_1fr_auto] items-center gap-2 text-body-sm">
                  <span className="nums text-caption text-ay-muted">{sig.generatedAt.slice(11, 16)}</span>
                  <span className="truncate">{sig.tradingsymbol}</span>
                  <span className={cn('text-caption font-semibold', sig.signalType === 'ENTRY' ? 'text-bull' : 'text-warn')}>
                    {sig.signalType} {sig.side}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-body-sm text-ay-muted">No live signals yet — publish a strategy.</p>
          )}
        </SectionCard>

        <SectionCard icon={Wallet} title="Paper P&L" to="/paper">
          {summary ? (
            <dl className="grid grid-cols-2 gap-y-1 text-body-sm">
              <dt className="text-ay-muted">Win rate</dt>
              <dd className="nums text-right">{summary.winRate ? formatDecimal(summary.winRate, 4) : '—'}</dd>
              <dt className="text-ay-muted">Closed trades</dt>
              <dd className="nums text-right">{summary.trades}</dd>
              <dt className="text-ay-muted">Open positions</dt>
              <dd className="nums text-right">{positions.data?.items.length ?? 0}</dd>
            </dl>
          ) : (
            <p className="text-body-sm text-ay-muted">No paper activity yet.</p>
          )}
        </SectionCard>

        <SectionCard icon={Cog} title="Jobs">
          {activeJobs.length > 0 ? (
            <ul className="flex flex-col gap-2">
              {activeJobs.map((j) => (
                <li key={j.jobId} className="text-body-sm">
                  <div className="flex items-center gap-2">
                    <span className="rounded-sm bg-surface-2 px-1.5 py-0.5 text-caption text-ay-muted">
                      {j.kind ?? 'job'}
                    </span>
                    <span className="nums">{j.jobId.slice(0, 8)}</span>
                    <span className="text-caption text-accent">{j.status}</span>
                  </div>
                  <div
                    role="progressbar"
                    aria-label={`${j.kind ?? 'job'} ${j.jobId.slice(0, 8)} progress`}
                    aria-valuenow={Math.round(j.progress)}
                    aria-valuemin={0}
                    aria-valuemax={100}
                    className="mt-1 h-1.5 overflow-hidden rounded-full bg-surface-2"
                  >
                    <div
                      className="h-full bg-accent transition-[width] duration-[var(--duration-base)] ease-[var(--ease-standard)]"
                      style={{ width: `${Math.round(j.progress)}%` }}
                    />
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-body-sm text-ay-muted">No jobs running.</p>
          )}
        </SectionCard>
      </BeatBlock>
    </LoadBeat>
  );
}
