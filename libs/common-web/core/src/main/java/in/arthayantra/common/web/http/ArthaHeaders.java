package in.arthayantra.common.web.http;

/**
 * Canonical header and MDC names shared by the gateway and every Java service (A.2.4 / A.6.3).
 * The gateway strips any inbound {@code X-Artha-User} and injects its own; it generates one
 * {@code X-Request-Id} UUID per inbound request and forwards it; services bind both to the MDC.
 */
public final class ArthaHeaders {

  /** Gateway-injected identity header — trusted only on the private compose network. */
  public static final String X_ARTHA_USER = "X-Artha-User";

  /** Correlation id, one UUID per inbound gateway request (§12.4). */
  public static final String X_REQUEST_ID = "X-Request-Id";

  /** MDC key the identity header binds to. */
  public static final String MDC_USER = "user";

  /** MDC key the correlation id binds to. */
  public static final String MDC_REQUEST_ID = "requestId";

  private ArthaHeaders() {}
}
