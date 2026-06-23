package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

  private final RestClient restClient;
  private final UpstoxAnalyticsProperties properties;

  /** Binds the wire client to the configured base URL (real Upstox, or WireMock in tests). */
  public UpstoxExpiredInstrumentsClient(
      RestClient.Builder builder, UpstoxAnalyticsProperties properties) {
    this.restClient = builder.baseUrl(properties.baseUrl()).build();
    this.properties = properties;
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
        restClient
            .get()
            .uri(b -> b.path("/v2/expired-instruments/expiries").queryParam("instrument_key", underlyingKey).build())
            .header("Authorization", "Bearer " + properties.resolveToken())
            .header("Accept", "application/json")
            .retrieve()
            .body(UpstoxExpiry.class);
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
            .body(UpstoxExpiredContract.class);
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
        restClient
            .get()
            .uri(
                b ->
                    b.path("/v2/expired-instruments/historical-candle/{key}/{interval}/{to}/{from}")
                        .build(expiredKey, interval, to.toString(), from.toString()))
            .header("Authorization", "Bearer " + properties.resolveToken())
            .header("Accept", "application/json")
            .retrieve()
            .body(UpstoxExpiredCandles.class);
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
}
