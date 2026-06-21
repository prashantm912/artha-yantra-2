import { describe, expect, it } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { BanksAnalysisTable } from './BanksAnalysisTable.tsx';
import type { BankAnalysisCell, BankAnalysisRow } from '../api/types.ts';

function cell(p: Partial<BankAnalysisCell>): BankAnalysisCell {
  return { bank: 'HDFCBANK', ltpPct: '1.94', oiPct: '0.23', interpretation: 'LONG_BUILDUP', ...p };
}
function row(p: Partial<BankAnalysisRow>): BankAnalysisRow {
  return { bucket: '2026-06-15T09:15:00+05:30', cells: [cell({})], ...p };
}

const BANKS = ['HDFCBANK', 'ICICIBANK'];

describe('BanksAnalysisTable', () => {
  it('renders the # + Time + per-bank column headers in order', () => {
    render(<BanksAnalysisTable banks={BANKS} rows={[row({ cells: [cell({}), cell({ bank: 'ICICIBANK' })] })]} intervalMinutes={5} />);
    expect(screen.getByRole('columnheader', { name: 'Time' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'HDFCBANK' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'ICICIBANK' })).toBeInTheDocument();
  });

  it('renders a cell as signed cumulative LTP%/OI% + the per-interval interpretation badge', () => {
    render(<BanksAnalysisTable banks={['HDFCBANK']} rows={[row({})]} intervalMinutes={5} />);
    expect(screen.getByText('+1.94%')).toBeInTheDocument(); // LTP% (signed decimal string, no parseFloat)
    expect(screen.getByText('+0.23%')).toBeInTheDocument(); // OI%
    expect(screen.getByLabelText('Long Build Up')).toBeInTheDocument(); // the 4-state badge
    expect(screen.getByText('09:15-09:20')).toBeInTheDocument(); // bucket window @ 5m
  });

  it('renders a muted em-dash for an absent (null) bank cell', () => {
    render(
      <BanksAnalysisTable banks={BANKS} rows={[row({ cells: [cell({}), null] })]} intervalMinutes={5} />,
    );
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
  });

  it('paginates at 25 rows per page', () => {
    const rows = Array.from({ length: 30 }, (_, i) =>
      row({ bucket: `2026-06-15T10:${String(i).padStart(2, '0')}:00+05:30` }),
    );
    render(<BanksAnalysisTable banks={['HDFCBANK']} rows={rows} intervalMinutes={5} />);
    expect(screen.getByText('1 – 25 of 30')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Next ›' }));
    expect(screen.getByText('26 – 30 of 30')).toBeInTheDocument();
  });

  it('renders the 4-state legend', () => {
    render(<BanksAnalysisTable banks={['HDFCBANK']} rows={[row({})]} intervalMinutes={5} />);
    expect(screen.getByText('L.B. = Long Build Up')).toBeInTheDocument();
    expect(screen.getByText('S.C. = Shorts Covering')).toBeInTheDocument();
  });
});
