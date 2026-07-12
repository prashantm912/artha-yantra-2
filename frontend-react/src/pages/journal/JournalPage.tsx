import { useMemo, useState } from 'react';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  LINKED_OPTIONS,
  linkLabel,
  useCreateJournal,
  useDeleteJournal,
  useJournal,
  useUpdateJournal,
  type JournalEntry,
  type NewJournalEntry,
} from '../../api/journal.ts';
import { useSignals } from '../../api/signals.ts';
import { usePaperPositions, usePaperTrades } from '../../api/paper.ts';
import { useJobs, fetchResultRef } from '../../api/backtests.ts';

// /journal (master plan §20 parity, F-44A): the weekly-review surface — a filterable entry list
// (tag / linked-entity) with an inline new-entry form (note + tags + discipline/emotion ratings +
// an optional link picked from recent signals / paper trades / backtest runs) and per-row
// edit / delete. Free entries (no link) are first-class. Links are set at CREATE and are immutable
// (the BE PUT only edits note/tags/ratings), so the pickers live on the new-entry form only.

const inputCls = 'h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text';

/** Split a comma-separated tag input into a trimmed, non-empty list. */
function parseTags(input: string): string[] {
  return input.split(',').map((t) => t.trim()).filter(Boolean);
}

/** A short IST date (YYYY-MM-DD) from an ISO stamp, for picker option labels. */
function shortDate(iso?: string | null): string {
  return iso ? iso.slice(0, 10) : '';
}

/** What a new entry can be linked to. `none` = a free entry (the default). */
type LinkType = 'none' | 'signal' | 'paper' | 'backtest';

const LINK_TYPE_OPTIONS = [
  { value: 'none', label: 'No link (free entry)' },
  { value: 'signal', label: 'Signal' },
  { value: 'paper', label: 'Paper trade' },
  { value: 'backtest', label: 'Backtest run' },
];

const pickerCls = 'w-full sm:w-64';

/** Pick one of your recent signals to link — value = signal id (the BE validates it exists). */
function SignalPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const q = useSignals(null);
  const options = (q.data?.items ?? []).map((s) => ({
    value: String(s.id),
    label: `#${s.id} ${s.tradingsymbol} ${s.side} · ${shortDate(s.generatedAt)}`,
  }));
  return (
    <Select
      value={value || null}
      options={options}
      onChange={onChange}
      ariaLabel="Pick a signal to link"
      placeholder={q.isLoading ? 'Loading signals…' : options.length ? 'Pick a signal' : 'No recent signals'}
      title="Link this entry to one of your recent signals."
      className={pickerCls}
    />
  );
}

/** Pick a recent paper position or closed trade to link — value = paper_positions id (BE-validated). */
function PaperPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const open = usePaperPositions();
  const closed = usePaperTrades();
  const loading = open.isLoading || closed.isLoading;
  const options = useMemo(() => {
    const rows = [
      ...(open.data?.items ?? []).map((p) => ({
        id: p.id, tradingsymbol: p.tradingsymbol, side: p.side, when: p.openedAt, status: 'open',
      })),
      ...(closed.data?.items ?? []).map((t) => ({
        id: t.id, tradingsymbol: t.tradingsymbol, side: t.side, when: t.closedAt, status: 'closed',
      })),
    ];
    const seen = new Set<number>();
    return rows
      .filter((r) => (seen.has(r.id) ? false : (seen.add(r.id), true)))
      .sort((a, b) => (a.when < b.when ? 1 : -1))
      .map((r) => ({
        value: String(r.id),
        label: `#${r.id} ${r.tradingsymbol} ${r.side} · ${r.status} ${shortDate(r.when)}`,
      }));
  }, [open.data, closed.data]);
  return (
    <Select
      value={value || null}
      options={options}
      onChange={onChange}
      ariaLabel="Pick a paper trade to link"
      placeholder={loading ? 'Loading paper trades…' : options.length ? 'Pick a paper trade' : 'No paper trades'}
      title="Link this entry to one of your paper positions or closed trades."
      className={pickerCls}
    />
  );
}

