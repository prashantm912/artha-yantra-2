import { describe, expect, it } from 'vitest';
import { formatAge, insightBand, type Insight } from './insights.ts';

function insight(partial: Partial<Insight>): Insight {
  return {
    id: 'i1',
    generatedAt: '2026-07-11T09:47:12+05:30',
    type: 'SIGNAL_PRIORITY',
    severity: 'WARN',
    scope: 'signal:1',
    title: 't',
    explanation: 'e',
    dataTrust: 'OK',
    suppressed: false,
    status: 'OPEN',
    ...partial,
  };
}

describe('insightBand', () => {
  it('prefers the explain-contract band when present', () => {
    expect(insightBand(insight({ priority: 10, priorityDetail: { score: 82, band: 'A', trustCap: 1, components: [] } }))).toBe('A');
  });

  it('derives the band from the numeric priority via the §3.2 thresholds', () => {
    expect(insightBand(insight({ priority: 85 }))).toBe('A');
    expect(insightBand(insight({ priority: 60 }))).toBe('B');
    expect(insightBand(insight({ priority: 40 }))).toBe('C');
    expect(insightBand(insight({ priority: 39.9 }))).toBe('D');
  });

  it('returns null for an unscored (BLOCKED) insight', () => {
    expect(insightBand(insight({ priority: null, priorityDetail: null }))).toBeNull();
    expect(insightBand(insight({ priority: undefined }))).toBeNull();
  });
});

describe('formatAge', () => {
  const now = new Date('2026-07-11T10:00:00+05:30').getTime();

  it('renders compact seconds/minutes/hours/days', () => {
    expect(formatAge('2026-07-11T09:59:48+05:30', now)).toBe('12s');
    expect(formatAge('2026-07-11T09:57:00+05:30', now)).toBe('3m');
    expect(formatAge('2026-07-11T08:00:00+05:30', now)).toBe('2h');
    expect(formatAge('2026-07-07T10:00:00+05:30', now)).toBe('4d');
  });

  it('reads a future timestamp as "now" rather than a negative age', () => {
    expect(formatAge('2026-07-11T10:00:05+05:30', now)).toBe('now');
  });
});
