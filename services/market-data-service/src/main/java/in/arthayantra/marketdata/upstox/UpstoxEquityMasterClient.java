package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Hand-rolled Upstox <b>instrument-master</b> client that resolves an NSE cash-equity tradingsymbol to
 * its Upstox {@code NSE_EQ|<ISIN>} {@code instrument_key} — the Phase-5 enabler for the 200-day daily
 * OHLCV equity backfill (Minervini 150/200-day MA history). Same ADR-0002 anti-corruption shape as
 * {@link UpstoxFnoMasterClient} (the broker SDK is never imported; the {@link UpstoxInstrumentMaster}
 * wire DTO isolates the parse), and reads the SAME public master file — the F&amp;O client keeps the
 * {@code *_FO} rows, this one keeps the {@code NSE_EQ} rows and indexes them by tradingsymbol.
 *
 * <p>Source: {@code GET https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz}
 * — the public, auth-free gzipped JSON master (one object per listed instrument, all segments). The
 * parsed map is cached and lazily refreshed at most once per {@link #REFRESH} window (the master is a
 * once-a-day publication). The host is a token-free CDN; {@code baseUrl} is configurable so WireMock
 * stands in for tests. A transport/parse failure degrades to {@code null} (the backfill simply skips
 * the symbol) and NEVER propagates out of a lookup.
 */
public final class UpstoxEquityMasterClient {

  /** The Upstox master is published once a day; re-fetch at most this often. */
  static final Duration REFRESH = Duration.ofHours(12);

  private static final Logger log = LoggerFactory.getLogger(UpstoxEquityMasterClient.class);

  private final RestClient restClient;
  private final ObjectMapper mapper;
  private final String masterPath;

  private volatile Map<String, String> keysBySymbol = Map.of();
  private volatile Instant loadedAt = Instant.EPOCH;

  /** Binds the wire client to the (configurable) assets host. */
  public UpstoxEquityMasterClient(
      RestClient.Builder builder, ObjectMapper mapper, UpstoxAnalyticsProperties properties) {
    // A 5MB+ gzip download — generous read timeout, fail-fast connect, so a dead CDN never parks the
    // first lookup forever (the resolver then simply returns null and the symbol is skipped).
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(15_000);
    factory.setReadTimeout(60_000);
    this.restClient = builder.baseUrl(properties.instrumentsBaseUrl()).requestFactory(factory).build();
    this.mapper = mapper;
    this.masterPath = "/market-quote/instruments/exchange/complete.json.gz";
  }

  /**
   * The Upstox {@code NSE_EQ|<ISIN>} {@code instrument_key} for an NSE cash-equity tradingsymbol (e.g.
   * {@code RELIANCE}), or {@code null} when the master holds no matching row. Lazily (re)loads the
   * master on first use / after the {@link #REFRESH} window.
   */
  public String equityKey(String tradingsymbol) {
    if (tradingsymbol == null || tradingsymbol.isBlank()) {
      return null;
    }
    return cache().get(tradingsymbol.trim().toUpperCase());
  }

  /** Returns the cached map, (re)loading it on first use or after the refresh window. */
  private Map<String, String> cache() {
    if (Duration.between(loadedAt, Instant.now()).compareTo(REFRESH) >= 0) {
      synchronized (this) {
        if (Duration.between(loadedAt, Instant.now()).compareTo(REFRESH) >= 0) {
          reload();
        }
      }
    }
    return keysBySymbol;
  }

  private void reload() {
    try {
      byte[] gzip = restClient.get().uri(masterPath).retrieve().body(byte[].class);
      if (gzip == null || gzip.length == 0) {
        log.warn("Upstox instrument master fetch returned empty body — keeping prior cache");
        loadedAt = Instant.now();
        return;
      }
      List<UpstoxInstrumentMaster> rows;
      try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
        rows = mapper.readValue(in, new TypeReference<List<UpstoxInstrumentMaster>>() {});
      }
      keysBySymbol = index(rows);
      loadedAt = Instant.now();
      log.info("Upstox NSE_EQ instrument master loaded: {} equities", keysBySymbol.size());
    } catch (IOException | RuntimeException e) {
      // Transport / gunzip / parse failure: keep any prior cache and don't hammer the CDN on every
      // lookup — defer the next attempt by the refresh window. NEVER propagates out of a lookup.
      loadedAt = Instant.now();
      log.warn("Upstox instrument master load failed — keeping prior cache: {}", e.toString());
    }
  }

  /** Indexes the {@code NSE_EQ} rows by uppercased tradingsymbol → {@code instrument_key}. */
  private static Map<String, String> index(List<UpstoxInstrumentMaster> rows) {
    Map<String, String> map = new HashMap<>();
    for (UpstoxInstrumentMaster r : rows) {
      if (!"NSE_EQ".equals(r.segment()) || r.instrumentKey() == null || r.tradingSymbol() == null) {
        continue;
      }
      map.putIfAbsent(r.tradingSymbol().trim().toUpperCase(), r.instrumentKey());
    }
    return map;
  }
}
