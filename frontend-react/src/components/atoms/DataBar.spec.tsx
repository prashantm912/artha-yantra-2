import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DataBar } from './DataBar.tsx';

describe('DataBar', () => {
  it('renders the numeric label (cell is never colour-only)', () => {
    render(<DataBar value={1000} max={2000} label="1,000" />);
    expect(screen.getByText('1,000')).toBeInTheDocument();
  });

  it('fills width = |value|/max, clamped to 100%', () => {
    const { container, rerender } = render(<DataBar value={50} max={100} label="50" />);
    const fill = () => container.querySelector('span[aria-hidden="true"]') as HTMLElement;
    expect(fill().style.width).toBe('50%');

    rerender(<DataBar value={250} max={100} label="250" />); // clamps
    expect(fill().style.width).toBe('100%');

    rerender(<DataBar value={-30} max={100} label="-30" />); // magnitude only
    expect(fill().style.width).toBe('30%');
  });

  it('floors at 0% when max <= 0', () => {
    const { container } = render(<DataBar value={5} max={0} label="5" />);
    const fill = container.querySelector('span[aria-hidden="true"]') as HTMLElement;
    expect(fill.style.width).toBe('0%');
  });
});
