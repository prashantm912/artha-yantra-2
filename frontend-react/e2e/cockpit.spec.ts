import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { settleAnimations } from './helpers';

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
  'Paper book',
  'OI change heatmap',
];

test('Scalping Cockpit renders the shared control bar + every panel, no axe violations', async ({
  page,
}) => {
  await page.goto('/cockpit');

  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Scalping cockpit' })).toBeAttached();

  // The SINGLE shared FilterBar drives all panels (name/expiry/interval/mode).
  await expect(page.getByLabel('Underlying', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Expiry', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Interval', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Toggle live/history mode' })).toBeVisible();
  // .first(): a composed panel may carry its own Go beside the shared FilterBar's.
  await expect(page.getByRole('button', { name: 'Go' }).first()).toBeVisible();

  // Every composed panel heading is present.
  for (const name of PANELS) {
    await expect(page.getByRole('heading', { name })).toBeVisible();
  }

  // The header sentiment strip is present (— until a live matrix accrues).
  await expect(page.getByText('Sentiment', { exact: false })).toBeVisible();

  // The paper-trading console: the risk-limit guard + the book renders. Data-tolerant: the shared
  // CI stack may carry open paper positions left by the backend e2e suite that runs first, so
  // assert the "Open positions" section exists (present in BOTH the empty and populated states) —
  // demanding the empty-state copy flaked the first run where a prior spec left a position open.
  await expect(page.getByLabel('Risk limits', { exact: true })).toBeVisible();
  await expect(page.getByText('Kill switch:', { exact: false })).toBeVisible();
  await expect(page.getByRole('heading', { name: /^Open positions/ })).toBeVisible();

  await settleAnimations(page);

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
    await expect(page.getByLabel('Underlying', { exact: true })).toBeVisible();

    // Panels stay reachable (single scrollable column).
    for (const name of PANELS) {
      await expect(page.getByRole('heading', { name })).toBeVisible();
    }

    await settleAnimations(page);

    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations).toEqual([]);
  });
});
