import { describe, expect, it } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ConnectingDotsTable } from './ConnectingDotsTable.tsx';
import type { ConnectingDotsRow } from '../api/types.ts';

function row(p: Partial<ConnectingDotsRow>): ConnectingDotsRow {
  return {
    timeInterval: '09:18-09:21',
    trend: 2,
    dow: 0,
    vix: 1,
    volume: 0,
    activeStrikeIv: 0,
    activeStrikeOi: 1,
    futOi: 2,
    vwap: 2,
    supertrend: 1,
    rsi: 1,
    futPrice: 2,
    dailyTrend: 2,
    ...p,
  };
}

describe('ConnectingDotsTable', () => {
  it('renders the 13-column header in the oipulse order', () => {
    render(<ConnectingDotsTable rows={[row({})]} />);
    for (const h of [
      'Date Time', 'Trend', 'Dow Jones', 'Vix', 'Volume', 'Active Strike IV', 'Active Strike OI',
      'OI Inter.', 'VWAP', 'Supertrend', 'RSI', 'Price', 'Daily Trend',
    ]) {
      expect(screen.getByRole('columnheader', { name: h })).toBeInTheDocument();
    }
  });

  it('renders factor glyphs with accessible labels by 3-state code', () => {
    render(<ConnectingDotsTable rows={[row({ dow: 0, vix: 1, futPrice: 2 })]} />);
    expect(screen.getAllByLabelText('Neutral').length).toBeGreaterThan(0); // dow=0 ↔
    expect(screen.getAllByLabelText('Bullish').length).toBeGreaterThan(0); // vix=1 ↑
    expect(screen.getAllByLabelText('Bearish').length).toBeGreaterThan(0); // futPrice=2 ↓
  });

  it('shows the 5-state composite trend label', () => {
    render(<ConnectingDotsTable rows={[row({ trend: 1 }), row({ timeInterval: '09:15-09:18', trend: 4 })]} />);
    expect(screen.getByText('Ext. Bullish')).toBeInTheDocument();
    expect(screen.getByText('Ext. Bearish')).toBeInTheDocument();
  });

  it('paginates at 25 rows per page', () => {
    const rows = Array.from({ length: 30 }, (_, i) =>
      row({ timeInterval: `09:${String(i).padStart(2, '0')}-x` }),
    );
    render(<ConnectingDotsTable rows={rows} />);
    expect(screen.getByText('1 – 25 of 30')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Next ›' }));
    expect(screen.getByText('26 – 30 of 30')).toBeInTheDocument();
  });

  it('renders the five-pill signals legend', () => {
    render(<ConnectingDotsTable rows={[row({})]} />);
    expect(screen.getByText('Ext. Bullish ↑')).toBeInTheDocument();
    expect(screen.getByText('↔ = Neutral')).toBeInTheDocument();
  });
});
