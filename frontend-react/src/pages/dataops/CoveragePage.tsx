import { useMemo } from 'react';
import { useCoverage, useQuota, type CoverageRow } from '../../api/dataops.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { QuotaGauge } from '../../components/dataops/QuotaGauge.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, BeatItem, BeatStrip, LoadBeat } from '../../components/LoadBeat.tsx';

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
      help: 'The index whose option/future contracts these rows cover (e.g. NIFTY or SENSEX).',
      align: 'left',
      sortValue: (r) => r.underlying,
      sortType: 'text',
      render: (r) => r.underlying,
      mobileLabel: 'Underlying',
    },
    {
      id: 'exchange',
      header: 'Exchange',
      help: 'The exchange the contracts trade on (e.g. NFO for NIFTY options, BFO for SENSEX options).',
      align: 'left',
      sortValue: (r) => r.exchange,
      sortType: 'text',
      render: (r) => r.exchange,
      mobileLabel: 'Exchange',
    },
    {
      id: 'contracts',
      header: 'Contracts',
      help: 'Total number of registered contracts in this group, whether or not their candles have been backfilled yet.',
      align: 'right',
      sortValue: (r) => r.contracts,
      sortType: 'number',
      render: (r) => r.contracts.toLocaleString(),
      mobileLabel: 'Contracts',
    },
    {
      id: 'complete',
      header: 'Complete',
      help: 'Contracts whose candle history is fully backfilled end to end.',
      align: 'right',
      sortValue: (r) => r.complete,
      sortType: 'number',
      render: (r) => r.complete.toLocaleString(),
      mobileLabel: 'Complete',
    },
    {
      id: 'partial',
      header: 'Partial',
      help: 'Contracts with some candles captured but gaps remaining — these still need more backfill.',
      align: 'right',
      sortValue: (r) => r.partial,
      sortType: 'number',
      render: (r) => r.partial.toLocaleString(),
      mobileLabel: 'Partial',
    },
    {
      id: 'completePct',
      header: 'Complete %',
      help: 'Share of this group’s contracts that are fully backfilled (complete ÷ contracts).',
      align: 'right',
      sortValue: (r) => (r.contracts > 0 ? (r.complete / r.contracts) * 100 : 0),
      sortType: 'number',
      render: (r) => pct(r.complete, r.contracts),
      mobileLabel: 'Complete %',
    },
    {
      id: 'candleRows',
      header: 'Candle rows',
      help: 'Total candle bars stored across all contracts in this group — the raw volume of captured price history.',
      align: 'right',
      sortValue: (r) => r.candleRows,
      sortType: 'number',
      render: (r) => r.candleRows.toLocaleString(),
      mobileLabel: 'Candle rows',
    },
    {
      id: 'minExpiry',
      header: 'Min expiry',
      help: 'The earliest contract expiry date captured in this group.',
      align: 'left',
      sortValue: (r) => r.minExpiry,
      sortType: 'text',
      render: (r) => r.minExpiry ?? '—',
      mobileLabel: 'Min expiry',
    },
    {
      id: 'maxExpiry',
      header: 'Max expiry',
      help: 'The latest contract expiry date captured in this group.',
      align: 'left',
      sortValue: (r) => r.maxExpiry,
      sortType: 'text',
      render: (r) => r.maxExpiry ?? '—',
      mobileLabel: 'Max expiry',
    },
  ];

  return (
    <LoadBeat>
      <PageHeader
        title="Data Coverage"
        help="A per-underlying, per-exchange summary of the expired-contract backfill — how many contracts are registered and how many have complete versus partial candle history; the top pills are grand totals."
      />

      <BeatStrip className="mb-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
        <BeatItem>
          <StatPill label="Contracts" value={totals.contracts} />
        </BeatItem>
        <BeatItem>
          <StatPill label="Complete" value={totals.complete} />
        </BeatItem>
        <BeatItem>
          <StatPill label="Partial" value={totals.partial} />
        </BeatItem>
        <BeatItem>
          <StatPill label="Candle rows" value={totals.candleRows} />
        </BeatItem>
      </BeatStrip>

      <BeatBlock>
        <QueryState
          query={coverage}
          empty={{ title: 'No coverage captured yet.' }}
          skeleton={<Skeleton variant="table-rows" rows={8} cols={6} />}
        >
          {() => (
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
        </QueryState>

        <div className="mt-3">
          <QuotaGauge configured={quota.data?.configured ?? false} windows={quota.data?.windows ?? []} />
        </div>
      </BeatBlock>
    </LoadBeat>
  );
}

function StatPill(props: { label: string; value: number }) {
  return (
    <div className="card shadow-e1">
      <div className="text-caption uppercase tracking-wide text-ay-muted">{props.label}</div>
      <div className="nums text-lg font-semibold text-ay-text">{props.value.toLocaleString()}</div>
    </div>
  );
}
