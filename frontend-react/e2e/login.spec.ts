import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { settleAnimations } from './helpers';

// Anonymous (override the shared authed storageState). The login ACTION is asserted in global-setup
// (one login total); here we verify the login page's form + a11y at both viewports.
test.use({ storageState: { cookies: [], origins: [] } });

test('login page renders the form with no axe violations', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  await expect(page.locator('input[name="password"]')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();

  await settleAnimations(page);

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});
