import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { settleAnimations } from './helpers';

// Pre-authenticated via the shared storageState (global-setup). Verifies the hybrid shell + the
// FilterBar control bar + the OI header on the Options Chain anchor page (§20.6 corrected mapping),
// at both viewports, with no axe violations.
test('Options Chain renders the control bar + header, no axe violations', async ({ page }) => {
  await page.goto('/options/options-chain');

  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Options', exact: true })).toBeVisible();
  // The theme picker hides below md (audit #456: the phone topbar keeps only MOCK/WS/Logout).
  if ((page.viewportSize()?.width ?? 1440) >= 768) {
    await expect(page.getByLabel('Theme', { exact: true })).toBeVisible();
  } else {
    await expect(page.getByLabel('Theme', { exact: true })).toBeAttached();
  }

  await expect(page.getByLabel('Underlying', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Interval', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Toggle live/history mode' })).toBeVisible();

  // Regression (the empty-options bug): underlying options must carry real values + default non-empty.
  const underlying = page.getByLabel('Underlying', { exact: true });
  const values = await underlying
    .locator('option')
    .evaluateAll((opts) => opts.map((o) => (o as HTMLOptionElement).value).filter(Boolean));
  // >1, not >5: the credential-free CI mock serves 2 underlyings (a data-rich local stack serves
  // the full roster) — the regression under test is EMPTY options, not roster size.
  expect(values.length).toBeGreaterThan(1);
  expect(await underlying.inputValue()).not.toBe('');

  // Cascade: selecting a different underlying reloads the expiry list (not disabled/empty).
  await underlying.selectOption(values[0]);
  await expect(page.getByLabel('Expiry', { exact: true })).toBeEnabled();

  // The header metric strip renders regardless of chain data ("—" until a snapshot accrues).
  // "OI bias" moved to the OI Spurt page — Total PCR is the chain strip's stable anchor.
  await expect(page.getByText('Total PCR')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Options chain' })).toBeAttached();

  await settleAnimations(page);

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});
