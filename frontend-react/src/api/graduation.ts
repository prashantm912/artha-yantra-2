// F7 graduation board data layer: per published+enabled strategy, its CLOSED paper-trade metrics
// (trades / net-realized / win-rate / cost-adjusted profit-factor / expectancy / max-drawdown)
// scored vs configurable thresholds into a PAPER vs TAKE_ELIGIBLE stage. Read-only, measurement
// only — the board never promotes or arms anything (promotion is an owner action). Powers the
// "Graduation" strategies page. Money/ratio NUMERICs arrive as decimal strings.

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

/** One scored criterion: its name, the required bound (human string), the actual value, pass/fail. */
export interface Criterion {
  name: string;
  required: string;
  actual: string;
  pass: boolean;
}

/** One strategy's readiness row. NUMERICs are decimal strings; profitFactor is null when no losses. */
export interface StrategyGraduation {
  strategyId: string;
  slug: string;
  name: string;
  stage: 'PAPER' | 'TAKE_ELIGIBLE';
  trades: number;
  netRealized: string;
  winRate: string | null;
  profitFactor: string | null;
  expectancy: string;
  maxDrawdownPct: string;
  criteria: Criterion[];
}

/** The thresholds each strategy is scored against (echoed so the board is self-describing). */
export interface GraduationThresholds {
  minTrades: number;
  minProfitFactor: string;
  minExpectancy: string;
  maxDrawdownPct: string;
}

/** The whole board + the thresholds used + the compute time. */
export interface GraduationBoard {
  strategies: StrategyGraduation[];
  thresholds: GraduationThresholds;
  asOf: string;
}

/**
 * One GRADUATED strategy (F7): the marker + the metrics snapshot captured at graduation.
 * The three metric NUMERICs are nullable in strategy.strategy_graduations (V024).
 */
export interface Promotion {
  strategyId: string;
  graduatedAt: string;
  trades: number;
  expectancy: string | null;
  sharpe: string | null;
  maxDrawdownPct: string | null;
}

const GRADUATION = 'graduation';

export function useGraduationBoard() {
  return useQuery({
    queryKey: [GRADUATION, 'board'],
    queryFn: () => apiFetch<GraduationBoard>('/strategies/graduation'),
  });
}

/**
 * The strategies the F7 evaluator has marked GRADUATED, newest first (`GET /graduation/promotions`).
 * A BARE array on the wire (a documented envelope exception), measurement-only.
 */
export function useGraduationPromotions() {
  return useQuery({
    queryKey: [GRADUATION, 'promotions'],
    queryFn: () => apiFetch<Promotion[]>('/strategies/graduation/promotions'),
  });
}
