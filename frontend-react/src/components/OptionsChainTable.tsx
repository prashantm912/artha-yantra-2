import type { ReactNode } from 'react';
import { useMemo, useRef, useState } from 'react';
import { cn } from '../lib/cn.ts';
import { useCenterRowInScroll } from '../lib/scrollToCenter.ts';
import type { Density } from '../lib/density.ts';
import {
  compareDecimal,
  formatDecimal,
  isNegative,
  multiplyByInt,
  subtractDecimal,
} from '../lib/decimal.ts';
import type { ChainTableLeg, ChainTableRow } from '../api/types.ts';
import { DataBar } from './atoms/DataBar.tsx';
import { OiBadge4 } from './atoms/OiBadge4.tsx';
import { PulseValue } from './atoms/PulseValue.tsx';

// Mobile card list: strikes shown either side of the ATM by default.
const MOBILE_WINDOW = 10;
import { ValueDeltaCell } from './atoms/ValueDeltaCell.tsx';

// The faithful oipulse Options Chain grid (§20.7): dense CALL | STRIKE | PUT mirror with a trailing
// per-strike PCR column. Distinct from MirroredCspTable (fixed LegCell, symmetric, no trailing col) —
// this carries the full greeks leg + interval deltas, side-coloured OI bars (red CALL / green PUT),
// green/red ΔOI bars, the 4-state OI-interpretation badge, ATM cream-tint, ITM tint, max-OI/ΔOI/Vol
// cell highlights and LTP flash. Column defs are module-local (non-exported) per the repo convention;
// the page reads the optional-column labels from the plain meta in optionsChainColumns.ts.

interface ChainCellCtx {
  side: 'CE' | 'PE';
  strike: string;
  spot: string | null;
  maxOi: number;
  maxAbsOiChange: number;
  isMaxOi: boolean;
  isMaxOiChange: boolean;
  isMaxVol: boolean;
  itm: boolean;
}

interface ChainColumn {
  key: string;
  header: string;
  render: (cell: ChainTableLeg | null, ctx: ChainCellCtx) => ReactNode;
  /** Per-cell <td> class (max-cell highlight / ITM tint) from the side context. */
  tdClass?: (ctx: ChainCellCtx) => string;
}

const MAX_CELL = 'ring-1 ring-inset ring-accent';
const num = (n: number | null | undefined) => (n != null ? n.toLocaleString('en-IN') : '—');
const signed = (n: number | null | undefined) =>
  n == null ? '—' : (n > 0 ? '+' : '') + n.toLocaleString('en-IN');
const dec = (v: string | null | undefined, d: number) => (v ? formatDecimal(v, d) : '—');
// IV rides the wire as a fraction (0.1396); oipulse displays it as a percent (13.96) — ×100, exact.
const ivPct = (v: string | null | undefined) => (v ? formatDecimal(multiplyByInt(v, 100), 2) : '—');
// oipulse convention: CALL OI bars are red, PUT OI bars green (NOT bull/bear semantics).
const oiTone = (side: 'CE' | 'PE') => (side === 'CE' ? 'bear' : 'bull');
// Intrinsic value: CE = max(0, spot − strike), PE = max(0, strike − spot); exact decimal, no parseFloat.
const intrinsic = (ctx: ChainCellCtx): string => {
  if (!ctx.spot) return '—';
  const diff =
    ctx.side === 'CE'
      ? subtractDecimal(ctx.spot, ctx.strike)
      : subtractDecimal(ctx.strike, ctx.spot);
  return isNegative(diff) ? formatDecimal('0', 2) : formatDecimal(diff, 2);
};

