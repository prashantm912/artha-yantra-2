import { describe, expect, it } from 'vitest';
import { defaultBasket } from './oiExpiryBasket.ts';

describe('defaultBasket', () => {
  it('returns the input unchanged when there are 5 or fewer strikes', () => {
    expect(defaultBasket(['a', 'b', 'c'])).toEqual(['a', 'b', 'c']);
    const five = ['a', 'b', 'c', 'd', 'e'];
    expect(defaultBasket(five)).toEqual(five);
  });

  it('picks the two ends, the centre, and the quartiles for a wider window', () => {
    // 9 strikes -> n=8 -> idx [0, round(2)=2, 4, round(6)=6, 8]
    const strikes = ['s0', 's1', 's2', 's3', 's4', 's5', 's6', 's7', 's8'];
    expect(defaultBasket(strikes)).toEqual(['s0', 's2', 's4', 's6', 's8']);
  });

  it('always keeps both ends and returns wide-spaced (non-adjacent) strikes', () => {
    const strikes = Array.from({ length: 11 }, (_, i) => `k${i}`);
    const basket = defaultBasket(strikes);
    // 11 strikes -> n=10 -> idx [0, round(2.5)=3, 5, round(7.5)=8, 10]
    expect(basket).toEqual(['k0', 'k3', 'k5', 'k8', 'k10']);
    expect(basket[0]).toBe('k0');
    expect(basket[basket.length - 1]).toBe('k10');
  });

  it('de-duplicates when rounded quartile indices collide', () => {
    // 6 strikes -> n=5 -> idx [0, round(1.25)=1, round(2.5)=3, round(3.75)=4, 5] = 5 unique
    const strikes = ['a', 'b', 'c', 'd', 'e', 'f'];
    const basket = defaultBasket(strikes);
    expect(new Set(basket).size).toBe(basket.length);
    expect(basket).toEqual(['a', 'b', 'd', 'e', 'f']);
  });
});
