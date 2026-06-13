import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionStore } from './session.store';

/** Auth-guards everything except /login against the gateway session (C-2.24). */
export const authGuard: CanActivateFn = async (_route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  if (session.authenticated()) {
    return true;
  }
  const ok = await session.probe();
  return ok ? true : router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};
