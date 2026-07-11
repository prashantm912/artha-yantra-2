import { test, expect } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers.ts';

// Audit M28: the /paper page (frontend-react/src/pages/paper/PaperPage.tsx) had NO e2e, and its BOOK
// dimension was unasserted. Paper is split into three per-family books (scalper / minervini /
// manas-arora); the `/paper/:book` route param scopes EVERY read on the page — usePaperPositions(book),
// usePaperAccount(book), usePaperTrades(book), usePaperPnl(book), useRiskSettings(book) all carry it,
// and the reset mutation is book-scoped. This journey proves that switching the book route re-scopes
// the page: the page identity (title + subtitle + the reset control) names the selected book, which is
// the deterministic surface of the same `book` variable that filters the positions/account queries.
// Data-tolerant (the mock stack seeds no per-book positions deterministically): assert the always-
// rendered book-scoped chrome + the Open-positions section, not specific rows. Render-only, no mutation.

test.beforeAll(() => resetLoginLimiter());

// The default /paper (no :book) pins the scalper book; the other two are reached by the route param.
const BOOKS = [
  { path: '/paper', label: 'Scalper' },
  { path: '/paper/minervini', label: 'Minervini' },
  { path: '/paper/manas-arora', label: 'Manas Arora' },
];

test('the paper page re-scopes its identity + controls per book across the /paper/:book routes', async ({
  page,
}) => {
  const pageErrors: Error[] = [];
  page.on('pageerror', (err) => pageErrors.push(err));

  await loginThroughForm(page);

  for (const { path, label } of BOOKS) {
    await page.goto(path);
    await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 20_000 });

    // The single <h1> names the selected book (PaperPage is lazy — generous wait for the chunk).
    await expect(
      page.getByRole('heading', { level: 1, name: `${label} paper trading` }),
    ).toBeVisible({ timeout: 20_000 });

    // The reset control is book-scoped ("🗑 Reset <label> ledger") — the book var also keys every
    // positions/account/risk query, so a per-book control here proves the read scope switched.
    await expect(
      page.getByRole('button', { name: `Reset ${label} ledger` }),
    ).toBeVisible({ timeout: 20_000 });

    // The Open-positions section mounted (present in both the empty and populated states).
    await expect(page.getByRole('heading', { name: 'Open positions' })).toBeVisible({
      timeout: 20_000,
    });
  }

  // Cross-book distinctness: the scalper title must NOT linger after routing to the minervini book
  // (the queryKey + ?book= change → a real re-scope, not a stale cache render).
  await expect(
    page.getByRole('heading', { level: 1, name: 'Scalper paper trading' }),
  ).toHaveCount(0);

  expect(pageErrors, `page errors: ${pageErrors.map((e) => e.message).join(' | ')}`).toEqual([]);
});
