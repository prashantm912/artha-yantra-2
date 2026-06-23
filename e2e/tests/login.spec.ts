import { test, expect } from '@playwright/test';
import { OWNER_PASSWORD, resetLoginLimiter } from './helpers.ts';

// Login journey (ported from the parked Angular C-2.20 spec → React-DOM).
// React LoginPage (frontend-react/src/pages/login/LoginPage.tsx): keeps input[name="password"] + a
// visible "Sign in" submit button; a failed login renders the inline error as <p role="alert">{msg}</p>
// and, because login() returns false, the component never navigates → URL stays on /login. A success
// navigates to the returnUrl (default "/", which the index route redirects to /options/options-chain)
// behind RequireAuth → AppShell, whose <main> carries data-testid="app-shell". Deterministic flow.
test.describe('login journey (React)', () => {
  test.beforeAll(() => resetLoginLimiter()); // clear the A.2.6 per-IP login limiter before the journey

  test('wrong password shows the inline error and stays on /login; the right one signs in', async ({
    page,
  }) => {
    const pageErrors: Error[] = [];
    page.on('pageerror', (err) => pageErrors.push(err));

    await page.goto('/login');
    const password = page.locator('input[name="password"]');
    const signIn = page.getByRole('button', { name: 'Sign in' });
    await expect(password).toBeVisible({ timeout: 20_000 });
    await expect(signIn).toBeVisible({ timeout: 20_000 });

    // 1) Wrong password → inline role="alert" error, never leaves /login, no app-shell.
    await password.fill('definitely-wrong-password');
    await signIn.click();
    await expect(page.getByRole('alert')).toBeVisible({ timeout: 20_000 });
    await expect(page).toHaveURL(/\/login(\?|$)/);
    await expect(page.getByTestId('app-shell')).toHaveCount(0);

    // 2) Correct password → navigates off /login into the authenticated AppShell.
    await password.fill(OWNER_PASSWORD);
    await signIn.click();
    await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 20_000 });
    await expect(page).not.toHaveURL(/\/login(\?|$)/);

    expect(pageErrors, `unexpected page errors: ${pageErrors.map((e) => e.message).join('; ')}`).toEqual(
      [],
    );
  });
});
