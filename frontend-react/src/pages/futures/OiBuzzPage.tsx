import { useCallback, useEffect, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { LayoutGrid } from 'lucide-react';
import { useOiBuzz, useOiBuzzIndices } from '../../api/oiAnalytics.ts';
import type { OiBuzzTile } from '../../api/types.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { oiIntMeta } from '../../core/oiInterpretation.ts';
import { formatDecimal } from '../../lib/decimal.ts';

// Futures OI Buzz (oipulse "Futures Heatmap"): one treemap tile per index constituent, sized + coloured
// by the near-month future's intraday % change (green gainers → red losers, intensity by magnitude).
// The raw feed carries no index weight, so v1 sizes by |% change| (owner's call). Header shows the
// advance/decline split; search locates a symbol; the OI-change interpretation badge is deferred to v2.
// Revamp rollout: visible display-face H1 (was ay-sr-only — text preserved). The data region stays on
// its existing empty path — the query is symbol-gated and the empty splits query-gap vs search-miss.

const compactInt = (n: number) => new Intl.NumberFormat('en-IN', { notation: 'compact' }).format(n);
const num = (s: string | null): number => (s == null ? 0 : Number(s));
const f2 = (s: string | null) => (s == null ? '—' : formatDecimal(s, 2));

/** Darken a hex/rgb colour toward black by factor `f` (0..1). Darker tiles keep the white labels
 *  readable (#8) — white-on-bright-green was low-contrast, and echarts treemap labels ignore a
 *  background plate, so the fix is the tile colour, not the label. */
function darken(color: string, f: number): string {
  const hex = /^#([0-9a-f]{6})$/i.exec(color);
  if (hex) {
    const n = parseInt(hex[1], 16);
    return `rgb(${Math.round(((n >> 16) & 255) * f)},${Math.round(((n >> 8) & 255) * f)},${Math.round((n & 255) * f)})`;
  }
  const rgb = /rgba?\(([^)]+)\)/i.exec(color);
  if (rgb) {
    const [r, g, b] = rgb[1].split(',').map((x) => Math.round(Number(x.trim()) * f));
    return `rgb(${r},${g},${b})`;
  }
  return color;
}

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
          itemStyle: { color: darken(pct >= 0 ? t.bull : t.bear, 0.6), opacity: 0.45 + 0.55 * ratio },
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
              // Bold white + a thin dark stroke on the now-darkened tiles (see `darken` above) — the
              // contrast comes from the darker tile, not a label plate (which echarts treemap ignores).
              color: '#ffffff',
              fontSize: 11,
              fontWeight: 700,
              textBorderColor: 'rgba(0,0,0,0.5)',
              textBorderWidth: 1,
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
    <LoadBeat>
      <PageHeader
        title="Futures OI Buzz"
        subtitle="Index-constituent heatmap — tile size and colour ∝ near-month future % change"
      />

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
          className="h-9 w-full sm:w-40 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
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

      <QueryState
        query={q}
        skeleton={<Skeleton variant="chart-block" className="h-72 sm:h-96 lg:h-[520px]" />}
        empty={{
          icon: LayoutGrid,
          title: 'No futures data for this index right now.',
        }}
      >
        {() =>
          visibleTiles.length > 0 ? (
            <BeatBlock className="card shadow-e1">
              <EChart
                makeOption={makeOption}
                className="h-72 sm:h-96 lg:h-[520px]"
                ariaLabel={`${index} constituent % change heatmap`}
              />
            </BeatBlock>
          ) : (
            <p className="py-12 text-center text-sm text-ay-muted">No matching constituents.</p>
          )
        }
      </QueryState>
    </LoadBeat>
  );
}
