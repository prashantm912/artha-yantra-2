import { useEffect, useMemo, useState } from 'react';
import { History } from 'lucide-react';
import { useChainHistory } from '../api/oiAnalytics.ts';
import { useSymbolContext } from '../stores/symbolContext.store.ts';
import { nearestStrike } from '../lib/strikes.ts';
import type { Density } from '../lib/density.ts';
import type { ChainHistoryRow, ChainTableLeg, ChainTableRow } from '../api/types.ts';
import { OptionsChainTable } from './OptionsChainTable.tsx';
import { QueryState } from './QueryState.tsx';
import { Skeleton } from './Skeletons.tsx';

// Chain time-travel (audit §6.8 / §2.4-2). In HISTORY mode a slider rewinds the session and renders the
// stored option chain AT that time off the existing /chain/history?at= endpoint (the backend snaps to the
// nearest captured 2-min snapshot). The raw snapshot carries no computed interval deltas, so the reused
// OptionsChainTable's ΔOI/interpretation columns honestly read "—"; OI / LTP / IV / greeks all render.
// Pre-capture or derived-history sessions have no stored snapshot → the hook 404s → honest empty state
// (real capture began 2026-06-15). Only mounted in history mode, so the live chain path is untouched.

// The regular NSE/BSE session is 09:15 → 15:30 IST = 555 min past midnight → 375 min wide.
const SESSION_OPEN_MIN = 555;
const SESSION_MINUTES = 375;

/** "HH:MM" from minutes-past-session-open (display + the ISO `at` the backend snaps on). */
function clockLabel(minutesFromOpen: number): string {
  const total = SESSION_OPEN_MIN + minutesFromOpen;
  const hh = String(Math.floor(total / 60)).padStart(2, '0');
  const mm = String(total % 60).padStart(2, '0');
  return `${hh}:${mm}`;
}

/** The IST ISO `at` for a session date + slider minute (backend parses an OffsetDateTime). */
function atIsoFor(date: string, minutesFromOpen: number): string {
  return `${date}T${clockLabel(minutesFromOpen)}:00+05:30`;
}

/** Folds the raw snapshot rows into the mirrored CALL | strike | PUT shape (no interval deltas). */
function toChainRows(rows: ChainHistoryRow[]): ChainTableRow[] {
  const byStrike = new Map<string, ChainTableRow>();
  for (const r of rows) {
    let row = byStrike.get(r.strike);
    if (!row) {
      row = { strike: r.strike, ce: null, pe: null };
      byStrike.set(r.strike, row);
    }
    const leg: ChainTableLeg = {
      leg: {
        // The captured-snapshot projection has no instrument row, so it carries no exchange — the
        // same null the backend's snapLeg emits. Time-travel is read-only, so nothing routes off it.
        exchange: null,
        tradingsymbol: r.tradingsymbol,
        ltp: r.ltp,
        bid: r.bid,
        ask: r.ask,
        volume: r.volume,
        oi: r.oi,
        iv: r.iv,
        delta: r.delta,
        gamma: r.gamma,
        theta: r.theta,
        vega: r.vega,
        rho: r.rho,
        ivReason: r.ivReason ?? '',
        priceSource: r.priceSource,
      },
      deltas: null, // the raw capture has no computed interval delta — the fold columns read "—"
    };
    if (r.optionType === 'CE') row.ce = leg;
    else row.pe = leg;
  }
  return [...byStrike.values()].sort((a, b) => Number(a.strike) - Number(b.strike));
}

/** Snapshot-time chain replay for the Options Chain page (history mode only). */
export function ChainTimeTravel({ density }: { density?: Density }) {
  const date = useSymbolContext((s) => s.date);

  // The slider drives the label live; the query `at` follows on a short debounce so a drag across the
  // session doesn't fire one fetch per pixel (each resolved snapshot is cached by TanStack key).
  const [minute, setMinute] = useState(SESSION_MINUTES); // default = session close → the last bucket
  const [committed, setCommitted] = useState(SESSION_MINUTES);
  useEffect(() => {
    const t = setTimeout(() => setCommitted(minute), 250);
    return () => clearTimeout(t);
  }, [minute]);

  const atIso = date ? atIsoFor(date, committed) : null;
  const q = useChainHistory(atIso, !!date);

  const snapshot = q.data ?? null;
  const rows = useMemo(() => (snapshot ? toChainRows(snapshot.rows) : []), [snapshot]);
  const spot = snapshot?.rows.find((r) => r.spotPrice != null)?.spotPrice ?? null;
  const atm = useMemo(() => nearestStrike(rows.map((r) => r.strike), spot), [rows, spot]);
  const resolved = snapshot?.ts ? snapshot.ts.slice(11, 19) : null;

  if (!date) {
    return (
      <p className="card shadow-e1 text-sm text-ay-muted">
        Pick a history date to travel through the session's captured chain snapshots.
      </p>
    );
  }

  return (
    <section className="card shadow-e1" aria-label="Chain time travel">
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-2 text-sm font-semibold text-ay-text">
          <History aria-hidden="true" className="size-4 text-ay-muted" />
          Time travel
        </div>
        <label className="flex flex-1 items-center gap-3 text-sm text-ay-text">
          <span className="tabular-nums text-ay-muted">09:15</span>
          <input
            type="range"
            min={0}
            max={SESSION_MINUTES}
            step={1}
            value={minute}
            onChange={(e) => setMinute(Number(e.target.value))}
            aria-valuetext={`${clockLabel(minute)} IST`}
            aria-label="Session time"
            title="Rewind the session — the chain snaps to the nearest captured snapshot"
            className="min-w-[8rem] flex-1 accent-[var(--ay-accent)]"
          />
          <span className="tabular-nums text-ay-muted">15:30</span>
        </label>
        <div className="text-sm tabular-nums text-ay-text" aria-live="polite">
          {clockLabel(minute)} IST
          {resolved && (
            <span className="ml-1 text-xs text-ay-muted">· snapshot {resolved}</span>
          )}
        </div>
      </div>

      <QueryState
        query={q}
        isEmpty={() => snapshot == null || rows.length === 0}
        empty={{
          icon: History,
          title: `No captured snapshot near ${clockLabel(minute)} — chain time-travel needs live-captured history (from 2026-06-15).`,
        }}
        errorTitle="Couldn't load the historical chain"
        skeleton={<Skeleton variant="table-rows" rows={14} cols={9} />}
      >
        {() => (
          <OptionsChainTable
            rows={rows}
            spot={spot}
            atmStrike={atm}
            optionalKeys={[]}
            density={density}
            linkStrikeToChart
            emptyMessage="No chain rows in this snapshot."
          />
        )}
      </QueryState>
    </section>
  );
}
