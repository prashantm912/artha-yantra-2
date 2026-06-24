import { useCallback, useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { Inbox, TrendingDown, TrendingUp } from 'lucide-react';
import { useFiiDiiCash } from '../../api/oiAnalytics.ts';
import { foldFiiDiiCash, type FiiDiiCashRow } from '../../api/fiiDiiCashFold.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { PageHeader, LiveDot } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { addDecimal, compareDecimal, formatDecimal, isNegative } from '../../lib/decimal.ts';

// FII/DII Capital Market (oipulse §fii-dii/capital-market): FII + DII cash buy/sell/net flows (₹ Crore).
// Two net-value bar series (FII Net, DII Net — green +, red −) + the detailed flows table with the
// computed In-Market net (= FII Net + DII Net). Daily; newest first in the table, oldest→newest in the
// chart. Bar plotting is numeric (echarts); the table keeps exact-decimal display.
// Revamp Phase 5 (§4.3): visible display-face H1 (text preserved), a NEW elevated metric strip folded
// from the existing rows (no new query/field), a carded chart consuming the --ay-chart-* tokens, the
// DataTable with the Date column pinned sticky-left (still read-only), and QueryState over the region.

const isoDaysAgo = (days: number): string => {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
};

const n = (s: string | null): number => (s == null ? 0 : Number(s));
const cr = (s: string | null) => (s ? formatDecimal(s, 2) : '—');

// One elevated metric tile: uppercase wide-tracked label / signed mono value (sign + arrow, never
// colour-only) / muted context sub-line. Exact-decimal in (never parseFloat).
function MetricStat({ label, value, sub }: { label: string; value: string | null; sub: string }) {
  const zero = value == null || compareDecimal(value, '0') === 0;
  const neg = value != null && isNegative(value);
  const tone = zero ? 'text-ay-text' : neg ? 'text-bear' : 'text-bull';
  const Arrow = zero ? null : neg ? TrendingDown : TrendingUp;
  const sign = value == null ? '' : neg || zero ? '' : '+';
  return (
    <div className="card shadow-e1">
      <div className="text-caption uppercase tracking-wide text-ay-muted">{label}</div>
      <div className={`mt-1 flex items-center gap-1 ${tone}`}>
        {Arrow && <Arrow aria-hidden="true" className="size-4" />}
        <span className="text-h3 nums font-semibold">
          {value == null ? '—' : `${sign}${formatDecimal(value, 2)}`}
        </span>
      </div>
      <div className="mt-0.5 text-caption text-ay-muted">{sub}</div>
    </div>
  );
}

export function FiiDiiCapitalMarketPage() {
  const to = useMemo(() => isoDaysAgo(0), []);
  const from = useMemo(() => isoDaysAgo(420), []);
  const q = useFiiDiiCash(from, to);

  const rows = useMemo(() => foldFiiDiiCash(q.data ?? []), [q.data]);
  const asc = useMemo(() => [...rows].reverse(), [rows]); // chart reads oldest→newest

  // FII Net trailing-20-session Σ (exact decimal, no new query/field). Skips null nets.
  const fiiNet20 = useMemo(() => {
    let sum: string | null = null;
    for (const r of rows.slice(0, 20)) {
      if (r.fiiNet == null) continue;
      sum = sum == null ? r.fiiNet : addDecimal(sum, r.fiiNet);
    }
    return sum;
  }, [rows]);

  const latest = rows[0];

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      // Per-bar colour by sign — encoded on each data item (avoids the echarts callback-param typing).
      const bars = (pick: (r: FiiDiiCashRow) => string | null) =>
        asc.map((r) => {
          const v = n(pick(r));
          return { value: v, itemStyle: { color: v >= 0 ? t.bull : t.bear } };
        });
      // ₹-formatted tooltip values (+sign, en-IN grouping, Cr suffix).
      const fmt = (v: number) => `${v >= 0 ? '+' : ''}${v.toLocaleString('en-IN')} Cr`;
      return {
        aria: { enabled: true },
        textStyle: { color: t.text },
        legend: { show: false },
        grid: { left: 64, right: 16, top: 16, bottom: 56 },
        tooltip: {
          trigger: 'axis',
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
          axisPointer: { type: 'cross', lineStyle: { color: t.crosshair } },
          valueFormatter: (v) => (typeof v === 'number' ? fmt(v) : String(v)),
        },
        xAxis: {
          type: 'category',
          data: asc.map((r) => r.tradeDate),
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted },
        },
        yAxis: {
          type: 'value',
          name: '₹ Cr',
          scale: true,
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted },
        },
        dataZoom: [
          { type: 'inside', start: 0, end: 100 },
          { type: 'slider', start: 0, end: 100, height: 18, bottom: 24, borderColor: t.border, textStyle: { color: t.muted } },
        ],
        series: [
          { name: 'FII Net', type: 'bar', data: bars((r) => r.fiiNet) },
          { name: 'DII Net', type: 'bar', data: bars((r) => r.diiNet) },
        ],
      };
    },
    [asc],
  );

  // oipulse: "No sort on any column" — columns omit sortValue so the table is read-only. Date is pinned
  // sticky-left (§4.3.E) — additive, no selector change (still <th scope="col">).
  const columns: DataColumn<FiiDiiCashRow>[] = [
    { id: 'date', header: 'Date', align: 'left', pin: 'left', render: (r) => r.tradeDate, mobileLabel: 'Date' },
    { id: 'fiiBuy', header: 'FII Buy', render: (r) => cr(r.fiiBuy), mobileLabel: 'FII Buy' },
    { id: 'fiiSell', header: 'FII Sell', render: (r) => cr(r.fiiSell), mobileLabel: 'FII Sell' },
    { id: 'fiiNet', header: 'FII Net', render: (r) => <ValueDeltaCell value={r.fiiNet} />, mobileLabel: 'FII Net' },
    { id: 'inMarket', header: 'In Market', render: (r) => <ValueDeltaCell value={r.inMarket} />, mobileLabel: 'In Market' },
    { id: 'diiNet', header: 'DII Net', render: (r) => <ValueDeltaCell value={r.diiNet} />, mobileLabel: 'DII Net' },
    { id: 'diiBuy', header: 'DII Buy', render: (r) => cr(r.diiBuy), mobileLabel: 'DII Buy' },
    { id: 'diiSell', header: 'DII Sell', render: (r) => cr(r.diiSell), mobileLabel: 'DII Sell' },
  ];

  const dateSub = latest ? `₹ Cr · ${latest.tradeDate}` : '₹ Cr';

  return (
    <LoadBeat>
      <PageHeader
        title="FII/DII Capital Market Activity"
        subtitle="Values in ₹ Crore · In Market = FII Net + DII Net · green = net buy, red = net sell"
        right={<LiveDot detail={latest ? `as on ${latest.tradeDate} · 420d` : '420d'} />}
      />

      <QueryState
        query={q}
        isEmpty={() => rows.length === 0}
        empty={{ icon: Inbox, title: 'No FII/DII cash flow data for this window.' }}
        errorTitle="Couldn't load FII/DII cash flows"
        skeleton={
          <div className="space-y-4">
            <Skeleton variant="metric-strip" cols={4} />
            <Skeleton variant="chart-block" height={300} />
            <Skeleton variant="table-rows" rows={6} cols={8} />
          </div>
        }
      >
        {() => (
          <>
            {/* B. Elevated metric strip (NEW hero element) — folded from rows, no new query. */}
            <BeatStrip className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
              <BeatItem>
                <MetricStat label="FII Net" value={latest?.fiiNet ?? null} sub={dateSub} />
              </BeatItem>
              <BeatItem>
                <MetricStat label="DII Net" value={latest?.diiNet ?? null} sub={dateSub} />
              </BeatItem>
              <BeatItem>
                <MetricStat label="In Market" value={latest?.inMarket ?? null} sub={dateSub} />
              </BeatItem>
              <BeatItem>
                <MetricStat label="FII Net 20-sess Σ" value={fiiNet20} sub="trailing 20 sessions" />
              </BeatItem>
            </BeatStrip>

            {/* D. Chart card — carded, header swatches; canvas consumes --ay-chart-* tokens. */}
            <BeatBlock className="card shadow-e1 mb-4">
              <div className="mb-2 flex items-center justify-between">
                <h2 className="text-h3 text-ay-text">Net cash flow per day</h2>
                <div className="flex items-center gap-3 text-caption text-ay-muted">
                  <span className="inline-flex items-center gap-1">
                    <span aria-hidden="true" className="inline-block size-2.5 rounded-sm bg-bull" />
                    FII Net
                  </span>
                  <span className="inline-flex items-center gap-1">
                    <span aria-hidden="true" className="inline-block size-2.5 rounded-sm bg-bear" />
                    DII Net
                  </span>
                </div>
              </div>
              <EChart makeOption={makeOption} height={300} ariaLabel="FII Net and DII Net cash flow bars per day" />
            </BeatBlock>

            {/* E. Table card. */}
            <BeatBlock>
              <DataTable
                columns={columns}
                rows={rows}
                rowKey={(r) => r.tradeDate}
                pageSize={25}
                ariaLabel="Detailed FII/DII capital market activity"
                emptyMessage="No FII/DII cash flow data for this window."
              />
            </BeatBlock>
          </>
        )}
      </QueryState>
    </LoadBeat>
  );
}
