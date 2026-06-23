import { useMemo } from 'react';
import { useCoverage, useQuota, type CoverageRow } from '../../api/dataops.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { QuotaGauge } from '../../components/dataops/QuotaGauge.tsx';

// B2 Coverage dashboard (route /data-ops/coverage): a per-(underlying, exchange) summary of the
// expired-contract backfill — how many contracts are registered, how many have complete vs partial
// candle coverage, the captured expiry span, and total candle rows. Top stat pills roll the columns
// up to grand totals; the Upstox quota gauge sits below so an operator sees collection headroom in
// the same view.

interface Totals {
  contracts: number;
  complete: number;
  partial: number;
  candleRows: number;
}

const pct = (complete: number, contracts: number): string =>
  contracts > 0 ? `${((complete / contracts) * 100).toFixed(0)}%` : '0%';

export function CoveragePage() {
  const coverage = useCoverage();
  const quota = useQuota();

  const rows = useMemo(() => coverage.data ?? [], [coverage.data]);

  const totals = useMemo<Totals>(
    () =>
      rows.reduce<Totals>(
        (acc, r) => ({
          contracts: acc.contracts + r.contracts,
          complete: acc.complete + r.complete,
          partial: acc.partial + r.partial,
          candleRows: acc.candleRows + r.candleRows,
        }),
        { contracts: 0, complete: 0, partial: 0, candleRows: 0 },
      ),
    [rows],
  );

  const columns: DataColumn<CoverageRow>[] = [
    {
      id: 'underlying',
      header: 'Underlying',
      align: 'left',
      sortValue: (r) => r.underlying,
      sortType: 'text',
      render: (r) => r.underlying,
      mobileLabel: 'Underlying',
    },
    {
      id: 'exchange',
      header: 'Exchange',
      align: 'left',
      sortValue: (r) => r.exchange,
      sortType: 'text',
      render: (r) => r.exchange,
      mobileLabel: 'Exchange',
    },
    {
      id: 'contracts',
      header: 'Contracts',
      align: 'right',
      sortValue: (r) => r.contracts,
      sortType: 'number',
      render: (r) => r.contracts.toLocaleString(),
      mobileLabel: 'Contracts',
    },
    {
      id: 'complete',
      header: 'Complete',
      align: 'right',
      sortValue: (r) => r.complete,
      sortType: 'number',
      render: (r) => r.complete.toLocaleString(),
      mobileLabel: 'Complete',
    },
    {
      id: 'partial',
      header: 'Partial',
      align: 'right',
      sortValue: (r) => r.partial,
      sortType: 'number',
      render: (r) => r.partial.toLocaleString(),
      mobileLabel: 'Partial',
    },
    {
      id: 'completePct',
      header: 'Complete %',
      align: 'right',
      sortValue: (r) => (r.contracts > 0 ? (r.complete / r.contracts) * 100 : 0),
      sortType: 'number',
      render: (r) => pct(r.complete, r.contracts),
      mobileLabel: 'Complete %',
    },
    {
      id: 'candleRows',
      header: 'Candle rows',
      align: 'right',
      sortValue: (r) => r.candleRows,
      sortType: 'number',
      render: (r) => r.candleRows.toLocaleString(),
      mobileLabel: 'Candle rows',
    },
    {
      id: 'minExpiry',
      header: 'Min expiry',
      align: 'left',
      sortValue: (r) => r.minExpiry,
      sortType: 'text',
      render: (r) => r.minExpiry ?? '—',
      mobileLabel: 'Min expiry',
    },
    {
      id: 'maxExpiry',
      header: 'Max expiry',
      align: 'left',
      sortValue: (r) => r.maxExpiry,
      sortType: 'text',
      render: (r) => r.maxExpiry ?? '—',
      mobileLabel: 'Max expiry',
    },
  ];

  return (
    <div>
      <h1 className="mb-2 text-center text-sm font-semibold text-ay-text">Data Coverage</h1>

      <dl className="mb-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
        <StatPill label="Contracts" value={totals.contracts} />
        <StatPill label="Complete" value={totals.complete} />
        <StatPill label="Partial" value={totals.partial} />
        <StatPill label="Candle rows" value={totals.candleRows} />
      </dl>

      {coverage.isLoading ? (
        <p className="py-6 text-center text-sm text-ay-muted">Loading coverage…</p>
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(r) => r.underlying + r.exchange}
          pageSize={20}
          initialSort={{ id: 'candleRows', dir: 'desc' }}
          ariaLabel="Expired-contract coverage"
          emptyMessage="No coverage captured yet."
        />
      )}

      <div className="mt-3">
        <QuotaGauge configured={quota.data?.configured ?? false} windows={quota.data?.windows ?? []} />
      </div>
    </div>
  );
}

function StatPill(props: { label: string; value: number }) {
  return (
    <div className="rounded-md border border-ay-border bg-surface-1 px-3 py-2">
      <dt className="text-xs text-ay-muted">{props.label}</dt>
      <dd className="text-lg font-semibold tabular-nums text-ay-text">{props.value.toLocaleString()}</dd>
    </div>
  );
}
