import { test, expect } from '@playwright/test';
import { apiLogin, loginThroughForm, publishE2eStrategy, resetLoginLimiter } from './helpers.ts';

// React port of the parked Angular `notifier` journey. INTENT: an owner can opt a strategy into
// signal notifications and pick a delivery channel. The Angular original drove the (now-removed)
// PATCH /notifications API + a test-send button; the React list page (pages/strategies/
// StrategiesListPage.tsx) exposes this purely as a row control — a "Notifications for <name>"
// checkbox that, once enabled, reveals a "Channel for <name>" channel <select>. There is NO
// separate test-send button in the React UI, so we don't assert one.
//
// Flow: publish the deterministic e2e strategy via the API (quick + idempotent), then drive the
// real list page — locate the e2e row by its notifications checkbox, toggle it on, and assert the
// channel select appears with the expected options. A pageerror collector guards the render.

const STRATEGY_NAME = 'E2E Live Momentum';

test.beforeAll(() => resetLoginLimiter());

test('opting a strategy into notifications reveals its delivery-channel select', async ({
  page,
  request,
}) => {
  // fixture: ensure the e2e strategy exists + is published (idempotent across the persistent mock DB)
  await apiLogin(request);
  await publishE2eStrategy(request);

  const pageErrors: string[] = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));

  await loginThroughForm(page);

  // navigate to the strategies list and confirm it rendered
  await page.goto('/strategies');
  await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 20_000 });
  // the search box is the page's stable anchor control (h1 is sr-only; no visible section heading)
  await expect(page.getByLabel('Search strategies')).toBeVisible({ timeout: 20_000 });

  // narrow the list to the e2e strategy so its row is the only candidate (debounced 300ms search)
  await page.getByLabel('Search strategies').fill('e2e-live-momentum');

  // the row's notification toggle — aria-label is `Notifications for <strategy name>`
  const notifyToggle = page.getByLabel(`Notifications for ${STRATEGY_NAME}`);
  await expect(notifyToggle).toBeVisible({ timeout: 20_000 });

  // the channel select is gated on the toggle being on; reset to a known-off state if it persisted.
  // NOTE: it is a CONTROLLED React checkbox — its `checked` only flips after the PATCH round-trips +
  // the list refetches, so `.check()/.uncheck()` (which assert a synchronous state change) flap. Drive
  // it with `.click()` and use the channel select's visibility as the real success signal.
  const channelSelect = page.getByLabel(`Channel for ${STRATEGY_NAME}`);
  if (await notifyToggle.isChecked()) {
    await notifyToggle.click();
    await expect(channelSelect).toBeHidden({ timeout: 20_000 });
  }
  await expect(channelSelect).toBeHidden();

  // opt in → the delivery-channel select appears (after the PATCH + refetch)
  await notifyToggle.click();
  await expect(channelSelect).toBeVisible({ timeout: 20_000 });

  // and it offers the wired channels (NOTIFY_CHANNELS = NTFY / TELEGRAM)
  await expect(channelSelect.locator('option', { hasText: 'NTFY' })).toHaveCount(1);
  await expect(channelSelect.locator('option', { hasText: 'TELEGRAM' })).toHaveCount(1);

  // switching the channel is a no-throw mutation (PATCH /notifications under the hood)
  await channelSelect.selectOption('TELEGRAM');
  await expect(channelSelect).toHaveValue('TELEGRAM', { timeout: 20_000 });

  expect(pageErrors, `page errors: ${pageErrors.join(' | ')}`).toEqual([]);
});
