import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import type { ScoreBreakdownDto } from '../../stores/signals.store';
import { ReasoningBreakdownPanel } from './reasoning-breakdown-panel';

/** The BPB provenance numbers: 0.85 + 0.52 over weights 1.0 + 0.8 = 0.76111111 (ADR A1). */
const BREAKDOWN: ScoreBreakdownDto = {
  composite: '0.76111111',
  threshold: '0.70000000',
  passed: true,
  requiredComposite: '0.76111111',
  optionalMinScore: '0.6',
  optionalGateMargin: '0.15',
  weightDenominator: '1.8',
  gate: {
    kind: 'all',
    passed: true,
    children: [
      {
        kind: 'expression',
        rule: 'close > vwap',
        passed: true,
        operands: { close: '101.25', vwap: '100.50' },
      },
      { kind: 'crossover', rule: 'crossover(fast, slow)', passed: true, operands: { fast: '9', slow: '8' } },
    ],
  },
  indicators: [
    {
      alias: 'r1',
      name: 'RSI',
      timeframe: '1m',
      score: '0.85',
      weight: '1.0',
      contribution: '0.85',
      optional: false,
      activated: true,
      activationReason: 'REQUIRED',
      rawValue: '67',
      params: { period: 14 },
    },
    {
      alias: 'r2',
      name: 'ADX',
      timeframe: '1m',
      score: '0.65',
      weight: '0.8',
      contribution: '0.52',
      optional: false,
      activated: true,
      activationReason: 'REQUIRED',
      rawValue: '31.2',
      params: { period: 14 },
    },
    {
      alias: 'soft',
      name: 'VOLUME_RATIO',
      timeframe: '1m',
      score: '0.30',
      weight: '0.5',
      contribution: '0.00000000',
      optional: true,
      activated: false,
      activationReason: 'SCORE_BELOW_MIN',
      rawValue: '1.2',
      params: { lookback: 20 },
    },
  ],
};

@Component({
  imports: [ReasoningBreakdownPanel],
  template: `<ay-reasoning-breakdown [breakdown]="breakdown()" />`,
})
class Host {
  readonly breakdown = signal(BREAKDOWN);
}

describe('ReasoningBreakdownPanel (the frozen C-2.6 renderer)', () => {
  async function render(): Promise<HTMLElement> {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('upholds the renderer invariant: composite = Σ contributions / weightDenominator', async () => {
    const activated = BREAKDOWN.indicators.filter((entry) => entry.activated);
    const sum = activated.reduce((total, entry) => total + Number(entry.contribution), 0);
    const reconstructed = sum / Number(BREAKDOWN.weightDenominator);
    expect(Math.abs(reconstructed - Number(BREAKDOWN.composite))).toBeLessThan(1e-7);

    const html = await render();
    expect(html.textContent).toContain('composite 0.7611');
    expect(html.textContent).toContain('threshold 0.7000');
    expect(html.textContent).toContain('1.8000'); // the denominator the invariant divides by
  });

  it('renders the gate checklist with actual operand values', async () => {
    const html = await render();
    expect(html.textContent).toContain('close > vwap');
    expect(html.textContent).toContain('close=101.25');
    expect(html.textContent).toContain('vwap=100.50');
    expect(html.querySelectorAll('.leaf-pass').length).toBeGreaterThanOrEqual(2);
  });

  it('shows optional reinforcement state via activationReason badges', async () => {
    const html = await render();
    expect(html.textContent).toContain('SCORE_BELOW_MIN');
    expect(html.textContent).toContain('REQUIRED');
    // non-activated optionals contribute no stacked segment
    expect(html.querySelectorAll('.stack .seg').length).toBe(2);
  });
});
