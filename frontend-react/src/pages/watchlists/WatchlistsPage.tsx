import { useEffect, useMemo, useState } from 'react';
import { formatDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import {
  SCREENER_PRESETS,
  useAddWatchItem,
  useCreateWatchlist,
  useInstrumentSearch,
  useRemoveWatchItem,
  useScreener,
  useWatchlists,
} from '../../api/watchlists.ts';

// /watchlists (master plan §20 parity, E-8): named watchlists (create / add / remove instruments via
// the typeahead) + the screener tab (preset runner). Live per-symbol tick prices are deferred (no tick
// bridge in React yet) — the lists manage membership; the screener shows the latest close.

export function WatchlistsPage() {
  const [tab, setTab] = useState<'lists' | 'screener'>('lists');
  const lists = useWatchlists();
  const createList = useCreateWatchlist();
  const addItem = useAddWatchItem();
  const removeItem = useRemoveWatchItem();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [newName, setNewName] = useState('');
  const [search, setSearch] = useState('');
  const hits = useInstrumentSearch(search);

  const rows = useMemo(() => lists.data?.items ?? [], [lists.data]);
  // default the selection to the first list once they load
  useEffect(() => {
    if (!selectedId && rows.length) setSelectedId(rows[0].id);
  }, [rows, selectedId]);
  const selected = rows.find((l) => l.id === selectedId) ?? null;

  const [preset, setPreset] = useState('momentum');
  const [runPreset, setRunPreset] = useState<string | null>(null);
  const screener = useScreener(runPreset ?? 'momentum', !!runPreset);

  const inputCls = 'h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text';

  return (
    <div>
      <h1 className="ay-sr-only">Watchlists & screener</h1>
      <div role="tablist" className="mb-4 flex gap-1 border-b border-ay-border">
        {(['lists', 'screener'] as const).map((t) => (
          <button
            key={t}
            type="button"
            role="tab"
            aria-selected={tab === t}
            onClick={() => setTab(t)}
            className={cn(
              '-mb-px border-b-2 px-4 py-2 text-sm font-medium',
              tab === t ? 'border-accent text-accent' : 'border-transparent text-ay-muted hover:text-ay-text',
            )}
          >
            {t === 'lists' ? 'Watchlists' : 'Screener'}
          </button>
        ))}
      </div>

      {tab === 'lists' && (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <Select
              value={selectedId}
              options={rows.map((l) => ({ value: l.id, label: l.name }))}
              onChange={setSelectedId}
              ariaLabel="Watchlist"
              placeholder="Watchlist"
            />
            <input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="New watchlist name" aria-label="New watchlist name" className={inputCls} />
            <button
              type="button"
              onClick={() => newName.trim() && createList.mutate(newName.trim(), { onSuccess: (l) => { setSelectedId(l.id); setNewName(''); } })}
              className="h-9 rounded-md bg-accent px-3 text-sm font-medium text-surface-0 hover:opacity-90"
            >
              Create
            </button>
          </div>

          {selected ? (
            <>
              <div className="relative mb-3 max-w-md">
                <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Add instrument…" aria-label="Add instrument" className={`${inputCls} w-full`} />
                {search.trim().length >= 2 && (hits.data?.length ?? 0) > 0 && (
                  <ul className="absolute z-10 mt-1 max-h-64 w-full overflow-auto rounded-md border border-ay-border bg-surface-1 shadow-lg">
                    {(hits.data ?? []).slice(0, 20).map((h) => (
                      <li key={`${h.exchange}:${h.tradingsymbol}`}>
                        <button
                          type="button"
                          onClick={() => {
                            addItem.mutate({ id: selected.id, item: { exchange: h.exchange, tradingsymbol: h.tradingsymbol } });
                            setSearch('');
                          }}
                          className="block w-full px-3 py-1.5 text-left text-sm hover:bg-surface-2"
                        >
                          {h.exchange}:{h.tradingsymbol} {h.name && <span className="text-ay-muted">— {h.name}</span>}
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>

              <div className="overflow-auto rounded-lg border border-ay-border">
                <table className="w-full border-collapse text-sm">
                  <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
                    <tr>
                      <th className="px-2 py-2 font-medium">Instrument</th>
                      <th className="px-2 py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {selected.items.map((it) => (
                      <tr key={`${it.exchange}:${it.tradingsymbol}`} className="border-t border-ay-border">
                        <td className="px-2 py-2">{it.exchange}:{it.tradingsymbol}</td>
                        <td className="px-2 py-2 text-right">
                          <button
                            type="button"
                            onClick={() => removeItem.mutate({ id: selected.id, item: it })}
                            className="px-1.5 text-xs text-bear hover:underline"
                          >
                            Remove
                          </button>
                        </td>
                      </tr>
                    ))}
                    {selected.items.length === 0 && (
                      <tr>
                        <td colSpan={2} className="px-2 py-6 text-center text-ay-muted">Empty — add an instrument above.</td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </>
          ) : (
            <p className="text-sm text-ay-muted">No watchlist selected. Create one to start tracking instruments.</p>
          )}
        </>
      )}

      {tab === 'screener' && (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <Select value={preset} options={[...SCREENER_PRESETS]} onChange={setPreset} ariaLabel="Preset" />
            <button
              type="button"
              onClick={() => setRunPreset(preset)}
              className="h-9 rounded-md bg-accent px-3 text-sm font-medium text-surface-0 hover:opacity-90"
            >
              Run
            </button>
          </div>
          <div className="overflow-auto rounded-lg border border-ay-border">
            <table className="w-full border-collapse text-sm">
              <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
                <tr>
                  <th className="px-2 py-2 font-medium">Instrument</th>
                  <th className="px-2 py-2 text-right font-medium">Close</th>
                  <th className="px-2 py-2 text-right font-medium">Value</th>
                  <th className="px-2 py-2 font-medium">Label</th>
                </tr>
              </thead>
              <tbody>
                {(screener.data?.items ?? []).map((r) => (
                  <tr key={`${r.exchange}:${r.tradingsymbol}`} className="border-t border-ay-border">
                    <td className="px-2 py-2">{r.exchange}:{r.tradingsymbol}</td>
                    <td className="px-2 py-2 text-right tabular-nums">{formatDecimal(r.latestClose, 2)}</td>
                    <td className="px-2 py-2 text-right tabular-nums">{r.value}</td>
                    <td className="px-2 py-2">{r.label ?? '—'}</td>
                  </tr>
                ))}
                {(!runPreset || (screener.data?.items ?? []).length === 0) && (
                  <tr>
                    <td colSpan={4} className="px-2 py-6 text-center text-ay-muted">
                      {runPreset ? 'No matches.' : 'Pick a preset and run the screener.'}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
