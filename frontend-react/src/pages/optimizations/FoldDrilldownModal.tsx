import { Layers } from 'lucide-react';
import { formatDecimal } from '../../lib/decimal.ts';
import { Modal } from '../../components/dataops/Modal.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { REGIME_LABELS, useTrialFolds, type TrialFold } from '../../api/optimizations.ts';

// The Phase-39 per-trial fold drill-down (master plan §20, deferred follow-on to PR #129's guard
// columns). Click a leaderboard row → this read-only modal breaks each walk-forward fold's OOS down
// BY REGIME (Sharpe / ₹ expectancy / trade count) alongside the train-vs-OOS headline Sharpe and the
// test window. Decimals are formatted with the string-safe helper (never parseFloat). A full-window
// trial (no fold structure) → the "no walk-forward folds" message.

const dec = (v: string | null | undefined, digits: number) =>
  v == null ? '—' : formatDecimal(v, digits);

// Short ISO date (yyyy-MM-dd) for the test-window column — the fold timestamps are ISO instants.
const day = (iso: string) => iso.slice(0, 10);

function regimeColumn(label: string): DataColumn<TrialFold> {
  return {
    id: `regime-${label}`,
    header: label,
    align: 'right',
    headerClassName: 'text-ay-text',
    render: (fold) => {
      const stat = fold.regimeOos[label as keyof TrialFold['regimeOos']];
      if (!stat) return <span className="text-ay-muted">—</span>;
      return (
        <span title={`${label}: OOS Sharpe / ₹ expectancy / trades`}>
          {dec(stat.sharpe, 2)}
          <span className="text-ay-muted"> / </span>
          {dec(stat.expectancy, 0)}
          <span className="text-ay-muted"> / {stat.tradeCount}</span>
        </span>
      );
    },
  };
}

const COLUMNS: DataColumn<TrialFold>[] = [
  {
    id: 'fold',
    header: 'Fold',
    align: 'left',
    headerClassName: 'text-ay-text',
    render: (f) => <span className="font-medium text-ay-text">#{f.fold}</span>,
  },
  {
    id: 'test',
    header: 'Test window',
    align: 'left',
    headerClassName: 'text-ay-text',
    render: (f) => (
      <span className="text-ay-muted">
        {day(f.test.from)} → {day(f.test.to)}
      </span>
    ),
  },
  {
    id: 'trainSharpe',
    header: 'Train Sharpe',
    align: 'right',
    headerClassName: 'text-ay-text',
    render: (f) => dec(f.trainMetrics.sharpe, 2),
  },
  {
    id: 'oosSharpe',
    header: 'OOS Sharpe',
    align: 'right',
    headerClassName: 'text-ay-text',
    render: (f) => <span className="font-medium text-ay-text">{dec(f.oosMetrics.sharpe, 2)}</span>,
  },
  ...REGIME_LABELS.map((r) => regimeColumn(r)),
];

interface FoldDrilldownModalProps {
  sweepId: string;
  /** The trial whose folds to show; the modal is open iff this is non-null. */
  trialNumber: number | null;
  onClose: () => void;
}

export function FoldDrilldownModal({ sweepId, trialNumber, onClose }: FoldDrilldownModalProps) {
  const folds = useTrialFolds(sweepId, trialNumber);
  const rows = folds.data ?? [];

  return (
    <Modal
      open={trialNumber != null}
      onClose={onClose}
      icon={Layers}
      title={`Trial #${trialNumber ?? ''} — fold drill-down (OOS by regime)`}
    >
      {folds.isLoading ? (
        <p className="py-6 text-center text-sm text-ay-muted">Loading folds…</p>
      ) : folds.isError ? (
        <p className="py-6 text-center text-sm text-bear">Could not load folds for this trial.</p>
      ) : rows.length === 0 ? (
        <p className="py-6 text-center text-sm text-ay-muted">
          No walk-forward folds — this trial ran over the full window.
        </p>
      ) : (
        <>
          <p className="mb-2 text-xs text-ay-muted">
            Each per-regime cell is OOS Sharpe / ₹ expectancy / trade count; a regime that never
            traded in a fold shows —.
          </p>
          <DataTable
            columns={COLUMNS}
            rows={rows}
            rowKey={(f) => String(f.fold)}
            ariaLabel="Per-fold OOS metrics by regime"
          />
        </>
      )}
    </Modal>
  );
}
