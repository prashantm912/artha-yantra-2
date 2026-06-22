import { describe, expect, it } from 'vitest';
import { buildPayoff, pnlAtSpot, type PayoffLeg } from './payoffEngine.ts';

const RANGE = { min: 80, max: 130, step: 1 };
const leg = (
  strike: number,
  type: PayoffLeg['type'],
  side: PayoffLeg['side'],
  premium: number,
  extra: Partial<PayoffLeg> = {},
): PayoffLeg => ({ strike, type, side, lots: 1, premium, ...extra });

describe('payoffEngine', () => {
  it('long call: breakeven = strike + premium, capped loss, unbounded profit', () => {
    const r = buildPayoff([leg(100, 'CE', 'BUY', 5)], 1, RANGE);
    expect(r.breakevens).toEqual([105]);
    expect(r.maxLoss).toBe(-5); // below the strike, only the premium is lost
    expect(r.unboundedProfit).toBe(true);
    expect(r.netPremium).toBe(-5); // a debit
    expect(pnlAtSpot([leg(100, 'CE', 'BUY', 5)], 1, 110)).toBe(5); // (110-100) - 5
  });

  it('short put: breakeven = strike − premium, capped profit', () => {
    const r = buildPayoff([leg(100, 'PE', 'SELL', 4)], 1, RANGE);
    expect(r.breakevens).toEqual([96]);
    expect(r.maxProfit).toBe(4); // above the strike, keep the premium
    expect(r.netPremium).toBe(4); // a credit
  });

  it('long straddle: two breakevens, max loss = sum of premiums at the strike', () => {
    const r = buildPayoff([leg(100, 'CE', 'BUY', 5), leg(100, 'PE', 'BUY', 4)], 1, RANGE);
    expect(r.breakevens).toEqual([91, 109]); // 100 ± 9
    expect(r.maxLoss).toBe(-9);
  });

  it('bull call spread: capped profit + loss, single breakeven', () => {
    const r = buildPayoff([leg(100, 'CE', 'BUY', 6), leg(110, 'CE', 'SELL', 2)], 1, RANGE);
    expect(r.breakevens).toEqual([104]); // lower strike + net debit (6−2)
    expect(r.maxLoss).toBe(-4); // the net debit
    expect(r.maxProfit).toBe(6); // (110−100) − 4
    expect(r.unboundedProfit).toBe(false);
    expect(r.netPremium).toBe(-4);
  });

  it('scales P&L and net greeks by lots × lotSize', () => {
    const legs = [leg(100, 'CE', 'BUY', 5, { delta: 0.5, lots: 2 })];
    const r = buildPayoff(legs, 75, RANGE);
    expect(r.netGreeks.delta).toBe(0.5 * 2 * 75); // 75
    expect(pnlAtSpot(legs, 75, 110)).toBe((10 - 5) * 2 * 75); // 750
  });

  it('empty legs yield a flat zero payoff', () => {
    const r = buildPayoff([], 75, RANGE);
    expect(r.maxProfit).toBe(0);
    expect(r.maxLoss).toBe(0);
    expect(r.breakevens).toEqual([]);
  });
});
