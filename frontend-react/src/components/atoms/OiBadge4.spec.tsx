import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { OiBadge4 } from './OiBadge4.tsx';
import type { OiInterpretation } from '../../core/oiInterpretation.ts';

describe('OiBadge4', () => {
  it('shows the abbreviation (non-colour cue) and the full label as the accessible name', () => {
    const cases: [OiInterpretation, string, string][] = [
      ['LONG_BUILDUP', 'Long Build Up', 'L.B.'],
      ['SHORT_BUILDUP', 'Short Build Up', 'S.B.'],
      ['SHORT_COVERING', 'Shorts Covering', 'S.C.'],
      ['LONG_UNWINDING', 'Long Unwinding', 'L.U.'],
    ];
    for (const [value, label, abbr] of cases) {
      const { unmount } = render(<OiBadge4 value={value} />);
      expect(screen.getByLabelText(label)).toHaveTextContent(abbr);
      unmount();
    }
  });

  it('shows the full label (not the abbreviation) when full', () => {
    render(<OiBadge4 value="LONG_BUILDUP" full />);
    expect(screen.getByLabelText('Long Build Up')).toHaveTextContent('Long Build Up');
  });

  it('renders an em-dash + sr-only text when null', () => {
    render(<OiBadge4 value={null} />);
    expect(screen.getByText('—')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByText('no interpretation')).toBeInTheDocument();
  });

  it('carries the full label + price/OI arrow as a tooltip', () => {
    render(<OiBadge4 value="LONG_BUILDUP" />);
    expect(screen.getByLabelText('Long Build Up')).toHaveAttribute(
      'title',
      'Long Build Up — price up · OI up',
    );
  });
});
