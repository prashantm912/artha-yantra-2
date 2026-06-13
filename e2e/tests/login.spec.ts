import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { OWNER_PASSWORD, loginThroughForm, resetLoginLimiter } from './helpers';

test.describe('login journey (C-2.20)', () => {
  test.beforeEach(resetLoginLimiter); // fresh A.2.6 window per journey
  test('deep links redirect anonymous users to /login and back after sign-in', async ({ page }) => {
    await page.goto('/signals');
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fsignals/);

    await page.locator('input[name="password"]').fill(OWNER_PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/signals/);
    await expect(page.locator('ay-shell')).toBeVisible();
  });

  test('session cookie carries the A.2.3 flags (HttpOnly, SameSite=Strict)', async ({
    page,
    context,
  }) => {
    await loginThroughForm(page);
    const session = (await context.cookies()).find((cookie) => cookie.name === 'SESSION');
    expect(session, 'SESSION cookie must exist').toBeTruthy();
    expect(session!.httpOnly).toBe(true);
    expect(session!.sameSite).toBe('Strict');
  });

  test('wrong password surfaces the envelope error, right one logs in, logout returns', async ({
    page,
  }) => {
    await page.goto('/login');
    await page.locator('input[name="password"]').fill('definitely-wrong');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.locator('p-message')).toBeVisible();

    await page.locator('input[name="password"]').fill(OWNER_PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.locator('ay-shell')).toBeVisible();

    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login/);
  });

  test('axe: the login page has no detectable violations', async ({ page }) => {
    await page.goto('/login');
    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});
