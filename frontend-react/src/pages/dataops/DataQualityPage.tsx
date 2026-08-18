import { useState } from 'react';
import {
  useDataQuality,
  type CompletenessReport,
  type CompletenessRow,
} from '../../api/dataQuality.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { DateInput } from '../../components/atoms/DateInput.tsx';
import { cn } from '../../lib/cn.ts';

const SCOPE_SECTIONS = [
  { scope: 'chain_capture', label: 'Chain capture' },
  { scope: 'intraday_1m', label: 'Intraday 1m' },
  // Scope KEY stays 'bhavcopy_eq' -- it is persisted in data_quality rows and renaming it would
  // orphan every historical row. Only the LABEL moves, since the population is EQ+BE (H24 PR-5).
  { scope: 'bhavcopy_eq', label: 'Bhavcopy cash (EQ+BE)' },
] as const;

function StatusBadge({ ok }: { ok: boolean }) {
  return (
    <span
      className={cn(
        'inline-block rounded px-2 py-0.5 text-xs font-semibold ring-1',
        ok ? 'text-bull ring-bull/40' : 'text-bear ring-bear/40',
      )}
    >
      {ok ? 'OK' : 'FAIL'}
    </span>
  );
}

function coverage(value: number | null): string {
  return value == null
    ? '—'
    : `${value.toLocaleString('en-IN', { maximumFractionDigits: 2 })}%`;
}

const columns: DataColumn<CompletenessRow>[] = [
  {
    id: 'symbol',
    header: 'Symbol',
    pin: 'left',
    mobileLabel: 'Symbol',
    render: (row) => (
      <span className="inline-flex flex-col">
        <span className="font-medium text-ay-text">{row.symbol}</span>
        {row.detail && <span className="text-caption text-ay-muted">{row.detail}</span>}
      </span>
    ),
    sortValue: (row) => row.symbol,
    sortType: 'text',
  },
  {
    id: 'present',
    header: 'Present / expected',
    align: 'right',
    mobileLabel: 'Present / expected',
    render: (row) => (
      <span className="tabular-nums text-ay-text">
        {row.present.toLocaleString('en-IN')} / {row.expected.toLocaleString('en-IN')}
      </span>
    ),
    sortValue: (row) => row.present,
  },
  {
    id: 'coverage',
    header: 'Coverage',
    align: 'right',
    mobileLabel: 'Coverage',
    render: (row) => <span className="tabular-nums text-ay-text">{coverage(row.coveragePct)}</span>,
    sortValue: (row) => row.coveragePct,
  },
  {
    id: 'status',
    header: 'Status',
    align: 'center',
    mobileLabel: 'Status',
    render: (row) => <StatusBadge ok={row.ok} />,
    sortValue: (row) => (row.ok ? 0 : 1),
  },
];

function BhavcopySummary({ row }: { row: CompletenessRow }) {
  return (
    <div className="mb-2 flex flex-wrap items-center gap-x-3 gap-y-1 rounded border border-ay-border bg-surface-1 px-3 py-2">
      <span className="font-medium tabular-nums text-ay-text">
        {row.present.toLocaleString('en-IN')}/{row.expected.toLocaleString('en-IN')} cash symbols
      </span>
      <span className="tabular-nums text-ay-muted">{coverage(row.coveragePct)}</span>
      <StatusBadge ok={row.ok} />
      {row.detail && <span className="text-caption text-ay-muted">{row.detail}</span>}
    </div>
  );
}

function ScopeSection({
  scope,
  label,
  rows,
}: {
  scope: (typeof SCOPE_SECTIONS)[number]['scope'];
  label: string;
  rows: CompletenessRow[];
}) {
  const summary = scope === 'bhavcopy_eq' ? rows.find((row) => row.symbol === '__SUMMARY__') : undefined;
  const symbolRows = summary ? rows.filter((row) => row !== summary) : rows;
  const headingId = `data-quality-${scope}`;

  return (
    <section aria-labelledby={headingId}>
      <h2 id={headingId} className="mb-1 text-h3 text-accent">
        {label}
      </h2>
      {summary && <BhavcopySummary row={summary} />}
      <DataTable
        columns={columns}
        rows={symbolRows}
        rowKey={(row) => `${row.symbol}:${row.computedAt}`}
        ariaLabel={`${label} completeness`}
        initialSort={{ id: 'status', dir: 'desc' }}
        emptyMessage={scope === 'bhavcopy_eq' ? 'No individual symbol dropouts.' : 'No symbols reported.'}
      />
    </section>
  );
}

function ReportSections({ report }: { report: CompletenessReport }) {
  return (
    <div className="space-y-4">
      {SCOPE_SECTIONS.map(({ scope, label }) => (
        <ScopeSection
          key={scope}
          scope={scope}
          label={label}
          rows={report.items.filter((row) => row.scope === scope)}
        />
      ))}
    </div>
  );
}

export function DataQualityPage() {
  const [date, setDate] = useState<string | null>(null);
  const reportQ = useDataQuality(date);
  const reportDate = reportQ.data?.date ?? date ?? 'latest settled day';

  return (
    <LoadBeat>
      <PageHeader
        title="Data Quality"
        help="Nightly per-symbol completeness for chain capture, intraday one-minute bars, and the EQ bhavcopy. OK/FAIL comes from the backend report for the selected settled trading day."
        right={
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-caption text-ay-muted">
              Report date <span className="tabular-nums text-ay-text">{reportDate}</span>
            </span>
            <DateInput
              ariaLabel="Report date"
              value={date}
              onChange={setDate}
              title="Pick a settled trading day; clear the date to return to the latest report."
            />
          </div>
        }
      />

      <BeatBlock>
        <QueryState
          query={reportQ}
          isEmpty={(report) => report.items.length === 0}
          empty={{
            title: 'No report for this day.',
            hint: 'Choose another settled trading day or return to the latest report.',
          }}
          errorTitle="Couldn't load data quality"
        >
          {(report) => <ReportSections report={report} />}
        </QueryState>
      </BeatBlock>
    </LoadBeat>
  );
}
