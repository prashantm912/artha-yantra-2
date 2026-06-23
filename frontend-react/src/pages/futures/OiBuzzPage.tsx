import { useCallback, useEffect, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { useOiBuzz, useOiBuzzIndices } from '../../api/oiAnalytics.ts';
import type { OiBuzzTile } from '../../api/types.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { oiIntMeta } from '../../core/oiInterpretation.ts';
import { formatDecimal } from '../../lib/decimal.ts';

// Futures OI Buzz (oipulse "Futures Heatmap"): one treemap tile per index constituent, sized + coloured
// by the near-month future's intraday % change (green gainers → red losers, intensity by magnitude).
// The raw feed carries no index weight, so v1 sizes by |% change| (owner's call). Header shows the
// advance/decline split; search locates a symbol; the OI-change interpretation badge is deferred to v2.

const compactInt = (n: number) => new Intl.NumberFormat('en-IN', { notation: 'compact' }).format(n);
const num = (s: string | null): number => (s == null ? 0 : Number(s));
const f2 = (s: string | null) => (s == null ? '—' : formatDecimal(s, 2));

interface TileDatum {
  name: string;
  value: number;
  pctLabel: string;
  tile: OiBuzzTile;
  itemStyle: { color: string; opacity: number };
}

export function OiBuzzPage() {
  const indicesQ = useOiBuzzIndices();
  const indices = useMemo(() => indicesQ.data ?? [], [indicesQ.data]);
  const [index, setIndex] = useState<string | null>(null);
  const [search, setSearch] = useState('');

  // Default to the first seeded index once the selector options arrive.
  useEffect(() => {
    if (!index && indices.length) setIndex(indices[0]);
  }, [index, indices]);

  const q = useOiBuzz(index);
  const data = q.data ?? null;

  const visibleTiles = useMemo(() => {
    const tiles = data?.tiles ?? [];
    const needle = search.trim().toUpperCase();
    return needle ? tiles.filter((t) => t.symbol.toUpperCase().includes(needle)) : tiles;
  }, [data, search]);

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const maxAbs = Math.max(
        0.01,
        ...visibleTiles.map((tile) => Math.abs(num(tile.changePct))),
      );
      const data2: TileDatum[] = visibleTiles.map((tile) => {
        const pct = num(tile.changePct);
        const ratio = Math.min(1, Math.abs(pct) / maxAbs);
        return {
          name: tile.symbol,
          value: Math.abs(pct) + 0.3, // size floor so flat movers still render
          pctLabel: `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`,
          tile,
          itemStyle: { color: pct >= 0 ? t.bull : t.bear, opacity: 0.3 + 0.7 * ratio },
        };
      });
      return {
        aria: { enabled: true },
        backgroundColor: 'transparent',
        tooltip: {
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
          formatter: (raw: unknown) => {
            const p = raw as { data?: TileDatum };
            const d = p.data?.tile;
            if (!d) return '';
            return [
              `<b>${d.symbol}</b> ${p.data?.pctLabel ?? ''}`,
              `O ${f2(d.open)} · H ${f2(d.high)} · L ${f2(d.low)}`,
              `LTP ${f2(d.ltp)}`,
              `OI ${d.oi == null ? '—' : compactInt(d.oi)}`,
              d.interpretation
                ? `OI: ${oiIntMeta(d.interpretation).label}${d.oiChange != null ? ` (${d.oiChange >= 0 ? '+' : ''}${compactInt(d.oiChange)})` : ''}`
                : null,
            ]
              .filter(Boolean)
              .join('<br/>');
          },
        },
        series: [
          {
            type: 'treemap',
            roam: false,
            nodeClick: false,
            breadcrumb: { show: false },
            width: '100%',
            height: '100%',
            label: {
              show: true,
              color: '#ffffff',
              fontSize: 11,
              overflow: 'truncate',
              formatter: (raw: unknown) => {
                const p = raw as { name: string; data: TileDatum };
                return `${p.name}\n${p.data.pctLabel}`;
              },
            },
            itemStyle: { borderColor: t.surface1, borderWidth: 1, gapWidth: 1 },
            data: data2,
          },
        ],
      };
    },
    [visibleTiles],
  );

  return (
    <div>
      <h1 className="ay-sr-only">Futures OI Buzz</h1>

      <div className="mb-2 flex flex-wrap items-center gap-2">
        <Select
          value={index}
          options={indices}
          onChange={setIndex}
          ariaLabel="Index"
          placeholder="Index…"
        />
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search symbol…"
          aria-label="Search symbol"
          className="h-9 w-40 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
        />
        <GoButton onClick={() => void q.refetch()} loading={q.isFetching} />
        {data && (
          <span className="ml-auto text-sm">
            <span className="text-bull">Advance: {data.advance}</span>
            <span className="text-ay-muted"> / </span>
            <span className="text-bear">Decline: {data.decline}</span>
          </span>
        )}
      </div>

      <p className="mb-2 text-xs text-ay-muted">
        Oi Buzz (Change in % wise) · tile size + colour ∝ near-month future % change
      </p>

      {data && visibleTiles.length > 0 ? (
        <EChart
          makeOption={makeOption}
          height={520}
          ariaLabel={`${index} constituent % change heatmap`}
        />
      ) : (
        <p className="py-12 text-center text-sm text-ay-muted">
          {data ? 'No matching constituents.' : 'No futures data for this index right now.'}
        </p>
      )}
    </div>
  );
}
