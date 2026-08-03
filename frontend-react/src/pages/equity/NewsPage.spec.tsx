import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { EquityNews } from '../../api/types.ts';

const data: EquityNews = {
  symbol: 'RELIANCE',
  available: true,
  items: [
    {
      heading: 'Reliance leads SENSEX rally',
      summary: 'RIL gained 2% on heavy volume.',
      thumbnail: null,
      articleLink: 'https://example.com/a',
      publishedTime: 1782124170137,
    },
  ],
};

vi.mock('../../api/oiAnalytics.ts', () => ({
  useEquityNews: () => ({ data, isFetching: false, refetch: () => {} }),
}));

import { NewsPage } from './NewsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <NewsPage />
    </QueryClientProvider>,
  );
}

describe('NewsPage', () => {
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 3341ms in a full-suite run.
  it('shows a prompt, then the news articles after Go', () => {
    renderPage();
    expect(screen.getByText(/Enter a stock symbol/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Stock symbol'), { target: { value: 'RELIANCE' } });
    fireEvent.click(screen.getByRole('button', { name: /go/i }));

    const link = screen.getByRole('link', { name: 'Reliance leads SENSEX rally' });
    expect(link).toHaveAttribute('href', 'https://example.com/a');
    expect(link).toHaveAttribute('target', '_blank');
    expect(screen.getByText('RIL gained 2% on heavy volume.')).toBeInTheDocument();
  }, 15_000);
});
