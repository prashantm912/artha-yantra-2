import { defineConfig, devices } from '@playwright/test';

// React e2e (master plan §20): runs against the Vite dev-server (:4300) which proxies /api,/ws to the
// gateway (:8080) — so the MOCK STACK must be up (ay up mock) before `npm run e2e`. Two viewports:
// desktop + ~480px (S24 Ultra portrait CSS). Playwright starts Vite automatically.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4300',
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
      use: { ...devices['Desktop Chrome'], viewport: { width: 480, height: 1010 }, isMobile: false },
    },
  ],
});
