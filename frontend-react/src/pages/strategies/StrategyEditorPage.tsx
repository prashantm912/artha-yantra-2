import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import {
  STARTER_TEMPLATE,
  VERSION_BUMPS,
  useCreateStrategy,
  useSaveStrategy,
  useStrategyEditDetail,
  useStrategyUniverse,
  useValidateStrategy,
  type VersionBump,
} from '../../api/strategies.ts';

// /strategies/:id/edit (master plan §20 parity, E-11 screen 2): the strategy editor. React has no
// Monaco (the monaco-yaml worker is unreliable — the Angular app already falls back to a textarea),
// so this is a YAML textarea gated by the server-authoritative POST /validate (debounced); save
// auto-bumps the version, id === 'new' creates. The v1 form-pane indicator weight/optional toggles
// (needs a YAML round-trip parser) and the quick-backtest drawer (PR-C6 owns submission) are deferred.

export function StrategyEditorPage() {
  const { id = 'new' } = useParams();
  const isNew = id === 'new';
  const navigate = useNavigate();

  const detail = useStrategyEditDetail(id, !isNew);
  const universe = useStrategyUniverse(id, !isNew);
  const validate = useValidateStrategy();
  const save = useSaveStrategy(id);
  const create = useCreateStrategy();

  const [draft, setDraft] = useState(isNew ? STARTER_TEMPLATE : '');
  const [name, setName] = useState('');
  const [bump, setBump] = useState<VersionBump>('minor');
  const [dirty, setDirty] = useState(false);
  const seeded = useRef(false);

  // seed the buffer from the loaded strategy once (edit mode)
  useEffect(() => {
    if (!isNew && detail.data && !seeded.current) {
      seeded.current = true;
      setDraft(detail.data.configYaml ?? '');
      setName(detail.data.name ?? '');
    }
  }, [isNew, detail.data]);

  // debounced server validation on every edit
  const validateRef = useRef(validate);
  validateRef.current = validate;
  useEffect(() => {
    if (!draft.trim()) return;
    const t = setTimeout(() => validateRef.current.mutate(draft), 500);
    return () => clearTimeout(t);
  }, [draft]);

  // warn on browser-close with unsaved changes (in-app route blocking needs a data router — deferred)
  useEffect(() => {
    if (!dirty) return;
    const handler = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [dirty]);

  const onYaml = (v: string) => {
    setDraft(v);
    setDirty(true);
  };

  const result = validate.data;
  const validBadge = validate.isPending ? 'checking' : result ? (result.valid ? 'valid' : 'invalid') : null;
  const saveDisabled = result?.valid === false || (isNew && !name.trim());

  const onSave = () => {
    if (isNew) {
      create.mutate(
        { name: name.trim(), config: draft },
        { onSuccess: (res) => navigate(`/strategies/${res.id}/edit`) },
      );
    } else {
      save.mutate({ config: draft, versionBump: bump }, { onSuccess: () => setDirty(false) });
    }
  };

  const u = universe.data;

  return (
    <div>
      <h1 className="ay-sr-only">Strategy editor</h1>

      {u && (
        <div className="mb-2 rounded-lg border border-ay-border bg-surface-1 p-2 text-sm">
          <strong>Published Universe</strong> ({u.mode}
          {u.asOf ? `, as of ${u.asOf}` : ''}): {u.constituentCount} constituents · checksum{' '}
          {u.checksum.slice(0, 12)}…
          {u.survivorshipCaveat && <div className="mt-1 text-ay-muted">{u.survivorshipCaveat}</div>}
        </div>
      )}

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <strong>{isNew ? 'New strategy' : (detail.data?.name ?? 'Strategy')}</strong>
        {detail.data && (
          <>
            <span className="rounded bg-surface-2 px-1.5 py-0.5 text-xs">v{detail.data.version}</span>
            <span
              className={cn(
                'rounded px-1.5 py-0.5 text-xs font-semibold ring-1',
                detail.data.status === 'published' ? 'text-bull ring-bull/40' : 'text-warn ring-warn/40',
              )}
            >
              {detail.data.status}
            </span>
          </>
        )}
        {validBadge && (
          <span
            className={cn(
              'rounded px-1.5 py-0.5 text-xs font-semibold ring-1',
              validBadge === 'valid'
                ? 'text-bull ring-bull/40'
                : validBadge === 'invalid'
                  ? 'text-bear ring-bear/40'
                  : 'text-ay-muted ring-ay-border',
            )}
          >
            {validBadge}
          </span>
        )}
        <div className="flex-1" />
        {!isNew && (
          <Select value={bump} options={[...VERSION_BUMPS]} onChange={(v) => setBump(v as VersionBump)} ariaLabel="Version bump" />
        )}
        <button
          type="button"
          onClick={onSave}
          disabled={saveDisabled || save.isPending || create.isPending}
          className="h-9 rounded-md bg-accent px-4 text-sm font-medium text-surface-0 hover:opacity-90 disabled:opacity-50"
        >
          {isNew ? 'Create draft' : 'Save draft'}
        </button>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(14rem,18rem)_minmax(0,1fr)]">
        <aside className="rounded-lg border border-ay-border bg-surface-1 p-3">
          <h3 className="mb-2 text-xs font-semibold uppercase text-ay-muted">Metadata</h3>
          {isNew ? (
            <label className="block text-sm">
              <span className="mb-1 block text-xs text-ay-muted">Name</span>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Strategy name (required)"
                aria-label="Strategy name"
                className="w-full rounded-md border border-ay-border bg-surface-1 px-2 py-1.5 text-sm text-ay-text"
              />
            </label>
          ) : (
            <p className="text-sm text-ay-muted">
              Edit the YAML on the right; save auto-bumps the version. Indicator weight/optional
              toggles are edited inline in the YAML in this build.
            </p>
          )}
        </aside>

        <section className="min-w-0">
          <textarea
            value={draft}
            onChange={(e) => onYaml(e.target.value)}
            spellCheck={false}
            aria-label="Strategy YAML"
            className="h-[calc(100vh-20rem)] min-h-72 w-full rounded-lg border border-ay-border bg-surface-1 p-3 font-mono text-xs text-ay-text"
          />
          <div className="mt-2 max-h-40 overflow-auto text-sm">
            {result && (
              <>
                {result.valid && <p className="text-bull">✓ Schema + semantic validation passed.</p>}
                {result.errors.map((e) => (
                  <div key={`e-${e.path}-${e.message}`} className="text-bear">
                    ✗ <span className="font-mono text-ay-muted">{e.path}</span> — {e.message}
                  </div>
                ))}
                {result.warnings.map((w) => (
                  <div key={`w-${w.path}-${w.message}`}>
                    <span className="font-mono text-ay-muted">{w.path}</span> — {w.message}
                  </div>
                ))}
              </>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
