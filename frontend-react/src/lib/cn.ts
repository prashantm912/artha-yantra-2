import { clsx, type ClassValue } from 'clsx';
import { extendTailwindMerge } from 'tailwind-merge';

// tailwind-merge must know the app's CUSTOM type-ramp utilities (index.css @utility text-display…
// text-dense) are FONT-SIZE classes. Stock twMerge can't classify them, treats them as text-COLOR
// conflicts, and silently DROPS the colour class merged alongside them — the Button atom's
// `text-surface-0` vanished next to `text-body-sm`, leaving dark --ay-text ink on the accent fill
// (2.71:1 — the audit 2026-07-02 §4 color-contrast nodes on chain/cockpit/oi-analysis).
const twMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      'font-size': [
        { text: ['display', 'h1', 'h2', 'h3', 'body', 'body-sm', 'caption', 'dense'] },
      ],
    },
  },
});

/** Merge Tailwind class lists, resolving conflicts (shadcn convention). */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
