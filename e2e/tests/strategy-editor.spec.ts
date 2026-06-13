import { expect, test, type APIRequestContext } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { apiLogin, loginThroughForm, resetLoginLimiter } from './helpers';

/** Read the double-submit XSRF cookie so raw mutating calls pass the gateway CSRF filter. */
async function csrf(request: APIRequestContext): Promise<Record<string, string>> {
  await request.get('/api/v1/auth/session');
  const { cookies } = await request.storageState();
  const token = cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  return token ? { 'X-XSRF-TOKEN': token } : {};
}

/** Delete the template strategy if a prior run left it, so "Create draft" always creates fresh. */
async function deleteIfExists(request: APIRequestContext, slug: string): Promise<void> {
  const list = await request.get(`/api/v1/strategies?q=${slug}`);
  const items =
    ((await list.json().catch(() => ({}))) as { items?: { id: string; slug: string }[] }).items ??
    [];
  const match = items.find((s) => s.slug === slug);
  if (match) {
    await request.delete(`/api/v1/strategies/${match.id}`, { headers: await csrf(request) });
  }
}

test.describe('strategy editor — author → validate → save → quick backtest (Phase 36)', () => {
  test.beforeEach(resetLoginLimiter);

  test('create from template → server validation passes → save draft', async ({ page, request }) => {
    await apiLogin(request);
    await deleteIfExists(request, 'my-strategy'); // idempotent across runs

    await loginThroughForm(page);
    await page.goto('/strategies');

    await page.getByRole('button', { name: 'Create strategy' }).click();
    await expect(page).toHaveURL(/\/strategies\/new\/edit/);
    await expect(page.locator('ay-monaco-yaml-editor')).toBeVisible();

    // debounced POST /validate against the template → the validation panel resolves to "passed"
    await expect(page.locator('.validation')).toContainText('validation passed', { timeout: 15_000 });

    // save the draft → a new strategy id, version tag appears
    await page.getByRole('button', { name: 'Create draft' }).click();
    await expect(page).toHaveURL(/\/strategies\/[0-9a-f-]{8,}\/edit/, { timeout: 15_000 });
    await expect(page.locator('.toolbar')).toContainText('v', { timeout: 15_000 });

    // the quick-backtest drawer opens (a full windowed run needs benchmark history — see the guide)
    await page.getByRole('button', { name: 'Quick backtest' }).click();
    await expect(page.getByText('Run quick backtest')).toBeVisible();
  });

  test('axe: the strategy list page has no detectable violations', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/strategies');
    await expect(page.locator('p-table')).toBeVisible();
    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});
