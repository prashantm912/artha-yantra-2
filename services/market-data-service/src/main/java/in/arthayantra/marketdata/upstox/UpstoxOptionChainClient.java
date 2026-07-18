package in.arthayantra.marketdata.upstox;

import in.arthayantra.marketdata.upstox.wire.UpstoxOptionChain;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Direct-Upstox LIVE option-chain client for Wave U1 OI capture (ADR-0002 anti-corruption pattern,
 * the SAME hand-rolled {@code RestClient} shape as {@link UpstoxExpiredInstrumentsClient}; the broker
 * SDK is never imported). One call — {@code GET /v2/option/chain?instrument_key=&lt;key&gt;
 * &amp;expiry_date=yyyy-MM-dd} — returns the WHOLE live chain for an (underlying, expiry): per-strike
 * CE/PE LTP+OI+prev_oi+bid/ask/volume plus the underlying spot, on the long-lived 1-yr analytics
 * token (proven login-free 2026-06-24).
 *
 * <p>It deserializes the raw {@code upstox.wire} DTO and maps it to its OWN top-level {@link Chain}
 * record. The {@code upstox.wire} package is module-internal — only the top-level {@code upstox}
 * package is this module's exposed API — so the {@code options}-package adapter consumes {@link
 * Chain}, never the wire DTO (no module-boundary violation). Upstox-computed greeks/IV are
 * deliberately dropped: black76 stays the IV/greeks source of record; this client carries only the
 * raw LTP+OI+spot the chain pipeline needs.
 */
public final class UpstoxOptionChainClient {

  private final RestClient restClient;
  private final UpstoxAnalyticsProperties properties;
  /** The token-scoped budget shared across every analytics-token client (EXT-02); live path (full cap). */
  private final UpstoxRateLimiter limiter;

  /** Binds the wire client to the configured base URL (real Upstox, or WireMock in tests). */
  public UpstoxOptionChainClient(
      RestClient.Builder builder, UpstoxAnalyticsProperties properties, UpstoxRateLimiter limiter) {
    // Bounded timeouts so a throttled/dead Upstox call fails fast (the snapshot pass logs + skips the
    // expiry) rather than parking the single-threaded snapshotter.
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(15_000);
    factory.setReadTimeout(45_000);
    this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    this.properties = properties;
    this.limiter = limiter;
  }

  /** One strike side's live market data (the raw fields the chain pipeline consumes). */
  public record Leg(
      BigDecimal ltp, BigDecimal bid, BigDecimal ask, Long volume, Long oi, Long prevOi) {}

  /** The mapped chain for one (underlying, expiry): spot + a CE/PE {@link Leg} per strike. */
  public record Chain(BigDecimal spot, Map<BigDecimal, Leg> ce, Map<BigDecimal, Leg> pe) {}

  /**
   * The whole live chain for one (underlying {@code instrumentKey}, {@code expiry}) — one call,
   * mapped to {@link Chain}. The {@code instrumentKey} is the Upstox key (e.g. {@code NSE_INDEX|Nifty
   * 50}); the {@code |} is URL-encoded by the query expansion. Returns {@code null} when Upstox has no
   * data (a {@code null} / empty {@code data}); transport / HTTP errors propagate so the caller skips
   * the expiry.
   */
  public Chain optionChain(String instrumentKey, LocalDate expiry) {
    limiter.acquire(); // live-critical OI capture — draws from the token's live-reserved headroom
    UpstoxOptionChain response =
        restClient
            .get()
            .uri(
                b ->
                    b.path("/v2/option/chain")
                        .queryParam("instrument_key", instrumentKey)
                        .queryParam("expiry_date", expiry.toString())
                        .build())
            .header("Authorization", "Bearer " + properties.resolveToken())
            .header("Accept", "application/json")
            .retrieve()
            .body(UpstoxOptionChain.class);
    if (response == null || response.data() == null || response.data().isEmpty()) {
      return null;
    }

    BigDecimal spot = null;
    Map<BigDecimal, Leg> ce = new LinkedHashMap<>();
    Map<BigDecimal, Leg> pe = new LinkedHashMap<>();
    for (UpstoxOptionChain.Row row : response.data()) {
      if (spot == null && row.underlyingSpotPrice() != null) {
        spot = row.underlyingSpotPrice(); // identical across rows — take the first non-null
      }
      if (row.strikePrice() == null) {
        continue;
      }
      // canonical scale-2 strike key (Upstox sends 25000.0, the instrument master 25000.00; some
      // stock strikes are X.50) so the adapter's strike-keyed lookup is scale-insensitive —
      // BigDecimal map keys compare by equals, which is scale-sensitive.
      BigDecimal strike = row.strikePrice().setScale(2, RoundingMode.HALF_UP);
      Leg ceLeg = toLeg(row.callOptions());
      Leg peLeg = toLeg(row.putOptions());
      if (ceLeg != null) {
        ce.put(strike, ceLeg);
      }
      if (peLeg != null) {
        pe.put(strike, peLeg);
      }
    }
    return new Chain(spot, ce, pe);
  }

  /** Maps one wire {@code call_options}/{@code put_options} leg to {@link Leg} (null-safe). */
  private static Leg toLeg(UpstoxOptionChain.Option option) {
    if (option == null || option.marketData() == null) {
      return null;
    }
    UpstoxOptionChain.MarketData md = option.marketData();
    return new Leg(md.ltp(), md.bidPrice(), md.askPrice(), md.volume(), md.oi(), md.prevOi());
  }
}
