import { defineConfig } from '@playwright/test';

/**
 * Phase 27 (C-2.20): the regression net runs against the FULL mock compose stack,
 * credential-free. Global setup boots compose and waits for the gateway; journeys only ever
 * APPEND in later stages.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 240_000,
  expect: { timeout: 15_000 },
  retries: process.env['CI'] ? 1 : 0,
  workers: 1, // journeys share one stack and one owner session world
  globalSetup: './global-setup.ts',
  use: {
    baseURL: 'http://127.0.0.1:8080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  reporter: process.env['CI'] ? [['list'], ['github']] : [['list']],
});
