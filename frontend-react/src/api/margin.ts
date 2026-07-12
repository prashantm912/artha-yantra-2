// Pre-trade SPAN margin (F9 source): POST /api/v1/market/margin resolves each leg to its Upstox
// instrument_key and asks Upstox for SPAN + exposure margin server-side. Typed + FAIL-SOFT — when the
// analytics token is off (mock stack / Kite-only live) it returns priced=false + a human reason, never
// a 5xx. Money fields ride as decimal strings. A leg may be given by tradingsymbol (resolved from the
// instrument master) OR the structured tuple.

import { useMutation } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

export interface MarginLeg {
  exchange: string;
  tradingsymbol?: string | null;
  underlying?: string | null;
  optionType?: string | null; // CE | PE | FUT
  expiry?: string | null; // yyyy-MM-dd
  strike?: string | null;
  quantity: number;
  side: string; // BUY | SELL
  product?: string | null; // D (NRML) | I (MIS)
}

export interface MarginResponse {
  priced: boolean;
  unpricedReason: string | null;
  spanMargin: string | null;
  exposureMargin: string | null;
  equityMargin: string | null;
  netBuyPremium: string | null;
  additionalMargin: string | null;
  totalMargin: string | null;
  requiredMargin: string | null;
  finalMargin: string | null;
}

/** Requests the basket's SPAN + exposure margin (≤20 legs). */
export function useMarginQuote() {
  return useMutation({
    mutationFn: (legs: MarginLeg[]) =>
      apiFetch<MarginResponse>('/market/margin', { method: 'POST', json: { legs } }),
  });
}
