package in.arthayantra.marketdata.kite.session;

import java.time.OffsetDateTime;

/** The OAuth-ritual surface behind {@code /api/v1/auth/kite} (B-1 catalog rows, Phase 12). */
public interface KiteSessionService {

  /** Status payload (B-1: connected, validity, ticker + circuit state; canary fields Phase 16). */
  record KiteStatus(
      boolean connected,
      String profile,
      String state,
      String kiteUserId,
      OffsetDateTime tokenValidUntil,
      OffsetDateTime lastValidatedAt,
      String tickerState,
      String circuitState) {}

  /** A completed exchange. */
  record ExchangeResult(boolean connected, String kiteUserId, OffsetDateTime tokenValidUntil) {}

  /** The Zerodha login URL embedding the API key. */
  String loginUrl();

  /** Exchanges a single-use request token, encrypts + persists, flips status CONNECTED. */
  ExchangeResult exchange(String requestToken);

  /** Current session health. */
  KiteStatus status();

  /** Drops the stored token (204 surface). */
  void invalidate();
}
