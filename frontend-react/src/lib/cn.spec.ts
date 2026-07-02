import { describe, expect, it } from 'vitest';
import { cn } from './cn.ts';

describe('cn (tailwind-merge with the custom type ramp)', () => {
  it('keeps a colour class next to a custom font-size utility (the dropped text-surface-0 bug)', () => {
    // Stock twMerge classified text-body-sm as a text-COLOUR conflict and dropped text-surface-0,
    // leaving --ay-text ink on the accent fill (audit 2026-07-02 §4, 2.71:1 contrast).
    const merged = cn('bg-accent text-surface-0 hover:opacity-90', 'h-9 px-4 text-body-sm');
    expect(merged).toContain('text-surface-0');
    expect(merged).toContain('text-body-sm');
  });

  it('still resolves genuine font-size conflicts (later wins)', () => {
    expect(cn('text-body', 'text-body-sm')).toBe('text-body-sm');
  });
});
