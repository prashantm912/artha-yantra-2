package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Hand-rolled Upstox <b>Expired-Instruments</b> REST client (ADR-0002 anti-corruption pattern, same
 * as {@link UpstoxAnalyticsClient}; the broker SDK is never imported). Wraps the four v2 endpoints
 * that the Upstox Plus plan exposes for EXPIRED F&amp;O contracts — the only source of expired-contract
 * per-minute OHLCV+OI, used to backfill the {@code candles} hypertable for backtesting:
 *
 * <ul>
 *   <li>{@code GET /v2/expired-instruments/expiries} — expiry dates for an underlying
 *   <li>{@code GET /v2/expired-instruments/option/contract} — the CE/PE roster for one expiry
 *   <li>{@code GET /v2/expired-instruments/future/contract} — the future(s) for one expiry
 *   <li>{@code GET /v2/expired-instruments/historical-candle/{key}/{interval}/{to}/{from}} — bars
 * </ul>
 *
 * <p>v2-only (v3 history is live-instrument-only); requires the dedicated Upstox Plus analytics token
 * (read-only/daily-OAuth tokens are rejected with UDAPI100067). HTTP/transport errors propagate so the
 * orchestrating backfill decides whether to skip a single leg and continue.
 */
public final class UpstoxExpiredInstrumentsClient {

  /** 429 backoff: Upstox throttles by burst; on UDAPI10005 we honor Retry-After, else exponential. */
  private static final int MAX_429_RETRIES = 5;
  private static final long BASE_BACKOFF_MS = 1_000;
  private static final long MAX_BACKOFF_MS = 16_000;

  private final RestClient restClient;
  private final UpstoxAnalyticsProperties properties;
  /**
   * The token-scoped budget shared across EVERY Upstox analytics-token client (EXT-02). This is the
   * heavy backfill walker, so it draws from the BATCH ceiling ({@link UpstoxRateLimiter#acquireForBatch()})
   * — it can never consume the headroom the live capture / quote / margin path reserves.
   */
  private final UpstoxRateLimiter limiter;

  /** Binds the wire client to the configured base URL (real Upstox, or WireMock in tests). */
  public UpstoxExpiredInstrumentsClient(
      RestClient.Builder builder, UpstoxAnalyticsProperties properties, UpstoxRateLimiter limiter) {
    // Bounded timeouts so a throttled/dead Upstox call fails fast (caught per-leg) rather than
    // parking a backfill worker forever at the run's barrier.
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(15_000);
    factory.setReadTimeout(45_000);
    this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    this.properties = properties;
    this.limiter = limiter;
  }

  /** One expired contract's spec — the orchestration's unit of work (a CE/PE leg or a future). */
  public record Leg(
      String segment,
      String instrumentKey,
      String instrumentType,
      String underlyingSymbol,
      String underlyingKey,
      LocalDate expiry,
      BigDecimal strike,
      int lotSize,
      BigDecimal tickSize,
      boolean weekly) {}

  /** One OHLCV+OI bar from the expired-candle series (IST bucket; OI may be null). */
  public record Bar(
      OffsetDateTime bucket,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      long volume,
      Long oi) {}

  /** All expiry dates Upstox holds for an underlying ({@code instrument_key} e.g. {@code NSE_INDEX|Nifty 50}). */
  public List<LocalDate> expiries(String underlyingKey) {
    UpstoxExpiry response =
        withRetry(
            () ->
                restClient
                    .get()
                    .uri(b -> b.path("/v2/expired-instruments/expiries").queryParam("instrument_key", underlyingKey).build())
                    .header("Authorization", "Bearer " + properties.resolveToken())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(UpstoxExpiry.class),
            true);
    if (response == null || response.data() == null) {
      return List.of();
    }
    return response.data().stream().map(LocalDate::parse).toList();
  }

  /** The CE/PE roster for one (underlying, expiry). */
  public List<Leg> optionContracts(String underlyingKey, LocalDate expiry) {
    return contracts("option", underlyingKey, expiry, null);
  }

  /** The future(s) for one (underlying, expiry) — empty on a weekly date (futures are monthly). */
  public List<Leg> futureContracts(String underlyingKey, LocalDate expiry) {
    return contracts("future", underlyingKey, expiry, "FUT");
  }

  private List<Leg> contracts(
      String kind, String underlyingKey, LocalDate expiry, String typeOverride) {
    UpstoxExpiredContract response =
        withRetry(
            () ->
                restClient
                    .get()
                    .uri(
                        b ->
                            b.path("/v2/expired-instruments/" + kind + "/contract")
                                .queryParam("instrument_key", underlyingKey)
                                .queryParam("expiry_date", expiry.toString())
                                .build())
                    .header("Authorization", "Bearer " + properties.resolveToken())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(UpstoxExpiredContract.class),
            true);
    if (response == null || response.data() == null) {
      return List.of();
    }
    List<Leg> legs = new ArrayList<>(response.data().size());
    for (UpstoxExpiredContract.Contract c : response.data()) {
      legs.add(
          new Leg(
              c.segment(),
              c.instrumentKey(),
              typeOverride != null ? typeOverride : c.instrumentType(),
              c.underlyingSymbol(),
              c.underlyingKey() != null ? c.underlyingKey() : underlyingKey,
              c.expiry() != null ? LocalDate.parse(c.expiry()) : expiry,
              c.strikePrice(),
              c.lotSize() != null ? c.lotSize() : 0,
              c.tickSize(),
              Boolean.TRUE.equals(c.weekly())));
    }
    return legs;
  }

