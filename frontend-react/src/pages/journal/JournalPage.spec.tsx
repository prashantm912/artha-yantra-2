import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const create = vi.fn();
const remove = vi.fn();

vi.mock('../../api/journal.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/journal.ts')>();
  return {
    ...actual, // keep LINKED_OPTIONS + linkLabel
    useJournal: () => ({
      data: { items: [{ id: 1, note: 'Missed the breakout', tags: ['discipline'], disciplineRating: 4, emotionRating: 2, signalId: 7, createdAt: '2026-06-23T09:00:00', updatedAt: '2026-06-23T09:00:00' }] },
    }),
    useCreateJournal: () => ({ mutate: create, isPending: false }),
    useDeleteJournal: () => ({ mutate: remove, isPending: false }),
  };
});

import { JournalPage } from './JournalPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <JournalPage />
    </QueryClientProvider>,
  );
}

describe('JournalPage', () => {
  it('lists entries with the linked label, creates and deletes', () => {
    renderPage();
    expect(screen.getByText('Missed the breakout')).toBeInTheDocument();
    expect(screen.getByText('signal #7')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'New note' } });
    fireEvent.click(screen.getByRole('button', { name: /New entry/ }));
    expect(create).toHaveBeenCalledWith(expect.objectContaining({ note: 'New note' }), expect.anything());

    fireEvent.click(screen.getByText('Delete'));
    expect(remove).toHaveBeenCalledWith(1);
  });
});
