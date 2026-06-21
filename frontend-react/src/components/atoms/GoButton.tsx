import { cn } from '../../lib/cn.ts';

// Explicit fetch trigger (oipulse §20.7.3): the chain is reactive (queries refire on context change),
// so Go just forces a refetch — faithful to the oipulse control bar without abandoning the reactive model.
interface GoButtonProps {
  onClick: () => void;
  loading?: boolean;
}

export function GoButton({ onClick, loading }: GoButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={loading}
      className={cn(
        'h-9 rounded-md bg-accent px-4 text-sm font-medium text-surface-0',
        'hover:opacity-90 focus:outline-none focus:ring-2 focus:ring-accent disabled:opacity-50',
      )}
    >
      {loading ? '…' : 'Go'}
    </button>
  );
}
