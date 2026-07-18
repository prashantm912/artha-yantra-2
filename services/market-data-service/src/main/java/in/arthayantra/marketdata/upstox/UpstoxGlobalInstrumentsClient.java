package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.upstox.wire.UpstoxMarketQuote;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Hand-rolled Upstox <b>global-instrument</b> client for the World Indices page (ADR-0002
 * anti-corruption pattern, the SAME shape as {@link UpstoxFnoMasterClient} + {@link UpstoxQuoteClient};
 * the broker SDK is never imported). Two seams:
 *
 * <ul>
 *   <li>{@link #fetchMaster()} — downloads + gunzips the public, auth-free global instrument master
 *       ({@code GET https://assets.upstox.com/market-quote/instruments/exchange/global.json.gz}),
 *       parses it through the {@link UpstoxGlobalInstrument} wire DTO, and keeps the
 *       {@code GLOBAL_INDEX} / {@code GLOBAL_INDICATOR} rows. The master is static (once-a-day
 *       publication), so it is cached and refreshed at most once per {@link #REFRESH} window.
 *   <li>{@link #worldIndices()} — joins the cached master with a single batched {@code
 *       GET /v2/market-quote/quotes} call (the analytics Bearer token) and maps each row to a {@link
 *       WorldIndex}. The quotes response keys the {@code data} map by a {@code :}-delimited variant of
 *       the request {@code |} key (e.g. request {@code GLOBAL_INDEX|^HSI} → response key
 *       {@code GLOBAL_INDEX:^HSI}), so the join folds that delimiter difference.
 * </ul>
 *
 * <p>Errors degrade gracefully — a failed/empty master returns an empty list (the page renders its
 * empty state), and a failed quote batch yields master rows with {@code null} prices (the universe
 * still lists). Nothing here ever 500s the page.
 */
public final class UpstoxGlobalInstrumentsClient {

  /** The global master is published once a day; re-fetch at most this often. */
  static final Duration REFRESH = Duration.ofHours(12);

  /** Quotes batch cap (the v2 endpoint takes up to ~500 keys per call); the global universe is small. */
  private static final int MAX_BATCH = 500;

  /** 429 backoff on the quotes call: honor Retry-After if present, else 1→16s exponential. */
  private static final int MAX_429_RETRIES = 5;
  private static final long BASE_BACKOFF_MS = 1_000;
  private static final long MAX_BACKOFF_MS = 16_000;

  private static final Logger log = LoggerFactory.getLogger(UpstoxGlobalInstrumentsClient.class);

  private final RestClient masterClient;
  private final RestClient quoteClient;
  private final ObjectMapper mapper;
  private final UpstoxAnalyticsProperties properties;
  /** The token-scoped budget shared across every analytics-token client (EXT-02); live path (full cap). */
  private final UpstoxRateLimiter limiter;
  private final String masterPath = "/market-quote/instruments/exchange/global.json.gz";

  private volatile List<UpstoxGlobalInstrument> master = List.of();
  private volatile Instant loadedAt = Instant.EPOCH;

  /** Binds the master client to the assets CDN and the quote client to the API host (both configurable). */
  public UpstoxGlobalInstrumentsClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      UpstoxAnalyticsProperties properties,
      UpstoxRateLimiter limiter) {
    // Generous read timeout for the gzip master download, fail-fast connect — so a dead host never
    // parks the page (the service then returns an empty list / null prices, never a 500).
    SimpleClientHttpRequestFactory masterFactory = new SimpleClientHttpRequestFactory();
    masterFactory.setConnectTimeout(15_000);
    masterFactory.setReadTimeout(60_000);
    this.masterClient =
        builder.clone().baseUrl(properties.instrumentsBaseUrl()).requestFactory(masterFactory).build();

    SimpleClientHttpRequestFactory quoteFactory = new SimpleClientHttpRequestFactory();
    quoteFactory.setConnectTimeout(15_000);
    quoteFactory.setReadTimeout(45_000);
    this.quoteClient =
        builder.clone().baseUrl(properties.baseUrl()).requestFactory(quoteFactory).build();

    this.mapper = mapper;
    this.properties = properties;
    this.limiter = limiter;
  }

  /**
   * The full World Indices snapshot: every {@code GLOBAL_INDEX} / {@code GLOBAL_INDICATOR} row of the
   * master, joined with its live quote. Order follows the master (segment grouping is the caller's).
   * Returns an empty list when the master is unreachable; rows with no resolved quote carry {@code
   * null} prices. NEVER throws — the page must always render.
   */
  public List<WorldIndex> worldIndices() {
    List<UpstoxGlobalInstrument> rows = fetchMaster();
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<String, UpstoxMarketQuote.Tick> quotes = quoteAll(rows.stream().map(UpstoxGlobalInstrument::instrumentKey).toList());
    OffsetDateTime asOf = OffsetDateTime.now(ZoneOffset.UTC);
    List<WorldIndex> out = new ArrayList<>(rows.size());
    for (UpstoxGlobalInstrument r : rows) {
      out.add(toWorldIndex(r, quotes.get(r.instrumentKey()), asOf));
    }
    return out;
  }

  /**
   * The cached, parsed global master ({@code GLOBAL_INDEX} + {@code GLOBAL_INDICATOR} rows), lazily
   * (re)loaded on first use / after the {@link #REFRESH} window. Returns an empty list on a
   * transport/gunzip/parse failure (kept off the critical path) — never throws.
   */
  public List<UpstoxGlobalInstrument> fetchMaster() {
    if (Duration.between(loadedAt, Instant.now()).compareTo(REFRESH) >= 0) {
      synchronized (this) {
        if (Duration.between(loadedAt, Instant.now()).compareTo(REFRESH) >= 0) {
          reload();
        }
      }
    }
    return master;
  }

  private void reload() {
    try {
      byte[] gzip = masterClient.get().uri(masterPath).retrieve().body(byte[].class);
      if (gzip == null || gzip.length == 0) {
        log.warn("Upstox global instrument master fetch returned empty body — keeping prior cache");
        loadedAt = Instant.now();
        return;
      }
      List<UpstoxGlobalInstrument> rows;
      try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
        rows = mapper.readValue(in, new TypeReference<List<UpstoxGlobalInstrument>>() {});
      }
      master = rows.stream().filter(UpstoxGlobalInstrumentsClient::isGlobalIndex).toList();
      loadedAt = Instant.now();
      log.info("Upstox global instrument master loaded: {} global index rows", master.size());
    } catch (IOException | RuntimeException e) {
      // Transport / gunzip / parse failure: keep any prior cache and defer the next attempt by the
      // refresh window so we don't hammer the CDN. NEVER propagates out (the page must still render).
      loadedAt = Instant.now();
      log.warn("Upstox global instrument master load failed — keeping prior cache: {}", e.toString());
    }
  }

  // GLOBAL_INDEX only. The GLOBAL_INDICATOR rows (USD/INR, Brent, Crude — forex/commodity, not
  // indices) are NOT quotable via /v2/market-quote/quotes, and Upstox rejects the WHOLE batch
  // (UDAPI1087) if any key is invalid — so including them blanked every price. Out of scope for a
  // World Indices page anyway.
  private static boolean isGlobalIndex(UpstoxGlobalInstrument r) {
    return r.instrumentKey() != null && "GLOBAL_INDEX".equals(r.segment());
  }

  /** Fetches quotes for every key in {@code MAX_BATCH}-sized batches; failed batches are skipped. */
  private Map<String, UpstoxMarketQuote.Tick> quoteAll(List<String> keys) {
    Map<String, UpstoxMarketQuote.Tick> out = new LinkedHashMap<>();
    for (int i = 0; i < keys.size(); i += MAX_BATCH) {
      List<String> batch = keys.subList(i, Math.min(i + MAX_BATCH, keys.size()));
      try {
        out.putAll(quote(batch));
      } catch (RuntimeException e) {
        // A failed quote batch leaves those rows price-less rather than failing the whole page.
        log.warn("Upstox global quote batch failed ({} keys) — rows render price-less: {}", batch.size(), e.toString());
      }
    }
    return out;
  }

  /**
   * The raw {@code /v2/market-quote/quotes} batch for a list of global {@code instrument_key}s, re-keyed
   * back to the request {@code |} form (Upstox keys the response map with a {@code :} variant). An empty
   * request short-circuits; transport / HTTP errors propagate to {@link #quoteAll} (per-batch skip).
   */
  Map<String, UpstoxMarketQuote.Tick> quote(List<String> instrumentKeys) {
    if (instrumentKeys == null || instrumentKeys.isEmpty()) {
      return Map.of();
    }
    String csv = String.join(",", instrumentKeys);
    UpstoxMarketQuote response =
        withRetry(
            () ->
                quoteClient
                    .get()
                    .uri(b -> b.path("/v2/market-quote/quotes").queryParam("instrument_key", csv).build())
                    .header("Authorization", "Bearer " + properties.resolveToken())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(UpstoxMarketQuote.class));
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

  /** Maps a master row + its (nullable) live tick to the domain {@link WorldIndex}. */
  private static WorldIndex toWorldIndex(
      UpstoxGlobalInstrument r, UpstoxMarketQuote.Tick tick, OffsetDateTime asOf) {
    if (tick == null) {
      return new WorldIndex(
          r.name(), r.country(), r.instrumentKey(), r.tradingSymbol(),
          null, null, null, null, null, null, null, null, r.latency());
    }
    UpstoxMarketQuote.Ohlc ohlc = tick.ohlc();
    BigDecimal ltp = tick.lastPrice();
    BigDecimal netChange = tick.netChange();
    // close in the quote ohlc is the PREVIOUS day's close; prefer net_change + ltp (more precise).
    BigDecimal prevClose =
        ltp != null && netChange != null
            ? ltp.subtract(netChange)
            : (ohlc == null ? null : ohlc.close());
    return new WorldIndex(
        r.name(),
        r.country(),
        r.instrumentKey(),
        r.tradingSymbol(),
        ltp,
        prevClose,
        netChange,
        changePct(netChange, prevClose),
        ohlc == null ? null : ohlc.open(),
        ohlc == null ? null : ohlc.high(),
        ohlc == null ? null : ohlc.low(),
        asOf,
        r.latency());
  }

  /** {@code net_change / prevClose * 100} (2 dp), or {@code null} when either is absent / prevClose=0. */
  private static BigDecimal changePct(BigDecimal netChange, BigDecimal prevClose) {
    if (netChange == null || prevClose == null || prevClose.signum() == 0) {
      return null;
    }
    return netChange.multiply(BigDecimal.valueOf(100)).divide(prevClose, 2, RoundingMode.HALF_UP);
  }

  /** Folds the request/response delimiter difference ({@code |} vs {@code :}) for key matching. */
  private static String normalize(String key) {
    return key.replace('|', ':');
  }

  /**
   * Runs a quote call, retrying on Upstox's {@code 429 Too Many Requests} — a brief backoff (the
   * {@code Retry-After} header if present, else 1→16s exponential) lets it succeed. After
   * {@value #MAX_429_RETRIES} retries the 429 propagates; other errors propagate immediately.
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
}
