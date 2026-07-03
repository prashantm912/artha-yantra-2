// Live-tick bridge (data-foundation slice): a per-symbol WS subscription to /topic/ticks.{exch}.{sym}
// (the conflated tick channel the gateway bridges) seeded by the REST /ticks/latest snapshot, returning
// a live LTP map keyed by canonical EXCHANGE:SYMBOL. Ports the Angular MarketStore's refcounted
// per-symbol tick map; the React paper + scalper positions overlay sub-second MTM on top of the BE's
// 5s server-computed mark. wsClient.topic is refcounted, so overlapping readers share one broker sub.
//
// Staleness (audit tick-overlay-prefers-stale-tick): each tick carries its receipt time; a sweep
// drops entries older than TICK_STALE_MS so consumers' `live[key] ?? serverMark` fallback re-engages
// when the upstream feed freezes while the WS stays connected (the documented stale-token incident
// shape). The map is also cleared on WS reconnect — pre-drop ticks must never outrank fresh marks.

import { useEffect, useMemo, useState } from 'react';
import { apiFetch } from './client.ts';
import { wsClient } from '../lib/wsClient.ts';

interface TickFrame {
  exchange: string;
  tradingsymbol: string;
  lastPrice: string;
}

interface TimedTick {
  price: string;
  at: number; // receipt wall-time (ms) — frames carry no trustworthy timestamp
}

/** ~3× the 5s server-mark cadence: past this the server mark is the fresher truth. */
export const TICK_STALE_MS = 15_000;

const SWEEP_INTERVAL_MS = 5_000;

function toPrices(timed: Record<string, TimedTick>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(timed)) out[k] = v.price;
  return out;
}

/** Live last-price map for the given canonical EXCHANGE:SYMBOL keys (REST seed + WS push per symbol). */
export function useLiveTicks(symbols: string[]): Record<string, string> {
  const [ticks, setTicks] = useState<Record<string, TimedTick>>({});
  // a stable dependency key (sorted, deduped) so the effect only re-subscribes when the set changes
  const key = [...new Set(symbols.filter((s) => s.includes(':')))].sort().join(',');

  useEffect(() => {
    const syms = key ? key.split(',') : [];
    if (syms.length === 0) {
      setTicks({});
      return;
    }
    let alive = true;

    // REST seed for an immediate value before the first WS frame
    const seed = () =>
      apiFetch<Record<string, TickFrame>>(`/market/ticks/latest?symbols=${encodeURIComponent(syms.join(','))}`)
        .then((m) => {
          if (!alive) return;
          setTicks((prev) => {
            const next = { ...prev };
            for (const [k, v] of Object.entries(m)) next[k] = { price: v.lastPrice, at: Date.now() };
            return next;
          });
        })
        .catch(() => {});
    seed();

    // per-symbol WS subscription (refcounted by wsClient)
    const offs = syms.map((sym) => {
      const sep = sym.indexOf(':');
      const dest = `/topic/ticks.${sym.slice(0, sep)}.${sym.slice(sep + 1)}`;
      return wsClient.topic(dest, (body) => {
        try {
          const t = JSON.parse(body) as TickFrame;
          setTicks((prev) => ({
            ...prev,
            [`${t.exchange}:${t.tradingsymbol}`]: { price: t.lastPrice, at: Date.now() },
          }));
        } catch {
          /* unparseable frame — the REST seed / next frame heals */
        }
      });
    });

    // drop-dead ticks so `?? serverMark` fallbacks re-engage on a frozen upstream
    const sweep = window.setInterval(() => {
      setTicks((prev) => {
        const cutoff = Date.now() - TICK_STALE_MS;
        const fresh = Object.entries(prev).filter(([, v]) => v.at >= cutoff);
        return fresh.length === Object.keys(prev).length ? prev : Object.fromEntries(fresh);
      });
    }, SWEEP_INTERVAL_MS);

    // a reconnect invalidates every held tick (the socket outage hid an unknown number of frames)
    const offReconnect = wsClient.onReconnect(() => {
      setTicks({});
      seed();
    });

    return () => {
      alive = false;
      window.clearInterval(sweep);
      offReconnect();
      offs.forEach((off) => off());
    };
  }, [key]);

  return useMemo(() => toPrices(ticks), [ticks]);
}
