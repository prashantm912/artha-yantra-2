import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const publish = vi.fn();
const setEnabled = vi.fn();

vi.mock('../../api/strategies.ts', () => ({
  useStrategyDetail: () => ({
    data: { id: 's1', name: 'EMA Cross', status: 'draft', version: '1.3.0', enabled: true },
  }),
  useStrategyVersions: () => ({
    data: {
      items: [
        { version: '1.3.0', status: 'draft', checksum: 'aaaa111122', createdAt: '2026-06-23' },
        { version: '1.1.0', status: 'published', checksum: 'bbbb333344', createdAt: '2026-06-20' },
      ],
    },
  }),
  useStrategyAudit: () => ({
    data: {
      items: [
        {
          id: 3,
          action: 'ENABLE',
          fromVersion: null,
          toVersion: null,
          diffSummary: 'enabled — armed for the live signal engine',
          actor: 'owner',
          createdAt: '2026-06-24T10:15:00Z',
        },
        {
          id: 2,
          action: 'PUBLISH',
          fromVersion: '1.0.0',
          toVersion: '1.1.0',
          diffSummary: 'published 1.1.0',
          actor: 'owner',
          createdAt: '2026-06-20T09:00:00Z',
        },
      ],
    },
  }),
  useStrategyDiff: () => ({
    data: {
      structured: [{ path: 'risk.max_positions', op: 'change', before: '1', after: '2' }],
      yamlFrom: 'max_positions: 1',
      yamlTo: 'max_positions: 2',
    },
  }),
  useStressWindow: () => ({ data: { from: '2026-05-01', to: '2026-06-20' } }),
  usePublish: () => ({ mutate: publish, isPending: false }),
  useRollback: () => ({ mutate: vi.fn() }),
  useSetEnabled: () => ({ mutate: setEnabled, isPending: false }),
  useCloneStrategy: () => ({ mutate: vi.fn(), isPending: false }),
}));

import { StrategyVersionsPage } from './StrategyVersionsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/strategies/s1/versions']}>
        <Routes>
          <Route path="/strategies/:id/versions" element={<StrategyVersionsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// The version timeline renders through the shared DataTable, which paints a desktop <table> AND a
// md:hidden mobile card list — so every cell text exists twice in jsdom (CSS doesn't apply). Scope
// each assertion to the desktop table so getByText stays exactly-one.
const timeline = () => within(screen.getByRole('table', { name: 'Strategy versions' }));

describe('StrategyVersionsPage', () => {
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 4085ms in a full-suite run.
  it('shows the version timeline, the structured diff, and opens the publish dialog', () => {
    renderPage();
    expect(screen.getByText('EMA Cross — versions')).toBeInTheDocument();
    expect(screen.getAllByText('1.3.0').length).toBeGreaterThan(0); // timeline row + compare options
    expect(timeline().getByText('draft')).toBeInTheDocument();
    expect(screen.getByText(/risk.max_positions/)).toBeInTheDocument();

    fireEvent.click(screen.getByText('Publish…'));
    expect(screen.getByText('Publish version')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }));
    expect(publish).toHaveBeenCalled();
  }, 15_000);

  it('renders the lifecycle timeline and confirm-gates the enabled toggle and clone', () => {
    renderPage();
    // the audit timeline renders lifecycle rows (newest-first)
    expect(screen.getByText('Lifecycle history')).toBeInTheDocument();
    expect(screen.getByText('Published')).toBeInTheDocument();
    expect(screen.getByText(/armed for the live signal engine/)).toBeInTheDocument();

    // disabling opens a confirm dialog that spells out the live-evaluation consequence
    fireEvent.click(screen.getByRole('button', { name: 'Disable' }));
    expect(screen.getByRole('heading', { name: 'Disable strategy' })).toBeInTheDocument();
    expect(screen.getByText(/disarms live evaluation/)).toBeInTheDocument();

    // the clone action opens its own dialog
    fireEvent.click(screen.getByText('Clone…'));
    expect(screen.getByRole('heading', { name: 'Clone strategy' })).toBeInTheDocument();
  });
});
