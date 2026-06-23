// Live-tick bridge (data-foundation slice): a per-symbol WS subscription to /topic/ticks.{exch}.{sym}
// (the conflated tick channel the gateway bridges) seeded by the REST /ticks/latest snapshot, returning
// a live LTP map keyed by canonical EXCHANGE:SYMBOL. Ports the Angular MarketStore's refcounted
// per-symbol tick map; the React paper + scalper positions overlay sub-second MTM on top of the BE's
// 5s server-computed mark. wsClient.topic is refcounted, so overlapping readers share one broker sub.

import { useEffect, useState } from 'react';
import { apiFetch } from './client.ts';
import { wsClient } from '../lib/wsClient.ts';

interface TickFrame {
  exchange: string;
  tradingsymbol: string;
  lastPrice: string;
}

/** Live last-price map for the given canonical EXCHANGE:SYMBOL keys (REST seed + WS push per symbol). */
export function useLiveTicks(symbols: string[]): Record<string, string> {
  const [ticks, setTicks] = useState<Record<string, string>>({});
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
    apiFetch<Record<string, TickFrame>>(`/market/ticks/latest?symbols=${encodeURIComponent(syms.join(','))}`)
      .then((m) => {
        if (!alive) return;
        setTicks((prev) => {
          const next = { ...prev };
          for (const [k, v] of Object.entries(m)) next[k] = v.lastPrice;
          return next;
        });
      })
      .catch(() => {});

    // per-symbol WS subscription (refcounted by wsClient)
    const offs = syms.map((sym) => {
      const sep = sym.indexOf(':');
      const dest = `/topic/ticks.${sym.slice(0, sep)}.${sym.slice(sep + 1)}`;
      return wsClient.topic(dest, (body) => {
        try {
          const t = JSON.parse(body) as TickFrame;
          setTicks((prev) => ({ ...prev, [`${t.exchange}:${t.tradingsymbol}`]: t.lastPrice }));
        } catch {
          /* unparseable frame — the REST seed / next frame heals */
        }
      });
    });

    return () => {
      alive = false;
      offs.forEach((off) => off());
    };
  }, [key]);

  return ticks;
}
