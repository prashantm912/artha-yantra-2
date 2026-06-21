import { subtractDecimal } from '../lib/decimal.ts';
import type { FiiDiiRow } from './types.ts';

// FII/DII Capital Market fold (oipulse §fii-dii/capital-market): the BE /cash feed returns one row per
// (date, category=FII|DII); the page pivots them to one row per date with both sides + the computed
// "In Market" = FII Net + DII Net (confirmed live 2026-06-18). Values are ₹ Crore decimal strings.

export interface FiiDiiCashRow {
  tradeDate: string;
  fiiBuy: string | null;
  fiiSell: string | null;
  fiiNet: string | null;
  inMarket: string | null;
  diiNet: string | null;
  diiBuy: string | null;
  diiSell: string | null;
}

/** Flip a decimal string's sign (−0 normalises to 0). */
function negate(v: string): string {
  if (v === '0' || v === '-0') return '0';
  return v.startsWith('-') ? v.slice(1) : `-${v}`;
}

/** Exact decimal a + b (via a − (−b)). */
function addDecimal(a: string | null, b: string | null): string | null {
  if (a == null || b == null) return null;
  return subtractDecimal(a, negate(b));
}

/** Pivots FII/DII cash rows into one row per date (newest first), with the In-Market net. */
export function foldFiiDiiCash(rows: FiiDiiRow[]): FiiDiiCashRow[] {
  const byDate = new Map<string, { fii?: FiiDiiRow; dii?: FiiDiiRow }>();
  for (const r of rows) {
    const slot = byDate.get(r.tradeDate) ?? {};
    if (r.category.toUpperCase() === 'FII') slot.fii = r;
    else if (r.category.toUpperCase() === 'DII') slot.dii = r;
    byDate.set(r.tradeDate, slot);
  }
  return [...byDate.entries()]
    .sort((a, b) => b[0].localeCompare(a[0])) // newest first
    .map(([tradeDate, { fii, dii }]) => ({
      tradeDate,
      fiiBuy: fii?.buyValue ?? null,
      fiiSell: fii?.sellValue ?? null,
      fiiNet: fii?.netValue ?? null,
      inMarket: addDecimal(fii?.netValue ?? null, dii?.netValue ?? null),
      diiNet: dii?.netValue ?? null,
      diiBuy: dii?.buyValue ?? null,
      diiSell: dii?.sellValue ?? null,
    }));
}
