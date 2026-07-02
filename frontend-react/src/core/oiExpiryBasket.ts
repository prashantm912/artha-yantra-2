/**
 * 5 strikes spread evenly across the server's ATM window (ends + quartiles + centre) — the oipulse
 * OI Expiry Strategy default is 5 WIDE-spaced near-ATM strikes, not 5 adjacent ones (audit §10.2-9).
 */
export function defaultBasket(strikes: string[]): string[] {
  if (strikes.length <= 5) return strikes;
  const n = strikes.length - 1;
  const idx = [0, Math.round(n * 0.25), Math.round(n * 0.5), Math.round(n * 0.75), n];
  return [...new Set(idx)].map((i) => strikes[i]);
}
