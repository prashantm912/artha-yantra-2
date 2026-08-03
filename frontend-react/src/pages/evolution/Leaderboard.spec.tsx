import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import type { Candidate, Generation } from '../../api/evolution.ts';
import { Leaderboard } from './Leaderboard.tsx';

// The leaderboard grid renders through the shared DataTable (desktop <table> + md:hidden card list),
// so every cell text exists twice in jsdom — scope every table assertion to the named desktop table.
// These specs pin (a) header + row content is preserved cell-for-cell through the DataTable adoption,
// and (b) the new onRowClick makes each row a keyboard-accessible button that calls onOpen.

const GENERATIONS = [{ id: 'g1', campaignId: 'c', n: 1, stressTouches: 0 }] as Generation[];

const CANDIDATES = [
  {
    id: 'c1',
    generationId: 'g1',
    versionId: 'v-champ',
    state: 'SURVIVOR',
    scorecard: {
      robustScore: 1.25,
      rank: 1,
      gates: [
        { id: 'g', status: 'PASS' },
        { id: 'h', status: 'PASS' },
      ],
      comparator: { delta: 0.5 },
      flags: [],
    },
  },
  {
    id: 'c2',
    generationId: 'g1',
    versionId: 'v2',
    state: 'SCORED',
    scorecard: {
      robustScore: 0.9,
      rank: 2,
      gates: [{ id: 'g', status: 'FAIL' }],
      comparator: { delta: -0.3 },
      flags: ['flagA'],
    },
  },
] as Candidate[];

function renderBoard(onOpen = vi.fn()) {
  render(
    <Leaderboard
      candidates={CANDIDATES}
      generations={GENERATIONS}
      championVersionId="v-champ"
      onOpen={onOpen}
    />,
  );
  return onOpen;
}

const grid = () => within(screen.getByRole('table', { name: 'Candidates leaderboard' }));

describe('Leaderboard grid', () => {
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 3190ms in a full-suite run.
  it('renders the columns in their declared order', () => {
    renderBoard();
    expect(grid().getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Rank',
      'RobustScore',
      'vs champ',
      'Gates',
      'State',
      'Gen',
      'Flags',
    ]);
  }, 15_000);

  it('renders each candidate row cell-for-cell, score-sorted (content preserved)', () => {
    renderBoard();
    // Desktop rows keep their table `row` semantics (audit M23) → [0] is the header row.
    const rows = grid().getAllByRole('row').slice(1);
    // Default sort is RobustScore desc → c1 (1.25) before c2 (0.9).
    expect(within(rows[0]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      '#1',
      '1.25champion', // the in-cell score button + champion chip ride in the RobustScore cell
      '+0.50',
      '2/2',
      'SURVIVOR',
      'Gen 1',
      '', // no flags
    ]);
    expect(within(rows[1]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      '#2',
      '0.9',
      '-0.30',
      '0/1',
      'SCORED',
      'Gen 1',
      '1', // one flag
    ]);
  });

  it('opens the scorecard via the in-cell RobustScore button (M23 keyboard/AT activator) and via the desktop row mouse-click', () => {
    const onOpen = renderBoard();
    const g = grid();
    // Desktop rows are a mouse-only convenience — they keep `row` semantics, never role="button".
    const rows = g.getAllByRole('row').slice(1);
    expect(rows[0]).not.toHaveAttribute('role');
    expect(rows[0]).not.toHaveAttribute('tabindex');

    // The keyboard/AT activator is the real in-cell button (focusable, named); clicking it opens c1.
    const activator = g.getByRole('button', { name: /Open scorecard for candidate c1/ });
    fireEvent.click(activator);
    expect(onOpen).toHaveBeenNthCalledWith(1, CANDIDATES[0]);

    // The desktop row itself still opens on a mouse click (convenience).
    fireEvent.click(rows[1]);
    expect(onOpen).toHaveBeenNthCalledWith(2, CANDIDATES[1]);
  });
});
