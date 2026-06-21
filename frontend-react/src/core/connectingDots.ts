import type { ConnectingDotsRow } from '../api/types.ts';

// Connecting Dots cell semantics (§20.7.8 — faithful to the LIVE oipulse page, QA'd 2026-06-21). Each
// factor is a 3-state code (0 Neutral / 1 Bullish / 2 Bearish); the composite Trend is 5-state. oipulse
// renders each cell as a SOLID-FILLED badge (green ↑ / red ↓ / blue ↔) with the arrow; we mirror the
// fills and put the glyph in `text-surface-0`, which is the opposite end of the palette from the
// saturated fill, so it stays ≥AA-contrast in every theme (faithful AND accessible).

export interface CellMeta {
  label: string;
  glyph: string;
  /** Solid fill class (bg-bull / bg-bear / bg-accent). */
  fill: string;
}

/** 3-state factor cell — solid green ↑ / red ↓ / blue ↔ (oipulse). */
export const FACTOR_META: Record<number, CellMeta> = {
  0: { label: 'Neutral', glyph: '↔', fill: 'bg-accent' },
  1: { label: 'Bullish', glyph: '↑', fill: 'bg-bull' },
  2: { label: 'Bearish', glyph: '↓', fill: 'bg-bear' },
};

/** 5-state composite Trend; `extreme` rows get a DIRECTION-coloured row tint (green up / red down). */
export const TREND_META: Record<number, CellMeta & { extreme: boolean; tint: string }> = {
  0: { label: 'Neutral', glyph: '↔', fill: 'bg-accent', extreme: false, tint: '' },
  1: {
    label: 'Ext. Bullish',
    glyph: '↑',
    fill: 'bg-bull',
    extreme: true,
    tint: 'bg-[color-mix(in_srgb,var(--ay-bull)_12%,transparent)]',
  },
  2: { label: 'Bullish', glyph: '↑', fill: 'bg-bull', extreme: false, tint: '' },
  3: { label: 'Bearish', glyph: '↓', fill: 'bg-bear', extreme: false, tint: '' },
  4: {
    label: 'Ext. Bearish',
    glyph: '↓',
    fill: 'bg-bear',
    extreme: true,
    tint: 'bg-[color-mix(in_srgb,var(--ay-bear)_12%,transparent)]',
  },
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
export function trendMeta(code: number): CellMeta & { extreme: boolean; tint: string } {
  return TREND_META[code] ?? TREND_META[0];
}
