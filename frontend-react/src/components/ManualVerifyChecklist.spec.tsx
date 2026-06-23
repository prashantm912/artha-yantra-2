import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ManualVerifyChecklist } from './ManualVerifyChecklist.tsx';
import type { ScalperDetail } from '../api/signals.ts';

function makeDetail(over: Partial<ScalperDetail> = {}): ScalperDetail {
  return {
    side: 'CE',
    underlying: 'NIFTY 50',
    expiry: '2026-06-26',
    tradeable: 'NFO:NIFTY26JUN24000CE',
    strike: 24000,
    option_ltp: 120,
    iv: 14.2,
    delta: 0.52,
    confluence_aggregate: 0.67,
    dots: [
      { dot: 'OI buildup', weight: 0.4, supports: true },
      { dot: 'VWAP below', weight: 0.3, supports: false },
    ],
    manual_checks: [
      { key: 'spread', label: 'Spread acceptable', doc_ref: 'CHEAT_SHEET §3', assist: 'bid/ask < 2%' },
      { key: 'trend', label: 'Trend aligned', doc_ref: 'CHEAT_SHEET §5', assist: 'price above VWAP' },
    ],
    ...over,
  };
}

describe('ManualVerifyChecklist', () => {
  it('renders the dots (supports/opposes) + each check with its assist and doc_ref', () => {
    render(<ManualVerifyChecklist detail={makeDetail()} onConfirmedChange={vi.fn()} />);
    expect(screen.getByText('OI buildup')).toBeInTheDocument();
    expect(screen.getByText('(supports)')).toBeInTheDocument();
    expect(screen.getByText('(opposes)')).toBeInTheDocument();
    expect(screen.getByText('Spread acceptable')).toBeInTheDocument();
    expect(screen.getByText('bid/ask < 2%')).toBeInTheDocument();
    expect(screen.getByText('CHEAT_SHEET §3')).toBeInTheDocument();
  });

  it('starts unconfirmed with a warning, and confirms only once ALL checks are ticked', () => {
    const onConfirmedChange = vi.fn();
    render(<ManualVerifyChecklist detail={makeDetail()} onConfirmedChange={onConfirmedChange} />);

    // initial: not confirmed + warning visible
    expect(onConfirmedChange).toHaveBeenLastCalledWith(false);
    expect(screen.getByRole('alert')).toHaveTextContent('2 of 2 checks unconfirmed');

    const [spread, trend] = screen.getAllByRole('checkbox') as HTMLInputElement[];

    // tick one → partial: still not confirmed, warning updates
    fireEvent.click(spread);
    expect(onConfirmedChange).toHaveBeenLastCalledWith(false);
    expect(screen.getByRole('alert')).toHaveTextContent('1 of 2 checks unconfirmed');

    // tick the second → all ticked: confirmed, warning gone
    fireEvent.click(trend);
    expect(onConfirmedChange).toHaveBeenLastCalledWith(true);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('confirms via the override checkbox without ticking the checks', () => {
    const onConfirmedChange = vi.fn();
    render(<ManualVerifyChecklist detail={makeDetail()} onConfirmedChange={onConfirmedChange} />);
    fireEvent.click(screen.getByLabelText('Confirm despite unchecked'));
    expect(onConfirmedChange).toHaveBeenLastCalledWith(true);
  });

  it('re-arms (clears ticks + override) when the signal changes, not on a same-signal re-render', () => {
    const onConfirmedChange = vi.fn();
    const detail = makeDetail();
    const { rerender } = render(
      <ManualVerifyChecklist detail={detail} onConfirmedChange={onConfirmedChange} />,
    );

    // confirm via override
    fireEvent.click(screen.getByLabelText('Confirm despite unchecked'));
    expect(onConfirmedChange).toHaveBeenLastCalledWith(true);

    // same signal (same tradeable), new object identity → ticks/override survive
    rerender(<ManualVerifyChecklist detail={makeDetail()} onConfirmedChange={onConfirmedChange} />);
    expect((screen.getByLabelText('Confirm despite unchecked') as HTMLInputElement).checked).toBe(true);
    expect(onConfirmedChange).toHaveBeenLastCalledWith(true);

    // different signal (new tradeable) → re-arm clears everything
    rerender(
      <ManualVerifyChecklist
        detail={makeDetail({ tradeable: 'NFO:NIFTY26JUN24100CE' })}
        onConfirmedChange={onConfirmedChange}
      />,
    );
    expect((screen.getByLabelText('Confirm despite unchecked') as HTMLInputElement).checked).toBe(false);
    expect(onConfirmedChange).toHaveBeenLastCalledWith(false);
  });
});
