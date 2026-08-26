package in.arthayantra.marketdata.kite.session.autologin;

/**
 * Why an auto-login attempt did not produce a {@code request_token} — a CLOSED set, because it is
 * a metric tag ({@code ay_kite_auto_login_refused_total{reason=...}}) and an unbounded tag is a
 * cardinality leak.
 *
 * <p>⚠️ {@link #retryable()} is the whole failure doctrine in one method. Only a TRANSPORT-level
 * failure — the request never reached a verdict — may be re-attempted, and even then exactly once
 * (see {@code KiteAutoLoginService}). A credential or TOTP refusal is a VERDICT: Zerodha looked at
 * what we sent and said no, so trying again sends the same wrong material again. A wrong-password
 * loop locks the BROKER ACCOUNT, and a locked broker account on a market morning is strictly worse
 * than the manual ritual this replaces. The codebase already encodes this one step along —
 * {@code LiveSessionWireClient:63} "re-login is the only cure, never retry".
 */
public enum LoginRefusal {

  /** Step 1 answered 4xx: the user id or password was rejected. TERMINAL for the day. */
  CREDENTIAL_REJECTED,

  /** Step 2 answered 4xx: the TOTP code was rejected. TERMINAL — never re-sent. */
  TOTP_REJECTED,

  /** Step 3 answered 4xx: the authorize call was rejected. TERMINAL. */
  AUTHORIZE_REJECTED,

  /**
   * A 2xx/3xx whose SHAPE we could not read — no {@code request_id}, no redirect, a redirect with
   * no {@code request_token}. This is the "Zerodha changed something" signature and it is
   * deliberately TERMINAL and loud: these endpoints are undocumented, so a shape change is the
   * most likely way this feature breaks, and retrying an unreadable flow cannot help.
   */
  UNEXPECTED_RESPONSE,

  /** A credential file was missing, blank or unparseable. TERMINAL — nothing was ever sent. */
  SECRET_UNREADABLE,

  /** The request never completed (connect/read failure). Transport, not a verdict — retryable. */
  NETWORK,

  /** Zerodha answered 5xx. Their side, not our material — retryable. */
  UPSTREAM_ERROR;

  /** Whether ONE delayed re-attempt is permitted. True only where no verdict was reached. */
  public boolean retryable() {
    return this == NETWORK || this == UPSTREAM_ERROR;
  }
}
