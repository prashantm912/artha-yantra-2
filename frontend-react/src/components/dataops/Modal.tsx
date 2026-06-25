import { useEffect, useId, useRef } from 'react';
import type { LucideIcon } from 'lucide-react';

// Tab-focusable descendants of the dialog (used by the focus trap to find the first/last stops).
const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function Modal(props: {
  open: boolean;
  onClose: () => void;
  title: string;
  icon?: LucideIcon;
  children: React.ReactNode;
}) {
  const { open, onClose, title, icon: Icon, children } = props;
  const dialogRef = useRef<HTMLDivElement>(null);
  const titleId = useId();

  useEffect(() => {
    if (!open) return;
    // Restore focus to whatever opened the dialog when it closes.
    const opener = document.activeElement as HTMLElement | null;
    // Move focus into the dialog on open (first focusable, else the dialog itself).
    const dialog = dialogRef.current;
    const first = dialog?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? dialog)?.focus();

    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        onClose();
        return;
      }
      if (e.key !== 'Tab' || !dialog) return;
      // Trap Tab within the dialog (wrap at both ends).
      const stops = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (stops.length === 0) {
        e.preventDefault();
        dialog.focus();
        return;
      }
      const firstStop = stops[0];
      const lastStop = stops[stops.length - 1];
      const active = document.activeElement;
      if (e.shiftKey && (active === firstStop || active === dialog)) {
        e.preventDefault();
        lastStop.focus();
      } else if (!e.shiftKey && active === lastStop) {
        e.preventDefault();
        firstStop.focus();
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      opener?.focus?.();
    };
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
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className="relative w-[92vw] max-w-3xl max-h-[80vh] overflow-auto rounded-lg border border-ay-border bg-surface-1 p-4"
      >
        <div className="mb-3 flex items-center justify-between gap-4">
          <h2 id={titleId} className="flex items-center gap-1.5 text-h3 text-ay-text">
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
