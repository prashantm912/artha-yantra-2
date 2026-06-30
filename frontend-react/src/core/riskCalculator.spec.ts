import { describe, expect, it } from 'vitest';
import { computeRisk, type RiskInput } from './riskCalculator.ts';

const base: RiskInput = {
  capital: 200000,
  riskPct: 1,
  entry: 100,
  stop: 90,
  lotSize: 1,
  rewardRisk: 2,
};

describe('computeRisk', () => {
  it('sizes a long so the loss at the stop equals the risk budget', () => {
    const r = computeRisk(base);
    expect(r.side).toBe('LONG');
    expect(r.riskAmount).toBe(2000); // 1% of 200k
    expect(r.perUnitRisk).toBe(10); // 100 - 90
    expect(r.rawUnits).toBe(200); // 2000 / 10
    expect(r.units).toBe(200);
    expect(r.sizedRisk).toBe(2000); // 200 × 10
    expect(r.valid).toBe(true);
  });

  it('derives the reward-target and reward amount from the R:R multiple', () => {
    const r = computeRisk(base);
    expect(r.target).toBe(120); // 100 + 10 × 2
    expect(r.rewardAmount).toBe(4000); // 200 × 10 × 2
  });

  it('detects a short when the stop is above the entry and flips the target', () => {
    const r = computeRisk({ ...base, entry: 100, stop: 110 });
    expect(r.side).toBe('SHORT');
    expect(r.perUnitRisk).toBe(10);
    expect(r.target).toBe(80); // 100 - 10 × 2 (downside target)
  });

  it('floors to whole lots so the realised risk never exceeds the budget', () => {
    // lotSize 75: rawUnits 200 / 75 = 2.66 -> 2 lots -> 150 units; sizedRisk 1500 < 2000 budget.
    const r = computeRisk({ ...base, lotSize: 75 });
    expect(r.lots).toBe(2);
    expect(r.units).toBe(150);
    expect(r.sizedRisk).toBe(1500);
    expect(r.sizedRisk).toBeLessThanOrEqual(r.riskAmount);
  });

  it('computes the 1%-of-capital lock price for the sized position', () => {
    const r = computeRisk(base); // 200 units, 1% of 200k = 2000 profit -> +10 price move
    expect(r.onePctTarget).toBe(110); // 100 + 2000/200
  });

  it('is invalid when entry equals stop (no per-unit risk)', () => {
    const r = computeRisk({ ...base, entry: 100, stop: 100 });
    expect(r.valid).toBe(false);
    expect(r.units).toBe(0);
  });

  it('is invalid on non-positive capital or risk', () => {
    expect(computeRisk({ ...base, capital: 0 }).valid).toBe(false);
    expect(computeRisk({ ...base, riskPct: 0 }).valid).toBe(false);
  });

  it('reports deployment as a percent of capital', () => {
    const r = computeRisk(base); // 200 units × 100 = 20000 deployed / 200000 = 10%
    expect(r.capitalDeployed).toBe(20000);
    expect(r.deploymentPct).toBe(10);
  });
});
