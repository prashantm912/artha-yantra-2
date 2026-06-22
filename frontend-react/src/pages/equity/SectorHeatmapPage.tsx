import { useCallback, useEffect, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { useOiBuzzIndices, useSectorHeatmap } from '../../api/oiAnalytics.ts';
import type { SectorStockChange } from '../../api/types.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// Equity → Sector Heatmap (oipulse): a sector-GROUPED treemap of an index's constituents — sector boxes
// at level 1, stock tiles at level 2. Sized by |% change| (the feed carries no market cap), coloured by
// % change (green up → red down). EOD (latest bhavcopy session). Reuses the OI-Buzz index selector.

const num = (s: string | null): number => (s == null ? 0 : Number(s));
const f2 = (s: string | null) => (s == null ? '—' : formatDecimal(s, 2));

interface LeafDatum {
  name: string;
  value: number;
  pctLabel: string;
  tile: SectorStockChange;
  itemStyle: { color: string; opacity: number };
}

export function SectorHeatmapPage() {
  const indicesQ = useOiBuzzIndices();
  const indices = useMemo(() => indicesQ.data ?? [], [indicesQ.data]);
  const [index, setIndex] = useState<string | null>(null);

  useEffect(() => {
    if (!index && indices.length) setIndex(indices[0]);
  }, [index, indices]);

  const q = useSectorHeatmap(index);
  const tiles = useMemo(() => q.data?.tiles ?? [], [q.data]);

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const maxAbs = Math.max(0.01, ...tiles.map((s) => Math.abs(num(s.changePct))));
      const bySector = new Map<string, LeafDatum[]>();
      for (const s of tiles) {
        const pct = num(s.changePct);
        const ratio = Math.min(1, Math.abs(pct) / maxAbs);
        const leaf: LeafDatum = {
          name: s.symbol,
          value: Math.abs(pct) + 0.3,
          pctLabel: `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`,
          tile: s,
          itemStyle: { color: pct >= 0 ? t.bull : t.bear, opacity: 0.3 + 0.7 * ratio },
        };
        const arr = bySector.get(s.sector);
        if (arr) arr.push(leaf);
        else bySector.set(s.sector, [leaf]);
      }
      const data = [...bySector.entries()].map(([sector, leaves]) => ({
        name: sector,
        children: leaves,
      }));
      return {
        aria: { enabled: true },
        backgroundColor: 'transparent',
        tooltip: {
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
          formatter: (raw: unknown) => {
            const p = raw as { data?: LeafDatum; name?: string };
            const d = p.data?.tile;
            if (!d) return p.name ?? '';
            return [
              `<b>${d.symbol}</b> ${p.data?.pctLabel ?? ''}`,
              `${d.sector}`,
              `Close ${f2(d.close)} · Prev ${f2(d.prevClose)}`,
            ].join('<br/>');
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
            upperLabel: { show: true, height: 18, color: t.text, fontSize: 11 },
            levels: [
              { itemStyle: { borderColor: t.border, borderWidth: 2, gapWidth: 2 } },
              { itemStyle: { borderColor: t.surface1, borderWidth: 1, gapWidth: 1 } },
            ],
            label: {
              show: true,
              color: '#ffffff',
              fontSize: 10,
              overflow: 'truncate',
              formatter: (raw: unknown) => {
                const p = raw as { name: string; data: LeafDatum };
                return `${p.name}\n${p.data.pctLabel}`;
              },
            },
            data,
          },
        ],
      };
    },
    [tiles],
  );

  return (
    <div>
      <h1 className="ay-sr-only">Sector Heatmap</h1>

      <div className="mb-2 flex flex-wrap items-center gap-2">
        <Select value={index} options={indices} onChange={setIndex} ariaLabel="Index" placeholder="Index…" />
        <GoButton onClick={() => void q.refetch()} loading={q.isFetching} />
        {q.data?.asOf && <span className="text-xs text-ay-muted">as on {q.data.asOf}</span>}
      </div>

      <p className="mb-2 text-xs text-ay-muted">
        Constituents grouped by sector · tile size + colour ∝ % change vs prev close
      </p>

      {tiles.length > 0 ? (
        <EChart makeOption={makeOption} height={560} ariaLabel={`${index} sector-grouped % change heatmap`} />
      ) : (
        <p className="py-12 text-center text-sm text-ay-muted">No bhavcopy for this index yet.</p>
      )}
    </div>
  );
}
