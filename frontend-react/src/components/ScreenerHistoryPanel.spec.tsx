import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { FunnelAttrition, ScreenDiff } from '../api/screenerHistory.ts';

const attrition: FunnelAttrition = {
  screenDate: '2026-07-01',
  scanned: 100,
  gates: [
    { gate: 1, passed: 80 },
    { gate: 2, passed: 60 },
  ],
  passesAll: 20,
  buyable: 5,
  onDeck: 8,
  watch: 7,
};

const diff: ScreenDiff = {
  screenDate: '2026-07-01',
  priorDate: '2026-06-30',
  enteredCount: 1,
  leftCount: 1,
  entered: [{ symbol: 'ENTERSYM', rsRank: '90' }],
  left: [{ symbol: 'LEFTSYM', rsRank: '80' }],
};

vi.mock('../api/screenerHistory.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/screenerHistory.ts')>();
  return {
    ...actual,
    useScreenerAttrition: () => ({ data: attrition, isPending: false, isError: false, isSuccess: true }),
    useScreenerDiff: () => ({ data: diff, isPending: false, isError: false, isSuccess: true }),
  };
});

import { ScreenerHistoryPanel } from './ScreenerHistoryPanel.tsx';
import { screenerExportPath } from '../api/screenerHistory.ts';

describe('ScreenerHistoryPanel', () => {
  it('renders the funnel attrition steps and the day-over-day diff', () => {
    render(<ScreenerHistoryPanel family="minervini" asOf="2026-07-01" />);
    expect(screen.getByText('Funnel attrition')).toBeInTheDocument();
    expect(screen.getByText('Scanned')).toBeInTheDocument();
    expect(screen.getByText('Pass gate 1')).toBeInTheDocument();
    expect(screen.getByText('Pass all gates')).toBeInTheDocument();
    expect(screen.getByText('Buyable')).toBeInTheDocument();
    // diff chips
    expect(screen.getByText('ENTERSYM')).toBeInTheDocument();
    expect(screen.getByText('LEFTSYM')).toBeInTheDocument();
    expect(screen.getByText(/Entered \(1\)/)).toBeInTheDocument();
    expect(screen.getByText(/Left \(1\)/)).toBeInTheDocument();
  });
});

describe('screenerExportPath', () => {
  it('builds the CSV export URL with asOf + passesAllOnly', () => {
    expect(screenerExportPath('manas-arora', '2026-07-01', true)).toBe(
      '/market/screener/manas-arora/export?passesAllOnly=true&asOf=2026-07-01',
    );
    expect(screenerExportPath('minervini', null, false)).toBe(
      '/market/screener/minervini/export?passesAllOnly=false',
    );
  });
});
