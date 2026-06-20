# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: login.spec.ts >> login lands on the shell and has no axe violations
- Location: e2e\login.spec.ts:5:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByTestId('app-shell')
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for getByTestId('app-shell')

```

```yaml
- main:
  - heading "Sign in" [level=1]
  - text: Password
  - textbox "Password": MyPassword123
  - alert: Too many login attempts; cooling down
  - button "Sign in"
```

# Test source

```ts
  1  | import { expect, type Page } from '@playwright/test';
  2  | 
  3  | // Mock owner password — override via E2E_OWNER_PASSWORD to match your .env hash.
  4  | export const OWNER_PASSWORD = process.env.E2E_OWNER_PASSWORD ?? 'e2e-owner-password';
  5  | 
  6  | /** Logs in through the form (keeps the input[name=password] + "Sign in" contract). */
  7  | export async function loginThroughForm(page: Page): Promise<void> {
  8  |   await page.goto('/login');
  9  |   await page.locator('input[name="password"]').fill(OWNER_PASSWORD);
  10 |   await page.getByRole('button', { name: 'Sign in', exact: true }).click();
> 11 |   await expect(page.getByTestId('app-shell')).toBeVisible();
     |                                               ^ Error: expect(locator).toBeVisible() failed
  12 | }
  13 | 
```