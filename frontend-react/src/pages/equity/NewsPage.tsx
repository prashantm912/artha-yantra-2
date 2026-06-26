import { useState } from 'react';
import { m } from 'motion/react';
import { Newspaper } from 'lucide-react';
import { useEquityNews } from '../../api/oiAnalytics.ts';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

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
    <LoadBeat>
      <PageHeader
        title="News / Announcements"
        help="Pulls the last seven days of news headlines for a stock from Upstox so you can scan recent catalysts before trading it."
        subtitle="Recent stock news (Upstox, last 7 days)"
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
          placeholder="Symbol (e.g. RELIANCE)"
          aria-label="Stock symbol"
          title="Type an NSE stock symbol (e.g. RELIANCE) and press Go to load its recent news"
          className="h-9 w-full sm:w-48 rounded-md border border-ay-border bg-surface-1 px-2 text-sm uppercase text-ay-text outline-none focus:border-accent"
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
              <BeatBlock>
                <m.ul
                  className="space-y-3"
                  initial="hidden"
                  animate="show"
                  variants={{ hidden: {}, show: { transition: { staggerChildren: 0.04 } } }}
                >
                  {d.items.map((it, i) => (
                    <m.li
                      key={i}
                      className="rounded-lg border border-ay-border bg-surface-1 p-3"
                      variants={{
                        hidden: { opacity: 0, y: 6 },
                        show: { opacity: 1, y: 0, transition: { duration: 0.16, ease: [0.2, 0, 0, 1] } },
                      }}
                    >
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
                    </m.li>
                  ))}
                </m.ul>
              </BeatBlock>
            )
          }
        </QueryState>
      )}
    </LoadBeat>
  );
}
