import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Stepper } from './Stepper.tsx';

describe('Stepper', () => {
  it('marks done steps with ✓, numbers the rest, and flags the current step', () => {
    render(<Stepper steps={['Underlyings', 'Date range', 'Review']} current={1} />);
    // step 0 is done → ✓ ; step 1 is current → '2' ; step 2 is future → '3'
    expect(screen.getByText('✓')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
    const current = document.querySelector('li[aria-current="step"]');
    expect(current?.textContent).toContain('Date range');
  });
});
