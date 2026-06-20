// Typed fetch wrapper (master plan §20 / §10.2): same-origin `/api/v1`, XSRF echo, the D8 error
// envelope → ApiError, and the {items} list helper. Replaces Angular's HttpClient + error.interceptor.

const BASE = '/api/v1';

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
    throw new ApiError(
      res.status,
      envelope.code,
      envelope.message ?? res.statusText ?? `HTTP ${res.status}`,
      envelope.details,
      opts.silenceToast ?? false,
    );
  }

  if (res.status === 204) return undefined as T;
  const contentType = res.headers.get('content-type') ?? '';
  if (contentType.includes('application/json')) return (await res.json()) as T;
  return (await res.text()) as unknown as T;
}

/** Unwraps the `{items:[...]}` list envelope (signals/paper/journal/oi-analysis/fii-dii/…). */
export function listItems<T>(res: { items?: T[] } | null | undefined): T[] {
  return res?.items ?? [];
}
