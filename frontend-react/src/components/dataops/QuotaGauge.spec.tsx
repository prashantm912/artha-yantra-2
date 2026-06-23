import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QuotaGauge } from './QuotaGauge.tsx';

describe('QuotaGauge', () => {
  it('shows a not-configured note when the analytics source is off', () => {
    render(<QuotaGauge configured={false} windows={[]} />);
    expect(screen.getByText(/not configured/i)).toBeInTheDocument();
  });

  it('renders a labelled bar per window with used/max and the threshold colour', () => {
    const { container } = render(
      <QuotaGauge
        configured
        windows={[
          { window: '1s', used: 10, max: 45, remaining: 35 }, // ~22% → bull
          { window: '1m', used: 400, max: 450, remaining: 50 }, // ~89% → warn
          { window: '30m', used: 1750, max: 1800, remaining: 50 }, // ~97% → bear
        ]}
      />,
    );
    expect(screen.getByText('10 / 45')).toBeInTheDocument();
    expect(container.querySelectorAll('[role="progressbar"]')).toHaveLength(3);
    expect(container.querySelector('.bg-bull')).toBeTruthy();
    expect(container.querySelector('.bg-warn')).toBeTruthy();
    expect(container.querySelector('.bg-bear')).toBeTruthy();
  });

  it('guards max=0 with a 0% fill (no divide-by-zero)', () => {
    const { container } = render(
      <QuotaGauge configured windows={[{ window: '1s', used: 5, max: 0, remaining: 0 }]} />,
    );
    const fill = container.querySelector('[role="progressbar"] > div') as HTMLElement;
    expect(fill.style.width).toBe('0%');
  });
});
