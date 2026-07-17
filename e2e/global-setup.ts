import { execSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));

/**
 * Boots the mock compose stack (8 core containers, zero credentials) and waits for the gateway
 * healthcheck. Idempotent: an already-healthy stack is reused — locally you can keep `ay up`
 * running between test runs. The E2E owner password is fixed (e2e-owner-password) via the
 * committed Argon2id hash; CI writes deploy/.env from scratch, locally an existing .env wins
 * unless E2E_FORCE_ENV=1.
 */
const REPO = join(__dirname, '..');
const COMPOSE = ['docker', 'compose', '-f', 'deploy/docker-compose.yml', '--env-file', '.env'];

// $ doubled for compose interpolation (the Stage-A .env convention)
const E2E_HASH =
  '$$argon2id$$v=19$$m=19456,t=2,p=1$$Wa8xb/nJeVWMgnJ7TuO+kg$$RbChqtDp/gAwF23INJV1SWwktz/z6RAO7a9cnjWH+9Q';

export default async function globalSetup(): Promise<void> {
  const envFile = join(REPO, '.env');
  if (!existsSync(envFile) || process.env['E2E_FORCE_ENV'] === '1') {
    writeFileSync(
      envFile,
      [
        'SPRING_PROFILES_ACTIVE=mock',
        `ARTHA_OWNER_PASSWORD_HASH=${E2E_HASH}`,
        // audit P0-6: compose notifier defaults are fail-closed blank; mock targets the stub
        'ARTHA_NOTIFIER_NTFY_URL=http://wiremock:8080',
        'ARTHA_NOTIFIER_NTFY_TOPIC=ay-signals-mock',
        '',
      ].join('\n'),
    );
    process.env['E2E_OWNER_PASSWORD'] = 'e2e-owner-password';
  }

  ensureSecrets();

  // Audit P1-7: the harness is a MOCK harness — pin the profile-derived env the compose file now
  // fail-closes on (the old :-artha/:-0 defaults let a locally-run suite write e2e strategies and
  // mock candles into the LIVE artha DB / redis db0). execSync inherits process.env.
  process.env['ARTHA_DB_NAME'] = 'artha_mock';
  process.env['ARTHA_REDIS_DB'] = '1';

  if (await gatewayHealthy()) {
    // Reuse ONLY a mock stack: the specs publish a fire-every-bar strategy and mutate the
    // registry/jobs — against a live stack (auto-paper ON) that pollutes real trading data.
    const profile = runningProfile();
    if (profile !== 'mock') {
      throw new Error(
        `[e2e] a healthy stack is running with SPRING_PROFILES_ACTIVE=${profile} — refusing to ` +
          'run the mutating e2e suite against a non-mock stack. Stop it or switch profiles via ay.',
      );
    }
    console.log('[e2e] gateway already healthy — reusing the running MOCK stack');
    await waitForSignalsEndpoint();
    return;
  }

  console.log('[e2e] compose up (mock stack)...');
  execSync(`${COMPOSE.join(' ')} up -d --build`, { cwd: REPO, stdio: 'inherit' });

  const deadline = Date.now() + 360_000;
  while (Date.now() < deadline) {
    if (await gatewayHealthy()) {
      console.log('[e2e] gateway healthy');
      await waitForSignalsEndpoint();
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 5_000));
  }
  execSync(`${COMPOSE.join(' ')} ps`, { cwd: REPO, stdio: 'inherit' });
  throw new Error('gateway did not become healthy within 6 minutes');
}

/**
 * The strategy-signal container healthcheck goes GREEN when the JVM boots, but the FIRST
 * authenticated `GET /api/v1/signals` (the query behind the /signals cockpit) can still cold-start
 * slowly on the 2-core CI runner — first JIT + first DB round-trip. When it does, SignalsPage sits in
 * its QueryState loading skeleton (`[data-testid="qs-loading"]`) past the per-assertion 20s budget,
 * which surfaces as the ws-reconnect.spec.ts / signals.spec.ts "table did not settle" flake. Absorb
 * that latency ONCE here so every test starts against a warm endpoint.
 *
 * The gateway 401s an unauthenticated /api/v1/* call from its auth filter (the request never reaches
 * strategy-signal), so a bare probe proves nothing — we must log in for a real SESSION cookie first.
 * Best-effort: a login/poll failure logs a warning and proceeds (the specs still degrade gracefully),
 * so this can only HELP a cold stack, never block a warm one.
 */
