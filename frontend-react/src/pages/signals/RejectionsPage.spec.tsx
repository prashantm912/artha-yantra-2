import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { SignalRejectionDto } from '../../api/signalRejections.ts';

// A mutable holder for the main-list rows, so one file can cover both the empty (dot-health) case and a
// populated adopted-table case. Hoisted so the vi.mock factory may read it. Reset to empty per test.
const state = vi.hoisted(() => ({ items: [] as unknown[] }));

vi.mock('../../api/signalRejections.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/signalRejections.ts')>();
  return {
    ...actual, // keep the shared types
    useSignalRejections: () => ({
      isPending: false,
      isError: false,
      isSuccess: true,
      data: { items: state.items },
      refetch: () => {},
    }),
    useRejectionRailCounts: () => ({ data: { items: [] } }),
    useShadowSummary: () => ({ data: { items: [] } }),
    useDotHealth: () => ({
      isLoading: false,
      isError: false,
      data: {
        asOf: '2026-07-10T10:00:00+05:30',
        session: true,
        rowsInspected: 12,
        dots: [
          { dot: 'breadth', alive: true, required: true, detail: 'input live in the last 12 rejections' },
          { dot: 'iv_rank', alive: false, required: true, detail: 'input dead across 12 rejections' },
          { dot: 'dow', alive: false, required: false, detail: 'input dead across 12 rejections' },
        ],
      },
    }),
  };
});

import { RejectionsPage } from './RejectionsPage.tsx';

const MOCK_ROW: SignalRejectionDto = {
  id: 42,
  strategyVersionId: 'abc',
  strategySlug: 'nifty-scalp-v3',
  exchange: 'NFO',
  tradingsymbol: 'NIFTY26JUL24000CE',
  interval: '3m',
  side: 'CE',
  blockingRail: 'vwap_align',
  blockingOperand: 0.4213,
  blockingThreshold: 0.6,
  blockingMargin: -0.1787,
  blockingReason: 'operand below threshold',
  compositeScore: 0.552,
  compositeThreshold: 0.7,
  diagnostic: {
    blockingRail: 'vwap_align',
    side: 'CE',
    operand: 0.4213,
    threshold: 0.6,
    margin: -0.1787,
    reason: 'operand below threshold',
    compositeScore: 0.552,
    compositeThreshold: 0.7,
    checks: [{ rail: 'vwap_align', pass: false, operand: 0.4213, threshold: 0.6, margin: -0.1787, reason: 'below' }],
    confluence: null,
    context: null,
  },
  barTime: '2026-07-10T09:20:00+05:30',
  generatedAt: '2026-07-10T09:20:01+05:30',
};

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <RejectionsPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  state.items = [];
});

describe('RejectionsPage dot-health panel', () => {
  it('renders per-dot liveness with honest words + required markers', () => {
    renderPage();

    // Each dot renders its name and its verdict WORD (colour is never the only cue).
    expect(screen.getByText('breadth')).toBeInTheDocument();
    expect(screen.getAllByText('Live').length).toBeGreaterThan(0); // breadth alive
    expect(screen.getAllByText('Dead').length).toBeGreaterThan(0); // iv_rank + dow dead

    // The as-of / session / sample-size line.
    expect(screen.getByText(/12 rejections inspected/)).toBeInTheDocument();

    // Required dots carry a marker chip.
    expect(screen.getAllByText('required').length).toBeGreaterThan(0);
  });
});

describe('RejectionsPage main table (adopted onto DataTable)', () => {
  // Scope every assertion to the NAMED desktop table — DataTable double-paints (desktop <table> +
  // md:hidden card list), so a bare getByText would match twice.
  const table = () => screen.getByRole('table', { name: 'Signal rejections' });

  it('renders the row through DataTable with each data cell preserved and no role="button" on the <tr>', () => {
    state.items = [MOCK_ROW];
    renderPage();

    const t = table();
    // Data cells preserved (a representative slice — full 10-cell parity is proven separately).
    expect(within(t).getByText('nifty-scalp-v3')).toBeInTheDocument();
    expect(within(t).getByText('NIFTY26JUL24000CE')).toBeInTheDocument();
    expect(within(t).getByText('vwap_align')).toBeInTheDocument();
    expect(within(t).getByText('0.552 / 0.7')).toBeInTheDocument();
    expect(within(t).getByText('operand below threshold')).toBeInTheDocument();

    // The single data row keeps table `row` semantics — never a role="button" (audit M23 / #596).
    const dataRows = within(t).getAllByRole('row').slice(1); // drop the header row
    expect(dataRows).toHaveLength(1);
    expect(dataRows[0]).not.toHaveAttribute('role');
    expect(dataRows[0]).not.toHaveAttribute('tabindex');
  });

  it('exposes a keyboard-accessible expander <button aria-expanded> that reveals the RejectionDetail breakdown', () => {
    state.items = [MOCK_ROW];
    renderPage();

    const t = table();
    const expander = within(t).getByRole('button', { name: 'Expand details' });
    expect(expander.tagName).toBe('BUTTON'); // a real, focusable button (Enter/Space native) — the AT path
    expect(expander).toHaveAttribute('aria-expanded', 'false');
    expect(expander).toHaveAttribute('aria-controls');

    // The detail is not present until expanded.
    expect(within(t).queryByText('Rails evaluated')).toBeNull();

    fireEvent.click(expander);

    // The RejectionDetail (kept verbatim) now renders in the inline detail row.
    const collapse = within(t).getByRole('button', { name: 'Collapse details' });
    expect(collapse).toHaveAttribute('aria-expanded', 'true');
    expect(within(t).getByText('Rails evaluated')).toBeInTheDocument();
    expect(within(t).getByText('Context')).toBeInTheDocument();
  });
});
