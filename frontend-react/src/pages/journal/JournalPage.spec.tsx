import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const create = vi.fn();
const update = vi.fn();
const remove = vi.fn();

vi.mock('../../api/journal.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/journal.ts')>();
  return {
    ...actual, // keep LINKED_OPTIONS + linkLabel
    useJournal: () => ({
      data: { items: [{ id: 1, note: 'Missed the breakout', tags: ['discipline'], disciplineRating: 4, emotionRating: 2, signalId: 7, createdAt: '2026-06-23T09:00:00', updatedAt: '2026-06-23T09:00:00' }] },
    }),
    useCreateJournal: () => ({ mutate: create, isPending: false }),
    useUpdateJournal: () => ({ mutate: update, isPending: false }),
    useDeleteJournal: () => ({ mutate: remove, isPending: false }),
  };
});

// The link pickers fetch recent items lazily; stub the sources so choosing a link type doesn't fetch.
vi.mock('../../api/signals.ts', () => ({ useSignals: () => ({ data: { items: [] }, isLoading: false }) }));

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

    // Delete now confirms first (§10.2-2): cancelled -> no mutate; accepted -> mutate.
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValueOnce(false);
    fireEvent.click(screen.getByText('Delete'));
    expect(remove).not.toHaveBeenCalled();
    confirmSpy.mockReturnValueOnce(true);
    fireEvent.click(screen.getByText('Delete'));
    expect(remove).toHaveBeenCalledWith(1);
    confirmSpy.mockRestore();
  });

  it('edits an entry inline via the PUT path (note/tags/ratings mutable, links immutable)', () => {
    renderPage();

    // Enter edit mode: the note cell becomes an input seeded from the row.
    fireEvent.click(screen.getByText('Edit'));
    const noteInput = screen.getByLabelText('Edit note') as HTMLInputElement;
    expect(noteInput.value).toBe('Missed the breakout');

    fireEvent.change(noteInput, { target: { value: 'Revised: took the retest' } });
    fireEvent.change(screen.getByLabelText('Edit discipline rating'), { target: { value: '5' } });
    fireEvent.click(screen.getByText('Save'));

    expect(update).toHaveBeenCalledWith(
      { id: 1, body: expect.objectContaining({ note: 'Revised: took the retest', disciplineRating: 5 }) },
      expect.anything(),
    );
  });

  it('reveals a link picker when a link type is chosen on the new-entry form', () => {
    renderPage();
    // No picker until a link type is chosen (free entry is the default).
    expect(screen.queryByLabelText('Signal to link')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Link entry to'), { target: { value: 'signal' } });
    expect(screen.getByLabelText('Signal to link')).toBeInTheDocument();
  });
});
