import { describe, expect, it } from 'vitest';
import {
  actionGate,
  formatAge,
  insightActions,
  insightBand,
  signalScopeId,
  type ActionSpec,
  type Insight,
} from './insights.ts';

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
    expect(insightBand(insight({ priority: '10', priorityDetail: { score: 82, band: 'A', trustCap: 1, components: [] } }))).toBe('A');
  });

  it('derives the band from the decimal-string priority via the §3.2 thresholds', () => {
    expect(insightBand(insight({ priority: '85' }))).toBe('A');
    expect(insightBand(insight({ priority: '60' }))).toBe('B');
    expect(insightBand(insight({ priority: '40' }))).toBe('C');
    expect(insightBand(insight({ priority: '39.9' }))).toBe('D');
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

describe('signalScopeId', () => {
  it('extracts the id from a signal: scope', () => {
    expect(signalScopeId(insight({ scope: 'signal:42' }))).toBe(42);
  });
  it('is null for a non-signal scope', () => {
    expect(signalScopeId(insight({ scope: 'dataops' }))).toBeNull();
    expect(signalScopeId(insight({ scope: 'strategy:abc' }))).toBeNull();
    expect(signalScopeId(insight({ scope: 'signal:nope' }))).toBeNull();
  });
});

describe('insightActions', () => {
  it('offers ticket + journal + mute for a signal-scoped insight', () => {
    const actions = insightActions(insight({ scope: 'signal:42', type: 'SIGNAL_PRIORITY' })).map((a) => a.action);
    expect(actions).toEqual(['OPEN_TICKET', 'JOURNAL_DRAFT_ACCEPT', 'MUTE_TYPE']);
  });
  it('offers sell-ack + mute for a SELL_DECISION insight', () => {
    const actions = insightActions(insight({ scope: 'strategy:x', type: 'SELL_DECISION' })).map((a) => a.action);
    expect(actions).toEqual(['ACK_SELL_DECISION', 'MUTE_TYPE']);
  });
  it('offers only mute for a non-signal, non-sell insight', () => {
    const actions = insightActions(insight({ scope: 'dataops', type: 'DATA_TRUST' })).map((a) => a.action);
    expect(actions).toEqual(['MUTE_TYPE']);
  });
});

describe('actionGate (§7.4 client mirror)', () => {
  const order: ActionSpec = { action: 'OPEN_TICKET', label: 'Take → ticket', order: true };
  const nonOrder: ActionSpec = { action: 'MUTE_TYPE', label: 'Mute type', order: false };

  it('BLOCKED refuses every action', () => {
    expect(actionGate(insight({ dataTrust: 'BLOCKED' }), order)).toMatch(/BLOCKED/);
    expect(actionGate(insight({ dataTrust: 'BLOCKED' }), nonOrder)).toMatch(/BLOCKED/);
  });
  it('DEGRADED refuses only the order prefills', () => {
    expect(actionGate(insight({ dataTrust: 'DEGRADED' }), order)).toMatch(/DEGRADED/);
    expect(actionGate(insight({ dataTrust: 'DEGRADED' }), nonOrder)).toBeNull();
  });
  it('OK enables every action', () => {
    expect(actionGate(insight({ dataTrust: 'OK' }), order)).toBeNull();
    expect(actionGate(insight({ dataTrust: 'OK' }), nonOrder)).toBeNull();
  });
});
