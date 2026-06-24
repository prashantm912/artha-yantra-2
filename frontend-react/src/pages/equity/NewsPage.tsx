import { useState } from 'react';
import { Newspaper } from 'lucide-react';
import { useEquityNews } from '../../api/oiAnalytics.ts';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';

// Equity → News / Announcements (Upstox /v2/news): recent news articles for a stock (last 7 days),
// keyed by the static symbol → Upstox instrument-key map. available=false when the Upstox news source
// isn't live (mock / off) → the page shows a clear notice instead of erroring.

const fmtTime = (ms: number | null): string =>
  ms == null ? '' : new Date(ms).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });

export function NewsPage() {
  const [input, setInput] = useState('');
  const [symbol, setSymbol] = useState<string | null>(null);
  const q = useEquityNews(symbol);

  const submit = () => {
    const s = input.trim().toUpperCase();
    if (s) setSymbol(s);
  };

  return (
    <div>
      <PageHeader title="News / Announcements" subtitle="Recent stock news (Upstox, last 7 days)" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
          placeholder="Symbol (e.g. RELIANCE)"
          aria-label="Stock symbol"
          className="h-9 w-48 rounded-md border border-ay-border bg-surface-1 px-2 text-sm uppercase text-ay-text outline-none focus:border-accent"
        />
        <GoButton onClick={submit} loading={q.isFetching} />
      </div>

      {symbol == null ? (
        <p className="py-8 text-center text-sm text-ay-muted">Enter a stock symbol and press Go.</p>
      ) : (
        <QueryState
          query={q}
          skeleton={<Skeleton variant="table-rows" rows={4} cols={1} />}
          isEmpty={(d) => d.available && d.items.length === 0}
          empty={{
            icon: Newspaper,
            title: `No recent news for ${symbol}.`,
            hint: 'Upstox returns the last 7 days of articles.',
          }}
        >
          {(d) =>
            !d.available ? (
              <p className="py-8 text-center text-sm text-ay-muted">
                News source is not live right now (Upstox news needs the live stack).
              </p>
            ) : (
              <ul className="space-y-3">
                {d.items.map((it, i) => (
                  <li key={i} className="rounded-lg border border-ay-border bg-surface-1 p-3">
                    <div className="flex items-start justify-between gap-3">
                      <a
                        href={it.articleLink ?? '#'}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-sm font-semibold text-accent hover:underline"
                      >
                        {it.heading ?? '—'}
                      </a>
                      <span className="shrink-0 text-xs text-ay-muted">{fmtTime(it.publishedTime)}</span>
                    </div>
                    {it.summary && <p className="mt-1 text-xs text-ay-muted">{it.summary}</p>}
                  </li>
                ))}
              </ul>
            )
          }
        </QueryState>
      )}
    </div>
  );
}