async function waitForSignalsEndpoint(): Promise<void> {
  const password = process.env['E2E_OWNER_PASSWORD'] ?? 'e2e-owner-password';
  // One login only (well under the gateway's 5/min limiter; each spec's beforeAll resets it anyway).
  const login = await fetch('http://127.0.0.1:8080/api/v1/auth/login', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ password }).toString(),
    signal: AbortSignal.timeout(5_000),
  }).catch(() => null);
  // Require a successful login AND the SESSION cookie specifically. A wrong password or a rate-limited
  // login still returns a Set-Cookie (XSRF-TOKEN), and polling 401s with that for the full 60s would
  // waste the whole budget instead of degrading immediately.
  if (!login || !login.ok) {
    console.warn('[e2e] signals-readiness: login not OK (wrong password or rate-limited) — skipping the endpoint warm-up');
    return;
  }
  const session = login.headers
    .getSetCookie()
    .map((c) => c.split(';')[0])
    .find((c) => c.startsWith('SESSION='));
  if (!session) {
    console.warn('[e2e] signals-readiness: login returned no SESSION cookie — skipping the endpoint warm-up');
    return;
  }

  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    try {
      const res = await fetch('http://127.0.0.1:8080/api/v1/signals?limit=1', {
        headers: { cookie: session },
        signal: AbortSignal.timeout(5_000),
      });
      if (res.ok) {
        console.log('[e2e] GET /api/v1/signals ready (200) — endpoint warmed');
        return;
      }
    } catch {
      /* keep polling — a cold JVM may reset the connection */
    }
    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }
  console.warn('[e2e] GET /api/v1/signals did not 200 within 60s — proceeding (specs degrade gracefully)');
}

/** The running stack's Spring profile (via the market-data container env); 'unknown' when unreadable. */
function runningProfile(): string {
  try {
    const env = execSync(
      'docker inspect -f "{{range .Config.Env}}{{println .}}{{end}}" ay-market-data-service',
      { encoding: 'utf8' },
    );
    const line = env.split('\n').find((l) => l.startsWith('SPRING_PROFILES_ACTIVE='));
    return line ? line.substring('SPRING_PROFILES_ACTIVE='.length).trim() : 'unknown';
  } catch {
    return 'unknown';
  }
}

/**
 * The compose stack mounts deploy/secrets/* as Docker secrets; `ay up` seeds them, but the E2E
 * boots compose directly, so CI (and a fresh local clone) has no secrets dir and the bind mount
 * fails before anything starts. Seed the same files `ay` does — idempotent, mock-safe values: a
 * fixed Postgres password (single source of truth for the DB and the services), a valid 32-byte
 * AES-256 master key, empty Kite placeholders, an empty OpenAlgo API key, and an empty Upstox
 * analytics token (all mock-unread — the OpenAlgo key and Upstox token mount into market-data-service
 * unconditionally, but are read only when a source.* / analytics capability routes through them,
 * which the mock stack never does).
 */
function ensureSecrets(): void {
  const dir = join(REPO, 'deploy', 'secrets');
  mkdirSync(dir, { recursive: true });
  const files: Record<string, string> = {
    postgres_password: 'e2e-postgres-pw',
    artha_master_key: Buffer.alloc(32).toString('base64'),
    kite_api_key: '',
    kite_api_secret: '',
    openalgo_api_key: '',
    upstox_analytics_token: '',
  };
  for (const [name, value] of Object.entries(files)) {
    const file = join(dir, name);
    if (!existsSync(file)) {
      writeFileSync(file, value);
    }
  }
}

async function gatewayHealthy(): Promise<boolean> {
  try {
    const health = await fetch('http://127.0.0.1:8080/actuator/health', {
      signal: AbortSignal.timeout(3_000),
    });
    if (!health.ok) {
      return false;
    }
    const body = (await health.json()) as { status?: string };
    if (body.status !== 'UP') {
      return false;
    }
    // the FULL stack: the SPA must serve through the catch-all route (React cutover — the
    // served shell is now the Vite `<div id="root">`, was Angular's `<app-root>`).
    const spa = await fetch('http://127.0.0.1:8080/', { signal: AbortSignal.timeout(3_000) });
    if (!spa.ok || !(await spa.text()).includes('id="root"')) {
      return false;
    }
    // ...and the upstream services must actually be HEALTHY. /api/v1/strategies returns 401 from
    // the GATEWAY's auth filter even when strategy-signal-service is down (the request never
    // reaches the upstream), so it proves nothing — check the container healthchecks directly (the
    // source of truth). Otherwise the first publish hits a 503 the instant the gateway is up.
    // audit P2: gate on backtest + optimizer too — backtest-results and sweep-explorer specs
    // drive them, so a cold-start 503 the instant the gateway is up otherwise reads as flake.
    for (const container of [
      'ay-strategy-signal-service',
      'ay-market-data-service',
      'ay-backtest-service',
      'ay-optimizer-service',
    ]) {
      const status = execSync(`docker inspect -f "{{.State.Health.Status}}" ${container}`, {
        encoding: 'utf8',
      }).trim();
      if (status !== 'healthy') {
        return false;
      }
    }
    return true;
  } catch {
    return false;
  }
}
