import { useEffect } from 'react';
import { Select } from '../../components/atoms/Select.tsx';
import { useSession } from '../../stores/session.store.ts';
import { THEMES } from '../../lib/theme.ts';
import { fetchKiteLoginUrl, useKiteStatus, useSyncInstruments, useSyncStatus } from '../../api/settings.ts';

// /settings (master plan §20 parity, E-8): Kite Connect (OAuth popup + status), theme, instrument
// data-sync trigger + status, and a Global-risk note pointing at the Paper page (where the kill switch
// + risk limits live). The OAuth popup is opened on the user's click; the user authenticates there.

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-xl border border-ay-border bg-surface-1 p-4">
      <h2 className="mb-3 text-sm font-semibold">{title}</h2>
      {children}
    </section>
  );
}

export function SettingsPage() {
  const theme = useSession((s) => s.theme);
  const setTheme = useSession((s) => s.setTheme);
  const mockMode = useSession((s) => s.mockMode);
  const kite = useKiteStatus();
  const sync = useSyncStatus();
  const syncInstruments = useSyncInstruments();

  // the OAuth popup posts back on completion — refetch the Kite status
  useEffect(() => {
    const onMessage = (e: MessageEvent) => {
      if (e.origin === window.location.origin && (e.data as { type?: string })?.type === 'kite-connected') {
        void kite.refetch();
      }
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [kite]);

  const connect = async () => {
    const url = await fetchKiteLoginUrl();
    if (url) window.open(url, 'kite-oauth', 'width=480,height=720');
  };

  const k = kite.data;
  const s = sync.data;

  return (
    <div className="flex max-w-3xl flex-col gap-4">
      <h1 className="ay-sr-only">Settings</h1>
      {mockMode && (
        <p className="text-sm text-warn">MOCK MODE — credential-free synthetic feed; no Kite session required.</p>
      )}

      <Section title="Kite Connect">
        {k && (
          <dl className="mb-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-ay-muted">Connected</dt>
            <dd className={k.connected ? 'text-bull' : 'text-bear'}>{k.connected ? 'YES' : 'NO'}</dd>
            <dt className="text-ay-muted">State</dt>
            <dd>{k.state ?? '—'}</dd>
            <dt className="text-ay-muted">User</dt>
            <dd>{k.kiteUserId ?? '—'}</dd>
            <dt className="text-ay-muted">Token valid until</dt>
            <dd className="tabular-nums">{k.tokenValidUntil ? k.tokenValidUntil.slice(0, 16).replace('T', ' ') : '—'}</dd>
            <dt className="text-ay-muted">Ticker</dt>
            <dd>{k.tickerState ?? '—'}</dd>
          </dl>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" onClick={connect} className="h-9 rounded-md border border-ay-border px-3 text-sm hover:border-accent">
            Connect Kite ↗
          </button>
          <button type="button" onClick={() => kite.refetch()} className="h-9 rounded-md px-3 text-sm text-accent hover:underline">
            Refresh status
          </button>
        </div>
      </Section>

      <Section title="Appearance">
        <div className="flex items-center gap-3">
          <span className="text-sm text-ay-muted">Theme</span>
          <Select value={theme} options={THEMES.map((t) => ({ value: t.id, label: t.label }))} onChange={(v) => setTheme(v as typeof theme)} ariaLabel="Theme" />
        </div>
      </Section>

      <Section title="Data sync">
        {s && (
          <dl className="mb-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-ay-muted">State</dt>
            <dd>{s.state ?? '—'}</dd>
            <dt className="text-ay-muted">Last run</dt>
            <dd className="tabular-nums">{s.lastRun ? s.lastRun.slice(0, 19).replace('T', ' ') : '—'}</dd>
          </dl>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => syncInstruments.mutate()}
            disabled={syncInstruments.isPending}
            className="h-9 rounded-md border border-ay-border px-3 text-sm hover:border-accent disabled:opacity-50"
          >
            Sync instruments
          </button>
          <button type="button" onClick={() => sync.refetch()} className="h-9 rounded-md px-3 text-sm text-accent hover:underline">
            Sync status
          </button>
        </div>
      </Section>

      <Section title="Global Risk">
        <p className="text-sm italic text-ay-muted">
          Kill switch, daily-loss and max-open limits are managed on the Paper trading page.
        </p>
      </Section>
    </div>
  );
}
