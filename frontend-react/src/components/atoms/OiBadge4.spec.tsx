import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { OiBadge4 } from './OiBadge4.tsx';
import type { OiInterpretation } from '../../core/oiInterpretation.ts';

describe('OiBadge4', () => {
  it('shows the abbreviation (non-colour cue) and the full label as the accessible name', () => {
    const cases: [OiInterpretation, string, string][] = [
      ['LONG_BUILDUP', 'Long Buildup', 'L.B.'],
      ['SHORT_BUILDUP', 'Short Buildup', 'S.B.'],
      ['SHORT_COVERING', 'Short Covering', 'S.C.'],
      ['LONG_UNWINDING', 'Long Unwinding', 'L.U.'],
    ];
    for (const [value, label, abbr] of cases) {
      const { unmount } = render(<OiBadge4 value={value} />);
      expect(screen.getByLabelText(label)).toHaveTextContent(abbr);
      unmount();
    }
  });

  it('renders an em-dash + sr-only text when null', () => {
    render(<OiBadge4 value={null} />);
    expect(screen.getByText('—')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByText('no interpretation')).toBeInTheDocument();
  });

  it('carries the full label + price/OI arrow as a tooltip', () => {
    render(<OiBadge4 value="LONG_BUILDUP" />);
    expect(screen.getByLabelText('Long Buildup')).toHaveAttribute(
      'title',
      'Long Buildup — price up · OI up',
    );
  });
});
