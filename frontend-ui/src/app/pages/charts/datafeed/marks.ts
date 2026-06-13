import { floorBucketMs } from './timestamp';

/**
 * Library-agnostic chart marks (E-10.3, Phase 40A) — zero chart-library imports. Maps backtest
 * trades + emitted signals onto internal mark DTOs; mark times get the SAME IST bucket-flooring as
 * candles so a marker coincides with a bar. The binding maps these onto lightweight-charts
 * `createSeriesMarkers`. SL/target marks come from SIGNALS (which carry them); backtest TradeRows do
 * not persist SL/target, so trade marks are entry/exit only.
 */

export type MarkKind = 'entry' | 'exit' | 'sl' | 'target';

export interface MarkDto {
  id: string;
  timeMs: number;
  price: string;
  kind: MarkKind;
  side?: string;
  label: string;
}

export interface TradeLike {
  seq: number;
  side: string;
  entryTs: string;
  entryPrice: string;
  exitTs?: string | null;
  exitPrice?: string | null;
  pnl?: string;
}

export interface SignalLike {
  id: number;
  exchange: string;
  tradingsymbol: string;
  side: string;
  entryPrice: string | null;
  stopLoss: string | null;
  target: string | null;
  generatedAt: string;
}

/** Entry + exit marks for a run's trades, within [fromMs, toMs); times IST bucket-floored. */
export function tradeMarks(
  trades: TradeLike[],
  interval: string,
  fromMs: number,
  toMs: number,
): MarkDto[] {
  const out: MarkDto[] = [];
  for (const t of trades) {
    const entryMs = floorBucketMs(Date.parse(t.entryTs), interval);
    if (entryMs >= fromMs && entryMs < toMs) {
      out.push({
        id: `t${t.seq}-entry`,
        timeMs: entryMs,
        price: t.entryPrice,
        kind: 'entry',
        side: t.side,
        label: `#${t.seq} entry`,
      });
    }
    if (t.exitTs && t.exitPrice) {
      const exitMs = floorBucketMs(Date.parse(t.exitTs), interval);
      if (exitMs >= fromMs && exitMs < toMs) {
        out.push({
          id: `t${t.seq}-exit`,
          timeMs: exitMs,
          price: t.exitPrice,
          kind: 'exit',
          side: t.side,
          label: `#${t.seq} exit${t.pnl ? ` P&L ${t.pnl}` : ''}`,
        });
      }
    }
  }
  return out;
}

/**
 * Entry marks for signals on `symbol`; SL/target marks added ONLY for the focused signal (single-
 * signal focus mode — full-width SL/target lines in the multi-signal view would stack into noise).
 */
export function signalMarks(
  signals: SignalLike[],
  symbol: string,
  interval: string,
  focusId?: number,
): MarkDto[] {
  const out: MarkDto[] = [];
  for (const s of signals) {
    if (`${s.exchange}:${s.tradingsymbol}` !== symbol) {
      continue;
    }
    const t = floorBucketMs(Date.parse(s.generatedAt), interval);
    out.push({
      id: `s${s.id}-entry`,
      timeMs: t,
      price: s.entryPrice ?? '0',
      kind: 'entry',
      side: s.side,
      label: `signal ${s.side}`,
    });
    if (focusId === s.id) {
      if (s.stopLoss) {
        out.push({
          id: `s${s.id}-sl`,
          timeMs: t,
          price: s.stopLoss,
          kind: 'sl',
          side: s.side,
          label: 'SL',
        });
      }
      if (s.target) {
        out.push({
          id: `s${s.id}-target`,
          timeMs: t,
          price: s.target,
          kind: 'target',
          side: s.side,
          label: 'target',
        });
      }
    }
  }
  return out;
}
