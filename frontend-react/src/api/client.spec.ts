/** apiFetch trust guards (audit P1-11): a 200 that isn't JSON must fail loud (the gateway
 * catch-all serves SPA index.html for unallowlisted paths), and a 401 flips the session store
 * so RequireAuth redirects instead of toast-spamming over frozen data. */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch } from './client.ts';
import { useSession } from '../stores/session.store.ts';

function mockFetch(status: number, body: string, contentType: string) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(body, { status, headers: { 'content-type': contentType } })),
  );
}

describe('apiFetch', () => {
  beforeEach(() => {
    vi.stubGlobal('document', Object.assign(document, { cookie: '' }));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('returns parsed JSON on an ok JSON response', async () => {
    mockFetch(200, '{"ok":true}', 'application/json');
    await expect(apiFetch('/x')).resolves.toEqual({ ok: true });
  });

  it('throws BAD_CONTENT_TYPE when an ok response is HTML (gateway allowlist misroute)', async () => {
    mockFetch(200, '<!doctype html><div id="root"></div>', 'text/html');
    const err = await apiFetch('/api/v1/new-endpoint').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe('BAD_CONTENT_TYPE');
  });

  it('throws the envelope code/message on a non-2xx response', async () => {
    mockFetch(422, '{"code":"DATA_GAP","message":"no snapshot"}', 'application/json');
    const err = await apiFetch('/x').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(422);
    expect((err as ApiError).code).toBe('DATA_GAP');
  });

  it('flips an authenticated session to anonymous on a 401 (RequireAuth then redirects)', async () => {
    useSession.setState({ auth: 'authenticated' });
    mockFetch(401, '{"code":"AUTH_REQUIRED","message":"auth"}', 'application/json');
    const err = await apiFetch('/x').catch((e: unknown) => e);
    expect((err as ApiError).status).toBe(401);
    expect((err as ApiError).silenced).toBe(true); // one redirect, not a toast per poll
    await vi.waitFor(() => expect(useSession.getState().auth).toBe('anonymous'));
  });
});
