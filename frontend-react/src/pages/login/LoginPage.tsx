import { useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useSession } from '../../stores/session.store.ts';

// Login page (master plan §20 / §10.3). Keeps input[name="password"] + a visible "Sign in" button so
// the e2e loginThroughForm helper keeps working. probe() on boot already seeded the XSRF cookie so
// this mutating POST carries X-XSRF-TOKEN.
export function LoginPage() {
  const login = useSession((s) => s.login);
  const loggingIn = useSession((s) => s.loggingIn);
  const loginError = useSession((s) => s.loginError);
  const [password, setPassword] = useState('');
  const navigate = useNavigate();
  const location = useLocation() as { state?: { returnUrl?: string } };

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    const ok = await login(password);
    if (ok) navigate(location.state?.returnUrl ?? '/', { replace: true });
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-surface-0 p-4">
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm rounded-lg border border-ay-border bg-surface-1 p-6 shadow"
      >
        <h1 className="mb-4 text-lg font-semibold text-ay-text">Sign in</h1>
        <label htmlFor="password" className="mb-1 block text-sm text-ay-muted">
          Password
        </label>
        <input
          id="password"
          name="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="mb-4 w-full rounded border border-ay-border bg-surface-2 px-3 py-2 text-ay-text outline-none focus:border-accent"
        />
        {loginError && (
          <p role="alert" className="mb-3 text-sm text-bear">
            {loginError}
          </p>
        )}
        <button
          type="submit"
          disabled={loggingIn}
          className="w-full rounded bg-accent px-3 py-2 font-medium text-surface-0 disabled:opacity-60"
        >
          {loggingIn ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </main>
  );
}
