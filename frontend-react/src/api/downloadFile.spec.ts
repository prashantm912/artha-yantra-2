import { afterEach, describe, expect, it, vi } from 'vitest';
import { toast } from 'sonner';
import { ApiError, downloadFile } from './client.ts';

vi.mock('sonner', () => ({
  toast: { warning: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn() },
}));

afterEach(() => {
  vi.restoreAllMocks();
  vi.mocked(toast.warning).mockClear();
});

/** A Headers-like stub keyed by name (case-sensitive is fine — the tests use the canonical casing). */
function headers(map: Record<string, string>): { get: (name: string) => string | null } {
  return { get: (name: string) => map[name] ?? null };
}

/** jsdom lacks URL.createObjectURL / anchor download — stub them so downloadFile can complete. */
function stubBlobDownload(): void {
  (URL as unknown as { createObjectURL: (b: Blob) => string }).createObjectURL = () => 'blob:x';
  (URL as unknown as { revokeObjectURL: (u: string) => void }).revokeObjectURL = () => {};
  const realCreate = document.createElement.bind(document);
  vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
    const el = realCreate(tag) as HTMLAnchorElement;
    if (tag === 'a') el.click = () => {};
    return el;
  });
}

describe('downloadFile', () => {
  it('saves the blob using the Content-Disposition filename', async () => {
    const clicked: string[] = [];
    const created: string[] = [];
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'attachment; filename="minervini_screen_2026-07-01.csv"' },
      blob: () => Promise.resolve(new Blob(['a,b\r\n1,2\r\n'], { type: 'text/csv' })),
    }));
    // jsdom lacks URL.createObjectURL — stub it + capture the anchor download.
    (URL as unknown as { createObjectURL: (b: Blob) => string }).createObjectURL = () => 'blob:x';
    (URL as unknown as { revokeObjectURL: (u: string) => void }).revokeObjectURL = () => {};
    const realCreate = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = realCreate(tag) as HTMLAnchorElement;
      if (tag === 'a') {
        el.click = () => clicked.push(el.download);
      }
      return el;
    });

    await downloadFile('/market/screener/minervini/export?passesAllOnly=true');

    expect((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0]).toBe(
      '/api/v1/market/screener/minervini/export?passesAllOnly=true',
    );
    expect(clicked).toEqual(['minervini_screen_2026-07-01.csv']);
    void created;
  });

  it('throws an ApiError on a non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 422,
      statusText: 'Unprocessable Entity',
      json: () => Promise.resolve({ code: 'DATA_GAP', message: 'no data' }),
    }));
    await expect(downloadFile('/market/breadth/history')).rejects.toBeInstanceOf(ApiError);
  });

  it('warns with the row cap when the export is truncated (X-Result-Truncated)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      headers: headers({
        'content-disposition': 'attachment; filename="signals.csv"',
        'X-Result-Truncated': 'true',
        'X-Result-Rows': '100000',
      }),
      blob: () => Promise.resolve(new Blob(['a\r\n'], { type: 'text/csv' })),
    }));
    stubBlobDownload();

    await downloadFile('/signals/export');

    expect(toast.warning).toHaveBeenCalledTimes(1);
    // Strip locale grouping (en-IN renders 1,00,000, en-US 100,000) — assert the digits + phrasing.
    const message = (vi.mocked(toast.warning).mock.calls[0][0] as string).replace(/,/g, '');
    expect(message).toBe('Export truncated to 100000 rows');
  });

  it('does not warn when the export is not truncated', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      headers: headers({
        'content-disposition': 'attachment; filename="signals.csv"',
        'X-Result-Truncated': 'false',
        'X-Result-Rows': '42',
      }),
      blob: () => Promise.resolve(new Blob(['a\r\n'], { type: 'text/csv' })),
    }));
    stubBlobDownload();

    await downloadFile('/signals/export');

    expect(toast.warning).not.toHaveBeenCalled();
  });
});
