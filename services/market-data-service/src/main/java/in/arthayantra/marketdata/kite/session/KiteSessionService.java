package in.arthayantra.marketdata.kite.session;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** The OAuth-ritual surface behind {@code /api/v1/auth/kite} (B-1 catalog rows, Phase 12). */
public interface KiteSessionService {

  /** Status payload (B-1: connected, validity, ticker + circuit state, B-9 canary fields). */
  record KiteStatus(
      boolean connected,
      String profile,
      String state,
      @Schema(types = {"string", "null"}) String kiteUserId,
      @Schema(types = {"string", "null"}) OffsetDateTime tokenValidUntil,
      @Schema(types = {"string", "null"}) OffsetDateTime lastValidatedAt,
      String tickerState,
      String circuitState,
      @Schema(types = {"string", "null"}) OffsetDateTime lastContractCheck,
      java.util.List<String> contractDrift) {}

  /**
   * A completed exchange.
   *
   * <p>{@code @Schema(name)} is load-bearing: {@code BhavcopyBackfillService.ExchangeResult} shares
   * this simple name with a COMPLETELY different field set ({@code days}/{@code bhavRows}/{@code
   * candleRows}), and springdoc keys components by simple name — the bhavcopy twin won the scan, so
   * {@code POST /api/v1/auth/kite/session} published bhavcopy's three int fields and none of its own.
   *
   * <p>{@code kiteUserId} rides the Kite session-exchange wire, which enforces no required field —
   * the sibling {@link KiteStatus#kiteUserId()} was ruled nullable on the same grounds (#1003).
   * {@code tokenValidUntil} is NOT annotated: {@code KiteSessionStore.store} sets {@code encryptedAt}
   * unconditionally (:76) immediately before the only construction site
   * (LiveKiteSessionService:59), so the accessor's {@code encryptedAt == null} branch (:131-133) is
   * dead here — unlike on {@code KiteStatus}, which is served without a preceding store.
   */
  @Schema(name = "KiteSessionExchangeResult")
  record ExchangeResult(
      boolean connected,
      @Schema(types = {"string", "null"}) String kiteUserId,
      OffsetDateTime tokenValidUntil) {}

  /** The Zerodha login URL embedding the API key. */
  String loginUrl();

  /** Exchanges a single-use request token, encrypts + persists, flips status CONNECTED. */
  ExchangeResult exchange(String requestToken);

  /** Current session health. */
  KiteStatus status();

  /** Drops the stored token (204 surface). */
  void invalidate();
}
