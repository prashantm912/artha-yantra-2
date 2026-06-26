import type { ReactNode } from 'react';
import { LazyMotion, MotionConfig, domAnimation, m } from 'motion/react';
import { cn } from '../lib/cn.ts';

// The ONE orchestrated load beat (revamp §4.0): header settles → metric strip staggers in
// (y 6→0, ~40ms stagger) → chart/table block fades up — total ≤200ms, --ease-standard.
// Motion is ON by default (§7 Q3) but every animation is reduced-motion-gated: MotionConfig
// reducedMotion="user" makes Motion honour prefers-reduced-motion and skip the transforms.
// The data grid NEVER animates row-by-row — only the strip TILES stagger, never the grid rows.

const EASE = [0.2, 0, 0, 1] as const; // --ease-standard

/** Wraps a page's animated region; provides the lazy-loaded DOM animation features. */
export function LoadBeat({ children }: { children: ReactNode }) {
  return (
    <LazyMotion features={domAnimation} strict>
      <MotionConfig reducedMotion="user">{children}</MotionConfig>
    </LazyMotion>
  );
}

/** A metric/status strip whose direct children stagger in (y 6→0). Use <BeatItem> for each tile.
 *  Forwards extra DOM props (e.g. data-testid) onto the wrapper. */
export function BeatStrip({
  className,
  children,
  ...rest
}: { className?: string; children: ReactNode } & Record<`data-${string}`, string>) {
  return (
    <m.div
      className={className}
      initial="hidden"
      animate="show"
      variants={{
        hidden: {},
        show: { transition: { staggerChildren: 0.04, delayChildren: 0.02 } },
      }}
      {...rest}
    >
      {children}
    </m.div>
  );
}

/** A single tile inside <BeatStrip> — fades + lifts into place. */
export function BeatItem({ className, children }: { className?: string; children: ReactNode }) {
  return (
    <m.div
      className={className}
      variants={{
        hidden: { opacity: 0, y: 6 },
        show: { opacity: 1, y: 0, transition: { duration: 0.16, ease: EASE } },
      }}
    >
      {children}
    </m.div>
  );
}

/** The chart/table block — fades up once, after the strip. No row-by-row motion. CSS-driven (not
 *  motion/react): a starved enter frame on a static, non-refetching page could leave an m.div stuck
 *  at initial opacity 0 — the table present in the DOM but invisible. The .ay-beat-block keyframe
 *  (fill-mode both) always settles visible; reduced-motion collapses it via the index.css guard. */
export function BeatBlock({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn('ay-beat-block', className)}>{children}</div>;
}
