import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SpurtQuadrant } from './SpurtQuadrant.tsx';
import { isStrong } from './spurtStrength.ts';
import type { SpurtRow } from '../api/types.ts';

function row(
  strike: string,
  oiChange: number,
  oi: number,
  ltpChangePct: string | null,
  spurtPct: string | null,
): SpurtRow {
  return {
    strike,
    optionType: 'CE',
    ltp: '100',
    prevLtp: '90',
    oi,
    oiChange,
    spurtPct,
    ltpChange: '10',
    ltpChangePct,
    volume: 1000,
    interpretation: 'LONG_BUILDUP',
  };
}

describe('isStrong (oipulse signal-strength gate)', () => {
  it('is true only when both |%ΔLTP| > 50 and |%ΔOI| > 50', () => {
    expect(isStrong(row('1', 1, 1, '60', '70'))).toBe(true);
    expect(isStrong(row('1', 1, 1, '40', '70'))).toBe(false);
    expect(isStrong(row('1', 1, 1, '-60', '-70'))).toBe(true); // magnitude
    expect(isStrong(row('1', 1, 1, null, '70'))).toBe(false);
  });
});

describe('SpurtQuadrant', () => {
  it('sorts by |ΔOI| desc and computes Old OI = New OI − ΔOI', () => {
    render(
      <SpurtQuadrant
        title="T"
        subtitle="S"
        rows={[row('A', 100, 1000, '10', '10'), row('B', 500, 2000, '10', '10')]}
      />,
    );
    expect(screen.getByText('1,500')).toBeInTheDocument(); // B: 2000 − 500
    expect(screen.getByText('900')).toBeInTheDocument(); // A: 1000 − 100
  });

  it('paginates beyond 7 rows', () => {
    // strikes S0..S8 with ΔOI 0..8 → page 1 (7 rows) holds the top-|ΔOI| S8..S2; S1 + S0 page off.
    const rows = Array.from({ length: 9 }, (_, i) => row(`S${i}`, i, 100 + i, '10', '10'));
    render(<SpurtQuadrant title="T" subtitle="S" rows={rows} />);
    expect(screen.getByText('S8')).toBeInTheDocument(); // largest |ΔOI| — page 1
    expect(screen.queryByText('S0')).toBeNull(); // smallest — paged off (only 7 of 9 render)
  });
});
