import { execSync } from 'node:child_process';
import { expect, test } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers';

test.describe('WS reconnect chaos (C-2.20)', () => {
  test.beforeEach(resetLoginLimiter); // fresh A.2.6 window per journey
  test('restarting the gateway mid-session recovers without a reload', async ({ page }) => {
    await loginThroughForm(page);
    await page.goto('/signals');
    await expect(page.getByText('WS connected')).toBeVisible({ timeout: 30_000 });

    // chaos: bounce the sole ingress
    execSync('docker restart ay-edge-gateway', { stdio: 'inherit' });

    // the client notices the dead socket...
    await expect(page.getByText(/WS (reconnecting|connecting)/)).toBeVisible({
      timeout: 60_000,
    });
    // ...and heals with backoff once the gateway is back - same tab, no reload
    await expect(page.getByText('WS connected')).toBeVisible({ timeout: 120_000 });

    // the store re-fetched its snapshot (at-least-once display): the table still renders
    await expect(page.locator('p-table')).toBeVisible();
  });
});
