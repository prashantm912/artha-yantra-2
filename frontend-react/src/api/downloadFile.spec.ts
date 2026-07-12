import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, downloadFile } from './client.ts';

afterEach(() => vi.restoreAllMocks());

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
});
