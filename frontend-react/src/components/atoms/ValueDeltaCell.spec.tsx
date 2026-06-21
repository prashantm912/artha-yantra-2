import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ValueDeltaCell } from './ValueDeltaCell.tsx';

describe('ValueDeltaCell', () => {
  it('prefixes + and tones bull for a positive value', () => {
    render(<ValueDeltaCell value="5.5" suffix="%" />);
    const el = screen.getByText('+5.50%');
    expect(el.className).toContain('text-bull');
  });

  it('keeps the minus sign and tones bear for a negative value', () => {
    render(<ValueDeltaCell value="-3.2" />);
    const el = screen.getByText('-3.20');
    expect(el.className).toContain('text-bear');
  });

  it('renders an em-dash for a null value', () => {
    render(<ValueDeltaCell value={null} />);
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('shows no sign and no tone for zero', () => {
    render(<ValueDeltaCell value="0" />);
    const el = screen.getByText('0.00');
    expect(el.className).not.toContain('text-bull');
    expect(el.className).not.toContain('text-bear');
  });
});
