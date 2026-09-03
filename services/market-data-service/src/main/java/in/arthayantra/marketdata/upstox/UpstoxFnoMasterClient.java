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

  /**
   * H26 U-A2 — NSE CASH identity, indexed by the exchange token.
   *
   * <p>⚠️ <b>The class name says Fno and this index does not. That tension is deliberate and cheaper
   * than the alternative.</b> Renaming would touch ~90 references across the live margin path, the
   * tick-feed adapter, the quote gateway and the contract canary — money- and live-engine-adjacent
   * code — to gain nothing but a better noun. The class is really "the Upstox instrument master
   * client"; it had only ever been used for F&amp;O.
   *
   * <p>Built from the SAME parsed rows as {@link #keysByLeg}, so this adds no second download of a
   * ~3 MB payload and inherits the candidate-then-check hardening in {@code reload()}.
   *
   * <p>⚠️ Assigned INDEPENDENTLY of the F&amp;O index, with its own emptiness guard. Gating both on
   * one condition would let an Upstox change that kills NSE_EQ also block F&amp;O refresh — coupling
   * two caches that fail for unrelated reasons.
   */
  private volatile Map<Long, NseCashIdentity> nseCashByToken = Map.of();

  /**
   * When the cash index last loaded SUCCESSFULLY — deliberately separate from {@link #loadedAt}.
   *
   * <p>⚠️ The two indexes have independent emptiness guards but shared the one timestamp, so a load
   * whose F&amp;O half succeeded and whose cash half mapped nothing stamped the cash index as freshly
   * loaded. That is this repository's catalogued "a cache that stores a FAILURE with a fresh
   * timestamp" shape, and it made the independence the javadoc above claims only half true. Nothing
   * reads the cash index yet, so the effect today is a wrong number rather than a wrong decision —
   * but A2-3 is the consumer that must be able to tell a CURRENT cash index from a stale one, and
   * {@link #nseCashSize()} alone cannot. Cross-vendor review 2026-09-03.
   */
  private volatile Instant cashLoadedAt = Instant.EPOCH;

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
        // Same rule as the catch below: an empty body is a FAILED attempt, so lastAttemptAt bounds
        // the retry and loadedAt stays where it was. Advancing it here would hand a dead upstream a
        // fresh refresh window.
        log.warn("Upstox instrument master fetch returned empty body — keeping prior cache");
        return false;
      }
      List<UpstoxInstrumentMaster> rows;
      try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
        rows = mapper.readValue(in, new TypeReference<List<UpstoxInstrumentMaster>>() {});
      }
      // ⚠️ INDEX INTO A CANDIDATE, THEN CHECK IT — never assign straight into the live cache.
      // A payload that parses cleanly but yields ZERO mapped legs is not a successful warm: Upstox
      // serving a truncated file, or a schema change that makes isFno() match nothing, both land
      // here. Assigning it would REPLACE a perfectly good cache with {}, stamp loadedAt fresh, and
      // return true — after which every instrument-key lookup misses and every margin call returns
      // `unpriced` for a full refresh interval, with the warmer reporting itself healthy throughout.
      // Cross-vendor review, 2026-08-10.
      Map<FnoKey, FnoLeg> candidate = index(rows);
      if (candidate.isEmpty()) {
        log.warn(
            "Upstox instrument master parsed {} row(s) but mapped ZERO F&O legs — keeping prior"
                + " cache of {} leg(s)",
            rows.size(),
            keysByLeg.size());
        return false;
      }
      keysByLeg = candidate;
      // Independent guard: an empty NSE index must neither discard a good one nor stop the F&O
      // cache above from refreshing.
      Map<Long, NseCashIdentity> cashCandidate = indexNseCash(rows);
      if (cashCandidate.isEmpty()) {
        log.warn(
            "Upstox master mapped ZERO NSE cash rows — keeping prior cash index of {}",
            nseCashByToken.size());
      } else {
        nseCashByToken = cashCandidate;
        cashLoadedAt = attemptAt;
      }
      loadedAt = attemptAt;
      log.info("Upstox F&O instrument master loaded: {} mapped legs", keysByLeg.size());
      return true;
    } catch (IOException | RuntimeException e) {
      // Transport / gunzip / parse failure: keep any prior cache (the resolver then falls back to the
      // Kite path — this enrichment must NEVER break the source). NEVER propagates out of a lookup.
      //
      // ⚠️ loadedAt is deliberately NOT touched here, and an earlier version of this method DID
      // advance it whenever a prior cache existed. That is the catalogued "a CACHE that stores a
      // FAILURE with a fresh timestamp" shape: a failed load bought the stale cache another full
      // REFRESH interval, so a source that had been broken for hours read as freshly warmed. The
      // rate limit on retries is lastAttemptAt + RETRY_BACKOFF, which is set unconditionally above
      // and is the only thing that should bound them.
      log.warn("Upstox instrument master load failed — keeping prior cache: {}", e.toString());
      return false;
    }
  }

  /**
   * The NSE cash identity Upstox holds for {@code exchangeToken}, or {@code null}.
   *
   * <p>H26 U-A2, and NOTHING CONSUMES THIS YET — it exists so the A2-3 shadow diff can compare
   * Upstox identity against our own without a second download.
   *
   * <p>⚠️ <b>PRECONDITION: the caller must already know this token belongs to a CASH row.</b> The
   * index is scoped to {@code NSE_EQ}, which makes the key unique on the UPSTOX side — but OUR
   * {@code exchange='NSE'} mixes cash with INDICES, and tokens collide across them (`computed`
   * 2026-09-03: 47 such tokens; it was 30 on 09-02, so re-derive it rather than quoting either).
   * Hand this an INDEX token and it returns a bond's identity — confidently, and wrong, rather than
   * {@code null}. A2-3 will iterate exactly that mixed set, which is why the warning belongs on the
   * method a caller reads and not only in the class doc.
   */
  public NseCashIdentity nseCashIdentity(long exchangeToken) {
    cache();
    return nseCashByToken.get(exchangeToken);
  }

  /**
   * How many NSE cash rows the master currently maps.
   *
   * <p>Zero means the index holds nothing — which is "never loaded" OR "loaded and mapped nothing".
   * ⚠️ Nothing here separates those two, and neither does {@link #nseCashLoadedAt()}: an empty cash
   * candidate is deliberately treated as a FAILED load, so both states read size 0 / EPOCH. What the
   * pair does tell a consumer is whether the index it is reading is CURRENT or left over from an
   * earlier load — which is the question A2-3 actually has to answer.
   */
  public int nseCashSize() {
    cache();
    return nseCashByToken.size();
  }

  /**
   * When the cash index last loaded SUCCESSFULLY, or {@link Instant#EPOCH} if it never has.
   *
   * <p>The timestamp of the last successful NON-EMPTY load: it advances only on a load that mapped
   * at least one cash row, never on one whose F&amp;O half succeeded and whose cash half mapped
   * nothing. That is the whole reason it is not {@code loadedAt} — it dates the index a caller is
   * actually reading, so A2-3 can tell a CURRENT index from one left over from an earlier load
   * before it treats a zero-mismatch session as evidence of anything.
   *
   * <p>⚠️ It does NOT separate "never loaded" from "loaded and mapped nothing" — an empty candidate
   * is treated as a failure, so both read {@code EPOCH}. Cross-vendor review 2026-09-03 (Minor).
   */
  public Instant nseCashLoadedAt() {
    cache();
    return cashLoadedAt;
  }

  /**
   * Indexes {@code NSE_EQ} rows by exchange token.
   *
   * <p>⚠️ <b>Keyed on the EXCHANGE TOKEN, not the symbol, and that is the measured choice.</b>
   * {@code computed} 2026-09-02 over the live master: the token joins our table 9,694/9,694 —
   * <b>100.00%</b> — while a naive {@code trading_symbol} join matches only 27% and would
   * MIS-IDENTIFY roughly 73% of NSE equities. Receipt:
   * {@code docs/signal-analysis/2026-09-02-h26-ua2-identity-join-measurement.md}.
   *
   * <p>⚠️ <b>The token is unique per SEGMENT, not per exchange.</b> Our {@code exchange='NSE'} lumps
   * cash together with INDICES, and tokens collide across those two — token 1001 is both
   * {@code NIFTY 50} and a bond. Scoping this index to {@code NSE_EQ} is what makes the key unique;
   * within segment the measurement is 10,096/10,096 distinct. Do not widen it to "all NSE".
   *
   * <p>⚠️ The collision COUNT moves and must be re-derived, never quoted: 30 on 2026-09-02, <b>47</b>
   * on 2026-09-03. It is one query. A moving number written down as a fixed fact is a trap this
   * repository has paid for before.
   *
   * <p>NSE only, deliberately (owner, 2026-09-02): the suffix rule below is verified on NSE, and BSE
   * is where that convention is already known not to apply.
   */
  private static Map<Long, NseCashIdentity> indexNseCash(List<UpstoxInstrumentMaster> rows) {
    Map<Long, NseCashIdentity> map = new HashMap<>();
    for (UpstoxInstrumentMaster r : rows) {
      if (!"NSE_EQ".equals(r.segment()) || r.instrumentKey() == null) {
        continue;
      }
      String symbol = r.tradingSymbol();
      String series = r.instrumentType();
      if (symbol == null || series == null) {
        continue;
      }
      Long token = parseExchangeToken(r.exchangeToken());
      if (token == null) {
        continue;
      }
      map.putIfAbsent(
          token,
          new NseCashIdentity(
              token,
              r.instrumentKey(),
              r.isin(),
              symbol,
              series,
              kiteTradingsymbol(symbol, series)));
    }
    return map;
  }

  /**
   * The wire {@code exchange_token} as a number, or {@code null} when it is absent, blank or not a
   * number.
   *
   * <p>⚠️ <b>Called only AFTER the {@code NSE_EQ} filter, and that ordering is the point.</b> The
   * field is mirrored as the JSON string Upstox actually sends, so no row can break the parse — 13
   * live rows carry the empty string, all of them in segments this index never looks at. Converting
   * here rather than in the DTO keeps that cost inside the one segment we index and keeps the whole
   * master parse independent of the mapper's coercion configuration.
   */
  private static Long parseExchangeToken(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Long.valueOf(raw.trim());
    } catch (NumberFormatException notANumber) {
      // A non-numeric token is a row we cannot identify, not a reason to fail the load: skip it and
      // leave the rest of the master indexed. Deliberately silent — the master carries ~117k rows
      // and a per-row WARN would be a log flood, while the A2-3 shadow diff reports the miss.
      return null;
    }
  }

  /**
   * Derives the Kite tradingsymbol from Upstox's bare symbol plus its series.
   *
   * <p>⚠️ <b>This is a DERIVATION, not a synthesis, and the distinction is the whole reason U-A2
   * shrank.</b> Kite appends the series for every non-EQ series — {@code 749RJ35-SG},
   * {@code KCK-ST}, and the familiar {@code -BE} — while Upstox carries the bare symbol and the
   * series separately. {@code computed} 2026-09-02 against the live master and our table:
   * 2,651 bare + 7,043 suffixed = <b>100.00%, ZERO unmatched</b>.
   *
   * <p>⚠️ It also generalises H29/H36: the {@code -BE} twin is not a special case but one instance
   * of a Kite-wide convention also covering SG, N0, SM, GS and ST.
   *
   * <p>⚠️ <b>This is a CROSS-CHECK on the token join, never a substitute for it.</b> The token is
   * the identity; if the two ever disagree, that disagreement is the A2-3 finding — resolving it in
   * favour of this rule would be trusting a string over an exchange-issued key.
   */
  static String kiteTradingsymbol(String upstoxSymbol, String series) {
    return "EQ".equals(series) ? upstoxSymbol : upstoxSymbol + "-" + series;
  }

  /**
   * One NSE cash instrument as Upstox describes it, plus the Kite symbol derived from it.
   * {@code isin} may be null; the token and key never are.
   */
  public record NseCashIdentity(
      long exchangeToken,
      String upstoxInstrumentKey,
      String isin,
      String upstoxTradingSymbol,
      String series,
      String derivedKiteTradingsymbol) {}

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
