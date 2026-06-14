import { describe, expect, it } from 'vitest';
import {
  paperTradeMarks,
  signalMarks,
  tradeMarks,
  type PaperTradeLike,
  type SignalLike,
  type TradeLike,
} from './marks';

const ms = (iso: string): number => Date.parse(iso);

describe('chart marks mapping (E-10.3)', () => {
  const trades: TradeLike[] = [
    {
      seq: 1,
      side: 'LONG',
      entryTs: '2026-02-03T09:15:30+05:30',
      entryPrice: '100.00',
      exitTs: '2026-02-03T09:20:10+05:30',
      exitPrice: '103.50',
      pnl: '3.50',
    },
  ];

  it('maps a trade to entry + exit marks, IST bucket-floored', () => {
    const marks = tradeMarks(
      trades,
      '1m',
      ms('2026-02-03T09:00:00+05:30'),
      ms('2026-02-03T10:00:00+05:30'),
    );
    expect(marks.map((m) => m.kind)).toEqual(['entry', 'exit']);
    expect(marks[0].timeMs).toBe(ms('2026-02-03T09:15:00+05:30')); // floored to the minute
    expect(marks[1].price).toBe('103.50');
  });

  it('drops marks outside the requested window', () => {
    const marks = tradeMarks(
      trades,
      '1m',
      ms('2026-02-03T09:18:00+05:30'),
      ms('2026-02-03T10:00:00+05:30'),
    );
    expect(marks.map((m) => m.id)).toEqual(['t1-exit']); // entry at 09:15 is before the window
  });

  it('maps a closed paper trade to entry + exit marks (FP-67)', () => {
    const paper: PaperTradeLike[] = [
      {
        id: 9,
        side: 'BUY',
        avgEntryPrice: '120.00',
        realizedPnl: '900.00',
        openedAt: '2026-02-03T09:16:00+05:30',
        closedAt: '2026-02-03T09:30:00+05:30',
      },
    ];
    const marks = paperTradeMarks(
      paper,
      '1m',
      ms('2026-02-03T09:00:00+05:30'),
      ms('2026-02-03T10:00:00+05:30'),
    );
    expect(marks.map((m) => m.id)).toEqual(['p9-entry', 'p9-exit']);
    expect(marks[1].label).toContain('P&L 900.00');
  });

  const signals: SignalLike[] = [
    {
      id: 7,
      exchange: 'NSE',
      tradingsymbol: 'RELIANCE',
      side: 'BUY',
      entryPrice: '2950.00',
      stopLoss: '2900.00',
      target: '3050.00',
      generatedAt: '2026-02-03T09:16:00+05:30',
    },
    {
      id: 8,
      exchange: 'NSE',
      tradingsymbol: 'INFY',
      side: 'BUY',
      entryPrice: '1500.00',
      stopLoss: null,
      target: null,
      generatedAt: '2026-02-03T09:17:00+05:30',
    },
  ];

  it('emits a signal entry mark only for the chart symbol', () => {
    const marks = signalMarks(signals, 'NSE:RELIANCE', '1m');
    expect(marks.map((m) => m.id)).toEqual(['s7-entry']);
  });

  it('adds SL + target marks only in single-signal focus mode', () => {
    const noFocus = signalMarks(signals, 'NSE:RELIANCE', '1m');
    expect(noFocus.some((m) => m.kind === 'sl')).toBe(false);
    const focused = signalMarks(signals, 'NSE:RELIANCE', '1m', 7);
    expect(focused.map((m) => m.kind).sort()).toEqual(['entry', 'sl', 'target']);
  });
});
