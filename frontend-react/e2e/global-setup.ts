import { chromium, expect, type FullConfig } from '@playwright/test';
import { OWNER_PASSWORD } from './helpers.ts';

// Logs in ONCE through the form and saves the session (storageState) for every spec to reuse — so the
// whole suite costs a single login (robust against the gateway's login rate-limiter). This also IS the
// login-works assertion: app-shell must appear after submit.
export default async function globalSetup(config: FullConfig) {
  const project = config.projects[0];
  const baseURL = project.use.baseURL as string;
  const statePath = project.use.storageState as string;

  const browser = await chromium.launch();
  const page = await browser.newPage({ baseURL });
  await page.goto('/login');
  await page.locator('input[name="password"]').fill(OWNER_PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 15_000 });
  await page.context().storageState({ path: statePath });
  await browser.close();
}