  /**
   * OHLCV+OI bars for one expired contract over {@code [from, to]} inclusive. {@code interval} is the
   * Upstox token ({@code 1minute}, {@code day}, …); {@code expiredKey} is the contract's
   * {@code instrument_key} (the {@code |} is URL-encoded by the path expansion).
   */
  public List<Bar> candles(String expiredKey, String interval, LocalDate from, LocalDate to) {
    UpstoxExpiredCandles response =
        withRetry(
            () ->
                restClient
                    .get()
                    .uri(
                        b ->
                            b.path("/v2/expired-instruments/historical-candle/{key}/{interval}/{to}/{from}")
                                .build(expiredKey, interval, to.toString(), from.toString()))
                    .header("Authorization", "Bearer " + properties.resolveToken())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(UpstoxExpiredCandles.class),
            true);
    return toBars(response);
  }

  /**
   * OHLCV+OI bars for an ACTIVE instrument over {@code [from, to]} inclusive — the {@code
   * expired-instruments/}-less sibling of {@link #candles} ({@code GET
   * /v2/historical-candle/{key}/{interval}/{to}/{from}}), identical wire shape + Plus token. Used by
   * the E1 Market-Movers screener to read a live stock FUTURE's daily OHLC+OI on-demand (no capture, no
   * storage). {@code instrumentKey} is the active contract's {@code instrument_key}.
   */
  public List<Bar> activeCandles(String instrumentKey, String interval, LocalDate from, LocalDate to) {
    UpstoxExpiredCandles response =
        withRetry(
            () ->
                restClient
                    .get()
                    .uri(
                        b ->
                            b.path("/v2/historical-candle/{key}/{interval}/{to}/{from}")
                                .build(instrumentKey, interval, to.toString(), from.toString()))
                    .header("Authorization", "Bearer " + properties.resolveToken())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(UpstoxExpiredCandles.class),
            false);
    return toBars(response);
  }

  /**
   * TODAY's bars for an ACTIVE instrument — {@code GET /v3/historical-candle/intraday/{key}/{unit}/
   * {interval}} (the v2 historical endpoint serves completed days only). Same candle-array wire
   * shape (col 6 = OI — live-probed 2026-07-02 on an unexpired stock option: 375 1-minute bars with
   * real OI). {@code unit} e.g. {@code minutes}, {@code interval} e.g. {@code 1}.
   */
  public List<Bar> intradayCandles(String instrumentKey, String unit, int interval) {
    UpstoxExpiredCandles response =
        withRetry(
            () ->
                restClient
                    .get()
                    .uri(
                        b ->
                            b.path("/v3/historical-candle/intraday/{key}/{unit}/{interval}")
                                .build(instrumentKey, unit, String.valueOf(interval)))
                    .header("Authorization", "Bearer " + properties.resolveToken())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(UpstoxExpiredCandles.class),
            false);
    return toBars(response);
  }

  /** Parse the Upstox candle-array response into {@link Bar}s (col 6 = OI, may be absent/null). */
  private static List<Bar> toBars(UpstoxExpiredCandles response) {
    if (response == null || response.data() == null || response.data().candles() == null) {
      return List.of();
    }
    List<Bar> bars = new ArrayList<>(response.data().candles().size());
    for (List<JsonNode> c : response.data().candles()) {
      Long oi = c.size() > 6 && !c.get(6).isNull() ? c.get(6).asLong() : null;
      bars.add(
          new Bar(
              OffsetDateTime.parse(c.get(0).asText()),
              new BigDecimal(c.get(1).asText()),
              new BigDecimal(c.get(2).asText()),
              new BigDecimal(c.get(3).asText()),
              new BigDecimal(c.get(4).asText()),
              c.get(5).asLong(),
              oi));
    }
    return bars;
  }

  /**
   * Runs a call, retrying on Upstox's {@code 429 Too Many Requests} (UDAPI10005) — the token is
   * throttled by burst, so a brief backoff (the {@code Retry-After} header if present, else 1→16s
   * exponential) lets it succeed instead of failing the leg. After {@value #MAX_429_RETRIES} retries
   * the 429 propagates (the leg fails + is retried next run). Other errors propagate immediately.
   */
  private <T> T withRetry(Supplier<T> call, boolean batch) {
    int attempt = 0;
    while (true) {
      // The heavy expired-contract WALK ({@code batch}) draws from the batch ceiling AND pauses while
      // the market is open (a background job that yields the token to live capture during the session).
      // The on-demand E1 Market-Movers reads use the bounded LIVE path so a user radar never pauses for
      // the whole session — the off-hours reserve keeps them headroom so this effectively never throws.
      if (batch) {
        limiter.acquireForBatch();
      } else if (!limiter.tryAcquire()) {
        throw new IllegalStateException("Upstox rate budget exhausted");
      }
      try {
        return call.get();
      } catch (HttpClientErrorException.TooManyRequests e) {
        if (++attempt > MAX_429_RETRIES) {
          throw e;
        }
        sleep(backoffMillis(attempt, e));
      }
    }
  }

  private static long backoffMillis(int attempt, HttpClientErrorException e) {
    String retryAfter =
        e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
    if (retryAfter != null) {
      try {
        return Long.parseLong(retryAfter.trim()) * 1_000L;
      } catch (NumberFormatException ignored) {
        // not a seconds value — fall through to exponential backoff
      }
    }
    return Math.min(BASE_BACKOFF_MS << (attempt - 1), MAX_BACKOFF_MS);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted during 429 backoff", ie);
    }
  }
}
