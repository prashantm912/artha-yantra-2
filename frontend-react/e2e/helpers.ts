import { expect, type Page } from '@playwright/test';

// Mock owner password — override via E2E_OWNER_PASSWORD to match your .env hash.
export const OWNER_PASSWORD = process.env.E2E_OWNER_PASSWORD ?? 'e2e-owner-password';

/** Logs in through the form (keeps the input[name=password] + "Sign in" contract). */
export async function loginThroughForm(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('input[name="password"]').fill(OWNER_PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByTestId('app-shell')).toBeVisible();
}
