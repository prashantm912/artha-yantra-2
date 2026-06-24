import { Inbox, type LucideIcon } from 'lucide-react';

// Illustrated empty-state card (revamp §3.3.2) — the EMPTY branch of QueryState, extracted into a
// shared atom so pages can render it directly for a zero-data canvas (e.g. a pre-submit prompt).
// `mesh` paints a subtle token-driven radial wash behind the card for big empty surfaces; default off
// keeps the original flat look. Keeps data-testid="qs-empty" so existing assertions hold.
interface EmptyCardProps {
  icon?: LucideIcon;
  title: string;
  hint?: string;
  mesh?: boolean;
}

// Faint radial mesh from --ay-* tokens only (low opacity → reads on any theme, fails open on light).
const MESH_BACKGROUND =
  'radial-gradient(60% 80% at 30% 20%, color-mix(in srgb, var(--ay-accent) 7%, transparent), transparent 70%), ' +
  'radial-gradient(50% 70% at 80% 90%, color-mix(in srgb, var(--ay-surface-2) 80%, transparent), transparent 70%)';

export function EmptyCard({ icon: Icon = Inbox, title, hint, mesh = false }: EmptyCardProps) {
  return (
    <div
      data-testid="qs-empty"
      className="flex flex-col items-center gap-2 rounded-lg border border-ay-border bg-surface-1 px-4 py-8 text-center"
      style={mesh ? { background: MESH_BACKGROUND } : undefined}
    >
      <Icon aria-hidden="true" className="size-6 text-ay-muted" />
      <p className="text-body font-medium text-ay-text">{title}</p>
      {hint && <p className="text-caption text-ay-muted">{hint}</p>}
    </div>
  );
}