const VISIBLE_COLUMNS: ChainColumn[] = [
  { key: 'oiInt', header: 'OI Int', render: (c) => <OiBadge4 value={c?.deltas?.interpretation ?? null} /> },
  { key: 'oiPct', header: 'OI %', render: (c) => <ValueDeltaCell value={c?.deltas?.oiChangePct ?? null} suffix="%" /> },
  {
    key: 'oi',
    header: 'OI',
    render: (c, ctx) => (
      <DataBar value={c?.leg.oi ?? 0} max={ctx.maxOi} tone={oiTone(ctx.side)} label={num(c?.leg.oi)} />
    ),
    tdClass: (ctx) => (ctx.isMaxOi ? MAX_CELL : ''),
  },
  {
    key: 'oiChg',
    header: 'OI Chng',
    render: (c, ctx) => {
      const d = c?.deltas?.oiChange ?? null;
      return (
        <DataBar
          value={d ?? 0}
          max={ctx.maxAbsOiChange}
          tone={d == null ? 'neutral' : d >= 0 ? 'bull' : 'bear'}
          label={signed(d)}
        />
      );
    },
    tdClass: (ctx) => (ctx.isMaxOiChange ? MAX_CELL : ''),
  },
  { key: 'iv', header: 'IV', render: (c) => <span className="tabular-nums">{ivPct(c?.leg.iv)}</span> },
  {
    key: 'ltp',
    header: 'LTP',
    render: (c, ctx) => (
      <>
        <PulseValue value={c?.leg.ltp ?? null} digits={2} />
        {ctx.itm && <span className="ay-sr-only"> in the money</span>}
      </>
    ),
    tdClass: (ctx) => (ctx.itm ? 'bg-accent/10' : ''),
  },
  { key: 'ltpPct', header: 'LTP %', render: (c) => <ValueDeltaCell value={c?.deltas?.ltpChangePct ?? null} suffix="%" /> },
  { key: 'ltpChg', header: 'LTP Chg', render: (c) => <ValueDeltaCell value={c?.deltas?.ltpChange ?? null} /> },
];

// Optional (Column-Setting-toggleable) columns — keys match optionsChainColumns.ts OPTIONAL_COLUMN_META.
// Real chain-table data only; O=H/O=L are deferred (they need a strike-session-stats join).
const OPTIONAL_COLUMNS: ChainColumn[] = [
  { key: 'delta', header: 'Delta', render: (c) => <span className="tabular-nums">{dec(c?.leg.delta, 4)}</span> },
  {
    key: 'volume',
    header: 'Volume',
    render: (c) => <span className="tabular-nums">{num(c?.leg.volume)}</span>,
    tdClass: (ctx) => (ctx.isMaxVol ? MAX_CELL : ''),
  },
  {
    key: 'intrinsic',
    header: 'Intrinsic',
    render: (_c, ctx) => <span className="tabular-nums">{intrinsic(ctx)}</span>,
  },
];

interface OptionsChainTableProps {
  rows: ChainTableRow[];
  spot: string | null;
  atmStrike: string | null;
  optionalKeys: string[];
  /** Row density (§4.2/Q4): comfortable `py-1` vs compact `py-0.5`. Defaults to the scalper compact. */
  density?: Density;
  emptyMessage?: string;
}

interface SideMax {
  oi: number;
  absChange: number;
  oiStrike: string | null;
  changeStrike: string | null;
  volStrike: string | null;
}

function emptyMax(): SideMax {
  return { oi: -1, absChange: -1, oiStrike: null, changeStrike: null, volStrike: null };
}

/**
 * PCR for a single strike: ΣPE OI / ΣCE OI (2dp display). Dashed when the CE side has less than a
 * dust floor of OI — a 5-lot denominator printed absurd ratios like 16385.00 (audit 2026-07-02 §8).
 */
const PCR_OI_FLOOR = 500;
function rowPcr(row: ChainTableRow): string {
  const ce = row.ce?.leg.oi ?? 0;
  const pe = row.pe?.leg.oi ?? 0;
  return ce >= PCR_OI_FLOOR ? (pe / ce).toFixed(2) : '—';
}

