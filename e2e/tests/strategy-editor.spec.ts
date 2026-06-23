import { test, expect } from '@playwright/test';
import { E2E_STRATEGY_YAML, loginThroughForm, resetLoginLimiter } from './helpers.ts';

// Journey (ported from the parked Angular strategy-editor spec): author a draft in the React
// StrategyEditorPage (pages/strategies/StrategyEditorPage.tsx) at /strategies/new/edit — fill the
// Name field + valid YAML → the debounced server validation (POST /validate, 500ms debounce) flips
// the badge to "valid" → "Create draft" → the URL becomes /strategies/<id>/edit. React has no
// Monaco (textarea fallback) and no validation-panel class; selectors are roles/labels/text.

test.describe('strategy editor — author → validate → create draft', () => {
  test.beforeAll(() => resetLoginLimiter());

  test('fill name + valid YAML → badge reads "valid" → Create draft → /strategies/<id>/edit', async ({
    page,
  }) => {
    const pageErrors: Error[] = [];
    page.on('pageerror', (err) => pageErrors.push(err));

    await loginThroughForm(page);

    // Land directly on the new-strategy editor route.
    await page.goto('/strategies/new/edit');

    // The editor's anchor controls render: the (sr-only) page heading, the Name field (isNew only),
    // the YAML textarea and the Create-draft button.
    const nameInput = page.getByLabel('Strategy name');
    const yamlInput = page.getByLabel('Strategy YAML');
    const createBtn = page.getByRole('button', { name: 'Create draft' });

    await expect(nameInput).toBeVisible({ timeout: 20_000 });
    await expect(yamlInput).toBeVisible({ timeout: 20_000 });
    await expect(createBtn).toBeVisible({ timeout: 20_000 });

    // Unique id AND name so the create never collides with the suite's persisted e2e strategy
    // (RegistryService.create 409s on a duplicate slug OR name; the slug derives from the YAML id).
    const slug = `e2e-editor-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
    const uniqueName = `E2E Editor ${slug}`;
    const uniqueYaml = E2E_STRATEGY_YAML
      .replace(/^id:.*$/m, `id: ${slug}`)
      .replace(/^name:.*$/m, `name: "${uniqueName}"`);
    await nameInput.fill(uniqueName);
    await yamlInput.fill(uniqueYaml);

    // The validation badge is a small span that renders 'checking' | 'valid' | 'invalid' after the
    // debounced (500ms) server POST /validate resolves — allow 15s for the round-trip. Match the
    // badge by its exact short text rather than a structural selector.
    await expect(page.getByText('valid', { exact: true })).toBeVisible({ timeout: 15_000 });

    // Create-draft is enabled only when the name is present and validation isn't invalid.
    await expect(createBtn).toBeEnabled({ timeout: 5_000 });
    await createBtn.click();

    // Success navigates to the saved strategy's edit route (UUID id).
    await expect(page).toHaveURL(/\/strategies\/[0-9a-f-]{8,}\/edit/, { timeout: 20_000 });

    // No client-side crash anywhere in the flow.
    expect(pageErrors, `unexpected page errors: ${pageErrors.map((e) => e.message).join('; ')}`).toEqual(
      [],
    );
  });
});
