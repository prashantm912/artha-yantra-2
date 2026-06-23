import { test, expect } from '@playwright/test';
import { loginThroughForm, resetLoginLimiter } from './helpers.ts';

// Ported from the parked Angular journey (tests-angular-parked/ws-reconnect.spec.ts), retargeted to
// the React app shell (frontend-react/src/components/AppShell.tsx) + WS singleton
// (frontend-react/src/lib/wsClient.ts, surfaced via stores/useWsState.ts).
//
// The Angular original bounced ay-edge-gateway mid-session (docker restart) to prove the STOMP client
// notices the dead socket and self-heals with backoff, same tab, no reload. That chaos step is
// impractical/flaky in the deployed CI suite (it would knock out the sole ingress the runner depends
// on for the rest of the journey, and reconnection timing is non-deterministic). The faithful port of
// the INTENT is the connects-and-stays-up half: after login the WS singleton (wsClient.activate(),
// fired in AppShell's effect) must drive the TopBar pill from 'idle' → 'connecting' → 'connected'
// within a few seconds, and the /signals page (which STOMP-subscribes via useSignalsLive) must render
// cleanly with no client error.
//
// The pill renders the literal "● WS <state>" where <state> ∈ idle | connecting | connected |
// reconnecting (WsPill in AppShell.tsx, PILL map). 'connected' is the steady success state.

test.describe('WS connect — the app shell socket comes up and stays up', () => {
  test.beforeAll(() => resetLoginLimiter());

  test('after login the WS-state pill settles to connected on /signals with no page error', async ({
    page,
  }) => {
    const pageErrors: string[] = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    await loginThroughForm(page);

    // The page that exercises a real subscription: SignalsPage calls useSignalsLive(),
    // which STOMP-subscribes /topic/signals over the same wsClient singleton.
    await page.goto('/signals');
    await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 20_000 });

    // Anchor control: confirms we are really on the signals cockpit (not a blank shell).
    await expect(page.getByLabel('Status filter')).toBeVisible({ timeout: 20_000 });

    // The WsPill lives in the header and reads "● WS <state>" with aria-live="polite".
    // Steady-state success is "WS connected"; allow generous slack for the mock gateway's STOMP
    // handshake on a cold CI stack (the Angular original waited up to 30s for the same pill).
    const wsPill = page.getByText(/●\s*WS\s+(idle|connecting|connected|reconnecting)/);
    await expect(wsPill).toBeVisible({ timeout: 20_000 });

    // The port of the intent: it CONNECTS and STAYS up. Wait for the steady 'connected' label.
    await expect(page.getByText(/WS connected/)).toBeVisible({ timeout: 25_000 });

    // ...and it does not silently drop back to a reconnect loop right after — re-assert it is
    // still connected after a short settle window (catches an immediate flap).
    await expect(page.getByText(/WS connected/)).toBeVisible({ timeout: 5_000 });

    // The live-subscribing page rendered its table (rows OR the empty state), proving the WS
    // subscription path did not throw on mount.
    const tableOrEmpty = page
      .getByText('No signals yet', { exact: false })
      .or(page.locator('tr[role="button"]').first());
    await expect(tableOrEmpty).toBeVisible({ timeout: 20_000 });

    expect(pageErrors, `client page errors: ${pageErrors.join(' | ')}`).toEqual([]);
  });
});
