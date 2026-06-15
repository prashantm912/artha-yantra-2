import { execFileSync } from 'node:child_process';
import { expect, type APIRequestContext, type Page } from '@playwright/test';

/** Locally export E2E_OWNER_PASSWORD to match your own .env hash; CI uses the fixed pair. */
export const OWNER_PASSWORD = process.env['E2E_OWNER_PASSWORD'] ?? 'e2e-owner-password';

// Deletes both login:attempts:* and login:cooldown:* in one shot; safe when none exist.
const RESET_LOGIN_LIMITER_LUA =
  "local k = redis.call('keys', 'login:*'); if #k > 0 then redis.call('del', unpack(k)) end; return #k";

/**
 * Clears the gateway's per-IP login rate-limit window (A.2.6: 5/min, then a 15-minute cooldown)
 * so each journey starts fresh — the suite signs in far more often than a human would, and a
 * single trip would poison every later test for 15 minutes. Mirrors
 * GatewayIntegrationTest.resetLoginLimiter; runs against the same ay-redis container. execFileSync
 * (no shell) keeps the Lua literal intact on both Windows and CI. Best-effort: a missing
 * docker/redis is non-fatal so a sparsely-run suite still works.
 */
export function resetLoginLimiter(): void {
  try {
    execFileSync(
      'docker',
      ['exec', 'ay-redis', 'redis-cli', 'EVAL', RESET_LOGIN_LIMITER_LUA, '0'],
      { stdio: 'ignore' },
    );
  } catch {
    /* non-fatal */
  }
}

/** UI login through the real form; lands on the shell. */
export async function loginThroughForm(page: Page): Promise<void> {
  await page.goto('/login');
  // target the inner <input>, not the PrimeNG <p-password> host (both mirror the placeholder)
  await page.locator('input[name="password"]').fill(OWNER_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.locator('ay-shell')).toBeVisible({ timeout: 20_000 });
}

/** API login for fixtures; returns a context carrying the SESSION cookie. */
export async function apiLogin(request: APIRequestContext): Promise<void> {
  const response = await request.post('/api/v1/auth/login', {
    form: { password: OWNER_PASSWORD },
  });
  expect(response.status(), 'API login must succeed').toBe(204);
}

/**
 * Deterministically warm an instrument's 1m history through the cache-first candle surface, over
 * the same 4-day window the signal engine warms (LiveSeriesStore.warmupDays). On a fresh CI stack
 * the candle store is empty, so the engine's self-warm at publish triggers a synchronous mock
 * historical synth inside its REST call; under 2-core contention that can be slow or time out, the
 * client swallows it ("engine starts cold"), and RSI(period 2) then needs ~3 LIVE 1m bars to become
 * non-NaN — racing the test's 180 s budget (the documented signals flake). Priming the cache here,
 * before publish, makes the engine's self-warm a fast populated cache hit: RSI is non-NaN on the
 * FIRST live bar, so the fixture emits within one bar (~75 s). Caller owns the wait — no 180 s
 * pressure on this synth. Requires an authenticated `request` (call apiLogin first).
 */
