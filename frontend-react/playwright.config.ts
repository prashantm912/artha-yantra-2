import { defineConfig, devices } from '@playwright/test';

// React e2e (master plan §20): runs against the Vite dev-server (:4300) which proxies /api,/ws to the
// gateway (:8080) — so the stack must be up before `npm run e2e`. global-setup logs in ONCE and saves
// storageState (one login total → robust against the login rate-limiter); specs reuse it. Two
// viewports: desktop + ~480px (S24 Ultra portrait CSS). Playwright starts Vite automatically.
const STATE = 'e2e/.auth/state.json';

export default defineConfig({
  testDir: './e2e',
  // Serial: the suite shares one Vite dev-server + one gateway/session — parallel workers contend
  // (frame-detached/timeouts) and would multiply logins against the rate-limiter.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: 'list',
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: 'http://127.0.0.1:4300',
    storageState: STATE,
    trace: 'on-first-retry',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:4300',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'mobile',
      use: { ...devices['Desktop Chrome'], viewport: { width: 480, height: 1010 } },
    },
  ],
});
