import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { DateInput } from './DateInput.tsx';

// Audit 2026-07-02 §9.3: a naturally-typed "02-07-72026" reached the server as year 72026.
// Only sane ISO dates inside [min, max] may propagate; partial keystrokes stay local drafts.

describe('DateInput', () => {
  it('propagates a sane date and null on clear', () => {
    const onChange = vi.fn();
    render(<DateInput ariaLabel="History date" value={null} onChange={onChange} max="2026-07-02" />);
    const input = screen.getByLabelText('History date');
    fireEvent.change(input, { target: { value: '2026-07-01' } });
    expect(onChange).toHaveBeenLastCalledWith('2026-07-01');
    fireEvent.change(input, { target: { value: '' } });
    expect(onChange).toHaveBeenLastCalledWith(null);
  });

  it('never propagates out-of-range years (72026, partial 0002) or future dates', () => {
    const onChange = vi.fn();
    render(<DateInput ariaLabel="History date" value={null} onChange={onChange} max="2026-07-02" />);
    const input = screen.getByLabelText('History date');
    fireEvent.change(input, { target: { value: '72026-07-02' } });
    fireEvent.change(input, { target: { value: '0002-07-02' } });
    fireEvent.change(input, { target: { value: '2027-01-01' } });
    expect(onChange).not.toHaveBeenCalled();
  });
});
