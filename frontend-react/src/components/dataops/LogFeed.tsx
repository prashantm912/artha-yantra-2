import { useEffect, useRef } from 'react';
import { cn } from '../../lib/cn.ts';

// Scrollable monospace activity-log feed for the Data Ops console. Auto-scrolls to the latest
// line when `lines` changes; tints each line by severity (ERROR/fail → bear, WARN → warn,
// done/→ → bull, otherwise muted). Empty → "No activity yet." Presentational only.
interface LogFeedProps {
  lines: string[];
  className?: string;
}

function lineTint(line: string): string {
  if (line.includes('ERROR') || line.includes('fail')) return 'text-bear';
  if (line.includes('WARN')) return 'text-warn';
  if (line.includes('done') || line.includes('→')) return 'text-bull';
  return 'text-ay-muted';
}

export function LogFeed({ lines, className }: LogFeedProps) {
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' });
  }, [lines]);

  return (
    <div
      role="log"
      aria-live="polite"
      aria-label="Activity log"
      className={cn(
        'max-h-64 overflow-auto rounded border border-ay-border bg-surface-2 p-2 font-mono text-xs',
        className,
      )}
    >
      {lines.length === 0 ? (
        <p className="text-ay-muted">No activity yet.</p>
      ) : (
        lines.map((line, i) => (
          <div key={i} className={cn('whitespace-pre-wrap break-words', lineTint(line))}>
            {line}
          </div>
        ))
      )}
      <div ref={endRef} />
    </div>
  );
}
