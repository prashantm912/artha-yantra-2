import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Stepper } from '../../components/dataops/Stepper.tsx';
import { MultiCheckboxGroup } from '../../components/dataops/MultiCheckboxGroup.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { triggerBackfill } from '../../api/dataops.ts';
import { ApiError } from '../../api/client.ts';
import { cn } from '../../lib/cn.ts';

const STEPS = ['Underlyings', 'Date range', 'Options', 'Review'];

const UNDERLYING_OPTIONS = [
  { value: 'NIFTY', label: 'NIFTY' },
  { value: 'SENSEX', label: 'SENSEX' },
];

/** Local YYYY-MM-DD for a Date, offset by `daysAgo` days. */
function isoDate(daysAgo: number): string {
  const d = new Date();
  d.setDate(d.getDate() - daysAgo);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
    d.getDate(),
  ).padStart(2, '0')}`;
}

/**
 * B3 Collection Wizard — guided 4-step setup over the existing
 * POST /market/admin/expired-backfill. Divergence: contract-type is not
 * selectable; the server always pulls both options and futures legs, and
 * the interval is fixed at 1-minute with strikes auto-bounded to ±20%.
 */
export function CollectionWizardPage() {
  const navigate = useNavigate();

  const [step, setStep] = useState(0);
  const [underlyings, setUnderlyings] = useState<string[]>(['NIFTY', 'SENSEX']);
  const [from, setFrom] = useState(isoDate(365));
  const [to, setTo] = useState(isoDate(0));
  const [force, setForce] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const stepValid = [
    underlyings.length >= 1,
    from <= to,
    true,
    true,
  ];
  const canNext = stepValid[step];
  const isLast = step === STEPS.length - 1;

  async function start(): Promise<void> {
    setSubmitting(true);
    setError(null);
    try {
      await triggerBackfill({ underlyings, from, to, force });
      navigate('/data-ops/status');
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 409) {
          setError('A backfill is already running.');
        } else if (err.status === 503) {
          setError('Backfill source is not configured.');
        } else {
          setError(err.message);
        }
      } else {
        setError('Failed to start backfill.');
      }
      setSubmitting(false);
    }
  }

  return (
    <LoadBeat>
      <PageHeader
        title="Collection Wizard"
        help="A guided four-step setup that kicks off the expired-contract backfill: pick the indices and date range, confirm the fixed options/futures settings, then start the job."
      />

      <div className="mx-auto max-w-2xl space-y-4">
        <Stepper steps={STEPS} current={step} />

        <BeatBlock className="space-y-4 rounded-md border border-ay-border bg-surface-1 p-4">
          {step === 0 && (
            <MultiCheckboxGroup
              legend="Indices"
              options={UNDERLYING_OPTIONS}
              selected={underlyings}
              onChange={setUnderlyings}
            />
          )}

          {step === 1 && (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div className="space-y-1">
                <label htmlFor="backfill-from" className="block text-xs text-ay-muted">
                  From
                </label>
                <input
                  id="backfill-from"
                  type="date"
                  title="Earliest date to collect contract history from (must be on or before the To date)"
                  value={from}
                  max={to}
                  onChange={(e) => setFrom(e.target.value)}
                  className="w-full rounded border border-ay-border bg-surface-2 px-2 py-1.5 text-sm text-ay-text"
                />
              </div>
              <div className="space-y-1">
                <label htmlFor="backfill-to" className="block text-xs text-ay-muted">
                  To
                </label>
                <input
                  id="backfill-to"
                  type="date"
                  title="Latest date to collect contract history up to (must be on or after the From date)"
                  value={to}
                  min={from}
                  onChange={(e) => setTo(e.target.value)}
                  className="w-full rounded border border-ay-border bg-surface-2 px-2 py-1.5 text-sm text-ay-text"
                />
              </div>
              {from > to && (
                <p className="text-xs text-bear sm:col-span-2">From date must be on or before To date.</p>
              )}
            </div>
          )}

          {step === 2 && (
            <div className="space-y-3">
              <label className="flex cursor-pointer items-center gap-2 rounded border border-ay-border bg-surface-2 px-2 py-1.5 text-sm text-ay-text hover:border-accent">
                <input
                  type="checkbox"
                  className="accent-accent"
                  title="Re-download contracts even if they're already marked complete (slower; use to repair suspect data)"
                  checked={force}
                  onChange={(e) => setForce(e.target.checked)}
                />
                <span>Force re-fetch complete contracts</span>
              </label>
              <p className="text-xs text-ay-muted">Interval is fixed at 1-minute.</p>
              <p className="text-xs text-ay-muted">
                Strikes are auto-bounded to &plusmn;20% server-side.
              </p>
              <p className="text-xs text-ay-muted">
                Contract type is not selectable — the server pulls both options and futures.
              </p>
            </div>
          )}

          {step === 3 && (
            <dl className="space-y-2 text-sm">
              <div className="flex justify-between gap-4">
                <dt className="text-ay-muted">Underlyings</dt>
                <dd className="text-right text-ay-text">{underlyings.join(', ') || '—'}</dd>
              </div>
              <div className="flex justify-between gap-4">
                <dt className="text-ay-muted">Date range</dt>
                <dd className="text-right text-ay-text">
                  {from} &rarr; {to}
                </dd>
              </div>
              <div className="flex justify-between gap-4">
                <dt className="text-ay-muted">Interval</dt>
                <dd className="text-right text-ay-text">1-minute</dd>
              </div>
              <div className="flex justify-between gap-4">
                <dt className="text-ay-muted">Force re-fetch</dt>
                <dd className="text-right text-ay-text">{force ? 'Yes' : 'No'}</dd>
              </div>
            </dl>
          )}
        </BeatBlock>

        {error && (
          <p role="alert" className="text-sm text-bear">
            {error}
          </p>
        )}

        <div className="flex justify-between">
          <button
            type="button"
            onClick={() => setStep((s) => Math.max(0, s - 1))}
            disabled={step === 0 || submitting}
            className="rounded-md border border-ay-border bg-surface-1 px-3 py-1.5 text-sm text-ay-text hover:border-accent disabled:opacity-50"
          >
            Prev
          </button>

          {isLast ? (
            <button
              type="button"
              onClick={() => void start()}
              disabled={submitting}
              className={cn(
                'rounded-md bg-accent px-3 py-1.5 text-sm text-surface-0 hover:opacity-90 disabled:opacity-50',
              )}
            >
              {submitting ? 'Starting…' : 'Start backfill'}
            </button>
          ) : (
            <button
              type="button"
              onClick={() => setStep((s) => Math.min(STEPS.length - 1, s + 1))}
              disabled={!canNext}
              className="rounded-md border border-ay-border bg-surface-1 px-3 py-1.5 text-sm text-ay-text hover:border-accent disabled:opacity-50"
            >
              Next
            </button>
          )}
        </div>
      </div>
    </LoadBeat>
  );
}
