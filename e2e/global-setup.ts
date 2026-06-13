import { execSync } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';
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
      ['SPRING_PROFILES_ACTIVE=mock', `ARTHA_OWNER_PASSWORD_HASH=${E2E_HASH}`, ''].join('\n'),
    );
    process.env['E2E_OWNER_PASSWORD'] = 'e2e-owner-password';
  }

  if (await gatewayHealthy()) {
    console.log('[e2e] gateway already healthy — reusing the running stack');
    return;
  }

  console.log('[e2e] compose up (mock stack)...');
  execSync(`${COMPOSE.join(' ')} up -d --build`, { cwd: REPO, stdio: 'inherit' });

  const deadline = Date.now() + 360_000;
  while (Date.now() < deadline) {
    if (await gatewayHealthy()) {
      console.log('[e2e] gateway healthy');
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 5_000));
  }
  execSync(`${COMPOSE.join(' ')} ps`, { cwd: REPO, stdio: 'inherit' });
  throw new Error('gateway did not become healthy within 6 minutes');
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
    // the FULL Stage-C stack: the SPA must serve through the catch-all route...
    const spa = await fetch('http://127.0.0.1:8080/', { signal: AbortSignal.timeout(3_000) });
    if (!spa.ok || !(await spa.text()).includes('<app-root')) {
      return false;
    }
    // ...and strategy-signal-service must be routable (401 = up; 5xx = absent)
    const sss = await fetch('http://127.0.0.1:8080/api/v1/strategies', {
      signal: AbortSignal.timeout(3_000),
    });
    return sss.status < 500;
  } catch {
    return false;
  }
}
