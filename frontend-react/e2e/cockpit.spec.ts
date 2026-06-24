import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

// Pre-authenticated via the shared storageState (global-setup). Verifies the unified Scalping Cockpit
// (/cockpit) — one operator screen composing the option chain · OI-confluence matrix · straddle ·
// live signals · OI heatmap, all driven by ONE shared FilterBar — renders the control bar + every
// panel heading at both viewports, axe-clean. The panel BODIES need captured chain snapshots (mock has
// none), so we assert the page chrome + the panel headings + the empty-state copy, not the chart canvas.

const PANELS = [
  'Option chain',
  'OI confluence matrix',
  'Straddle premium',
  'Live signals',
  'OI change heatmap',
];

test('Scalping Cockpit renders the shared control bar + every panel, no axe violations', async ({
  page,
}) => {
  await page.goto('/cockpit');

  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Scalping cockpit' })).toBeAttached();

  // The SINGLE shared FilterBar drives all panels (name/expiry/interval/mode).
  await expect(page.getByLabel('Underlying')).toBeVisible();
  await expect(page.getByLabel('Expiry')).toBeVisible();
  await expect(page.getByLabel('Interval')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Toggle live/history mode' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Go' })).toBeVisible();

  // Every composed panel heading is present.
  for (const name of PANELS) {
    await expect(page.getByRole('heading', { name })).toBeVisible();
  }

  // The header sentiment strip is present (— until a live matrix accrues).
  await expect(page.getByText('Sentiment', { exact: false })).toBeVisible();

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test.describe('mobile (~480px)', () => {
  test.use({ viewport: { width: 480, height: 1010 } });
  test('Scalping Cockpit collapses to one column on a narrow viewport, axe-clean', async ({
    page,
  }) => {
    await page.goto('/cockpit');
    await expect(page.getByTestId('app-shell')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Scalping cockpit' })).toBeAttached();
    await expect(page.getByLabel('Underlying')).toBeVisible();

    // Panels stay reachable (single scrollable column).
    for (const name of PANELS) {
      await expect(page.getByRole('heading', { name })).toBeVisible();
    }

    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});
