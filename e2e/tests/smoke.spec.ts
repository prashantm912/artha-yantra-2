import { test, expect } from '@playwright/test';
import { apiLogin, loginThroughForm, resetLoginLimiter } from './helpers.ts';

// Deployed-stack smoke for the React cutover: proves the full mock compose stack boots, the React SPA
// serves through the gateway's catch-all (the SOLE ingress, same-origin), form login obtains a
// session, and an authenticated page renders + the API answers through the gateway. The rich Angular
// journey suite is parked under e2e/tests-angular-parked/ pending a React-DOM port.

test.beforeAll(() => resetLoginLimiter());

test('the React app loads, authenticates, and renders through the gateway', async ({ page, request }) => {
  await loginThroughForm(page);

  // landed on the shell, off the login route (the index redirects to the options chain)
  await expect(page).not.toHaveURL(/\/login$/);
  await expect(page.getByTestId('app-shell')).toBeVisible();

  // a known cockpit route renders behind auth
  await page.goto('/signals');
  await expect(page.getByTestId('app-shell')).toBeVisible();

  // and the API answers through the same-origin gateway with a live session
  await apiLogin(request);
  const session = await request.get('/api/v1/auth/session');
  expect(session.ok()).toBeTruthy();
  expect((await session.json()).authenticated).toBe(true);
});
