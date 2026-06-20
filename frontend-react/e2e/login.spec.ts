import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { loginThroughForm } from './helpers.ts';

test('login lands on the shell and has no axe violations', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);

  await loginThroughForm(page);
  // Index redirects to the OI Analysis anchor under the shell.
  await expect(page).toHaveURL(/\/options\/oi-analysis/);
});
