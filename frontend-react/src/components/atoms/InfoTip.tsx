import type { ReactNode } from 'react';
import { Info } from 'lucide-react';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../ui/tooltip.tsx';
import { cn } from '../../lib/cn.ts';

// The universal help affordance (#16 — labels + tooltips on every control + data field). A small,
// keyboard-focusable ⓘ glyph that reveals an explanation on hover AND focus (radix Tooltip, so screen
// readers + keyboard users get it too, not mouse-only). The trigger carries an explicit aria-label so
// axe sees a named control; the icon itself is aria-hidden. Content is theme-tokened via the shadcn
// bridge (bg-foreground / text-background → readable on every theme). It wraps its OWN TooltipProvider
// so it is fully self-contained — drops into any page (and any isolated RTL test) with no app-level
// provider required.

interface InfoTipProps {
  /** The explanation shown in the tooltip. Plain text or rich content. */
  text: ReactNode;
  /** Accessible name for the trigger; defaults to a generic "More information". Pass the field name
   *  (e.g. "Open Interest") so the screen-reader announces "Open Interest, help". */
  label?: string;
  side?: 'top' | 'right' | 'bottom' | 'left';
  className?: string;
  /** Icon size in px-utility form; defaults to a compact inline glyph. */
  iconClassName?: string;
}

export function InfoTip({ text, label, side = 'top', className, iconClassName }: InfoTipProps) {
  if (text == null || text === '') return null;
  return (
    <TooltipProvider delayDuration={150}>
      <Tooltip>
        <TooltipTrigger
          type="button"
          aria-label={label ? `${label} — help` : 'More information'}
          className={cn(
            'ay-infotip inline-flex shrink-0 items-center justify-center rounded-full text-ay-muted',
            'align-middle hover:text-accent focus-visible:outline focus-visible:outline-1 focus-visible:outline-accent',
            className,
          )}
        >
          <Info aria-hidden="true" className={cn('size-3.5', iconClassName)} />
        </TooltipTrigger>
        <TooltipContent side={side} className="max-w-xs text-pretty leading-snug">
          {text}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
