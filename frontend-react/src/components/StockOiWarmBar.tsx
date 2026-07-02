import { useEffect, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { useSymbolContext } from '../stores/symbolContext.store.ts';
import {
  isCapturedIndex,
  useStartStockChainWarm,
  useStockChainWarmStatus,
} from '../api/stockChainWarm.ts';

// The stock-chain warm affordance (audit §9.3, Wave 5): interval OI for STOCK underlyings loads on
// demand (the standing 2-min capture covers the 6 indices only). Renders nothing for indices. For a
// stock it offers "Load OI data" → the warm pulls the day's 1-minute Upstox bars into the snapshot
// store, shows fetched/total progress, and on DONE invalidates the OI queries so the open page
// refills in place. Mounted on the OI-suite pages next to the FilterBar.

export function StockOiWarmBar() {
  const name = useSymbolContext((s) => s.name);
  const expiry = useSymbolContext((s) => s.expiry);
  const mode = useSymbolContext((s) => s.mode);
  const storeDate = useSymbolContext((s) => s.date);
  const date = mode === 'history' ? storeDate : null;

  const isStock = !!name && !isCapturedIndex(name);
  const statusQ = useStockChainWarmStatus(name, expiry, date, isStock);
  const start = useStartStockChainWarm();
  const qc = useQueryClient();

  // On the RUNNING→DONE edge, refill the OI pages + confirm.
  const prevState = useRef<string | undefined>(undefined);
  const state = statusQ.data?.state;
  useEffect(() => {
    if (prevState.current === 'RUNNING' && state === 'DONE') {
      void qc.invalidateQueries({ queryKey: ['oi'] });
      toast.success(`OI data loaded for ${name} (${statusQ.data?.rowsInStore ?? 0} readings)`);
    }
    prevState.current = state;
  }, [state, name, qc, statusQ.data]);

  if (!isStock || !statusQ.data) return null;
  const s = statusQ.data;

  if (s.state === 'UNAVAILABLE') {
    return (
      <p className="mb-3 rounded-md border border-ay-border bg-surface-1 px-3 py-2 text-xs text-ay-muted">
        Stock interval OI loads on demand via Upstox — not configured on this stack (indices only).
      </p>
    );
  }
  return (
    <div className="mb-3 flex flex-wrap items-center gap-2 rounded-md border border-ay-border bg-surface-1 px-3 py-2 text-xs text-ay-muted">
      <span>
        Stock interval OI loads on demand{s.rowsInStore > 0 ? ` — ${s.rowsInStore} readings stored for ${s.day}` : ` — nothing stored for ${s.day} yet`}.
      </span>
      {s.state === 'RUNNING' ? (
        <span className="font-medium text-ay-text" aria-live="polite">
          Loading… {s.fetchedLegs}/{s.totalLegs} legs
        </span>
      ) : (
        <button
          type="button"
          onClick={() => start.mutate({ name, expiry, date })}
          disabled={start.isPending}
          title="Fetch this day's 1-minute option-chain OI from Upstox (~1–2 minutes for a full chain); every OI page then serves this stock."
          className="h-7 rounded-md border border-ay-border px-2 text-ay-text hover:border-accent disabled:opacity-50"
        >
          {s.rowsInStore > 0 ? 'Top up OI data' : 'Load OI data'}
        </button>
      )}
      {s.state === 'FAILED' && <span className="text-bear">Last load failed — try again.</span>}
    </div>
  );
}
