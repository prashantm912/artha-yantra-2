import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { settleAnimations } from './helpers';

// Scalper manual-verify checklist (Phase 4b, owner-locked soft-gate). On the mock stack global-setup
// publishes E2E_STRATEGY_YAML, so an ACTIVE signal feed exists on /scalper. The checklist only mounts
// for a signal that carries a scalperDetail; the mock momentum fixture may emit non-scalper ENTRY
// signals (no detail), so this is data-dependent: we walk the feed for a signal whose checklist
// renders and exercise the soft-gate (tick the checks → the Place button un-disables, local state
// only — no POST). If no scalper signal is present, we fall back to asserting the page renders +
// axe-clean (the task forbids hard-failing on absent data). Pre-authenticated via storageState.

const CHECKLIST = 'Manual verification checklist'; // section aria-label
const PLACE = /^Place paper (BUY|SELL)$/; // the cockpit ticket's place button

/**
 * Click each ACTIVE signal in the feed until one renders the ManualVerifyChecklist. Returns true when
 * a scalper signal was found + selected (the checklist is visible), false when the feed has none.
 */
/** Feed rows only — the ticket's "Place paper BUY/SELL" also ends in BUY/SELL, so exclude it. */
function signalFeedButtons(page: Page) {
  return page.getByRole('button', { name: /(BUY|SELL)$/ }).filter({ hasNotText: 'Place paper' });
}

async function selectSignalWithChecklist(page: Page): Promise<boolean> {
  const feedButtons = signalFeedButtons(page);
  const count = await feedButtons.count();
  for (let i = 0; i < count; i++) {
    await feedButtons.nth(i).click();
    const checklist = page.getByRole('region', { name: CHECKLIST });
    if (await checklist.isVisible().catch(() => false)) {
      return true;
    }
    // Give the hydrating GET /signals/{id} a beat before declaring this row checklist-less.
    if (await checklist.waitFor({ state: 'visible', timeout: 1_500 }).then(() => true, () => false)) {
      return true;
    }
  }
  return false;
}

test('the scalper cockpit gates Place behind the manual-verify checklist (or renders cleanly absent one)', async ({
  page,
}) => {
  await page.goto('/scalper');
  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Paper order ticket' })).toBeVisible();

  // Wait for the live feed to settle: either signal rows appear or the empty-state copy renders.
  // .first() on the whole or-chain — both sides can be present at once (strict-mode safe).
  await expect(
    signalFeedButtons(page)
      .first()
      .or(page.getByText('No live signals — publish a strategy.'))
      .first(),
  ).toBeVisible({ timeout: 20_000 });

  const found = await selectSignalWithChecklist(page);

  if (found) {
    const checklist = page.getByRole('region', { name: CHECKLIST });
    await expect(checklist).toBeVisible();
    // The confluence dots + the soft-warning render in the not-yet-confirmed state.
    await expect(checklist.getByRole('alert')).toContainText(/unconfirmed/);

    const place = page.getByRole('button', { name: PLACE });
    await expect(place).toBeDisabled(); // gated until the owner confirms

    // Confirm via the explicit override (one checkbox — robust regardless of how many checks exist).
    await checklist.getByRole('checkbox', { name: 'Confirm despite unchecked' }).check();
    await expect(checklist.getByRole('alert')).toHaveCount(0); // soft-warning clears
    await expect(place).toBeEnabled(); // un-disables (local state only — no order POSTed)
  } else {
    // No scalper signal in the mock feed — assert the cockpit rendered and is a11y-clean.
    await expect(page.getByRole('heading', { name: 'Live signals' })).toBeVisible();
  }

  await settleAnimations(page);

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test.describe('mobile (~480px)', () => {
  test.use({ viewport: { width: 480, height: 1010 } });

  test('the scalper cockpit stacks and still renders the ticket', async ({ page }) => {
    await page.goto('/scalper');
    await expect(page.getByTestId('app-shell')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Paper order ticket' })).toBeVisible();
    await expect(page.getByLabel('Instrument', { exact: true })).toBeVisible();
  });
});
