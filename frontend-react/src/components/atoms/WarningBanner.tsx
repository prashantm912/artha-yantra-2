import type { ReactNode } from 'react';

// A token-driven warning banner (warn tone + hairline ring). Either pass freeform {children} or the
// {title, detail} pair. Used for non-blocking caveats (e.g. a not-like-for-like comparison notice).
interface WarningBannerProps {
  children?: ReactNode;
  title?: string;
  detail?: ReactNode;
  className?: string;
}

export function WarningBanner({ children, title, detail, className }: WarningBannerProps) {
  return (
    <p
      className={`rounded bg-warn/10 px-2 py-1 text-sm text-warn ring-1 ring-warn/30${
        className ? ` ${className}` : ''
      }`}
    >
      {children ?? (
        <>
          {title}
          {detail != null && <span className="text-ay-muted"> {detail}</span>}
        </>
      )}
    </p>
  );
}
