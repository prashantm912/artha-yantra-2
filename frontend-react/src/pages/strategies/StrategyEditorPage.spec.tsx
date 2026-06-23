import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const create = vi.fn();

vi.mock('../../api/strategies.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/strategies.ts')>();
  return {
    ...actual, // keep STARTER_TEMPLATE + VERSION_BUMPS
    useStrategyEditDetail: () => ({ data: undefined }),
    useStrategyUniverse: () => ({ data: null }),
    useValidateStrategy: () => ({ mutate: vi.fn(), data: { valid: true, errors: [], warnings: [] }, isPending: false }),
    useSaveStrategy: () => ({ mutate: vi.fn(), isPending: false }),
    useCreateStrategy: () => ({ mutate: create, isPending: false }),
  };
});

import { StrategyEditorPage } from './StrategyEditorPage.tsx';

function renderNew() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/strategies/new/edit']}>
        <Routes>
          <Route path="/strategies/:id/edit" element={<StrategyEditorPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('StrategyEditorPage (create mode)', () => {
  it('requires a name before create, then submits the draft', () => {
    renderNew();
    expect(screen.getByText('New strategy')).toBeInTheDocument();
    const yaml = screen.getByLabelText('Strategy YAML') as HTMLTextAreaElement;
    expect(yaml.value).toContain('strategy-schema/v1'); // seeded with the starter template

    const createBtn = screen.getByRole('button', { name: 'Create draft' });
    expect(createBtn).toBeDisabled(); // no name yet

    fireEvent.change(screen.getByLabelText('Strategy name'), { target: { value: 'My Strat' } });
    expect(createBtn).toBeEnabled();

    fireEvent.click(createBtn);
    expect(create).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'My Strat' }),
      expect.anything(),
    );
  });
});
