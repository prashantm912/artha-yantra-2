import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MessageService } from 'primeng/api';
import { catchError, throwError } from 'rxjs';

/**
 * Set on fire-and-forget housekeeping calls (e.g. UI ticker (un)subscription) whose failure has no
 * user-facing meaning — the caller already degrades gracefully — so a NOT_FOUND_INSTRUMENT 404 must
 * not pop a toast.
 */
export const SILENCE_ERROR_TOAST = new HttpContextToken<boolean>(() => false);

/**
 * THE one place the D8 error envelope {code, message, details} becomes a Toast (C-2.22).
 * Session-probe 401s stay silent - an anonymous user is a state, not an error.
 */
export const errorEnvelopeInterceptor: HttpInterceptorFn = (req, next) => {
  const toast = inject(MessageService);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse) {
        const probe = req.url.includes('/auth/session') && error.status === 401;
        if (!probe && !req.context.get(SILENCE_ERROR_TOAST)) {
          const body = error.error as { code?: string; message?: string } | null;
          toast.add({
            severity: 'error',
            summary: body?.code ?? `HTTP ${error.status}`,
            detail: body?.message ?? error.message,
            life: 6000,
          });
        }
      }
      return throwError(() => error);
    }),
  );
};
