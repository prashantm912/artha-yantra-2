import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { OiBadge4 } from './OiBadge4.tsx';
import type { OiInterpretation } from '../../core/oiInterpretation.ts';

describe('OiBadge4', () => {
  it('always renders the text label (colour is never the sole cue) for every state', () => {
    const cases: [OiInterpretation, string][] = [
      ['LONG_BUILDUP', 'Long Buildup'],
      ['SHORT_BUILDUP', 'Short Buildup'],
      ['SHORT_COVERING', 'Short Covering'],
      ['LONG_UNWINDING', 'Long Unwinding'],
    ];
    for (const [value, label] of cases) {
      const { unmount } = render(<OiBadge4 value={value} />);
      expect(screen.getByText(label)).toBeInTheDocument();
      unmount();
    }
  });

  it('renders an em-dash + sr-only text when null', () => {
    render(<OiBadge4 value={null} />);
    expect(screen.getByText('—')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByText('no interpretation')).toBeInTheDocument();
  });

  it('carries the price/OI arrow as a tooltip', () => {
    render(<OiBadge4 value="LONG_BUILDUP" />);
    expect(screen.getByText('Long Buildup')).toHaveAttribute('title', 'price up · OI up');
  });
});
