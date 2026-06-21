import { useMemo, useState } from 'react';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { nearestStrike } from '../../lib/strikes.ts';
import { useChainTable, useVix } from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { ColumnSettings } from '../../components/ColumnSettings.tsx';
import { OptionsChainTable } from '../../components/OptionsChainTable.tsx';
import { OPTIONAL_COLUMN_META } from '../../components/optionsChainColumns.ts';

// Options Chain — the faithful oipulse chain (§20.7): 18 visible cols off /chain-table (live black76
// greeks + interval deltas), side-coloured OI bars, OI-interpretation badges, ATM/ITM tints, max-cell
// highlights, LTP flash, per-strike PCR, a Go button + Column-Setting modal, and the live header strip.
// Money/IV stay decimal strings (never parseFloat). Header gaps (INDIA VIX, underlying DH/DL/DO,
// prev-PCR) are marked pending — they need endpoints the chain-table feed does not yet carry.

/** Whole calendar days from today (IST-agnostic display) to the ISO expiry date. */
function daysToExpiry(expiry: string | null): number | null {
  if (!expiry) return null;
  const exp = Date.parse(`${expiry}T00:00:00`);
  if (Number.isNaN(exp)) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.max(0, Math.round((exp - today.getTime()) / 86_400_000));
}

/** "12.97 (+2.37%)" from the VIX quote; "—" until it loads. */
function vixLabel(ltp: string | null | undefined, changePct: string | null | undefined): string {
  if (!ltp) return '—';
  if (!changePct) return formatDecimal(ltp, 2);
  const sign = isNegative(changePct) ? '' : '+';
  return `${formatDecimal(ltp, 2)} (${sign}${formatDecimal(changePct, 2)}%)`;
}

export function OptionsChainPage() {
  const chainQ = useChainTable();
  const vixQ = useVix();
  const [optional, setOptional] = useState<Record<string, boolean>>({});

  const chain = chainQ.data ?? null;
  const rows = useMemo(() => chain?.rows ?? [], [chain]);
  const atm = useMemo(
    () => nearestStrike(rows.map((r) => r.strike), chain?.spot ?? null),
    [rows, chain],
  );
  const dte = daysToExpiry(chain?.expiry ?? null);
  const optionalKeys = useMemo(
    () => OPTIONAL_COLUMN_META.filter((c) => optional[c.key]).map((c) => c.key),
    [optional],
  );

  return (
    <div>
      <h1 className="ay-sr-only">Options chain</h1>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry />
        <GoButton onClick={() => chainQ.refetch()} loading={chainQ.isFetching} />
        <ColumnSettings
          columns={OPTIONAL_COLUMN_META}
          visible={optional}
          onToggle={(key) => setOptional((v) => ({ ...v, [key]: !v[key] }))}
        />
      </div>

      {/* Live header strip (§20.7.4). Max-pain/Sentiment intentionally NOT here — they belong to the
          separate OI Statistics / Active Strikes pages. */}
      <div className="mb-3 flex flex-wrap items-center gap-2" aria-live="polite">
        <Metric
          label="INDIA VIX"
          value={vixLabel(vixQ.data?.ltp, vixQ.data?.changePct)}
          title={
            vixQ.data?.dayHigh
              ? `DH ${formatDecimal(vixQ.data.dayHigh, 2)} · DL ${vixQ.data.dayLow ? formatDecimal(vixQ.data.dayLow, 2) : '—'} · DO ${vixQ.data.dayOpen ? formatDecimal(vixQ.data.dayOpen, 2) : '—'}`
              : undefined
          }
        />
        <Metric label="Total PCR" value={chain?.pcr ? formatDecimal(chain.pcr, 2) : '—'} />
        <Metric label="ATM" value={atm ?? '—'} />
        <Metric label="Days to expiry" value={dte != null ? String(dte) : '—'} />
        <Metric
          label={chain?.underlying ?? 'Underlying'}
          value={chain?.spot ? formatDecimal(chain.spot, 2) : '—'}
          title="DH/DL/DO pending — underlying OHLC not in the chain-table feed"
        />
        {chain?.stale && (
          <span className="rounded border border-warn/40 px-2 py-1 text-xs text-warn">stale</span>
        )}
      </div>

      {chain == null && !chainQ.isLoading && (
        <p className="mb-3 text-sm text-ay-muted">
          No chain — pick an underlying + expiry with a live option chain.
        </p>
      )}

      <OptionsChainTable
        rows={rows}
        spot={chain?.spot ?? null}
        atmStrike={atm}
        optionalKeys={optionalKeys}
      />
    </div>
  );
}
