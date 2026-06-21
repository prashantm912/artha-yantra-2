import type { ConnectingDotsRow } from '../api/types.ts';

// Connecting Dots cell semantics (§20.7.8 — faithful to the oipulse study). Each factor is a 3-state
// code (0 Neutral / 1 Bullish / 2 Bearish); the composite Trend is 5-state (0 Neutral, 1 Ext.Bullish,
// 2 Bullish, 3 Bearish, 4 Ext.Bearish). Tone classes are the shared --ay-* semantic tokens
// (bull/bear/accent); the glyph carries the signal for non-colour users (a11y), matching OiBadge4.

export interface CellMeta {
  label: string;
  glyph: string;
  tone: string;
}

/** 3-state factor cell (oipulse: ↑ green / ↓ red / ↔ blue). */
export const FACTOR_META: Record<number, CellMeta> = {
  0: { label: 'Neutral', glyph: '↔', tone: 'text-accent' },
  1: { label: 'Bullish', glyph: '↑', tone: 'text-bull' },
  2: { label: 'Bearish', glyph: '↓', tone: 'text-bear' },
};

/** 5-state composite Trend; `extreme` rows get the faint maroon row tint (oipulse). */
export const TREND_META: Record<number, CellMeta & { extreme: boolean }> = {
  0: { label: 'Neutral', glyph: '↔', tone: 'text-accent', extreme: false },
  1: { label: 'Ext. Bullish', glyph: '↑', tone: 'text-bull', extreme: true },
  2: { label: 'Bullish', glyph: '↑', tone: 'text-bull', extreme: false },
  3: { label: 'Bearish', glyph: '↓', tone: 'text-bear', extreme: false },
  4: { label: 'Ext. Bearish', glyph: '↓', tone: 'text-bear', extreme: true },
};

/** The 11 factor columns after Trend, in the exact oipulse display order. */
export const FACTOR_COLUMNS: ReadonlyArray<{ key: keyof ConnectingDotsRow; label: string }> = [
  { key: 'dow', label: 'Dow Jones' },
  { key: 'vix', label: 'Vix' },
  { key: 'volume', label: 'Volume' },
  { key: 'activeStrikeIv', label: 'Active Strike IV' },
  { key: 'activeStrikeOi', label: 'Active Strike OI' },
  { key: 'futOi', label: 'OI Inter.' },
  { key: 'vwap', label: 'VWAP' },
  { key: 'supertrend', label: 'Supertrend' },
  { key: 'rsi', label: 'RSI' },
  { key: 'futPrice', label: 'Price' },
  { key: 'dailyTrend', label: 'Daily Trend' },
];

/** Cell meta for a factor code, defaulting to Neutral for an unexpected value. */
export function factorMeta(code: number): CellMeta {
  return FACTOR_META[code] ?? FACTOR_META[0];
}

/** Cell meta for a trend code, defaulting to Neutral. */
export function trendMeta(code: number): CellMeta & { extreme: boolean } {
  return TREND_META[code] ?? TREND_META[0];
}
