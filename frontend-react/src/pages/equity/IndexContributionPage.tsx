import { useEffect, useMemo, useState } from 'react';
import { useIndexContribution, useOiBuzzIndices } from '../../api/oiAnalytics.ts';
import type { ContribRow } from '../../api/types.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// Equity → Index Contribution (oipulse): how much each constituent pushes the index, split into
// Advances and Declines. Contribution = free-float weight × % change (weights seeded from the
// niftyindices factsheet; % change from the latest EQ bhavcopy). oipulse renders index POINTS, which
// need the live index level (not captured) — so this reports contribution in PERCENT (same ranking +
// sign; the per-index points scale is one constant multiple). Summed contributions = index % change.

const ltpCell = (r: ContribRow) => (
  <span className="tabular-nums">
    {r.close ? formatDecimal(r.close, 2) : '—'}{' '}
    <span className="text-xs">
      (<ValueDeltaCell value={r.changePct} suffix="%" />)
    </span>
  </span>
);

const columns: DataColumn<ContribRow>[] = [
  { id: 'rank', header: '#', render: (r) => String(r.rank), mobileLabel: '#' },
  { id: 'name', header: 'Name', align: 'left', render: (r) => r.symbol, mobileLabel: 'Name' },
  {
    id: 'point',
    header: 'Point',
    render: (r) => (r.points == null ? '—' : <ValueDeltaCell value={r.points} digits={2} />),
    mobileLabel: 'Point',
  },
  {
    id: 'contrib',
    header: 'Contribution',
    render: (r) => <ValueDeltaCell value={r.contribution} digits={4} suffix="%" />,
    mobileLabel: 'Contribution',
  },
  { id: 'ltp', header: 'LTP', render: ltpCell, mobileLabel: 'LTP' },
];

export function IndexContributionPage() {
  const indicesQ = useOiBuzzIndices();
  const indices = useMemo(() => indicesQ.data ?? [], [indicesQ.data]);
  const [index, setIndex] = useState<string | null>(null);

  useEffect(() => {
    if (!index && indices.length) setIndex(indices[0]);
  }, [index, indices]);

  const q = useIndexContribution(index);
  const data = q.data ?? null;

  return (
    <div>
      <h1 className="mb-2 text-base font-semibold text-ay-text">Index Contribution</h1>

      <div className="mb-2 flex flex-wrap items-center gap-2">
        <Select value={index} options={indices} onChange={setIndex} ariaLabel="Index" placeholder="Index…" />
        <GoButton onClick={() => void q.refetch()} loading={q.isFetching} />
        {data && (
          <span className="text-sm">
            {data.index} weighted change:{' '}
            <span className="font-semibold">
              <ValueDeltaCell value={data.indexChangePct} suffix="%" />
            </span>
            {data.asOf ? <span className="text-ay-muted"> · as on {data.asOf}</span> : null}
          </span>
        )}
      </div>

      <p className="mb-3 text-xs text-ay-muted">
        Contribution = free-float weight × % change · index points need the live index level (deferred)
      </p>

      {data ? (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <div>
            <h2 className="mb-1 text-sm font-semibold text-bull">
              Advances: {data.advances.length}
              {data.advancePoints != null ? (
                <> · Points <ValueDeltaCell value={data.advancePoints} digits={2} /></>
              ) : (
                <> · Σ <ValueDeltaCell value={data.advanceTotal} suffix="%" /></>
              )}
            </h2>
            <DataTable
              columns={columns}
              rows={data.advances}
              rowKey={(r) => r.symbol}
              pageSize={50}
              ariaLabel={`${data.index} advancing contributors`}
              emptyMessage="No advancers."
            />
          </div>
          <div>
            <h2 className="mb-1 text-sm font-semibold text-bear">
              Declines: {data.declines.length}
              {data.declinePoints != null ? (
                <> · Points <ValueDeltaCell value={data.declinePoints} digits={2} /></>
              ) : (
                <> · Σ <ValueDeltaCell value={data.declineTotal} suffix="%" /></>
              )}
            </h2>
            <DataTable
              columns={columns}
              rows={data.declines}
              rowKey={(r) => r.symbol}
              pageSize={50}
              ariaLabel={`${data.index} declining contributors`}
              emptyMessage="No decliners."
            />
          </div>
        </div>
      ) : (
        <p className="py-12 text-center text-sm text-ay-muted">No bhavcopy for this index yet.</p>
      )}
    </div>
  );
}
