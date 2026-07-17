import { useEffect, useMemo, useState } from 'react';
import { m } from 'motion/react';
import { formatDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  SCREENER_PRESETS,
  useAddWatchItem,
  useCreateWatchlist,
  useInstrumentSearch,
  useRemoveWatchItem,
  useScreener,
  useWatchlists,
  type ScreenerRow,
  type WatchItem,
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

  const itemColumns: DataColumn<WatchItem>[] = useMemo(
    () => [
      {
        id: 'instrument',
        header: 'Instrument',
        align: 'left',
        mobileLabel: 'Instrument',
        render: (it) => `${it.exchange}:${it.tradingsymbol}`,
      },
      {
        // Visually-hidden header (the house pattern — JobsPage/ScalperCockpitPage) keeps the
        // column's accessible name without painting a redundant "Actions" label. Unlike those
        // two, this one keeps a mobileLabel: Remove is reachable on a phone today via the
        // scrolling table, and dropping it from card mode would be a real regression.
        id: 'actions',
        header: 'Actions',
        headerClassName: 'ay-sr-only',
        mono: false,
        mobileLabel: 'Actions',
        render: (it) => (
          <button
            type="button"
            onClick={() => { if (selectedId && window.confirm(`Remove ${it.exchange}:${it.tradingsymbol} from this watchlist?`)) removeItem.mutate({ id: selectedId, item: it }); }}
            className="px-1.5 text-xs text-bear hover:underline"
          >
            Remove
          </button>
        ),
      },
    ],
    [selectedId, removeItem],
  );

  const screenerColumns: DataColumn<ScreenerRow>[] = useMemo(
    () => [
      {
        id: 'instrument',
        header: 'Instrument',
        align: 'left',
        mobileLabel: 'Instrument',
        render: (r) => `${r.exchange}:${r.tradingsymbol}`,
      },
      {
        id: 'close',
        header: 'Close',
        mobileLabel: 'Close',
        render: (r) => formatDecimal(r.latestClose, 2),
      },
      { id: 'value', header: 'Value', mobileLabel: 'Value', render: (r) => r.value },
      {
        id: 'label',
        header: 'Label',
        align: 'left',
        mobileLabel: 'Label',
        render: (r) => r.label ?? '—',
      },
    ],
    [],
  );

  return (
    <LoadBeat>
      <PageHeader title="Watchlists & screener" subtitle="Named instrument lists + the preset screener" help="Build named lists of instruments to track, and run preset screens that surface instruments matching a rule (e.g. momentum)." />
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

      <m.div key={tab} initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.15 }}>
      {tab === 'lists' && (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <Select
              value={selectedId}
              options={rows.map((l) => ({ value: l.id, label: l.name }))}
              onChange={setSelectedId}
              ariaLabel="Watchlist"
              placeholder="Watchlist"
              title="Pick which watchlist to view and edit."
            />
            <input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="New watchlist name" aria-label="New watchlist name" title="Name for a new watchlist to create." className={inputCls} />
            <button
              type="button"
              onClick={() => newName.trim() && createList.mutate(newName.trim(), { onSuccess: (l) => { setSelectedId(l.id); setNewName(''); } })}
              title="Create a new watchlist with the entered name."
              className="h-9 rounded-md bg-accent px-3 text-sm font-medium text-surface-0 hover:opacity-90"
            >
              Create
            </button>
          </div>

          <QueryState
            query={lists}
            empty={{ title: 'No watchlist selected. Create one to start tracking instruments.' }}
            errorTitle="Couldn't load watchlists"
            skeleton={<Skeleton variant="table-rows" rows={6} cols={2} />}
          >
            {() =>
              selected ? (
                <>
                  <div className="relative mb-3 max-w-md">
                    <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Add instrument…" aria-label="Add instrument" title="Type to search instruments, then click one to add it to this watchlist." className={`${inputCls} w-full`} />
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

                  <BeatBlock>
                    <DataTable
                      columns={itemColumns}
                      rows={selected.items}
                      rowKey={(it) => `${it.exchange}:${it.tradingsymbol}`}
                      ariaLabel="Watchlist instruments"
                      emptyMessage="Empty — add an instrument above."
                    />
                  </BeatBlock>
                </>
              ) : (
                <p className="text-sm text-ay-muted">No watchlist selected. Create one to start tracking instruments.</p>
              )
            }
          </QueryState>
        </>
      )}

      {tab === 'screener' && (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <Select value={preset} options={[...SCREENER_PRESETS]} onChange={setPreset} ariaLabel="Preset" title="Choose which screening rule to run." />
            <button
              type="button"
              onClick={() => setRunPreset(preset)}
              title="Run the selected screener preset."
              className="h-9 rounded-md bg-accent px-3 text-sm font-medium text-surface-0 hover:opacity-90"
            >
              Run
            </button>
          </div>
          <BeatBlock>
            <DataTable
              columns={screenerColumns}
              rows={screener.data?.items ?? []}
              rowKey={(r) => `${r.exchange}:${r.tradingsymbol}`}
              ariaLabel="Screener results"
              emptyMessage={runPreset ? 'No matches.' : 'Pick a preset and run the screener.'}
            />
          </BeatBlock>
        </>
      )}
      </m.div>
    </LoadBeat>
  );
}
