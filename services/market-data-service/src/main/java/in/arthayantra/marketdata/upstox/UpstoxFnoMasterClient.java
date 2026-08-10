package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Hand-rolled Upstox <b>instrument-master</b> client that resolves a FUT/option contract to its Upstox
 * {@code NSE_FO|<token>} / {@code BSE_FO|<token>} {@code instrument_key} (Wave-U4 enabler for the
 * U2 quote + U3 ticker paths). Same ADR-0002 anti-corruption shape as {@link
 * UpstoxExpiredInstrumentsClient} — the broker SDK is never imported; a {@link UpstoxInstrumentMaster}
 * wire DTO ({@code @JsonIgnoreProperties}) isolates the parse from Upstox's exact JSON shape.
 *
 * <p>Source: {@code GET https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz}
 * — the public, auth-free gzipped JSON master (one object per listed instrument, all segments). This
 * client keeps ONLY the {@code *_FO} rows and indexes them by a domain-free {@link FnoKey} tuple
 * {@code (segment, underlying, type, expiry, strike)} so the {@code options}-module resolver can map
 * an ArthaYantra F&amp;O leg (underlying name + expiry + strike + type, read from the instruments
 * table) to the Upstox key WITHOUT relying on the broker-specific tradingsymbol grammar (Kite vs
 * Upstox vs OpenAlgo all differ; the structured tuple is the stable join, mirroring how {@code
 * OpenAlgoHistoryClient} re-resolves the leg).
 *
 * <p>The parsed map is cached and refreshed at most once per {@link #REFRESH} window (the master is a
 * once-a-day publication) — lazily, on the first lookup after the window lapses, so no scheduler is
 * needed. The host (assets.upstox.com) is a CDN and needs NO token; {@code baseUrl} is configurable so
 * WireMock stands in for tests.
 */
public final class UpstoxFnoMasterClient {

  /** The Upstox master is published once a day; re-fetch at most this often. */
  static final Duration REFRESH = Duration.ofHours(12);

  /** A failed empty-cache load may be retried by a lookup after this backoff, not on every lookup. */
  static final Duration RETRY_BACKOFF = Duration.ofMinutes(5);

  /** Upstox publishes expiry as the contract's end-of-day IST instant; convert in IST. */
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private static final Logger log = LoggerFactory.getLogger(UpstoxFnoMasterClient.class);

  private final RestClient restClient;
  private final ObjectMapper mapper;
  private final String masterPath;
  private final Clock clock;

  private volatile Map<FnoKey, FnoLeg> keysByLeg = Map.of();
  private volatile Instant loadedAt = Instant.EPOCH;
  private volatile Instant lastAttemptAt = Instant.EPOCH;

  /** Binds the wire client to the (configurable) assets host. */
  public UpstoxFnoMasterClient(
      RestClient.Builder builder, ObjectMapper mapper, UpstoxAnalyticsProperties properties) {
    this(builder, mapper, properties, Clock.systemUTC());
  }

  UpstoxFnoMasterClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      UpstoxAnalyticsProperties properties,
      Clock clock) {
    // A 5MB+ gzip download — generous read timeout, fail-fast connect, so a dead CDN never parks the
    // first lookup forever (the resolver then simply returns null and the Kite path stays the source).
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(15_000);
    factory.setReadTimeout(60_000);
    this.restClient = builder.baseUrl(properties.instrumentsBaseUrl()).requestFactory(factory).build();
    this.mapper = mapper;
    this.masterPath = "/market-quote/instruments/exchange/complete.json.gz";
    this.clock = clock;
  }

  /**
   * The Upstox {@code instrument_key} for one F&amp;O leg, or {@code null} when the master holds no
   * matching contract (the caller then leaves it to the Kite path). {@code exchange} is the
   * ArthaYantra F&amp;O exchange ({@code NFO} / {@code BFO}); {@code strike} is {@code null} for a
   * future. Lazily (re)loads the master the first time / after the {@link #REFRESH} window.
   */
  public String keyFor(
      String exchange, String underlying, String type, LocalDate expiry, BigDecimal strike) {
    FnoLeg leg = resolve(exchange, underlying, type, expiry, strike);
    return leg == null ? null : leg.instrumentKey();
  }

  /**
   * The full resolved F&amp;O leg — its Upstox {@code instrument_key} plus the master's {@code
   * lot_size} — or {@code null} when the master holds no matching contract (same tolerance as {@link
   * #keyFor}). Callers needing the tradable lot alongside the key (e.g. the margin drift-probe, whose
   * basket qty MUST be a lot multiple) use this instead of resolving the lot from a hardcode that goes
   * stale on an NSE lot change.
   */
  public FnoLeg resolve(
      String exchange, String underlying, String type, LocalDate expiry, BigDecimal strike) {
    String segment = segmentFor(exchange);
    if (segment == null || underlying == null || type == null || expiry == null) {
      return null;
    }
    return cache()
        .get(new FnoKey(segment, underlying.trim().toUpperCase(), type, expiry, normalizeStrike(strike)));
  }

  /**
   * Forces a master (re)load NOW, off any caller's critical path — the {@link UpstoxFnoMasterWarmer}
   * hook. Its whole purpose is that a LIVE lookup never pays the cold load: the master is a 5MB+ gzip
   * fetched on this client's own generous timeouts (connect 15s / read 60s), while the F9 heat read
   * reaching {@code keyFor} through market-data allows the caller only 2000ms end-to-end. Measured
   * 2026-08-05: two WARNs at exactly 2000ms with the master landing 535ms later, so the heat gate went
   * inert on that session's only funded entry; the same cold-load shape on 2026-08-06 completed inside
   * budget. It is a RACE, not a deterministic failure — warming removes the race rather than fixing a
   * constant break.
   *
   * <p>Unconditional by design: the warmer runs on a period STRICTLY SHORTER than {@link #REFRESH}, so
   * the cache is always inside its window when a caller arrives and {@link #cache()}'s lazy branch —
   * the one that would run the download on the caller's thread, under this monitor — never fires in a
   * long-running service. Takes the same lock as that branch so a warm and a lazy load can never
   * double-fetch. Fail-soft: {@link #reload()} swallows transport/gunzip/parse failure and keeps the
   * prior cache, so this NEVER throws.
   */
  public boolean warm() {
    synchronized (this) {
      return reload();
    }
  }

  /** ArthaYantra F&amp;O exchange → Upstox segment; {@code null} (skip) for a non-F&amp;O exchange. */
  private static String segmentFor(String exchange) {
    return switch (exchange) {
      case "NFO" -> "NSE_FO";
      case "BFO" -> "BSE_FO";
      default -> null;
    };
  }

  /** Returns the cached map, (re)loading it on first use or after the refresh window. */
  private Map<FnoKey, FnoLeg> cache() {
    Instant now = Instant.now(clock);
    if (loadDue(now)) {
      synchronized (this) {
        if (loadDue(Instant.now(clock))) {
          reload();
        }
      }
    }
    return keysByLeg;
  }

  private boolean loadDue(Instant now) {
    return Duration.between(loadedAt, now).compareTo(REFRESH) >= 0
        && Duration.between(lastAttemptAt, now).compareTo(RETRY_BACKOFF) >= 0;
  }

  private boolean reload() {
    Instant attemptAt = Instant.now(clock);
    lastAttemptAt = attemptAt;
    try {
      byte[] gzip = restClient.get().uri(masterPath).retrieve().body(byte[].class);
      if (gzip == null || gzip.length == 0) {
        log.warn("Upstox instrument master fetch returned empty body — keeping prior cache");
        markFailedAttempt(attemptAt);
        return false;
      }
      List<UpstoxInstrumentMaster> rows;
      try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
        rows = mapper.readValue(in, new TypeReference<List<UpstoxInstrumentMaster>>() {});
      }
      keysByLeg = index(rows);
      loadedAt = attemptAt;
      log.info("Upstox F&O instrument master loaded: {} mapped legs", keysByLeg.size());
      return true;
    } catch (IOException | RuntimeException e) {
      // Transport / gunzip / parse failure: keep any prior cache (the resolver then falls back to the
      // Kite path — this enrichment must NEVER break the source). A non-empty prior cache keeps its
      // refresh timestamp; an empty cache stays retryable after the short backoff above. NEVER
      // propagates out of a lookup.
      markFailedAttempt(attemptAt);
      log.warn("Upstox instrument master load failed — keeping prior cache: {}", e.toString());
      return false;
    }
  }

  private void markFailedAttempt(Instant attemptAt) {
    if (!keysByLeg.isEmpty()) {
      loadedAt = attemptAt;
    }
  }

  /** Indexes the {@code *_FO} rows by the structured leg tuple → the resolved leg (key + lot). */
  private static Map<FnoKey, FnoLeg> index(List<UpstoxInstrumentMaster> rows) {
    Map<FnoKey, FnoLeg> map = new HashMap<>();
    for (UpstoxInstrumentMaster r : rows) {
      if (!isFno(r) || r.instrumentKey() == null) {
        continue;
      }
      String underlying = r.assetSymbol() != null ? r.assetSymbol() : r.underlyingSymbol();
      if (underlying == null) {
        continue;
      }
      LocalDate expiry = Instant.ofEpochMilli(r.expiry()).atZone(IST).toLocalDate();
      map.putIfAbsent(
          new FnoKey(
              r.segment(),
              underlying.trim().toUpperCase(),
              r.instrumentType(),
              expiry,
              normalizeStrike(r.strikePrice())),
          new FnoLeg(r.instrumentKey(), r.lotSize()));
    }
    return map;
  }

  private static boolean isFno(UpstoxInstrumentMaster r) {
    boolean foSegment = "NSE_FO".equals(r.segment()) || "BSE_FO".equals(r.segment());
    String t = r.instrumentType();
    boolean derivative = "FUT".equals(t) || "CE".equals(t) || "PE".equals(t);
    return foSegment && derivative && r.expiry() != null;
  }

  /**
   * Canonicalizes a strike for tuple equality: a future (null/zero strike) collapses to {@code null};
   * an option strike strips trailing zeros so {@code 18000}, {@code 18000.0} and {@code 18000.00}
   * compare equal across the two sources.
   */
  private static BigDecimal normalizeStrike(BigDecimal strike) {
    if (strike == null || strike.signum() == 0) {
      return null;
    }
    return strike.stripTrailingZeros();
  }

  /**
   * Domain-free structured identity of an F&amp;O contract — the stable join between the ArthaYantra
   * instruments table and the Upstox master, independent of any broker's tradingsymbol grammar.
   * {@code strike} is {@code null} for a future.
   */
  record FnoKey(
      String segment, String underlying, String type, LocalDate expiry, BigDecimal strike) {}

  /**
   * A resolved F&amp;O leg: the Upstox {@code instrumentKey} plus the master's {@code lotSize} (the
   * tradable market lot, e.g. NIFTY 65). {@code lotSize} is {@code null} when the master row omits
   * {@code lot_size} (never seen for a listed F&amp;O contract) — the caller then falls back.
   */
  public record FnoLeg(String instrumentKey, Integer lotSize) {}
}
