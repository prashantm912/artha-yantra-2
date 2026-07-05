import { useMemo, useState } from 'react';
import { Stepper } from '../../components/dataops/Stepper.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  downloadExport,
  downloadBulkExport,
  useExportContracts,
  useExportExpiries,
  type ExportContract,
} from '../../api/dataops.ts';
import { ApiError } from '../../api/client.ts';
import { cn } from '../../lib/cn.ts';

// B6 Export wizard (Data Ops console, route /data-ops/export). A four-step picker —
// Underlying → Expiry → Contract → Download — that resolves one registered contract and
// streams its candles to a file via downloadExport. Each step is gated: Next stays disabled
// until the current step is satisfied.

const STEPS = ['Underlying', 'Expiry', 'Contract', 'Download'];
const UNDERLYINGS = ['NIFTY', 'SENSEX'];

/** Shift a YYYY-MM-DD date string by a number of days, preserving the YYYY-MM-DD shape. */
function shiftDate(iso: string, days: number): string {
  const d = new Date(iso + 'T00:00:00Z');
  if (Number.isNaN(d.getTime())) return iso;
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

function strikeLabel(strike: ExportContract['strike']): string {
  return strike == null ? '—' : String(strike);
}

export function ExportWizardPage() {
  const [step, setStep] = useState(0);
  const [underlying, setUnderlying] = useState<string | null>(null);
  const [expiry, setExpiry] = useState<string | null>(null);
  const [contract, setContract] = useState<ExportContract | null>(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [format, setFormat] = useState<'csv' | 'json'>('csv');
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const expiriesQ = useExportExpiries(underlying);
  const contractsQ = useExportContracts(underlying, expiry);

  const expiries = useMemo(() => expiriesQ.data ?? [], [expiriesQ.data]);
  const contracts = useMemo(() => contractsQ.data ?? [], [contractsQ.data]);

  function pickUnderlying(next: string) {
    setUnderlying(next);
    setExpiry(null);
    setContract(null);
  }

  function pickExpiry(next: string) {
    setExpiry(next);
    setContract(null);
    // Default the download window to the 30 days leading up to the expiry.
    setFrom(shiftDate(next, -30));
    setTo(next);
  }

  function goNext() {
    setError(null);
    setStep((s) => Math.min(s + 1, STEPS.length - 1));
  }

  function goPrev() {
    setError(null);
    setStep((s) => Math.max(s - 1, 0));
  }

  const stepSatisfied =
    step === 0
      ? underlying != null
      : step === 1
        ? expiry != null
        : step === 2
          ? contract != null
          : true;

  async function handleDownload() {
    if (!contract) return;
    setDownloading(true);
    setError(null);
    try {
      await downloadExport({
        exchange: contract.exchange,
        symbol: contract.tradingsymbol,
        from,
        to,
        format,
      });
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e.message || `Export failed (HTTP ${e.status}).`);
      } else {
        setError(e instanceof Error ? e.message : 'Export failed.');
      }
    } finally {
      setDownloading(false);
    }
  }

  // Bulk: zip EVERY contract of the chosen (underlying, expiry) — the whole chain in one download.
  async function handleBulkDownload() {
    if (!underlying || !expiry) return;
    setDownloading(true);
    setError(null);
    try {
      await downloadBulkExport({ underlying, expiry, from, to, format });
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e.message || `Bulk export failed (HTTP ${e.status}).`);
      } else {
        setError(e instanceof Error ? e.message : 'Bulk export failed.');
      }
    } finally {
      setDownloading(false);
    }
  }

  return (
    <LoadBeat>
      <PageHeader
        title="Export Wizard"
        help="A four-step picker — underlying, expiry, contract, then download — that streams one registered contract's candle history to a CSV or JSON file."
      />

      <div className="mx-auto max-w-3xl">
        <Stepper steps={STEPS} current={step} />

        <BeatBlock className="mt-4 rounded-md border border-ay-border bg-surface-1 p-4">
          {step === 0 && (
            <fieldset>
              <legend className="mb-2 text-xs font-semibold text-ay-muted">
                Choose an underlying
              </legend>
              <div className="flex gap-2">
                {UNDERLYINGS.map((u) => {
                  const active = underlying === u;
                  return (
                    <button
                      key={u}
                      type="button"
                      aria-pressed={active}
                      onClick={() => pickUnderlying(u)}
                      className={cn(
                        'rounded-md border px-4 py-2 text-sm',
                        active
                          ? 'border-accent bg-accent text-surface-0'
                          : 'border-ay-border bg-surface-1 text-ay-text hover:border-accent',
                      )}
                    >
                      {u}
                    </button>
                  );
                })}
              </div>
            </fieldset>
          )}

          {step === 1 && (
            <fieldset>
              <legend className="mb-2 text-xs font-semibold text-ay-muted">
                Choose an expiry{underlying ? ` for ${underlying}` : ''}
              </legend>
              {expiriesQ.isLoading && <p className="text-sm text-ay-muted">Loading expiries…</p>}
              {expiriesQ.isError && (
                <p role="alert" className="text-sm text-bear">
                  Failed to load expiries.
                </p>
              )}
              {!expiriesQ.isLoading && !expiriesQ.isError && expiries.length === 0 && (
                <p className="text-sm text-ay-muted">No expiries available.</p>
              )}
              <div className="flex flex-wrap gap-2">
                {expiries.map((d) => {
                  const active = expiry === d;
                  return (
                    <button
                      key={d}
                      type="button"
                      aria-pressed={active}
                      onClick={() => pickExpiry(d)}
                      className={cn(
                        'rounded-md border px-3 py-1.5 text-sm',
                        active
                          ? 'border-accent bg-accent text-surface-0'
                          : 'border-ay-border bg-surface-1 text-ay-text hover:border-accent',
                      )}
                    >
                      {d}
                    </button>
                  );
                })}
              </div>
            </fieldset>
          )}

          {step === 2 && (
            <div>
              <p className="mb-2 text-xs font-semibold text-ay-muted">
                Choose a contract{expiry ? ` expiring ${expiry}` : ''}
              </p>
              {contractsQ.isLoading && (
                <p className="text-sm text-ay-muted">Loading contracts…</p>
              )}
              {contractsQ.isError && (
                <p role="alert" className="text-sm text-bear">
                  Failed to load contracts.
                </p>
              )}
              {!contractsQ.isLoading && !contractsQ.isError && contracts.length === 0 && (
                <p className="text-sm text-ay-muted">No contracts available.</p>
              )}
              {contracts.length > 0 && (
                <div className="max-h-72 overflow-auto rounded border border-ay-border">
                  <table
                    className="w-full text-left text-sm"
                    aria-label="Registered contracts"
                  >
                    <thead className="sticky top-0 bg-surface-2 text-xs text-ay-muted">
                      <tr>
                        <th scope="col" className="px-3 py-1.5 font-semibold">
                          Type
                        </th>
                        <th scope="col" className="px-3 py-1.5 font-semibold">
                          Strike
                        </th>
                        <th scope="col" className="px-3 py-1.5 font-semibold">
                          Tradingsymbol
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {contracts.map((c) => {
                        const active = contract?.tradingsymbol === c.tradingsymbol;
                        return (
                          <tr key={c.tradingsymbol}>
                            <td className="p-0" colSpan={3}>
                              <button
                                type="button"
                                aria-pressed={active}
                                onClick={() => setContract(c)}
                                className={cn(
                                  'grid w-full grid-cols-3 px-3 py-1.5 text-left text-ay-text',
                                  active
                                    ? 'bg-accent/20 ring-1 ring-accent'
                                    : 'hover:bg-surface-2',
                                )}
                              >
                                <span>{c.instrumentType}</span>
                                <span>{strikeLabel(c.strike)}</span>
                                <span className="font-mono">{c.tradingsymbol}</span>
                              </button>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}

          {step === 3 && contract && (
            <div className="space-y-4">
              <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
                <dt className="text-ay-muted">Underlying</dt>
                <dd className="text-ay-text">{underlying}</dd>
                <dt className="text-ay-muted">Expiry</dt>
                <dd className="text-ay-text">{expiry}</dd>
                <dt className="text-ay-muted">Exchange</dt>
                <dd className="text-ay-text">{contract.exchange}</dd>
                <dt className="text-ay-muted">Symbol</dt>
                <dd className="font-mono text-ay-text">{contract.tradingsymbol}</dd>
              </dl>

              <div className="flex flex-wrap gap-4">
                <div>
                  <label
                    htmlFor="export-from"
                    className="mb-1 block text-xs font-semibold text-ay-text"
                  >
                    From
                  </label>
                  <input
                    id="export-from"
                    type="date"
                    title="Start of the date window to export candles from (defaults to 30 days before expiry)"
                    value={from}
                    onChange={(e) => setFrom(e.target.value)}
                    className="rounded border border-ay-border bg-surface-2 px-2 py-1.5 text-sm text-ay-text focus:outline-none focus:ring-1 focus:ring-accent"
                  />
                </div>
                <div>
                  <label
                    htmlFor="export-to"
                    className="mb-1 block text-xs font-semibold text-ay-text"
                  >
                    To
                  </label>
                  <input
                    id="export-to"
                    type="date"
                    title="End of the date window to export candles up to (defaults to the contract's expiry)"
                    value={to}
                    onChange={(e) => setTo(e.target.value)}
                    className="rounded border border-ay-border bg-surface-2 px-2 py-1.5 text-sm text-ay-text focus:outline-none focus:ring-1 focus:ring-accent"
                  />
                </div>
              </div>

              <fieldset>
                <legend className="mb-1 text-xs font-semibold text-ay-text">Format</legend>
                <div className="flex gap-4">
                  {(['csv', 'json'] as const).map((f) => (
                    <label key={f} className="flex items-center gap-1.5 text-sm text-ay-text">
                      <input
                        type="radio"
                        name="export-format"
                        value={f}
                        checked={format === f}
                        onChange={() => setFormat(f)}
                      />
                      {f.toUpperCase()}
                    </label>
                  ))}
                </div>
              </fieldset>

              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={handleDownload}
                  disabled={downloading || !contract || !from || !to}
                  title="Stream this contract's candles over the chosen date window to a file in the selected format"
                  className="rounded-md bg-accent px-3 py-1.5 text-sm text-surface-0 hover:opacity-90 disabled:opacity-50"
                >
                  {downloading ? 'Downloading…' : 'Download'}
                </button>
                <button
                  type="button"
                  onClick={handleBulkDownload}
                  disabled={downloading || !underlying || !expiry || !from || !to}
                  title="Zip EVERY contract of this expiry over the chosen date window (the whole chain in one download)"
                  className="rounded-md border border-ay-border px-3 py-1.5 text-sm text-ay-text hover:bg-surface-2 disabled:opacity-50"
                >
                  {downloading ? 'Downloading…' : 'Download whole expiry (ZIP)'}
                </button>
              </div>

              {error && (
                <p role="alert" className="text-sm text-bear">
                  {error}
                </p>
              )}
            </div>
          )}
        </BeatBlock>

        <div className="mt-4 flex justify-between">
          <button
            type="button"
            onClick={goPrev}
            disabled={step === 0}
            className="rounded-md border border-ay-border bg-surface-1 px-3 py-1.5 text-sm text-ay-text hover:border-accent disabled:opacity-50"
          >
            Prev
          </button>
          {step < STEPS.length - 1 && (
            <button
              type="button"
              onClick={goNext}
              disabled={!stepSatisfied}
              className="rounded-md bg-accent px-3 py-1.5 text-sm text-surface-0 hover:opacity-90 disabled:opacity-50"
            >
              Next
            </button>
          )}
        </div>
      </div>
    </LoadBeat>
  );
}
