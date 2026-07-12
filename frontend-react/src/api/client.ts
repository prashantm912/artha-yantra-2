// Typed fetch wrapper (master plan §20 / §10.2): same-origin `/api/v1`, XSRF echo, the D8 error
// envelope → ApiError, and the {items} list helper. Replaces Angular's HttpClient + error.interceptor.

import { toast } from 'sonner';

const BASE = '/api/v1';

// The shared CSV-export standard's loud-truncation headers (libs/common-web CsvExport): the server
// clips a download at a row cap and says so via these headers rather than a silently short file.
// Same-origin through the gateway, so fetch reads them without any Access-Control-Expose-Headers.
const HEADER_TRUNCATED = 'X-Result-Truncated';
const HEADER_ROWS = 'X-Result-Rows';

/**
 * Surfaces the server's loud-truncation contract to the user: when an export response carries
 * `X-Result-Truncated: true`, a warning toast names how many rows actually made the file
 * (`X-Result-Rows` = the row cap). No-op when the header is absent or false. Called from every
 * export download path so a clipped export is never silent (A9 residual / task_f12c165f).
 */
export function warnIfExportTruncated(res: Response): void {
  if (res.headers.get(HEADER_TRUNCATED) !== 'true') return;
  const rows = Number(res.headers.get(HEADER_ROWS));
  const message =
    Number.isFinite(rows) && rows > 0
      ? `Export truncated to ${rows.toLocaleString()} rows`
      : 'Export truncated at the server row cap';
  toast.warning(message, {
    description: 'The row cap was reached — some rows were left out of the download.',
  });
}

export interface ApiErrorBody {
  code?: string;
  message?: string;
  details?: unknown;
}

/** The D8 `{code,message,details}` envelope as a thrown error; `silenced` skips the global toast. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    message: string,
    readonly details?: unknown,
    readonly silenced = false,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** Reads the XSRF-TOKEN cookie the gateway sets (echoed as X-XSRF-TOKEN on mutating calls). */
export function xsrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** Raw body (string/FormData/etc.). For JSON use `json`. */
  body?: BodyInit;
  /** Convenience: JSON-encodes the value and sets the content-type. */
  json?: unknown;
  headers?: Record<string, string>;
  /** Tags the thrown ApiError as silenced so the global handler skips the toast (e.g. 422 DATA_GAP). */
  silenceToast?: boolean;
  signal?: AbortSignal;
}

/** Issues a request against the gateway and returns the parsed body (or undefined for 204). */
export async function apiFetch<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const method = opts.method ?? 'GET';
  const headers: Record<string, string> = { ...opts.headers };

  let body = opts.body;
  if (opts.json !== undefined) {
    body = JSON.stringify(opts.json);
    headers['Content-Type'] ??= 'application/json';
  }

  // Angular echoed XSRF for free; React must do it explicitly on mutating methods.
  if (method !== 'GET') {
    const token = xsrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }

  const res = await fetch(BASE + path, {
    method,
    headers,
    body,
    credentials: 'include', // the SESSION cookie rides
    signal: opts.signal,
  });

  if (!res.ok) {
    let envelope: ApiErrorBody = {};
    try {
      envelope = (await res.json()) as ApiErrorBody;
    } catch {
      /* non-JSON error body */
    }
    // Audit P1-11: an expired gateway session must read as "signed out", not as an endless
    // error-toast stream over frozen data — flip the auth store so RequireAuth redirects to
    // /login (the store import is deferred to avoid a module cycle).
    if (res.status === 401) {
      void import('../stores/session.store').then(({ useSession }) => {
        if (useSession.getState().auth === 'authenticated') {
          useSession.setState({ auth: 'anonymous' });
        }
      });
    }
    throw new ApiError(
      res.status,
      envelope.code,
      envelope.message ?? res.statusText ?? `HTTP ${res.status}`,
      envelope.details,
      (opts.silenceToast ?? false) || res.status === 401, // 401 redirects; a toast per poll is spam
    );
  }

  if (res.status === 204) return undefined as T;
  const contentType = res.headers.get('content-type') ?? '';
  if (contentType.includes('application/json')) return (await res.json()) as T;
  // Audit P1-11: a 200 that is not JSON is the gateway catch-all serving SPA index.html for a
  // path missing from the route allowlist (the recurring #404-class incident) — returning the
  // HTML as T rendered a permanent, silent empty state. Fail loud instead.
  throw new ApiError(
    res.status,
    'BAD_CONTENT_TYPE',
    `expected JSON from ${path} but got ${contentType || 'no content-type'} — is the path in the gateway route allowlist?`,
  );
}

/** Unwraps the `{items:[...]}` list envelope (signals/paper/journal/oi-analysis/fii-dii/…). */
export function listItems<T>(res: { items?: T[] } | null | undefined): T[] {
  return res?.items ?? [];
}

/**
 * Downloads a file from a GET endpoint (the CSV export standard, §10 Phase-3) via a temporary anchor.
 * Unlike {@link apiFetch} (JSON-only), this reads the response as a blob and honours the server's
 * {@code Content-Disposition} filename; the D8 error envelope still surfaces as an {@link ApiError}.
 */
export async function downloadFile(path: string): Promise<void> {
  const res = await fetch(BASE + path, { credentials: 'include' });
  if (!res.ok) {
    let envelope: ApiErrorBody = {};
    try {
      envelope = (await res.json()) as ApiErrorBody;
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(
      res.status,
      envelope.code,
      envelope.message ?? res.statusText ?? `HTTP ${res.status}`,
      envelope.details,
    );
  }
  warnIfExportTruncated(res);
  const dispo = res.headers.get('content-disposition') ?? '';
  const match = dispo.match(/filename="?([^"]+)"?/);
  const filename = match ? match[1] : 'export.csv';
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
