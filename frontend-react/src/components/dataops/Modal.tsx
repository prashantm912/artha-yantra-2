import { useEffect } from 'react';
import type { LucideIcon } from 'lucide-react';

export function Modal(props: {
  open: boolean;
  onClose: () => void;
  title: string;
  icon?: LucideIcon;
  children: React.ReactNode;
}) {
  const { open, onClose, title, icon: Icon, children } = props;

  useEffect(() => {
    if (!open) return;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop as a native button → keyboard/AT-accessible close (no static-element click handler).
          The dialog card is a SIBLING, so card clicks never reach the backdrop (no stopPropagation). */}
      <button
        type="button"
        aria-label="Close dialog"
        className="absolute inset-0 bg-black/50"
        onClick={onClose}
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="relative w-[92vw] max-w-3xl max-h-[80vh] overflow-auto rounded-lg border border-ay-border bg-surface-1 p-4"
      >
        <div className="mb-3 flex items-center justify-between gap-4">
          <h2 className="flex items-center gap-1.5 text-h3 text-ay-text">
            {Icon && <Icon aria-hidden="true" className="size-4 text-ay-muted" />}
            {title}
          </h2>
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            className="rounded-md border border-ay-border bg-surface-1 px-2 py-1 text-sm text-ay-text hover:border-accent"
          >
            ×
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
