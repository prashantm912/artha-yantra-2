import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { OptionsChainTable } from './OptionsChainTable.tsx';
import type { ChainTableLeg, ChainTableRow } from '../api/types.ts';
import type { OiInterpretation } from '../core/oiInterpretation.ts';

function leg(oi: number, ltp: string, interp: OiInterpretation): ChainTableLeg {
  return {
    leg: {
      exchange: 'NFO',
      tradingsymbol: 'X',
      ltp,
      bid: null,
      ask: null,
      volume: 100,
      oi,
      iv: '0.18',
      delta: '0.5',
      gamma: null,
      theta: null,
      vega: null,
      rho: null,
      ivReason: 'OK',
      priceSource: 'MID',
    },
    deltas: {
      oiChange: 200,
      oiChangePct: '20.00',
      ltpChange: '10',
      ltpChangePct: '10.00',
      interpretation: interp,
    },
  };
}

const rows: ChainTableRow[] = [
  { strike: '24100', ce: leg(1000, '100', 'LONG_BUILDUP'), pe: leg(2000, '90', 'SHORT_BUILDUP') },
  { strike: '24150', ce: leg(500, '80', 'SHORT_COVERING'), pe: leg(1500, '70', 'LONG_UNWINDING') },
];

describe('OptionsChainTable', () => {
  it('renders the mirrored CALL | strike | PUT structure with the OI-Int badge', () => {
    render(<OptionsChainTable rows={rows} spot="24150" atmStrike="24150" optionalKeys={[]} />);
    expect(screen.getAllByText('CALL').length).toBeGreaterThan(0);
    expect(screen.getAllByText('PUT').length).toBeGreaterThan(0);
    // OI-Int badge: full label is the accessible name, the abbreviation is the visible cue
    expect(screen.getAllByLabelText('Long Build Up').length).toBeGreaterThan(0);
  });

  it('tints the ATM strike row (sr-only at-the-money cue)', () => {
    render(<OptionsChainTable rows={rows} spot="24150" atmStrike="24150" optionalKeys={[]} />);
    expect(screen.getByText('at the money')).toBeInTheDocument();
  });

  it('computes the per-strike PCR (ΣPE OI / ΣCE OI)', () => {
    render(<OptionsChainTable rows={rows} spot="24150" atmStrike="24150" optionalKeys={[]} />);
    // strike 24100: 2000 / 1000 = 2.00
    expect(screen.getAllByText('2.00').length).toBeGreaterThan(0);
  });

  it('shows optional columns only when their key is enabled', () => {
    const { rerender } = render(
      <OptionsChainTable rows={rows} spot="24150" atmStrike="24150" optionalKeys={[]} />,
    );
    expect(screen.queryByText('Delta')).toBeNull();
    rerender(
      <OptionsChainTable rows={rows} spot="24150" atmStrike="24150" optionalKeys={['delta']} />,
    );
    expect(screen.getAllByText('Delta').length).toBeGreaterThan(0);
  });
});
