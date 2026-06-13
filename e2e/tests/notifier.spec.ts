import { expect, request as playwrightRequest, test, type APIRequestContext } from '@playwright/test';
import { apiLogin, publishE2eStrategy, resetLoginLimiter } from './helpers';

const WIREMOCK = 'http://127.0.0.1:9099';

/** The gateway requires X-XSRF-TOKEN on mutating calls; the raw request context must echo it. */
async function csrf(request: APIRequestContext): Promise<Record<string, string>> {
  await request.get('/api/v1/auth/session');
  const { cookies } = await request.storageState();
  const token = cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  return token ? { 'X-XSRF-TOKEN': token } : {};
}

/**
 * Phase 41 (Q6): an opted-in strategy's signal arrives as a phone push. ntfy is WireMock-stubbed
 * under mock (catch-all 200); we opt the e2e strategy in, let the mock feed emit a signal, and
 * assert WireMock received the notifier's POST.
 */
test.describe('signal notifier — ntfy push (Phase 41)', () => {
  test.beforeEach(resetLoginLimiter);

  test('opting a strategy in pushes its signal to the (stubbed) ntfy endpoint', async ({ request }) => {
    await apiLogin(request);
    await publishE2eStrategy(request);

    // find the strategy id and enable NTFY notifications (PATCH never mints a version)
    const list = await request.get('/api/v1/strategies?q=e2e-live-momentum');
    const id = ((await list.json()) as { items: { id: string; slug: string }[] }).items.find(
      (s) => s.slug === 'e2e-live-momentum',
    )!.id;
    const headers = await csrf(request);
    const patch = await request.patch(`/api/v1/strategies/${id}/notifications`, {
      data: { enabled: true, channel: 'NTFY' },
      headers,
    });
    expect(patch.ok()).toBeTruthy();

    // a test-send proves the wiring immediately; the live-signal push then follows from the feed
    const sent = await request.post(`/api/v1/strategies/${id}/notifications/test`, {
      data: {},
      headers,
    });
    expect(sent.ok()).toBeTruthy();

    // WireMock recorded the notifier's POST
    const wm = await playwrightRequest.newContext();
    await expect
      .poll(
        async () => {
          const res = await wm.get(`${WIREMOCK}/__admin/requests`);
          const body = (await res.json()) as { requests: { request: { method: string } }[] };
          return body.requests.filter((r) => r.request.method === 'POST').length;
        },
        { timeout: 30_000, intervals: [1000] },
      )
      .toBeGreaterThan(0);
    await wm.dispose();
  });
});
