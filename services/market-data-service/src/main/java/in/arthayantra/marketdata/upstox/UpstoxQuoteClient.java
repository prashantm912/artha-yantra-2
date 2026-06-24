package in.arthayantra.marketdata.upstox;

import in.arthayantra.marketdata.upstox.wire.UpstoxMarketQuote;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Direct-Upstox LIVE market-quote client for Wave U2 spot/FUT quotes (ADR-0002 anti-corruption
 * pattern, the SAME hand-rolled {@code RestClient} shape as {@link UpstoxOptionChainClient}; the
 * broker SDK is never imported). One BATCH call — {@code GET
 * /v2/market-quote/quotes?instrument_key=&lt;csv&gt;} — returns the full quote (LTP + day OHLC +
 * volume + OI + net_change + best bid/ask depth) for up to ~500 instruments at once, on the long-lived
 * 1-yr analytics token (proven login-free 2026-06-24). The batch endpoint is used directly — NO
 * one-key fan-out.
 *
 * <p>It deserializes the raw {@code upstox.wire} DTO and maps it to its OWN top-level {@link Tick}
 * record keyed by the Upstox {@code instrument_key}. The {@code upstox.wire} package is
 * module-internal — only the top-level {@code upstox} package is this module's exposed API — so the
 * {@code QuoteGateway} adapter consumes {@link Tick}, never the wire DTO (no module-boundary
 * violation), and the client stays free of the {@code kite} domain port (the adapter maps {@link Tick}
 * to the domain {@code Quote}).
 */
public final class UpstoxQuoteClient {

  private final RestClient restClient;
  private final UpstoxAnalyticsProperties properties;

  /** Binds the wire client to the configured base URL (real Upstox, or WireMock in tests). */
  public UpstoxQuoteClient(RestClient.Builder builder, UpstoxAnalyticsProperties properties) {
    // Bounded timeouts so a throttled/dead Upstox call fails fast (the quote pass logs + skips) rather
    // than parking the caller.
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(15_000);
    factory.setReadTimeout(45_000);
    this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    this.properties = properties;
  }

  /** One instrument's mapped quote — the raw fields the quote seam consumes (greeks-free). */
  public record Tick(
      BigDecimal lastPrice,
      BigDecimal bid,
      BigDecimal ask,
      Long volume,
      Long oi,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal prevClose,
      OffsetDateTime timestamp) {}

  /**
   * The full market quote for a BATCH of Upstox {@code instrumentKeys} (e.g. {@code NSE_INDEX|Nifty
   * 50}) in ONE call. Returns a map keyed by the SAME request {@code instrument_key} (Upstox returns
   * the data map under a {@code :}-delimited variant of the key — e.g. {@code NSE_INDEX:Nifty 50} — so
   * the result is re-keyed back to the request form by symbol match). Absent / unmatched keys are
   * omitted (mirrors the {@code QuoteGateway} contract). An empty request short-circuits to an empty
   * map; transport / HTTP errors propagate so the caller decides whether to fall back.
   */
  public Map<String, Tick> quotes(List<String> instrumentKeys) {
    if (instrumentKeys == null || instrumentKeys.isEmpty()) {
      return Map.of();
    }
    String csv = String.join(",", instrumentKeys);
    UpstoxMarketQuote response =
        restClient
            .get()
            .uri(b -> b.path("/v2/market-quote/quotes").queryParam("instrument_key", csv).build())
            .header("Authorization", "Bearer " + properties.resolveToken())
            .header("Accept", "application/json")
            .retrieve()
            .body(UpstoxMarketQuote.class);
    if (response == null || response.data() == null || response.data().isEmpty()) {
      return Map.of();
    }

    // Upstox keys the data map by a ':'-delimited variant of the request key (e.g. request
    // "NSE_INDEX|Nifty 50" -> response key "NSE_INDEX:Nifty 50"). Re-key back to the request form so
    // the adapter's instrument-key lookup matches exactly.
    Map<String, String> byNormalized = new LinkedHashMap<>();
    for (String requested : instrumentKeys) {
      byNormalized.put(normalize(requested), requested);
    }
    Map<String, Tick> out = new LinkedHashMap<>();
    for (Map.Entry<String, UpstoxMarketQuote.Tick> entry : response.data().entrySet()) {
      String requested = byNormalized.get(normalize(entry.getKey()));
      if (requested != null) {
        out.put(requested, toTick(entry.getValue()));
      }
    }
    return out;
  }

  /** Folds the request/response delimiter difference ({@code |} vs {@code :}) for key matching. */
  private static String normalize(String key) {
    return key.replace('|', ':');
  }

  /** Maps one wire {@code data} value to {@link Tick} (null-safe; depth top-of-book → bid/ask). */
  private static Tick toTick(UpstoxMarketQuote.Tick wire) {
    UpstoxMarketQuote.Ohlc ohlc = wire.ohlc();
    return new Tick(
        wire.lastPrice(),
        bestPrice(wire.depth() == null ? null : wire.depth().buy()),
        bestPrice(wire.depth() == null ? null : wire.depth().sell()),
        wire.volume(),
        wire.oi(),
        ohlc == null ? null : ohlc.open(),
        ohlc == null ? null : ohlc.high(),
        ohlc == null ? null : ohlc.low(),
        ohlc == null ? null : ohlc.close(),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  /** Best price of a depth side (first level), or {@code null} when the side is absent/empty. */
  private static BigDecimal bestPrice(List<UpstoxMarketQuote.Level> side) {
    if (side == null || side.isEmpty()) {
      return null;
    }
    return side.get(0).price();
  }
}