/** Pick a recent completed backtest run — value = jobId; resolved to the run id (resultRef) on submit. */
function BacktestPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const q = useJobs('completed', null, 0);
  const options = (q.data?.items ?? []).map((j) => ({
    value: j.jobId,
    label:
      `${shortDate(j.testTo ?? j.createdAt)}` +
      `${j.strategyId ? ` · ${j.strategyId.slice(0, 8)}` : ''}` +
      `${j.totalReturn != null ? ` · ret ${j.totalReturn}` : ''}`,
  }));
  return (
    <Select
      value={value || null}
      options={options}
      onChange={onChange}
      ariaLabel="Pick a backtest run to link"
      placeholder={q.isLoading ? 'Loading runs…' : options.length ? 'Pick a completed run' : 'No completed runs'}
      title="Link this entry to a completed backtest run."
      className={pickerCls}
    />
  );
}

export function JournalPage() {
  const [tag, setTag] = useState('');
  const [linkedTo, setLinkedTo] = useState<string | null>(null);
  const list = useJournal(tag.trim() || null, linkedTo);
  const create = useCreateJournal();
  const update = useUpdateJournal();
  const remove = useDeleteJournal();

  const [note, setNote] = useState('');
  const [tagsInput, setTagsInput] = useState('');
  const [discipline, setDiscipline] = useState('');
  const [emotion, setEmotion] = useState('');
  const [linkType, setLinkType] = useState<LinkType>('none');
  const [linkId, setLinkId] = useState('');
  const [linkError, setLinkError] = useState<string | null>(null);

  const [editingId, setEditingId] = useState<number | null>(null);
  const [editNote, setEditNote] = useState('');
  const [editTags, setEditTags] = useState('');
  const [editDisc, setEditDisc] = useState('');
  const [editEmo, setEditEmo] = useState('');

  const rows = useMemo(() => list.data?.items ?? [], [list.data]);

  const resetForm = () => {
    setNote('');
    setTagsInput('');
    setDiscipline('');
    setEmotion('');
    setLinkType('none');
    setLinkId('');
    setLinkError(null);
  };

  const submit = () => {
    if (!note.trim()) return;
    const base: NewJournalEntry = {
      note: note.trim(),
      tags: parseTags(tagsInput),
      disciplineRating: discipline ? Number(discipline) : null,
      emotionRating: emotion ? Number(emotion) : null,
    };
    // A backtest link stores the run id (resultRef), not the jobId — resolve it from the completed
    // job before creating; every other link (or none) is synchronous.
    if (linkType === 'backtest' && linkId) {
      setLinkError(null);
      void fetchResultRef(linkId)
        .then((runId) => {
          if (!runId) {
            setLinkError('That run has no result id yet — pick another run.');
            return;
          }
          create.mutate({ ...base, backtestRunId: runId }, { onSuccess: resetForm });
        })
        .catch(() => setLinkError('Couldn’t resolve that run — try again.'));
      return;
    }
    const link =
      linkType === 'signal' && linkId
        ? { signalId: Number(linkId) }
        : linkType === 'paper' && linkId
          ? { paperPositionId: Number(linkId) }
          : {};
    create.mutate({ ...base, ...link }, { onSuccess: resetForm });
  };

  // Keyboard flow (a11y review LOW): the row swap unmounts the focused control — return focus to the
  // row's Edit button after save/cancel (the edit-note input focuses via callback ref on the way in —
  // a user-initiated row swap, not a page-load autofocus, so jsx-a11y/no-autofocus doesn't apply).
  const focusOnMount = (el: HTMLInputElement | null) => el?.focus();
  const restoreEditFocus = (id: number) => {
    requestAnimationFrame(() => document.getElementById(`journal-edit-${id}`)?.focus());
  };

  const startEdit = (e: JournalEntry) => {
    setEditingId(e.id);
    setEditNote(e.note);
    setEditTags(e.tags.join(', '));
    setEditDisc(e.disciplineRating != null ? String(e.disciplineRating) : '');
    setEditEmo(e.emotionRating != null ? String(e.emotionRating) : '');
  };

  const saveEdit = (id: number) => {
    if (!editNote.trim()) return;
    update.mutate(
      {
        id,
        body: {
          note: editNote.trim(),
          tags: parseTags(editTags),
          disciplineRating: editDisc ? Number(editDisc) : null,
          emotionRating: editEmo ? Number(editEmo) : null,
        },
      },
      { onSuccess: () => setEditingId(null) },
    );
  };

  return (
    <LoadBeat>
      <PageHeader title="Trade journal" subtitle="Weekly-review entries — filter by tag or linked entity, rate discipline & emotion" help="Your trading diary: free-form notes linked to signals, paper trades or backtests, with discipline and emotion ratings for weekly review. Links are set when you create an entry; editing changes the note, tags and ratings." />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input value={tag} onChange={(e) => setTag(e.target.value)} placeholder="Filter by tag" aria-label="Filter by tag" title="Show only entries carrying this tag." className={`${inputCls} flex-1`} />
        <Select value={linkedTo} options={LINKED_OPTIONS} onChange={(v) => setLinkedTo(v || null)} ariaLabel="Linked to" placeholder="Linked to" title="Filter entries by what they are linked to — a signal, paper trade or backtest." />
      </div>

      <div className="mb-4 rounded-lg border border-ay-border bg-surface-1 p-3">
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-1 flex-col gap-1">
            <span className="text-xs text-ay-muted">Note</span>
            <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="What happened / what to improve" aria-label="Note" title="The diary note — what happened and what to do differently next time." className={inputCls} />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-ay-muted">Tags (comma-sep)</span>
            <input value={tagsInput} onChange={(e) => setTagsInput(e.target.value)} aria-label="Tags" title="Comma-separated labels to group and later filter this entry." className={`${inputCls} w-full sm:w-40`} />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-ay-muted">Disc. (1–5)</span>
            <input type="number" min={1} max={5} value={discipline} onChange={(e) => setDiscipline(e.target.value)} aria-label="Discipline rating" title="Rate how well you followed your plan, 1 (poor) to 5 (excellent)." className={`${inputCls} w-16`} />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-ay-muted">Emo. (1–5)</span>
            <input type="number" min={1} max={5} value={emotion} onChange={(e) => setEmotion(e.target.value)} aria-label="Emotion rating" title="Rate your emotional state during the trade, 1 (rattled) to 5 (calm)." className={`${inputCls} w-16`} />
          </label>
          <button
            type="button"
            onClick={submit}
            disabled={!note.trim() || create.isPending}
            title="Save this journal entry."
            className="h-9 rounded-md bg-accent px-4 text-sm font-medium text-surface-0 hover:opacity-90 disabled:opacity-50"
          >
            + New entry
          </button>
        </div>

        <div className="mt-2 flex flex-wrap items-end gap-2">
          {/* The Select carries its own ariaLabel, so this caption wrapper is a <div>, matching the
              filter-bar convention (custom Select components aren't seen as label-associated controls). */}
          <div className="flex flex-col gap-1">
            <span className="text-xs text-ay-muted">Link to (optional)</span>
            <Select
              value={linkType}
              options={LINK_TYPE_OPTIONS}
              onChange={(v) => {
                setLinkType(v as LinkType);
                setLinkId('');
                setLinkError(null);
              }}
              ariaLabel="Link to (optional)"
              title="Optionally attach this entry to a signal, paper trade or backtest run."
              className="w-full sm:w-48"
            />
          </div>
          {linkType !== 'none' && (
            // The picker's Select carries its own ariaLabel ("Signal to link" etc.), so this is a
            // plain caption wrapper, not a <label> (which would have no directly-associated control).
            <div className="flex flex-col gap-1">
              <span className="text-xs text-ay-muted">Pick a {linkType === 'backtest' ? 'run' : linkType}</span>
              {linkType === 'signal' && <SignalPicker value={linkId} onChange={setLinkId} />}
              {linkType === 'paper' && <PaperPicker value={linkId} onChange={setLinkId} />}
              {linkType === 'backtest' && <BacktestPicker value={linkId} onChange={setLinkId} />}
            </div>
          )}
          {linkError && (
            <span className="text-xs text-bear" role="alert">
              {linkError}
            </span>
          )}
        </div>
      </div>

      <BeatBlock>
      <QueryState
        query={list}
        empty={{ title: 'No journal entries — annotate a signal, paper trade or backtest.' }}
        errorTitle="Couldn't load journal entries"
        skeleton={<Skeleton variant="table-rows" rows={8} cols={7} />}
      >
        {() => (
          <div className="overflow-auto rounded-lg border border-ay-border">
            <table className="w-full border-collapse text-sm">
              <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
                <tr>
                  <th className="px-2 py-2 font-medium">Note</th>
                  <th className="px-2 py-2 font-medium">Tags</th>
                  <th className="px-2 py-2 font-medium">Linked</th>
                  <th className="px-2 py-2 text-right font-medium">Disc.</th>
                  <th className="px-2 py-2 text-right font-medium">Emo.</th>
                  <th className="px-2 py-2 font-medium">Created</th>
                  <th className="px-2 py-2"><span className="ay-sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((e) =>
                  editingId === e.id ? (
                    <tr key={e.id} className="border-t border-ay-border bg-surface-1/40">
                      <td className="px-2 py-2">
                        <input ref={focusOnMount} value={editNote} onChange={(ev) => setEditNote(ev.target.value)} aria-label="Edit note" className={`${inputCls} w-full`} />
                      </td>
                      <td className="px-2 py-2">
                        <input value={editTags} onChange={(ev) => setEditTags(ev.target.value)} aria-label="Edit tags" placeholder="comma-sep" className={`${inputCls} w-full sm:w-40`} />
                      </td>
                      <td className="px-2 py-2 text-xs text-ay-muted" title="Links are fixed when an entry is created and can't be changed here.">{linkLabel(e)}</td>
                      <td className="px-2 py-2 text-right">
                        <input type="number" min={1} max={5} value={editDisc} onChange={(ev) => setEditDisc(ev.target.value)} aria-label="Edit discipline rating" className={`${inputCls} w-14`} />
                      </td>
                      <td className="px-2 py-2 text-right">
                        <input type="number" min={1} max={5} value={editEmo} onChange={(ev) => setEditEmo(ev.target.value)} aria-label="Edit emotion rating" className={`${inputCls} w-14`} />
                      </td>
                      <td className="px-2 py-2 tabular-nums">{e.createdAt?.slice(0, 10)}</td>
                      <td className="px-2 py-2 text-right">
                        <div className="flex justify-end gap-2">
                          <button type="button" onClick={() => { saveEdit(e.id); restoreEditFocus(e.id); }} disabled={!editNote.trim() || update.isPending} className="px-1.5 text-xs font-medium text-accent hover:underline disabled:opacity-50">Save</button>
                          <button type="button" onClick={() => { setEditingId(null); restoreEditFocus(e.id); }} className="px-1.5 text-xs text-ay-muted hover:underline">Cancel</button>
                        </div>
                      </td>
                    </tr>
                  ) : (
                    <tr key={e.id} className="border-t border-ay-border">
                      <td className="px-2 py-2">{e.note}</td>
                      <td className="px-2 py-2">
                        <span className="flex flex-wrap gap-1">
                          {e.tags.map((t) => (
                            <span key={t} className="rounded bg-surface-2 px-1.5 py-0.5 text-xs text-ay-muted">{t}</span>
                          ))}
                        </span>
                      </td>
                      <td className="px-2 py-2 text-xs text-ay-muted">{linkLabel(e)}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{e.disciplineRating ?? '—'}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{e.emotionRating ?? '—'}</td>
                      <td className="px-2 py-2 tabular-nums">{e.createdAt?.slice(0, 10)}</td>
                      <td className="px-2 py-2 text-right">
                        <div className="flex justify-end gap-2">
                          <button type="button" id={`journal-edit-${e.id}`} onClick={() => startEdit(e)} title="Edit this entry's note, tags and ratings." className="px-1.5 text-xs text-accent hover:underline">
                            Edit
                          </button>
                          <button type="button" onClick={() => { if (window.confirm('Delete this journal entry? This cannot be undone.')) remove.mutate(e.id); }} className="px-1.5 text-xs text-bear hover:underline">
                            Delete
                          </button>
                        </div>
                      </td>
                    </tr>
                  ),
                )}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>
      </BeatBlock>
    </LoadBeat>
  );
}
