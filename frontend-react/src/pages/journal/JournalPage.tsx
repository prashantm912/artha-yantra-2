import { useMemo, useState } from 'react';
import { Select } from '../../components/atoms/Select.tsx';
import {
  LINKED_OPTIONS,
  linkLabel,
  useCreateJournal,
  useDeleteJournal,
  useJournal,
} from '../../api/journal.ts';

// /journal (master plan §20 parity, F-44A): the weekly-review surface — a filterable entry list
// (tag / linked-entity) with an inline new-entry form (note + tags + discipline/emotion ratings) and
// per-row delete. Free entries (no link) are first-class.

export function JournalPage() {
  const [tag, setTag] = useState('');
  const [linkedTo, setLinkedTo] = useState<string | null>(null);
  const list = useJournal(tag.trim() || null, linkedTo);
  const create = useCreateJournal();
  const remove = useDeleteJournal();

  const [note, setNote] = useState('');
  const [tagsInput, setTagsInput] = useState('');
  const [discipline, setDiscipline] = useState('');
  const [emotion, setEmotion] = useState('');

  const rows = useMemo(() => list.data?.items ?? [], [list.data]);

  const submit = () => {
    if (!note.trim()) return;
    create.mutate(
      {
        note: note.trim(),
        tags: tagsInput.split(',').map((t) => t.trim()).filter(Boolean),
        disciplineRating: discipline ? Number(discipline) : null,
        emotionRating: emotion ? Number(emotion) : null,
      },
      {
        onSuccess: () => {
          setNote('');
          setTagsInput('');
          setDiscipline('');
          setEmotion('');
        },
      },
    );
  };

  const inputCls = 'h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text';

  return (
    <div>
      <h1 className="ay-sr-only">Trade journal</h1>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input value={tag} onChange={(e) => setTag(e.target.value)} placeholder="Filter by tag" aria-label="Filter by tag" className={`${inputCls} flex-1`} />
        <Select value={linkedTo} options={LINKED_OPTIONS} onChange={(v) => setLinkedTo(v || null)} ariaLabel="Linked to" placeholder="Linked to" />
      </div>

      <div className="mb-4 flex flex-wrap items-end gap-2 rounded-lg border border-ay-border bg-surface-1 p-3">
        <label className="flex flex-1 flex-col gap-1">
          <span className="text-xs text-ay-muted">Note</span>
          <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="What happened / what to improve" aria-label="Note" className={inputCls} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-ay-muted">Tags (comma-sep)</span>
          <input value={tagsInput} onChange={(e) => setTagsInput(e.target.value)} aria-label="Tags" className={`${inputCls} w-40`} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-ay-muted">Disc.</span>
          <input type="number" min={1} max={5} value={discipline} onChange={(e) => setDiscipline(e.target.value)} aria-label="Discipline rating" className={`${inputCls} w-16`} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-ay-muted">Emo.</span>
          <input type="number" min={1} max={5} value={emotion} onChange={(e) => setEmotion(e.target.value)} aria-label="Emotion rating" className={`${inputCls} w-16`} />
        </label>
        <button
          type="button"
          onClick={submit}
          disabled={!note.trim() || create.isPending}
          className="h-9 rounded-md bg-accent px-4 text-sm font-medium text-surface-0 hover:opacity-90 disabled:opacity-50"
        >
          + New entry
        </button>
      </div>

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
              <th className="px-2 py-2" />
            </tr>
          </thead>
          <tbody>
            {rows.map((e) => (
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
                  <button type="button" onClick={() => remove.mutate(e.id)} className="px-1.5 text-xs text-bear hover:underline">
                    Delete
                  </button>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={7} className="px-2 py-6 text-center text-ay-muted">
                  No journal entries — annotate a signal, paper trade or backtest.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
