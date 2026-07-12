import { describe, expect, it } from 'vitest';
import {
  allGatesGreen,
  bestChallengerScore,
  expiryCountdown,
  fmtNum,
  fmtSigned,
  gateSummary,
  isGateFail,
  isGatePass,
  pipelineCounts,
  robustScoreContribs,
  shortId,
  type Candidate,
  type GateResult,
  type Scorecard,
} from './evolution.ts';

const gate = (id: string, status: string): GateResult => ({ id, status });

describe('gate helpers', () => {
  it('classifies PASS/FAIL, treating SKIPPED/UNKNOWN as neither', () => {
    expect(isGatePass('PASS')).toBe(true);
    expect(isGatePass('SKIPPED')).toBe(false);
    expect(isGateFail('FAIL')).toBe(true);
    expect(isGateFail('UNKNOWN')).toBe(false);
  });

  it('summarizes passed/failed/total + the failed ids (skipped counts as neither)', () => {
    const s = gateSummary([
      gate('evidence_floor', 'PASS'),
      gate('oos_sign', 'FAIL'),
      gate('holdout', 'SKIPPED'),
      gate('regime_floor', 'FAIL'),
    ]);
    expect(s).toEqual({ passed: 1, failed: 2, total: 4, failedIds: ['oos_sign', 'regime_floor'] });
  });

  it('handles an undefined gate list', () => {
    expect(gateSummary(undefined)).toEqual({ passed: 0, failed: 0, total: 0, failedIds: [] });
  });

  it('allGatesGreen only when there is at least one gate and no FAIL', () => {
    expect(allGatesGreen([gate('a', 'PASS'), gate('b', 'SKIPPED')])).toBe(true);
    expect(allGatesGreen([gate('a', 'PASS'), gate('b', 'FAIL')])).toBe(false);
    expect(allGatesGreen([])).toBe(false);
    expect(allGatesGreen(undefined)).toBe(false);
  });
});

describe('robustScoreContribs', () => {
  it('computes weight × z and sorts by descending magnitude with labels', () => {
    const card: Scorecard = {
      weights: { oos_return: 0.22, stability: 0.16, live_alignment: 0.08 },
      components: [
        { id: 'oos_return', z: 0.5 }, // 0.11
        { id: 'stability', z: -2.0 }, // -0.32 (dominant magnitude)
        { id: 'live_alignment', z: 1.0 }, // 0.08
      ],
    };
    const out = robustScoreContribs(card);
    expect(out.map((c) => c.id)).toEqual(['stability', 'oos_return', 'live_alignment']);
    expect(out[0]).toMatchObject({ label: 'Stability', weight: 0.16, z: -2, contribution: -0.32 });
    expect(out[1].contribution).toBeCloseTo(0.11, 6);
  });

  it('defaults a missing weight to 0 and returns [] for no components', () => {
    const out = robustScoreContribs({ components: [{ id: 'mystery', z: 3 }] });
    expect(out[0]).toMatchObject({ weight: 0, contribution: 0, label: 'mystery' });
    expect(robustScoreContribs(null)).toEqual([]);
    expect(robustScoreContribs({})).toEqual([]);
  });
});

const cand = (state: string, score?: number): Candidate => ({
  id: `c-${state}-${score ?? 'x'}`,
  generationId: 'g1',
  state,
  scorecard: score == null ? null : { robustScore: score },
});

describe('pipelineCounts', () => {
  it('tallies candidates into the 7 lifecycle stages + the two terminal branches', () => {
    const out = pipelineCounts([
      cand('SCORED'),
      cand('SCORED'),
      cand('SURVIVOR'),
      cand('PAPER'),
      cand('PROMOTED'),
      cand('RETIRED'),
      cand('RETIRED'),
      cand('ROLLED_BACK'),
    ]);
    const byStage = Object.fromEntries(out.stages.map((s) => [s.stage, s.count]));
    expect(byStage.SCORED).toBe(2);
    expect(byStage.SURVIVOR).toBe(1);
    expect(byStage.PROMOTED).toBe(1);
    expect(byStage.EVALUATING).toBe(0);
    expect(out.retired).toBe(2);
    expect(out.rolledBack).toBe(1);
    // RETIRED/ROLLED_BACK are terminal — never a pipeline stage column.
    expect(out.stages.some((s) => s.stage === ('RETIRED' as never))).toBe(false);
  });

  it('defaults a null state to PROPOSED', () => {
    const out = pipelineCounts([{ id: 'x', generationId: 'g', state: null }]);
    expect(out.stages.find((s) => s.stage === 'PROPOSED')?.count).toBe(1);
  });
});

describe('bestChallengerScore', () => {
  it('returns the max scored RobustScore, ignoring unscored candidates', () => {
    expect(bestChallengerScore([cand('SCORED', 0.3), cand('SURVIVOR', 1.42), cand('SCORED')])).toBe(1.42);
  });
  it('returns null when nothing is scored', () => {
    expect(bestChallengerScore([cand('PROPOSED'), cand('EVALUATING')])).toBeNull();
    expect(bestChallengerScore([])).toBeNull();
  });
});

describe('formatters', () => {
  it('fmtSigned adds an explicit + for positives, — for null', () => {
    expect(fmtSigned(0.19)).toBe('+0.19');
    expect(fmtSigned(-0.5)).toBe('-0.50');
    expect(fmtSigned(0)).toBe('0.00');
    expect(fmtSigned(null)).toBe('—');
    expect(fmtSigned(Number.NaN)).toBe('—');
  });

  it('fmtNum renders — for null/NaN', () => {
    expect(fmtNum(1.5)).toBe('1.5');
    expect(fmtNum(null)).toBe('—');
    expect(fmtNum(undefined)).toBe('—');
  });

  it('shortId keeps the trailing 8 chars of a uuid', () => {
    expect(shortId('a1b2c3d4-e5f6-7890-abcd-ef1234567890')).toBe('…34567890');
    expect(shortId('short')).toBe('short');
    expect(shortId(null)).toBe('—');
  });
});

describe('expiryCountdown', () => {
  const now = new Date('2026-07-12T10:00:00+05:30').getTime();
  it('renders hours then days, and "expired" for the past', () => {
    expect(expiryCountdown('2026-07-12T15:00:00+05:30', now)).toBe('expires in 5h');
    expect(expiryCountdown('2026-07-15T10:00:00+05:30', now)).toBe('expires in 3d');
    expect(expiryCountdown('2026-07-12T09:00:00+05:30', now)).toBe('expired');
  });
  it('returns null for an unstamped expiry', () => {
    expect(expiryCountdown(null, now)).toBeNull();
    expect(expiryCountdown(undefined, now)).toBeNull();
  });
});