export async function warm1mHistory(
  request: APIRequestContext,
  exchange: string,
  tradingsymbol: string,
): Promise<void> {
  const to = new Date();
  const from = new Date(to.getTime() - 4 * 86_400_000);
  for (let attempt = 0; attempt < 15; attempt++) {
    const res = await request.get('/api/v1/market/candles', {
      params: {
        exchange,
        tradingsymbol,
        interval: '1m',
        from: from.toISOString(),
        to: to.toISOString(),
        limit: 50_000,
      },
    });
    if (res.ok()) {
      const body = (await res.json()) as { items?: unknown[] };
      // ≥3 bars covers RSI(period 2)'s look-back; a healthy synth returns hundreds.
      if ((body.items?.length ?? 0) >= 3) {
        return;
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }
  throw new Error(`failed to warm 1m history for ${exchange}:${tradingsymbol}`);
}

/**
 * The gateway requires X-XSRF-TOKEN on mutating calls (CSRF, A.2.3). Angular's HttpClient echoes
 * the XSRF-TOKEN cookie automatically, but Playwright's raw request context does not — so a fixture
 * POST would be 403'd. Read the double-submit cookie and surface it as the header; if it has not
 * been rendered yet, a GET primes it (CsrfWebFilter sets it on any request).
 */
async function csrfHeader(request: APIRequestContext): Promise<Record<string, string>> {
  let token = await readXsrfCookie(request);
  if (!token) {
    await request.get('/api/v1/auth/session');
    token = await readXsrfCookie(request);
  }
  return token ? { 'X-XSRF-TOKEN': token } : {};
}

async function readXsrfCookie(request: APIRequestContext): Promise<string | undefined> {
  const { cookies } = await request.storageState();
  return cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value;
}

const E2E_STRATEGY_SLUG = 'e2e-live-momentum';

/**
 * The deterministic E2E strategy. Warm history (market-data REST) makes RSI non-NaN on the FIRST
 * live bar; a step catch-all normalize then scores it 1.0 unconditionally, so the composite clears
 * the threshold every bar regardless of where the mock random-walk's RSI happens to sit. This is a
 * smoke fixture (does a signal flow end-to-end?), not a quality strategy — score realism is
 * covered by the golden vectors. It still exercises a real RSI indicator, gate leaf and breakdown.
 */
export const E2E_STRATEGY_YAML = `schema: strategy-schema/v1
id: ${E2E_STRATEGY_SLUG}
name: "E2E Live Momentum"
version: 1.0.0
universe:
  mode: explicit
  instruments:
    - { exchange: NSE, tradingsymbol: RELIANCE }
timeframes: { primary: 1m }
indicators:
  - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 2 }, weight: 1.0,
      normalize: { type: step, bands: [ { score: 1.0 } ] } }
entry_rules:
  direction: long
  gate:
    all:
      - "close > 1"
  scoring: { threshold: 0.05 }
exit_rules:
  - { type: stop_loss, params: { basis: premium_pct, value: 50 } }
risk:
  position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
  max_positions: 1
  session: { style: intraday }
`;

/**
 * Ensures the E2E strategy exists with the CURRENT config and is published — idempotent across
 * runs AND across config changes. Create (201) on a clean DB; otherwise update the existing
 * strategy to the current config (a new draft; 409 = identical, already current) and publish.
 */
export async function publishE2eStrategy(request: APIRequestContext): Promise<void> {
  const headers = await csrfHeader(request);
  const created = await request.post('/api/v1/strategies', {
    data: { name: 'E2E Live Momentum', config: E2E_STRATEGY_YAML },
    headers,
  });
  let id: string;
  if (created.status() === 201) {
    id = ((await created.json()) as { id: string }).id;
  } else {
    const list = await request.get(`/api/v1/strategies?q=${E2E_STRATEGY_SLUG}`);
    const body = (await list.json().catch(() => ({}))) as { items?: { id: string; slug: string }[] };
    const items = body.items ?? [];
    const match = items.find((item) => item.slug === E2E_STRATEGY_SLUG);
    expect(
      match,
      `create=${created.status()} list=${list.status()} (${items.length} items) — ${E2E_STRATEGY_SLUG} not found`,
    ).toBeTruthy();
    id = match!.id;
    // bring an existing (possibly older-config) strategy up to the current config as a new draft
    const updated = await request.put(`/api/v1/strategies/${id}`, {
      data: { config: E2E_STRATEGY_YAML, versionBump: 'minor', notes: 'e2e refresh' },
      headers,
    });
    expect([200, 409]).toContain(updated.status()); // 409 CONFLICT_NO_CONTENT_CHANGE = already current
  }
  const published = await request.post(`/api/v1/strategies/${id}/publish`, { data: {}, headers });
  expect([200, 409]).toContain(published.status()); // 409 CONFLICT_NOT_A_DRAFT = already published
}
