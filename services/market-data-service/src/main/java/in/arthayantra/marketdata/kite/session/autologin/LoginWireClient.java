package in.arthayantra.marketdata.kite.session.autologin;

/**
 * The browser leg of the daily Kite ritual, behind a seam so it can be stood in for by WireMock —
 * the same split as {@code SessionWireClient}/{@code LiveSessionWireClient}, which exists because
 * the Kite SDK pins {@code Routes._rootUrl} and is therefore unstubbable.
 *
 * <p>Produces exactly one thing: the single-use {@code request_token} that the EXISTING
 * {@code KiteSessionService.exchange()} already knows how to spend. Nothing about the token
 * exchange, storage or feed re-arm is reimplemented here.
 */
public interface LoginWireClient {

  /** Which of the three steps a refusal happened on — a metric/alert tag, not free text. */
  enum Step {
    /** The credential POST that yields a {@code request_id}. */
    CREDENTIALS,
    /** The TOTP POST that establishes the session cookies. */
    TWOFA,
    /** The authorize GET whose redirect carries the {@code request_token}. */
    AUTHORIZE
  }

  /**
   * Runs the three steps once and returns the {@code request_token}.
   *
   * <p>⚠️ ONE attempt. This method never retries internally — see {@link LoginRefusal#retryable()}
   * for why, and {@code KiteAutoLoginService} for the single, delayed, transport-only re-attempt
   * that lives one level up where it can be counted and alerted on.
   *
   * @throws LoginRefused on every failure, carrying the step and the closed-set reason
   */
  String fetchRequestToken();
}
