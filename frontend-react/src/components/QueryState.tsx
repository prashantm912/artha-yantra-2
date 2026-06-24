import type { ReactNode } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { AlertTriangle, Inbox, RefreshCw, type LucideIcon } from 'lucide-react';
import { Skeleton } from './Skeletons.tsx';
import { Button } from './atoms/Button.tsx';

// Shared 4-way query wrapper (revamp §3.3.2). Separates ERROR from EMPTY — kills the
// "a 500 renders the empty-state lie" bug: error → inline Retry card (role=alert), empty →
// illustrated EmptyCard, pending → Skeleton, success → children(data narrowed non-null).
type QueryLike<T> = Pick<UseQueryResult<T>, 'isPending' | 'isError' | 'isSuccess' | 'data' | 'refetch'>;

interface QueryStateProps<T> {
  query: QueryLike<T>;
  children: (data: NonNullable<T>) => ReactNode;
  skeleton?: ReactNode;
  isEmpty?: (data: NonNullable<T>) => boolean;
  empty?: { icon?: LucideIcon; title: string; hint?: string };
  errorTitle?: string;
  pendingWhenDisabled?: boolean;
}

function defaultIsEmpty(data: unknown): boolean {
  if (data == null) return true;
  if (Array.isArray(data)) return data.length === 0;
  if (typeof data === 'object') {
    const items = (data as { items?: unknown[] }).items;
    if (Array.isArray(items)) return items.length === 0;
  }
  return false;
}

export function QueryState<T>({
  query,
  children,
  skeleton,
  isEmpty = defaultIsEmpty,
  empty,
  errorTitle = "Couldn't load this data",
  pendingWhenDisabled = false,
}: QueryStateProps<T>) {
  if (query.isError) {
    // error wins over pending — never render the empty-state copy for a real failure
    return (
      <div
        role="alert"
        data-testid="qs-error"
        className="flex flex-col items-center gap-3 rounded-lg border border-bear/40 bg-surface-1 px-4 py-8 text-center"
      >
        <AlertTriangle aria-hidden="true" className="size-6 text-bear" />
        <p className="text-body font-medium text-ay-text">{errorTitle}</p>
        <p className="text-caption text-ay-muted">The server returned an error. This is not an empty result.</p>
        <Button variant="outline" size="sm" icon={RefreshCw} onClick={() => void query.refetch()}>
          Retry
        </Button>
      </div>
    );
  }
  if (query.isPending) {
    if (pendingWhenDisabled && empty) return <EmptyCard {...empty} />;
    return (
      <div data-testid="qs-loading">
        <span className="ay-sr-only" role="status" aria-live="polite">
          Loading…
        </span>
        <div aria-hidden="true">{skeleton ?? <Skeleton variant="card" />}</div>
      </div>
    );
  }
  const data = query.data as NonNullable<T>;
  if (isEmpty(data)) return <EmptyCard {...(empty ?? { title: 'No data for this selection.' })} />;
  return <>{children(data)}</>;
}

function EmptyCard({ icon: Icon = Inbox, title, hint }: { icon?: LucideIcon; title: string; hint?: string }) {
  return (
    <div
      data-testid="qs-empty"
      className="flex flex-col items-center gap-2 rounded-lg border border-ay-border bg-surface-1 px-4 py-8 text-center"
    >
      <Icon aria-hidden="true" className="size-6 text-ay-muted" />
      <p className="text-body font-medium text-ay-text">{title}</p>
      {hint && <p className="text-caption text-ay-muted">{hint}</p>}
    </div>
  );
}
