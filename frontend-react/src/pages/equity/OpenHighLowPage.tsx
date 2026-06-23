import { useEffect, useMemo, useState } from 'react';
import { useOiBuzzIndices, useOpenHighLow } from '../../api/oiAnalytics.ts';
import type { OpenHighSetup } from '../../api/types.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// Equity → Open=High / Open=Low (oipulse): which index constituents opened AT their day high (O=H,
// bearish — capped at the open) or day low (O=L, bullish). Computed live from the near-month future
// quote (day open/high/low + LTP); farPct = how far the LTP sits from that level. The triggered-time +
// probability columns of the full oipulse page need intraday tick history we don't capture — v1 shows
// the core setups (no trigger time).

const dec = (s: string | null) => (s == null ? '—' : formatDecimal(s, 2));

function levelTable(level: 'high' | 'low') {
  const cols: DataColumn<OpenHighSetup>[] = [
    { id: 'symbol', header: 'Name', align: 'left', render: (r) => r.symbol, sortValue: (r) => r.symbol, mobileLabel: 'Name' },
    { id: 'open', header: 'Day Open', render: (r) => dec(r.dayOpen), mobileLabel: 'Day Open' },
    {
      id: 'level',
      header: level === 'high' ? 'Day High' : 'Day Low',
      render: (r) => dec(level === 'high' ? r.dayHigh : r.dayLow),
      mobileLabel: level === 'high' ? 'Day High' : 'Day Low',
    },
    { id: 'ltp', header: 'LTP', render: (r) => dec(r.ltp), mobileLabel: 'LTP' },
    {
      id: 'far',
      header: 'Far %',
      render: (r) => <ValueDeltaCell value={r.farPct} suffix="%" />,
      sortValue: (r) => Number(r.farPct ?? 0),
      mobileLabel: 'Far %',
    },
  ];
  return cols;
}

export function OpenHighLowPage() {
  const indicesQ = useOiBuzzIndices();
  const indices = useMemo(() => indicesQ.data ?? [], [indicesQ.data]);
  const [index, setIndex] = useState<string | null>(null);

  useEffect(() => {
    if (!index && indices.length) setIndex(indices[0]);
  }, [index, indices]);

  const q = useOpenHighLow(index);
  const data = q.data ?? null;

  const ohCols = useMemo(() => levelTable('high'), []);
  const olCols = useMemo(() => levelTable('low'), []);

  return (
    <div>
      <h1 className="mb-2 text-base font-semibold text-ay-text">Open = High / Open = Low</h1>

      <div className="mb-2 flex flex-wrap items-center gap-2">
        <Select value={index} options={indices} onChange={setIndex} ariaLabel="Index" placeholder="Index…" />
        <GoButton onClick={() => void q.refetch()} loading={q.isFetching} />
        {data?.asOf && <span className="text-xs text-ay-muted">as on {data.asOf}</span>}
      </div>

      <p className="mb-3 text-xs text-ay-muted">
        O=H = opened at the day high (bearish) · O=L = opened at the day low (bullish) · Far % = LTP vs that level
      </p>

      {data ? (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <div>
            <h2 className="mb-1 text-sm font-semibold text-bear">Open = High (bearish): {data.openHigh.length}</h2>
            <DataTable
              columns={ohCols}
              rows={data.openHigh}
              rowKey={(r) => r.symbol}
              pageSize={50}
              ariaLabel={`${data.index} opened at day high`}
              emptyMessage="None opened at the day high."
            />
          </div>
          <div>
            <h2 className="mb-1 text-sm font-semibold text-bull">Open = Low (bullish): {data.openLow.length}</h2>
            <DataTable
              columns={olCols}
              rows={data.openLow}
              rowKey={(r) => r.symbol}
              pageSize={50}
              ariaLabel={`${data.index} opened at day low`}
              emptyMessage="None opened at the day low."
            />
          </div>
        </div>
      ) : (
        <p className="py-12 text-center text-sm text-ay-muted">No data for this index right now.</p>
      )}
    </div>
  );
}
