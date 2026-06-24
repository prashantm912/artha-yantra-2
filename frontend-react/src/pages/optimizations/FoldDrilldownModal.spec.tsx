import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import type { TrialFold } from '../../api/optimizations.ts';

// A controllable stand-in for the lazy folds query — each test sets what it returns before render.
let foldsResult: { data?: TrialFold[]; isLoading?: boolean; isError?: boolean };
vi.mock('../../api/optimizations.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/optimizations.ts')>();
  return { ...actual, useTrialFolds: () => foldsResult }; // keep REGIME_LABELS
});

import { FoldDrilldownModal } from './FoldDrilldownModal.tsx';

const FOLD: TrialFold = {
  fold: 1,
  train: { from: '2026-01-01T00:00:00Z', to: '2026-03-31T00:00:00Z' },
  test: { from: '2026-04-01T00:00:00Z', to: '2026-04-30T00:00:00Z' },
  trainMetrics: { sharpe: '2.1' },
  oosMetrics: { sharpe: '1.4' },
  regimeMix: { BULL: 12, RANGE: 5, BEAR: 0, CRASH: 0 },
  regimeOos: {
    BULL: { sharpe: '1.65', expectancy: '320', tradeCount: 8 },
    RANGE: { sharpe: '0.40', expectancy: '90', tradeCount: 3 },
  },
};

describe('FoldDrilldownModal', () => {
  beforeEach(() => {
    foldsResult = { data: [FOLD] };
  });

  it('opens on a non-null trial and renders the per-fold OOS-by-regime rows', () => {
    render(<FoldDrilldownModal sweepId="sweep-1" trialNumber={5} onClose={() => {}} />);
    // labelled dialog
    expect(screen.getByRole('dialog', { name: /Trial #5 — fold drill-down/ })).toBeInTheDocument();
    expect(screen.getByText('#1')).toBeInTheDocument(); // fold index
    expect(screen.getByText('2026-04-01 → 2026-04-30')).toBeInTheDocument(); // test window
    expect(screen.getByText('2.10')).toBeInTheDocument(); // train Sharpe
    expect(screen.getByText('1.40')).toBeInTheDocument(); // OOS Sharpe headline
    // a regime that traded shows OOS Sharpe / ₹ expectancy / trades in one cell (text-node split)
    const bullCell = screen.getByTitle(/BULL: OOS Sharpe/);
    expect(bullCell.textContent?.replace(/\s+/g, ' ')).toContain('1.65 / 320 / 8');
  });

  it('does not render when no trial is selected (trialNumber null)', () => {
    render(<FoldDrilldownModal sweepId="sweep-1" trialNumber={null} onClose={() => {}} />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows the no-walk-forward-folds message for a full-window trial', () => {
    foldsResult = { data: [] };
    render(<FoldDrilldownModal sweepId="sweep-1" trialNumber={8} onClose={() => {}} />);
    expect(screen.getByText(/No walk-forward folds/)).toBeInTheDocument();
  });

  it('closes on Escape and on a backdrop click', () => {
    const onClose = vi.fn();
    render(<FoldDrilldownModal sweepId="sweep-1" trialNumber={5} onClose={onClose} />);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByLabelText('Close dialog')); // the backdrop button
    expect(onClose).toHaveBeenCalledTimes(2);
  });
});
