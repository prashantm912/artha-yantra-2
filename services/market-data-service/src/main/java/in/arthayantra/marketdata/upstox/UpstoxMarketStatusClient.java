package in.arthayantra.marketdata.upstox;

import in.arthayantra.marketdata.upstox.wire.UpstoxMarketQuote;
import in.arthayantra.marketdata.upstox.wire.UpstoxMarketStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Hand-rolled Upstox <b>market-status + index pre-open</b> client for the Pre-Open Market page (ADR-0002
 * anti-corruption pattern, the SAME shape as {@link UpstoxGlobalInstrumentsClient} + {@link
 * UpstoxQuoteClient}; the broker SDK is never imported). Two seams on the 1-yr analytics Bearer token:
 *
 * <ul>
 *   <li>{@link #marketStatuses()} — one {@code GET /v2/market/status/{exchange}} per exchange
 *       ({@code NSE} / {@code BSE} / {@code MCX}), each mapped to a {@link MarketStatus} (the raw phase
 *       enum + the derived {@code preOpen} boolean = phase starts with {@code "PRE_OPEN"}). A failed
 *       exchange degrades to status {@code "UNKNOWN"} (never throws), so the banner always renders.
 *   <li>{@link #preOpen()} — the NSE phase plus one batched {@code GET /v2/market-quote/quotes} call
 *       for the four index keys (Nifty 50 / Nifty Bank / Nifty Fin Service / Sensex), each mapped to a
 *       {@link PreOpenIndex}. The quotes response keys the {@code data} map by a {@code :}-delimited
 *       variant of the request {@code |} key (e.g. request {@code NSE_INDEX|Nifty 50} → response key
 *       {@code NSE_INDEX:Nifty 50}), so the join folds that delimiter difference.
 * </ul>
 *
 * <p>Errors degrade gracefully — a failed status call yields {@code UNKNOWN}, a failed quote batch
 * yields index rows with {@code null} prices. Nothing here ever 500s the page.
 */
public final class UpstoxMarketStatusClient {

  /** The exchanges whose session phase the status banner shows, in display order. */
  static final List<String> EXCHANGES = List.of("NSE", "BSE", "MCX");

  /** The four index keys (request {@code |} form) for the pre-open snapshot, in display order. */
  static final List<IndexKey> INDEX_KEYS =
      List.of(
          new IndexKey("Nifty 50", "NSE_INDEX|Nifty 50"),
          new IndexKey("Nifty Bank", "NSE_INDEX|Nifty Bank"),
          new IndexKey("Nifty Fin Service", "NSE_INDEX|Nifty Fin Service"),
          new IndexKey("Sensex", "BSE_INDEX|SENSEX"));

  /** A display name paired with its Upstox request instrument key. */
  record IndexKey(String name, String key) {}

  /** 429 backoff: honor Retry-After if present, else 1→16s exponential. */
  private static final int MAX_429_RETRIES = 5;
  private static final long BASE_BACKOFF_MS = 1_000;
  private static final long MAX_BACKOFF_MS = 16_000;

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private static final Logger log = LoggerFactory.getLogger(UpstoxMarketStatusClient.class);

  private final RestClient restClient;
  private final UpstoxAnalyticsProperties properties;
  private final UpstoxRateLimiter limiter = new UpstoxRateLimiter();

  /** Binds the wire client to the configured API base URL (real Upstox, or WireMock in tests). */
  public UpstoxMarketStatusClient(RestClient.Builder builder, UpstoxAnalyticsProperties properties) {
    // Bounded timeouts so a throttled/dead Upstox call fails fast (the seam logs + degrades) rather
    // than parking the page.
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(15_000);
    factory.setReadTimeout(45_000);
    this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    this.properties = properties;
  }

  /**
   * The session phase of every exchange ({@code NSE}, {@code BSE}, {@code MCX}), in display order. A
   * failed exchange call lists with status {@code "UNKNOWN"} (preOpen=false, null asOf) rather than
   * failing the whole banner. NEVER throws — the page must always render.
   */
  public List<MarketStatus> marketStatuses() {
    List<MarketStatus> out = new ArrayList<>(EXCHANGES.size());
    for (String exchange : EXCHANGES) {
      out.add(status(exchange));
    }
    return out;
  }

  /**
   * One exchange's session phase. Maps the raw Upstox status enum to a {@link MarketStatus} (preOpen =
   * the phase starts with {@code "PRE_OPEN"}); a transport / HTTP / parse failure degrades to status
   * {@code "UNKNOWN"} (never throws).
   */
  MarketStatus status(String exchange) {
    try {
      UpstoxMarketStatus response =
          withRetry(
              () ->
                  restClient
                      .get()
                      .uri("/v2/market/status/{exchange}", exchange)
                      .header("Authorization", "Bearer " + properties.resolveToken())
                      .header("Accept", "application/json")
                      .retrieve()
                      .body(UpstoxMarketStatus.class));
      UpstoxMarketStatus.Data data = response == null ? null : response.data();
      if (data == null || data.status() == null) {
        return unknown(exchange);
      }
      return new MarketStatus(exchange, data.status(), isPreOpen(data.status()), toIst(data.lastUpdated()));
    } catch (RuntimeException e) {
      log.warn("Upstox market status for {} failed — degrading to UNKNOWN: {}", exchange, e.toString());
      return unknown(exchange);
    }
  }

  /**
   * The Pre-Open snapshot: the NSE session phase paired with the four index quotes. The NSE
   * {@link MarketStatus} drives the page-level {@code phase} / {@code preOpen}; the indices list always
   * carries the four index rows (price-less when a quote did not resolve). NEVER throws.
   */
  public PreOpen preOpen() {
    MarketStatus nse = status("NSE");
    Map<String, UpstoxMarketQuote.Tick> quotes = indexQuotes(INDEX_KEYS.stream().map(IndexKey::key).toList());
    OffsetDateTime asOf = OffsetDateTime.now(IST);
    List<PreOpenIndex> indices = new ArrayList<>(INDEX_KEYS.size());
    for (IndexKey ik : INDEX_KEYS) {
      indices.add(toIndex(ik, quotes.get(ik.key()), asOf));
    }
    return new PreOpen(nse.status(), nse.preOpen(), indices);
  }

  /**
   * A cross-module quote: just the {@code last_price} (the pre-open indicative LTP) + {@code net_change}
   * vs the prev close, keyed by the request {@code |} instrument key. The exposed shape of {@link
   * #quotes} — the internal wire {@link UpstoxMarketQuote.Tick} stays module-internal.
   */
  public record Quote(BigDecimal lastPrice, BigDecimal netChange) {}

  /**
   * The pre-open quote ({@code last_price} + {@code net_change}) for a BATCH of {@code instrumentKeys} in
   * ONE call — the cross-module entrypoint other market-data modules (the Futures Pre-Open scan) use to
   * batch {@code NSE_FO|<token>} stock-future keys. Maps the internal wire tick to the domain {@link
   * Quote} so no wire type crosses the module boundary. Empty map on an empty request / any failure —
   * NEVER throws.
   */
  public Map<String, Quote> quotes(List<String> instrumentKeys) {
    Map<String, Quote> out = new LinkedHashMap<>();
    for (Map.Entry<String, UpstoxMarketQuote.Tick> e : indexQuotes(instrumentKeys).entrySet()) {
      out.put(e.getKey(), new Quote(e.getValue().lastPrice(), e.getValue().netChange()));
    }
    return out;
  }

  /**
   * The full market quote for a BATCH of {@code instrumentKeys} in ONE call, re-keyed back to the
   * request {@code |} form (Upstox keys the response map with a {@code :} variant). Module-internal (the
   * {@link UpstoxMarketQuote.Tick} wire type does not cross the module boundary — {@link #quotes} is the
   * exposed counterpart). Returns an empty map on an empty request OR any transport / HTTP failure (the
   * snapshot then lists price-less rows) — NEVER throws.
   */
  Map<String, UpstoxMarketQuote.Tick> indexQuotes(List<String> instrumentKeys) {
    if (instrumentKeys == null || instrumentKeys.isEmpty()) {
      return Map.of();
    }
    String csv = String.join(",", instrumentKeys);
    UpstoxMarketQuote response;
    try {
      response =
          withRetry(
              () ->
                  restClient
                      .get()
                      .uri(b -> b.path("/v2/market-quote/quotes").queryParam("instrument_key", csv).build())
                      .header("Authorization", "Bearer " + properties.resolveToken())
                      .header("Accept", "application/json")
                      .retrieve()
                      .body(UpstoxMarketQuote.class));
    } catch (RuntimeException e) {
      // A failed quote batch leaves the indices price-less rather than failing the whole page.
      log.warn("Upstox index quote batch failed — rows render price-less: {}", e.toString());
      return Map.of();
    }
    if (response == null || response.data() == null || response.data().isEmpty()) {
      return Map.of();
    }
    Map<String, String> byNormalized = new LinkedHashMap<>();
    for (String requested : instrumentKeys) {
      byNormalized.put(normalize(requested), requested);
    }
    Map<String, UpstoxMarketQuote.Tick> out = new LinkedHashMap<>();
    for (Map.Entry<String, UpstoxMarketQuote.Tick> entry : response.data().entrySet()) {
      String requested = byNormalized.get(normalize(entry.getKey()));
      if (requested != null) {
        out.put(requested, entry.getValue());
      }
    }
    return out;
  }

  /** Maps one index key + its (nullable) live tick to the domain {@link PreOpenIndex}. */
  private static PreOpenIndex toIndex(IndexKey ik, UpstoxMarketQuote.Tick tick, OffsetDateTime asOf) {
    if (tick == null) {
      return new PreOpenIndex(ik.name(), ik.key(), null, null, null, null, asOf);
    }
    UpstoxMarketQuote.Ohlc ohlc = tick.ohlc();
    BigDecimal ltp = tick.lastPrice();
    BigDecimal netChange = tick.netChange();
    // close in the quote ohlc is the PREVIOUS day's close; prefer net_change + ltp (more precise).
    BigDecimal prevClose =
        ltp != null && netChange != null
            ? ltp.subtract(netChange)
            : (ohlc == null ? null : ohlc.close());
    return new PreOpenIndex(
        ik.name(), ik.key(), ltp, prevClose, netChange, changePct(netChange, prevClose), asOf);
  }

  /** {@code net_change / prevClose * 100} (2 dp), or {@code null} when either is absent / prevClose=0. */
  private static BigDecimal changePct(BigDecimal netChange, BigDecimal prevClose) {
    if (netChange == null || prevClose == null || prevClose.signum() == 0) {
      return null;
    }
    return netChange.multiply(BigDecimal.valueOf(100)).divide(prevClose, 2, RoundingMode.HALF_UP);
  }

  /** The pre-open phase = the Upstox status enum starts with {@code "PRE_OPEN"}. */
  private static boolean isPreOpen(String status) {
    return status != null && status.startsWith("PRE_OPEN");
  }

  private static MarketStatus unknown(String exchange) {
    return new MarketStatus(exchange, "UNKNOWN", false, null);
  }

  /** Upstox {@code last_updated} (epoch-millis) → an IST-offset instant, or null when absent. */
  private static OffsetDateTime toIst(Long epochMillis) {
    return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis).atZone(IST).toOffsetDateTime();
  }

  /** Folds the request/response delimiter difference ({@code |} vs {@code :}) for key matching. */
  private static String normalize(String key) {
    return key.replace('|', ':');
  }

  /**
   * Runs a call, retrying on Upstox's {@code 429 Too Many Requests} — a brief backoff (the {@code
   * Retry-After} header if present, else 1→16s exponential) lets it succeed. After {@value
   * #MAX_429_RETRIES} retries the 429 propagates; other errors propagate immediately.
   */
  private <T> T withRetry(Supplier<T> call) {
    int attempt = 0;
    while (true) {
      limiter.acquire();
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

  /** The Pre-Open snapshot the controller serves: the NSE phase + the four index quotes. */
  public record PreOpen(String phase, boolean preOpen, List<PreOpenIndex> indices) {}
}
