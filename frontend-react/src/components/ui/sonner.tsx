import {
  CircleCheckIcon,
  InfoIcon,
  Loader2Icon,
  OctagonXIcon,
  TriangleAlertIcon,
} from 'lucide-react';
import { Toaster as Sonner, type ToasterProps } from 'sonner';

// ArthaYantra: themed via the §2 --ay-* bridge (--normal-* → --popover/--border), NOT next-themes
// (we drive themes with data-theme on <html>). light/dark base derives from the active data-theme.
const Toaster = ({ ...props }: ToasterProps) => {
  const isLight =
    typeof document !== 'undefined' &&
    document.documentElement.getAttribute('data-theme') === 'light';

  return (
    <Sonner
      theme={isLight ? 'light' : 'dark'}
      className="toaster group"
      icons={{
        success: <CircleCheckIcon className="size-4" />,
        info: <InfoIcon className="size-4" />,
        warning: <TriangleAlertIcon className="size-4" />,
        error: <OctagonXIcon className="size-4" />,
        loading: <Loader2Icon className="size-4 animate-spin" />,
      }}
      style={
        {
          '--normal-bg': 'var(--popover)',
          '--normal-text': 'var(--popover-foreground)',
          '--normal-border': 'var(--border)',
          '--border-radius': 'var(--radius)',
        } as React.CSSProperties
      }
      {...props}
    />
  );
};

export { Toaster };