export function OptionsChainTable({
  rows,
  spot,
  atmStrike,
  optionalKeys,
  density = 'compact',
  emptyMessage = 'No chain for this selection.',
}: OptionsChainTableProps) {
  // Per-row cell padding only (headers stay py-1); compact is the scalper default.
  const cellPad = density === 'compact' ? 'py-0.5' : 'py-1';
  const enabledOptional = useMemo(
    () => OPTIONAL_COLUMNS.filter((c) => optionalKeys.includes(c.key)),
    [optionalKeys],
  );
  const callColumns = useMemo(() => [...VISIBLE_COLUMNS, ...enabledOptional], [enabledOptional]);
  const putColumns = useMemo(() => [...callColumns].reverse(), [callColumns]);

  // Per-side maxima (bar scaling) + the strikes holding each side's max OI / |ΔOI| / volume (highlights).
  const stats = useMemo(() => {
    let maxOi = 1;
    let maxAbsOiChange = 1;
    const ce = emptyMax();
    const pe = emptyMax();
    let ceMaxVol = -1;
    let peMaxVol = -1;
    const track = (s: SideMax, leg: ChainTableLeg | null, strike: string) => {
      const oi = leg?.leg.oi ?? 0;
      const chg = Math.abs(leg?.deltas?.oiChange ?? 0);
      maxOi = Math.max(maxOi, oi);
      maxAbsOiChange = Math.max(maxAbsOiChange, chg);
      if (oi > s.oi) {
        s.oi = oi;
        s.oiStrike = strike;
      }
      if (chg > s.absChange) {
        s.absChange = chg;
        s.changeStrike = strike;
      }
    };
    for (const r of rows) {
      track(ce, r.ce, r.strike);
      track(pe, r.pe, r.strike);
      const cv = r.ce?.leg.volume ?? 0;
      const pv = r.pe?.leg.volume ?? 0;
      if (cv > ceMaxVol) {
        ceMaxVol = cv;
        ce.volStrike = r.strike;
      }
      if (pv > peMaxVol) {
        peMaxVol = pv;
        pe.volStrike = r.strike;
      }
    }
    return { maxOi, maxAbsOiChange, ce, pe };
  }, [rows]);

  const ctxFor = (row: ChainTableRow, side: 'CE' | 'PE'): ChainCellCtx => {
    const s = side === 'CE' ? stats.ce : stats.pe;
    const itm =
      spot != null &&
      (side === 'CE' ? compareDecimal(row.strike, spot) < 0 : compareDecimal(row.strike, spot) > 0);
    return {
      side,
      strike: row.strike,
      spot,
      maxOi: stats.maxOi,
      maxAbsOiChange: stats.maxAbsOiChange,
      isMaxOi: s.oiStrike === row.strike,
      isMaxOiChange: s.changeStrike === row.strike,
      isMaxVol: s.volStrike === row.strike,
      itm,
    };
  };

  const colSpan = callColumns.length + 1 + putColumns.length + 1;
  const atmStyle = { background: 'color-mix(in srgb, var(--ay-warn) 22%, transparent)' };

  // Land the ATM strike in the middle of the scroll viewport (on load + when the ATM moves).
  const scrollRef = useRef<HTMLDivElement>(null);
  const atmRef = useRef<HTMLTableRowElement>(null);
  useCenterRowInScroll(scrollRef, atmRef, atmStrike);

  // Mobile ATM window (§11 item 18): default ±MOBILE_WINDOW strikes around the ATM.
  const [showAllMobile, setShowAllMobile] = useState(false);
  const mobileRows = useMemo(() => {
    if (showAllMobile || atmStrike == null) return rows;
    const i = rows.findIndex((r) => r.strike === atmStrike);
    if (i < 0) return rows;
    return rows.slice(Math.max(0, i - MOBILE_WINDOW), i + MOBILE_WINDOW + 1);
  }, [rows, atmStrike, showAllMobile]);

  return (
    <>
      {/* Desktop / landscape: the dense mirrored grid */}
      <div
        ref={scrollRef}
        className="ay-table-scroll hidden max-h-[64vh] overflow-auto rounded border border-ay-border md:block"
        tabIndex={0}
        role="region"
        aria-label="Options chain"
      >
        <table aria-label="Options chain" className="w-full border-collapse text-sm">
          <thead className="sticky top-0 z-10 bg-surface-1 text-ay-muted">
            <tr>
              <th colSpan={callColumns.length} scope="colgroup" className="px-2 py-1 text-center text-bear">
                CALL
              </th>
              <th scope="col" className="px-2 py-1 text-center">
                Strike
              </th>
              <th colSpan={putColumns.length} scope="colgroup" className="px-2 py-1 text-center text-bull">
                PUT
              </th>
              <th scope="col" className="px-2 py-1 text-center">
                PCR
              </th>
            </tr>
            <tr>
              {callColumns.map((c) => (
                <th key={`ceh-${c.key}`} scope="col" className="px-2 py-1 text-right font-medium">
                  {c.header}
                </th>
              ))}
              <th scope="col" className="px-2 py-1 text-center">
                Strike
              </th>
              {putColumns.map((c) => (
                <th key={`peh-${c.key}`} scope="col" className="px-2 py-1 text-right font-medium">
                  {c.header}
                </th>
              ))}
              <th scope="col" className="px-2 py-1 text-right font-medium">
                Ratio
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const ce = ctxFor(row, 'CE');
              const pe = ctxFor(row, 'PE');
              const isAtm = atmStrike != null && row.strike === atmStrike;
              return (
                <tr
                  key={row.strike}
                  ref={isAtm ? atmRef : null}
                  className="border-t border-ay-border text-ay-text"
                >
                  {callColumns.map((c) => (
                    <td key={`ce-${c.key}`} className={cn('px-2 text-right', cellPad, c.tdClass?.(ce))}>
                      {c.render(row.ce, ce)}
                    </td>
                  ))}
                  <td
                    className={cn('px-2 text-center font-semibold', cellPad)}
                    style={isAtm ? atmStyle : undefined}
                  >
                    {row.strike}
                    {isAtm && <span className="ay-sr-only"> at the money</span>}
                  </td>
                  {putColumns.map((c) => (
                    <td key={`pe-${c.key}`} className={cn('px-2 text-right', cellPad, c.tdClass?.(pe))}>
                      {c.render(row.pe, pe)}
                    </td>
                  ))}
                  <td className={cn('px-2 text-right tabular-nums text-ay-muted', cellPad)}>
                    {rowPcr(row)}
                  </td>
                </tr>
              );
            })}
            {rows.length === 0 && (
              <tr>
                <td colSpan={colSpan} className="px-2 py-4 text-center text-ay-muted">
                  {emptyMessage}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Phone (portrait, ~480px): card per strike, CALL/PUT split. Defaults to an ATM-centred
          window — the full list started ~55 cards of deep-ITM scrolling before the ATM (audit
          2026-07-02 §4 / §11 item 18); "All strikes" restores the full ladder. */}
      <div className="space-y-2 md:hidden">
        {atmStrike != null && rows.length > MOBILE_WINDOW * 2 + 1 && (
          <button
            type="button"
            onClick={() => setShowAllMobile((v) => !v)}
            className="w-full rounded border border-ay-border bg-surface-2 px-2 py-1.5 text-xs text-ay-text"
          >
            {showAllMobile ? `Show ATM ±${MOBILE_WINDOW} strikes` : `Show all ${rows.length} strikes`}
          </button>
        )}
        {mobileRows.map((row) => {
          const ce = ctxFor(row, 'CE');
          const pe = ctxFor(row, 'PE');
          const isAtm = atmStrike != null && row.strike === atmStrike;
          return (
            <div
              key={row.strike}
              className="rounded border border-ay-border bg-surface-1 p-2 text-sm"
              style={isAtm ? atmStyle : undefined}
            >
              <div className="mb-1 flex items-center justify-between font-semibold text-ay-text">
                <span>Strike {row.strike}</span>
                <span className="text-xs text-ay-muted">PCR {rowPcr(row)}</span>
              </div>
              <div className="grid grid-cols-2 gap-2 text-xs">
                <div>
                  <div className="text-bear">CALL</div>
                  {callColumns.map((c) => (
                    <div key={`ce-${c.key}`} className="text-ay-text">
                      {c.header}: {c.render(row.ce, ce)}
                    </div>
                  ))}
                </div>
                <div>
                  <div className="text-bull">PUT</div>
                  {callColumns.map((c) => (
                    <div key={`pe-${c.key}`} className="text-ay-text">
                      {c.header}: {c.render(row.pe, pe)}
                    </div>
                  ))}
                </div>
              </div>
            </div>
          );
        })}
        {rows.length === 0 && <p className="text-center text-ay-muted">{emptyMessage}</p>}
      </div>
    </>
  );
}
