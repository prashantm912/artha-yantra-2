import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const publish = vi.fn();

vi.mock('../../api/strategies.ts', () => ({
  useStrategyDetail: () => ({ data: { id: 's1', name: 'EMA Cross', status: 'draft', version: '1.3.0' } }),
  useStrategyVersions: () => ({
    data: {
      items: [
        { version: '1.3.0', status: 'draft', checksum: 'aaaa111122', createdAt: '2026-06-23' },
        { version: '1.1.0', status: 'published', checksum: 'bbbb333344', createdAt: '2026-06-20' },
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

describe('StrategyVersionsPage', () => {
  it('shows the version timeline, the structured diff, and opens the publish dialog', () => {
    renderPage();
    expect(screen.getByText('EMA Cross — versions')).toBeInTheDocument();
    expect(screen.getAllByText('1.3.0').length).toBeGreaterThan(0); // timeline row + compare options
    expect(screen.getByText('draft')).toBeInTheDocument();
    expect(screen.getByText(/risk.max_positions/)).toBeInTheDocument();

    fireEvent.click(screen.getByText('Publish…'));
    expect(screen.getByText('Publish version')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }));
    expect(publish).toHaveBeenCalled();
  });
});
