import { Skeleton as Base } from './ui/skeleton.tsx';
import { cn } from '../lib/cn.ts';

// Content-dimensioned skeleton variants (revamp §3.3.3) → zero CLS. The base is shadcn's
// `ay-skeleton animate-pulse` div (reduced-motion-gated in index.css). aria-hidden — the
// QueryState wrapper owns the SR-only "Loading…" status.
type Variant = 'card' | 'table-rows' | 'metric-strip' | 'chart-block';

interface SkeletonProps {
  variant?: Variant;
  rows?: number;
  cols?: number;
  height?: number;
  className?: string;
}

export function Skeleton({ variant = 'card', rows = 6, cols = 4, height, className }: SkeletonProps) {
  if (variant === 'metric-strip') {
    return (
      <div className={cn('flex flex-wrap gap-3', className)} aria-hidden="true">
        {Array.from({ length: cols }).map((_, i) => (
          <Base key={i} className="h-8 w-28" />
        ))}
      </div>
    );
  }
  if (variant === 'chart-block') {
    // When `height` is omitted a responsive `h-*` className drives the height (matches its chart).
    return (
      <Base
        className={cn('w-full', height == null && 'h-72', className)}
        style={height != null ? { height } : undefined}
        aria-hidden="true"
      />
    );
  }
  if (variant === 'table-rows') {
    return (
      <div className={cn('space-y-2', className)} aria-hidden="true">
        {Array.from({ length: rows }).map((_, r) => (
          <div key={r} className="flex gap-2">
            {Array.from({ length: cols }).map((_, c) => (
              <Base key={c} className="h-5 flex-1" />
            ))}
          </div>
        ))}
      </div>
    );
  }
  return (
    <div className={cn('space-y-2', className)} aria-hidden="true">
      <Base className="h-4 w-1/3" />
      <Base className="h-4 w-full" />
      <Base className="h-4 w-2/3" />
    </div>
  );
}
