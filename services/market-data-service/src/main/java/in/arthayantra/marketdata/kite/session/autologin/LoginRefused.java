package in.arthayantra.marketdata.kite.session.autologin;

import java.io.Serial;

/**
 * A single auto-login attempt that did not produce a {@code request_token}, carrying the step and
 * the closed-set reason.
 *
 * <p>⚠️ The message is built ONLY from the step, the reason and (where relevant) an HTTP status
 * CODE. It never carries a request body, a response body, a header or an upstream exception
 * message: {@code HttpClientErrorException.getMessage()} embeds the response body, and a Zerodha
 * error page is exactly the kind of thing that can echo a submitted field back. Everything that
 * logs or alerts on this exception logs the message, so the message is the containment boundary.
 */
public final class LoginRefused extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final LoginWireClient.Step step;
  private final LoginRefusal refusal;

  /** Builds a refusal whose message is safe to log verbatim. */
  public LoginRefused(LoginWireClient.Step step, LoginRefusal refusal, String detail) {
    super("kite auto-login refused at " + step + ": " + refusal + " (" + detail + ")");
    this.step = step;
    this.refusal = refusal;
  }

  /** Which step refused. */
  public LoginWireClient.Step step() {
    return step;
  }

  /** Why — the metric tag and the alert class. */
  public LoginRefusal refusal() {
    return refusal;
  }
}
