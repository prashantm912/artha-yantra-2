package in.arthayantra.marketdata.kite.session;

/**
 * The two Kite session wire calls (B-2). Deliberately NOT routed through the retry stack:
 * {@code POST /session/token} spends a single-use request token (non-idempotent — a retry can
 * never succeed) and the 5-min profile probe is classification, not exception flow.
 */
public interface SessionWireClient {

  /** A successful exchange. */
  record TokenSession(String accessToken, String kiteUserId) {}

  /** Probe classification (B-2 step 4). */
  enum ProbeResult {
    VALID,
    EXPIRED,
    ERROR
  }

  /** Exchanges a single-use request token for an access token (SHA-256 checksum on the wire). */
  TokenSession exchange(String requestToken);

  /** Lightweight {@code getProfile} health probe. */
  ProbeResult probe(String accessToken);
}
