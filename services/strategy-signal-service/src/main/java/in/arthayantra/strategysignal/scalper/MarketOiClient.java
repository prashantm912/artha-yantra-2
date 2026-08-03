package in.arthayantra.strategysignal.scalper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.black76.Black76;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Assembles the OI/macro half of a {@link ScalperGateContext} from the market-data analytics REST
 * (master plan §12.2). This service holds no marketdata grant (D8), so every OI primitive — the
 * 4-state quadrants, sentiment, the PE−CE trending cross, futures basis, IV rank, breadth, FII
 * positioning — is read over HTTP and mapped into the LOCAL domain records the gates and the
 * Connect-the-Dots scorer consume. The chart half ({@code close/vwap/vwma20/psar/…}) is NOT here —
 * it comes from the engine {@code IndicatorBank} at evaluation time and is handed to {@link
 * #context}.
 *
 * <p><b>Live-feed only.</b> These are current snapshots, so the client is consulted on the LIVE
 * path only; it is never part of a deterministic replay (historical scalp backtests are out of
 * scope until ExpiryTrack historical OI lands, §S5). Parity is preserved instead by persisting the
 * computed confluence at entry (the V009 side-channel, §12.9) and replaying that, not by re-calling
 * this client.
 *
 * <p><b>Best-effort, conservatively.</b> Each primitive is fetched in isolation; an upstream miss
 * (422/500/empty) degrades that one field to a default that NEVER falsely confirms a side —
 * {@link OiQuadrant#NEUTRAL} for a quadrant, {@code null} for a soft numeric (the gate treats null
 * as "unavailable → pass"), {@code 0/0} for breadth (so the &gt;32 gate cannot confirm). One dead
 * endpoint can lower the confluence aggregate but can never manufacture a buy.
 */
@Component
public class MarketOiClient {

  private static final Logger log = LoggerFactory.getLogger(MarketOiClient.class);
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  /** Bucket window requested from the temporal series endpoints (trending/sentiment) — newest-last. */
  private static final int SERIES_WINDOW = 20;

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final MarketCalendar calendar;
  /** Chain legs with no resolvable canonical exchange (see {@link #addLeg}) — a live alarm. */
  private final io.micrometer.core.instrument.Counter legsWithoutExchange;

  /**
   * The runtime capability handshake (cross-vendor review Major 3). True while the market-data we
   * are talking to publishes {@code exchange} on chain legs; false the moment one arrives without
   * it — i.e. market-data is older than this build and every leg is costing an extra master lookup.
   * Exposed as {@code ay_scalper_chain_exchange_capability} so the degraded path is a dashboard
   * fact, not something inferred from a suddenly quiet tape. Tracks the LAST leg observed, so it
   * self-clears as soon as a newer market-data is deployed — no restart needed.
   */
  private final java.util.concurrent.atomic.AtomicBoolean chainPublishesExchange =
      new java.util.concurrent.atomic.AtomicBoolean(true);

  /**
   * Short-TTL response memo (audit P1-12): every scalper passing the chart gate on the SAME bar
   * re-ran the full ~15-call OI/macro fan-out sequentially on the single eval thread — 12 CE
   * variants ≈ 180 round-trips per bar. Successful bodies are cached by resolved URI for one
   * bar-ish window, so the first strategy pays and the rest read memory; the mappers are pure and
   * the OI snapshots only change on market-data's own capture cadence.
   */
  private record CachedBody(String body, long expiresAtMs) {}

  private static final long MEMO_TTL_MS = 45_000;
  private final java.util.concurrent.ConcurrentHashMap<String, CachedBody> memo =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final org.springframework.web.util.DefaultUriBuilderFactory uriFactory;

  /** Wires the configured market-data base URL (same bean pattern as the candle client). */
  public MarketOiClient(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      MarketCalendar calendar,
      io.micrometer.core.instrument.MeterRegistry meterRegistry,
      @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.objectMapper = objectMapper;
    this.calendar = calendar;
    this.legsWithoutExchange = meterRegistry.counter("ay_scalper_chain_leg_no_exchange_total");
    io.micrometer.core.instrument.Gauge.builder(
            "ay_scalper_chain_exchange_capability",
            chainPublishesExchange,
            b -> b.get() ? 1d : 0d)
        .description("1 while market-data publishes the canonical exchange on each chain leg")
        .register(meterRegistry);
    this.uriFactory = new org.springframework.web.util.DefaultUriBuilderFactory(baseUrl);
  }

  /**
   * The full per-bar context: the engine-supplied {@code chart} plus the freshly-read OI and macro
   * halves. This is the §12.2 assembler — a thin composition, since the OI/macro reads each carry
   * their own failure isolation.
   *
   * @param underlying index name as market-data knows it (e.g. {@code "NIFTY 50"})
   * @param istTime the bar's IST wall-clock (drives the time-window gate)
   * @param eodDate the LIVE BAR's IST date for the EOD-sourced reads (breadth/FII). Pass the bar's
   *     own date: {@link #macro} resolves the last SETTLED session from it internally. It used to be
   *     the caller's job to subtract a session, and all three call sites passed today instead —
   *     which reads empty all session, every session, because NSE publishes this data post-close.
   * @param expiry the option expiry the scalp will trade (options analytics require it)
   * @param tradeDate the live bar's IST date — drives the monthly-expiry OI suppression (S24 caveat)
   * @param chart the chart dots already computed by the engine {@code IndicatorBank}
   */
  public ScalperGateContext context(
      String underlying,
      String signalIndex,
      java.time.LocalTime istTime,
      LocalDate eodDate,
      LocalDate expiry,
      LocalDate tradeDate,
      ScalperGateContext.Chart chart) {
    // OI/macro key off `underlying` (the oi-confluence index); `signalIndex` rides through to the
    // §0B volume-floor dot (the chart's volume is the signal future's). Equal for a single-index scalper.
    return new ScalperGateContext(
        underlying, signalIndex, istTime, chart,
        oi(underlying, expiry, tradeDate), macro(underlying, eodDate, expiry));
  }

  /**
   * #2 Open=High per-strike session footprint: the ATM strike plus one {@link StrikeStat} per
   * CE/PE leg over the ATM+-window listed strikes (the {@code /options/strike-session-stats}
   * endpoint). {@code items} is empty when the endpoint is unavailable or returns no rows — the
   * {@link OpenHighLowGate} then blocks (a missing footprint never yields a false HIGH).
   */
  public record OpenHighStats(BigDecimal atmStrike, List<StrikeStat> items) {
    static final OpenHighStats EMPTY = new OpenHighStats(null, List.of());
  }

  /** One CE/PE leg's session OH/OL footprint (the subset of the endpoint the grading reads). */
  public record StrikeStat(
      BigDecimal strike,
      Black76.OptionType type,
      boolean ohMark,
      boolean olMark,
      BigDecimal last,
      BigDecimal open,
      BigDecimal high,
      Long declineVolume,
      BigDecimal fallPctFromPrevClose,
      // W3 PR-6: per-strike session change-in-OI % (the Day-14 p20 AVOID veto operand). Nullable —
      // a market-data without the field (older deploy) maps to null, so the veto degrades to safe.
      BigDecimal oiChangePct) {}

  /**
   * The #2-only per-strike OH/OL footprint for {@code underlying}'s {@code expiry} chain over the
   * ATM+-{@code window} listed strikes. Fetched directly by {@link OpenHighLowGate} (NOT folded into
   * the shared {@link #context} fan-out — only the open-high-low strategy pays this HTTP call). A
   * missing endpoint / empty body degrades to {@link OpenHighStats#EMPTY} so the gate blocks.
   */
  public OpenHighStats openHighStats(String underlying, LocalDate expiry, int window) {
    return get(
        uri ->
            uri.path("/api/v1/market/options/strike-session-stats")
                .queryParam("underlying", underlying)
                .queryParam("expiry", expiry)
                .queryParam("window", window)
                .build(),
        this::toOpenHighStats,
        OpenHighStats.EMPTY,
        "options/strike-session-stats");
  }

  private OpenHighStats toOpenHighStats(JsonNode json) {
    BigDecimal atm = decimal(json.path("atmStrike"));
    List<StrikeStat> items = new ArrayList<>();
    for (JsonNode row : json.path("items")) {
      Black76.OptionType type = optionType(text(row.path("optionType")));
      BigDecimal strike = decimal(row.path("strike"));
      if (type == null || strike == null) {
        continue; // a malformed row is skipped, not fatal
      }
      items.add(
          new StrikeStat(
              strike,
              type,
              row.path("ohMark").asBoolean(false),
              row.path("olMark").asBoolean(false),
              decimal(row.path("last")),
              decimal(row.path("open")),
              decimal(row.path("high")),
              longOrNull(row.path("declineVolume")),
              decimal(row.path("fallPctFromPrevClose")),
              decimal(row.path("oiChangePct"))));
    }
    return new OpenHighStats(atm, items);
  }

  private static Black76.OptionType optionType(String raw) {
    if ("CE".equals(raw)) {
      return Black76.OptionType.CE;
    }
    if ("PE".equals(raw)) {
      return Black76.OptionType.PE;
    }
    return null;
  }

  private static Long longOrNull(JsonNode node) {
    String t = text(node);
    return t == null ? null : Long.valueOf(t);
  }

  /**
   * One strike's CE/PE open interest from the live chain - the LIVE-only per-strike OI ladder the #7
   * Hero-Zero selector walks to find the max-OI short-covering strike. Never on the parity path (the
   * chosen leg is persisted at entry, section 12.9).
   */
  public record StrikeOi(BigDecimal strike, Long ceOi, Long peOi) {}

  /**
   * The nearest-expiry option chain flattened for {@link StrikePicker}: spot, forward, the candidates,
   * plus the per-strike CE/PE OI ladder ({@link #strikeOi}) used only by the #7 Hero-Zero one-away
   * strike selection. The OI ladder is live-only - the chosen leg rides the V009 side-channel, so it
   * never enters deterministic replay.
   */
  public record ChainSnapshot(
      LocalDate expiry,
      BigDecimal spot,
      BigDecimal forward,
      List<StrikePicker.Candidate> candidates,
      List<StrikeOi> strikeOi) {

    /** Back-compat constructor (no OI ladder) - the per-strike OI defaults to empty. */
    public ChainSnapshot(
        LocalDate expiry, BigDecimal spot, BigDecimal forward, List<StrikePicker.Candidate> candidates) {
      this(expiry, spot, forward, candidates, List.of());
    }

    /** Forward - spot - the StrikePicker basis (Black-76 is on the forward). */
    public BigDecimal basis() {
      return forward == null || spot == null ? BigDecimal.ZERO : forward.subtract(spot);
    }
  }

  /**
   * The live nearest-expiry chain for {@code underlying} (no explicit expiry → market-data resolves
   * the nearest), flattened into both-side {@link StrikePicker.Candidate}s. Empty when the chain is
   * unavailable or carries no usable legs — the seam then blocks rather than guesses a strike. The
   * leg {@code iv} is already a fraction (the solver's sigma), so it passes straight to StrikePicker.
   */
  public Optional<ChainSnapshot> chain(String underlying) {
    return Optional.ofNullable(
        get(
            uri -> uri.path("/api/v1/market/options/chain").queryParam("underlying", underlying).build(),
            this::toChainSnapshot,
            null,
            "options/chain"));
  }

  private ChainSnapshot toChainSnapshot(JsonNode chain) {
    String expiryRaw = text(chain.path("expiry"));
    if (expiryRaw == null) {
      return null;
    }
    BigDecimal spot = decimal(chain.path("spot"));
    BigDecimal forward = decimal(chain.path("forward"));
    List<StrikePicker.Candidate> candidates = new ArrayList<>();
    List<StrikeOi> strikeOi = new ArrayList<>();
    for (JsonNode row : chain.path("rows")) {
      BigDecimal strike = decimal(row.path("strike"));
      if (strike == null) {
        continue;
      }
      addLeg(candidates, strike, Black76.OptionType.CE, row.path("ce"));
      addLeg(candidates, strike, Black76.OptionType.PE, row.path("pe"));
      // The per-strike OI ladder (#7 Hero-Zero one-away selection) - independent of the tradeable
      // filter: a strike with no usable (ltp+iv) leg can still be the max-OI SC strike.
      strikeOi.add(
          new StrikeOi(strike, longOrNull(row.path("ce").path("oi")), longOrNull(row.path("pe").path("oi"))));
    }
    if (candidates.isEmpty()) {
      return null;
    }
    return new ChainSnapshot(LocalDate.parse(expiryRaw), spot, forward, candidates, strikeOi);
  }

  /**
   * Maps one chain-row side to a {@link StrikePicker.Candidate}, resolving its canonical exchange.
   *
   * <p>A leg needs a live premium and a solved IV to be a candidate at all. Its canonical
   * {@code (exchange, tradingsymbol)} key is what makes it <b>tradeable</b>, and the exchange comes
   * from the instrument master — published on the payload by market-data, which already resolved
   * the instrument to quote it — and is NEVER inferred from the underlying's name. A name-prefix
   * guess silently mis-routes any newly listed BSE root, and downstream that means a 404'd
   * instrument-meta lookup, a lot-1 equity proxy and a non-lot-aligned quantity that also 400s the
   * Upstox margin call (UDAPI1104).
   *
   * <p><b>Absent ⇒ KEEP the candidate with a NULL exchange</b> (cross-vendor review
   * Critical 1). Dropping it here was an ENTRY-safety reflex applied to a path that also serves
   * EXITS: this same snapshot feeds the read-only confluence-flip exit oracle, and an absent
   * decision reads as "do not exit" ({@code SignalEngine.confluenceFlipExit} → {@code
   * now.isPresent()}), so an open position would have silently lost its CONFLUENCE_FLIP rail. That
   * inverts the project doctrine: entries need FRESH truth (you can always not enter), exits need
   * the BEST AVAILABLE truth (you cannot refuse to leave forever). Analytical eligibility and TRADE
   * eligibility are separate concerns — the null travels with the candidate and {@code
   * SignalEngine.tradeableLeg} refuses the ENTRY there — and only there does {@code
   * OptionExchangeResolver} get consulted, because this parse re-runs for every strategy on every
   * bar (the memo caches the response BODY, not the parsed snapshot), so a per-leg lookup here would
   * multiply into hundreds of synchronous calls on the single evaluation thread that also drives
   * EXITS. Loud either way: a WARN naming the symbol plus
   * {@code ay_scalper_chain_leg_no_exchange_total}.
   */
  private void addLeg(
      List<StrikePicker.Candidate> out, BigDecimal strike, Black76.OptionType type, JsonNode leg) {
    BigDecimal ltp = decimal(leg.path("ltp"));
    BigDecimal iv = decimal(leg.path("iv"));
    if (ltp == null || iv == null || iv.signum() <= 0) {
      return;
    }
    String tradingsymbol = text(leg.path("tradingsymbol"));
    String exchange = text(leg.path("exchange"));
    boolean fromPayload = exchange != null && !exchange.isBlank();
    chainPublishesExchange.set(fromPayload);
    if (!fromPayload) {
      exchange = null; // a blank is as unkeyed as a missing one — never an empty-string key
      legsWithoutExchange.increment();
      log.warn(
          "chain leg {} (strike {} {}) carries no exchange — retained for read-only exit/confluence"
              + " evaluation; the entry path resolves it from the instrument master or refuses",
          tradingsymbol, strike, type);
    }
    out.add(new StrikePicker.Candidate(exchange, tradingsymbol, strike, type, ltp, iv));
  }

  /**
   * The OI confluence half: underlying + futures quadrants, sentiment, PE−CE cross, futures basis.
   *
   * <p><b>S24 monthly-expiry caveat:</b> on a MONTHLY index-expiry day the expiring series' writers
   * are unwinding, so the chain-OI / Trending-OI read is corrupted. The four OI reads (spurt /
   * futures-quadrant / active-strikes / trending) are SKIPPED and degrade to their inert defaults
   * (NEUTRAL quadrants, null soft-numerics, false flags) — exactly as if the OI endpoints were
   * unavailable, so every OI dot/gate is non-confirming. The price-derived futures basis is kept.
   */
  public Oi oi(String underlying, LocalDate expiry, LocalDate tradeDate) {
    // Per-root expiry model (P1-9): SENSEX suppression keys on the BSE Thursday monthly, not NSE's.
    if (ScalperCalendars.forUnderlying(underlying).isMonthlyIndexExpiryDay(tradeDate)) {
      log.debug("scalper OI suppressed for monthly-expiry day {} ({}) - chain-OI is corrupted (S24)", tradeDate, underlying);
      BigDecimal futuresBasis = futuresBasis(underlying);
      return new Oi(
          OiQuadrant.NEUTRAL, OiQuadrant.NEUTRAL, null, null, futuresBasis, null, null, null,
          false, false, null, null, null);
    }
    // /options/spurt: one read → the underlying quadrant PLUS the spurt OI/price magnitudes (§A6).
    Spurt spurt =
        get(
            uri ->
                uri.path("/api/v1/market/options/spurt")
                    .queryParam("name", underlying)
                    .queryParam("expiry", expiry)
                    .build(),
            this::deriveSpurt,
            Spurt.EMPTY,
            "options/spurt");

    OiQuadrant futuresQuadrant =
        get(
            uri ->
                uri.path("/api/v1/market/futures/banks").queryParam("name", underlying).build(),
            json -> frontFuturesQuadrant(json),
            OiQuadrant.NEUTRAL,
            "futures/banks");

    // /options/active-strikes?buckets=N: one read → the sentiment LEVEL plus the sentiment SLOPE (§A5).
    Sentiment sentiment =
        get(
            uri ->
                uri.path("/api/v1/market/options/active-strikes")
                    .queryParam("name", underlying)
                    .queryParam("expiry", expiry)
                    .queryParam("buckets", SERIES_WINDOW)
                    .build(),
            this::deriveSentiment,
            Sentiment.EMPTY,
            "options/active-strikes");

    // /options/trending: one read → the PE−CE LEVEL plus the full-window derivations (§A3).
    Trending trending =
        get(
            uri ->
                uri.path("/api/v1/market/options/trending")
                    .queryParam("name", underlying)
                    .queryParam("expiry", expiry)
                    // pin the rolling window: without `buckets` the endpoint now serves the FULL
                    // session (trending-page baseline fix) and the first-vs-last derivations below
                    // would silently become session-cumulative
                    .queryParam("buckets", SERIES_WINDOW)
                    .build(),
            this::deriveTrending,
            Trending.EMPTY,
            "options/trending");

    BigDecimal futuresBasis = futuresBasis(underlying);

    return new Oi(
        spurt.quadrant(),
        futuresQuadrant,
        sentiment.level(),
        trending.peMinusCePct(),
        futuresBasis,
        trending.ceOiDelta(),
        trending.peOiDelta(),
        trending.imbalancePct(),
        trending.crossed(),
        trending.gapWidening(),
        sentiment.slope(),
        spurt.oiPct(),
        spurt.pricePct(),
        trending.divergencePct(),
        // Measurement-only carrier — no gate or dot reads it (see SentimentLevelShadow).
        sentiment.levelBased());
  }

  /**
   * E2 M7 (P12): the UP(+1)/DOWN(-1)/UNKNOWN(0) sign of the 60-minute OI build — a FOCUSED 2nd
   * {@code /options/trending?interval=60m} read (the slower broader-trend confirmation above the 5m
   * series). Called from {@link ScalperConfluenceGate} ONLY when the {@code oi-interval-and-60m-trend}
   * gate is armed, so the primary trending read is untouched and unarmed scalpers pay no extra fetch.
   * The sign is the 60m CE/PE OI delta-imbalance (peΔ − ceΔ over the window): {@code >0} = PE building
   * faster (put-writing = bullish/CE), {@code <0} the mirror; {@code 0} when the series is short/flat,
   * the read fails, or the day is a monthly index expiry (chain-OI corrupt, S24) → a fail-open gate.
   */
  public int trend60mDir(String underlying, LocalDate expiry, LocalDate tradeDate) {
    if (ScalperCalendars.forUnderlying(underlying).isMonthlyIndexExpiryDay(tradeDate)) {
      return 0;
    }
    return get(
        uri ->
            uri.path("/api/v1/market/options/trending")
                .queryParam("name", underlying)
                .queryParam("expiry", expiry)
                .queryParam("interval", "60m")
                // pin the rolling window (see the primary trending read above)
                .queryParam("buckets", SERIES_WINDOW)
                .build(),
        this::deriveTrend60mDir,
        0,
        "options/trending?interval=60m");
  }

  /** §M7: the sign of the 60m CE/PE OI delta-imbalance over the trending window (0 when short/flat). */
  Integer deriveTrend60mDir(JsonNode trending) {
    JsonNode items = trending.path("items");
    if (!items.isArray() || items.size() < 2) {
      return 0;
    }
    JsonNode first = items.get(0);
    JsonNode last = items.get(items.size() - 1);
    long ceBuild = last.path("ceOi").asLong() - first.path("ceOi").asLong();
    long peBuild = last.path("peOi").asLong() - first.path("peOi").asLong();
    return Long.signum(peBuild - ceBuild);
  }

  /** Front-contract absolute futures basis (F − S) — price-derived, so NOT suppressed on a monthly expiry. */
  private BigDecimal futuresBasis(String underlying) {
    return get(
        uri ->
            uri.path("/api/v1/market/futures/term-structure")
                .queryParam("underlying", underlying)
                .build(),
        this::frontContractBasis,
        null,
        "futures/term-structure");
  }

  /**
   * The most recent session whose EOD data can exist, given a bar on {@code tradeDate}.
   *
   * <p>NSE publishes FII/participant and bhavcopy data AFTER the close, so for a live intraday bar
   * the EOD reads must ask for the PREVIOUS session — asking for today returns nothing all session,
   * every session. This is resolved here, once, rather than at each call site: the parameter callers
   * pass is the live bar's date (they cannot know which session has settled data), and a caller that
   * has to remember to subtract a day is a caller that will eventually forget. Three call sites had
   * already forgotten.
   *
   * <p>It also removes a latent LOOKAHEAD on any replay path: reading a session's own FII data while
   * that session is still running would consume numbers published after its close.
   *
   * <p>Past the bundled holiday-CSV horizon the calendar throws; degrade to the bar date rather than
   * take the gate down — that reproduces the stale-read behaviour but never breaks evaluation.
   */
  private LocalDate lastSettledSession(LocalDate tradeDate) {
    try {
      return calendar.previousTradingDay(tradeDate);
    } catch (RuntimeException uncoveredYear) {
      log.warn(
          "EOD reads falling back to the bar date {} — NSE calendar does not cover it"
              + " (CD-2 cliff); breadth/FII will read empty",
          tradeDate);
      return tradeDate;
    }
  }

  /** The macro confluence half: ATM IV + rank, breadth, FII positioning (VIX is a v1 gap → null). */
  public Macro macro(String underlying, LocalDate tradeDate, LocalDate expiry) {
    // Every EOD-sourced read below asks for the last SETTLED session, never the live bar's own date.
    LocalDate eodDate = lastSettledSession(tradeDate);
    JsonNode ivHistory =
        get(
            uri ->
                uri.path("/api/v1/market/options/iv-history")
                    .queryParam("underlying", underlying)
                    .build(),
            json -> json,
            objectMapper.nullNode(),
            "options/iv-history");
    BigDecimal atmIv = decimal(ivHistory.path("currentIv"));
    // /iv-history.rank is a 0..1 fraction; the scorer's IV_RANK_LOW gate is on a 0..100 scale, so
    // scale here (×100) — otherwise the iv_rank dot would fire on every signal. null when the
    // floor (60 trading days) is not met (insufficientHistory) → the dot stays unconfirmed.
    BigDecimal rank = decimal(ivHistory.path("rank"));
    BigDecimal ivRank = rank == null ? null : rank.multiply(HUNDRED);

    // F3.1 live breadth: the "advances > 32" rule is a NIFTY-50-universe rule, so the operand MUST be
    // the ~50-name constituent fold. `/breadth/live` serves exactly that and already falls back to an
    // EOD read WITHIN the same universe (its `live` flag reports which it used), so one read is enough.
    //
    // The old `/breadth?date=` second fallback is REMOVED, not re-dated. That endpoint counts the whole
    // NSE EQ bhavcopy — thousands of names, not fifty — so a >32 test against it is satisfied by
    // essentially any session and would support BOTH sides at once, manufacturing entries and flip
    // exits out of a breadth outage. It was inert only because it was being asked for TODAY (0/0 until
    // the post-close bhavcopy lands); pointing it at a settled date would have armed a scale mismatch
    // that market-data's own BreadthController javadoc warns about. Degrade to 0/0 instead: a dead
    // breadth read must never confirm a side.
    int[] breadth =
        get(
            uri -> uri.path("/api/v1/market/breadth/live").queryParam("index", "NIFTY 50").build(),
            this::advanceDecline,
            new int[] {0, 0},
            "breadth-live");

    BigDecimal fiiLongPct =
        get(
            uri ->
                uri.path("/api/v1/market/fii-dii/long-short")
                    .queryParam("from", eodDate)
                    .build(),
            this::latestFiiLongPct,
            null,
            "fii-dii/long-short");

    // §A4 + E7: the 6-strike CE/PE IV pair (3 above + 3 below the ATM) AND the per-side ATM premium
    // skew, derived in ONE pass over the same /options/chain read (no extra round-trip). null pair when
    // < 6 usable strikes; null skew when the ATM premiums are absent → each dependent dot stays inert.
    ChainDerived chain =
        get(
            uri -> uri.path("/api/v1/market/options/chain").queryParam("underlying", underlying).build(),
            this::deriveChain,
            ChainDerived.EMPTY,
            "options/chain");

    // E3 §4.6 constituent contribution: the index heavyweights' net weighted % push (its SIGN is the
    // constituent direction). Read off the existing /equity/index-contribution endpoint; null on a
    // bhavcopy/weights gap → the constituent gate degrades to pass.
    BigDecimal constituentBias =
        get(
            uri ->
                uri.path("/api/v1/market/equity/index-contribution")
                    .queryParam("name", underlying)
                    .build(),
            json -> decimal(json.path("indexChangePct")),
            null,
            "equity/index-contribution");

    // E4 iv-per-strike §4.6: the per-strike IV DIRECTION — the signed CE/PE IV slope of the peak-OI
    // strike over the active-strike window. Reuses the same /options/active-strikes read shape as oi()
    // (name+expiry+buckets — the activeStrikeIvSeries is serialized only when `buckets` is present).
    // null slopes on a short/absent series → the iv_slope dot stays inert.
    IvSlope ivSlope =
        get(
            uri ->
                uri.path("/api/v1/market/options/active-strikes")
                    .queryParam("name", underlying)
                    .queryParam("expiry", expiry)
                    .queryParam("buckets", SERIES_WINDOW)
                    .build(),
            this::deriveActiveStrikeIvSlope,
            IvSlope.EMPTY,
            "options/active-strikes");

    // E3 directional VIX: the INDIA VIX quote (level + the change-vs-prev-close → rising/falling). VIX
    // rising favours PE, falling favours CE (the §A4 vix dot + the directional-vix-gate read these). A
    // 422/absent quote (off-hours / mock / history) degrades to null → the vix gate stays non-blocking.
    Vix vix =
        get(uri -> uri.path("/api/v1/market/vix").build(), this::deriveVix, Vix.EMPTY, "vix");

    // E3 Dow global cue: the Dow Jones LTP-direction (up → bullish/CE, down → bearish/PE). A 422 (global
    // feed unconfigured / off-hours / history) degrades to null → the dow dot stays neutral.
    Boolean dowUp =
        get(uri -> uri.path("/api/v1/market/global/dow").build(), this::deriveDowUp, null, "global/dow");

    // E3 §3.3 fii-dii-bias: the COMBINED FII EOD participant bias sign (the futures change-in-OI
    // classifier + the option-leg seller read), read off the new /fii-dii/bias endpoint for the prior
    // completed session's tradeDate. null on a participant-OI gap → the fii-dii-gate degrades to pass.
    BigDecimal fiiBiasSign =
        get(
            uri -> uri.path("/api/v1/market/fii-dii/bias").queryParam("date", eodDate).build(),
            json -> decimal(json.path("biasSign")),
            null,
            "fii-dii/bias");

    return new Macro(
        atmIv, ivRank, vix.level(), vix.rising(), breadth[0], breadth[1], fiiLongPct,
        chain.ivPair().ceIvAvg6(), chain.ivPair().peIvAvg6(),
        constituentBias, ivSlope.ceSlope(), ivSlope.peSlope(), chain.premiumSkewPct(), dowUp,
        fiiBiasSign);
  }

  /** Front (nearest-expiry) index-future quadrant from the term-structure-with-interpretation grid. */
  private OiQuadrant frontFuturesQuadrant(JsonNode banks) {
    JsonNode front = frontByExpiry(banks.path("items"));
    return front == null ? OiQuadrant.NEUTRAL : OiQuadrant.fromInterpretation(text(front.path("interpretation")));
  }

  /** Front-contract absolute basis (F − S), server-computed; null if the term structure is empty. */
  private BigDecimal frontContractBasis(JsonNode termStructure) {
    JsonNode front = frontByExpiry(termStructure.path("contracts"));
    return front == null ? null : decimal(front.path("basisAbsolute"));
  }

  /** The nearest-expiry leg of a contract array (min {@code expiry}); null if none carry an expiry. */
  private static JsonNode frontByExpiry(JsonNode contracts) {
    JsonNode front = null;
    LocalDate frontExpiry = null;
    for (JsonNode leg : contracts) {
      String raw = text(leg.path("expiry"));
      if (raw == null) {
        continue;
      }
      LocalDate expiry = LocalDate.parse(raw);
      if (frontExpiry == null || expiry.isBefore(frontExpiry)) {
        frontExpiry = expiry;
        front = leg;
      }
    }
    return front;
  }

  // ---------------------------------------------------------------------------------------------
  // Phase-3.5 temporal derivations (§A3/A4/A5/A6). All temporal math lives HERE so the pure,
  // point-in-time scorer just reads the resulting fields. Each helper degrades to a value that
  // never falsely confirms a side: null/false on a short or absent series, never an exception.
  // ---------------------------------------------------------------------------------------------

  /**
   * §A3 carrier: the PE−CE LEVEL (unchanged from the old {@code latestPeMinusCePct}) plus the
   * full-window derivations over the {@code /options/trending} series.
   */
  record Trending(
      BigDecimal peMinusCePct,
      BigDecimal ceOiDelta,
      BigDecimal peOiDelta,
      BigDecimal imbalancePct,
      boolean crossed,
      boolean gapWidening,
      BigDecimal divergencePct) {
    static final Trending EMPTY = new Trending(null, null, null, null, false, false, null);
  }

  /**
   * §A3: the trending full-series derivations over {@code {items:[{ceOi,peOi}], ...}} (newest-last).
   * Reuses the same JSON that feeds the PE−CE level — one fetch maps to both. Signed CE/PE OI deltas
   * (last − first), the call/put delta imbalance % (null on the FLAT-OI caveat: both deltas ~0), the
   * within-window PE-over-CE / CE-over-PE sign cross, and whether the latest gap widened vs the prior.
   */
  Trending deriveTrending(JsonNode trending) {
    JsonNode items = trending.path("items");
    if (!items.isArray() || items.isEmpty()) {
      return Trending.EMPTY;
    }
    JsonNode last = items.get(items.size() - 1);
    BigDecimal level = peMinusCePct(last);
    if (items.size() < 2) {
      // A single bucket gives a level but no temporal signal — all derivations null/false.
      return new Trending(level, null, null, null, false, false, null);
    }
    JsonNode first = items.get(0);
    JsonNode prior = items.get(items.size() - 2);

    long ceFirst = first.path("ceOi").asLong();
    long peFirst = first.path("peOi").asLong();
    long ceLast = last.path("ceOi").asLong();
    long peLast = last.path("peOi").asLong();

    BigDecimal ceDelta = BigDecimal.valueOf(ceLast - ceFirst);
    BigDecimal peDelta = BigDecimal.valueOf(peLast - peFirst);
    BigDecimal imbalance = imbalancePct(ceDelta, peDelta);

    long gapFirst = peFirst - ceFirst;
    long gapPrior = (prior.path("peOi").asLong()) - (prior.path("ceOi").asLong());
    long gapLast = peLast - ceLast;
    // A sign transition of (peOi − ceOi) across the window: first below 0 → last above 0 (bullish
    // PE-over-CE cross) or first above → last below (bearish). Zero on either edge is not a cross.
    boolean crossed = (gapFirst < 0 && gapLast > 0) || (gapFirst > 0 && gapLast < 0);
    boolean widening = Math.abs(gapLast) > Math.abs(gapPrior);
    // E2 M3: the SIGNED PE−CE gap as a % of the latest bucket's total OI — the "lines diverge ~20-30%"
    // magnitude AND direction (>0 = PE-heavy = bullish/CE; <0 = CE-heavy = bearish/PE). Null when the
    // bucket carries no OI (flat) so a missing read can't pass the gate. The gate reads the sign so the
    // divergence must favour the traded side (not just be large in either direction).
    long totalLast = peLast + ceLast;
    BigDecimal divergencePct =
        totalLast <= 0 ? null : BigDecimal.valueOf(gapLast * 100.0 / totalLast);

    return new Trending(level, ceDelta, peDelta, imbalance, crossed, widening, divergencePct);
  }

  /** A single trending bucket's PE−CE tilt as a % of total OI; null when the bucket carries no OI. */
  private BigDecimal peMinusCePct(JsonNode bucket) {
    long ceOi = bucket.path("ceOi").asLong();
    long peOi = bucket.path("peOi").asLong();
    long total = ceOi + peOi;
    if (total == 0) {
      return null;
    }
    return BigDecimal.valueOf(peOi - ceOi)
        .multiply(HUNDRED)
        .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
  }

  /**
   * {@code |peDelta − ceDelta| / max(|peDelta|, |ceDelta|) × 100}, scale 4. null when both deltas are
   * ~0 — the FLAT-OI caveat: a static PE/CE gap with unchanged OI over the window must yield null, not
   * a large (here, divide-by-zero) imbalance.
   */
  private BigDecimal imbalancePct(BigDecimal ceDelta, BigDecimal peDelta) {
    BigDecimal denom = ceDelta.abs().max(peDelta.abs());
    if (denom.signum() == 0) {
      return null;
    }
    return peDelta.subtract(ceDelta).abs().multiply(HUNDRED).divide(denom, 4, RoundingMode.HALF_UP);
  }

  /**
   * §A5 carrier: the sentiment LEVEL plus the signed slope over the {@code sentimentSeries} window.
   *
   * <p>{@code levelBased} is the MEASUREMENT-ONLY sibling: market-data's {@code sentimentLevelPct}
   * (OI stock, the oipulse convention) beside the ΔOI-FLOW {@code level} the gates consume. Read by
   * {@link SentimentLevelShadow} only; null when the field is absent from the payload.
   */
  record Sentiment(BigDecimal level, BigDecimal slope, BigDecimal levelBased) {
    static final Sentiment EMPTY = new Sentiment(null, null, null);

    /** Flow-only form (the shape before the shadow carrier): {@code levelBased} defaults to null. */
    Sentiment(BigDecimal level, BigDecimal slope) {
      this(level, slope, null);
    }
  }

  /**
   * §A5: the sentiment LEVEL (scalar {@code sentimentPct}) plus a simple signed slope over the new
   * {@code sentimentSeries:[{sentimentPct}]} (newest-last): {@code last − first}. null slope when the
   * series is shorter than 2 buckets or absent — the level is still surfaced from the scalar.
   *
   * <p>The LEVEL-based {@code sentimentLevelPct} rides alongside on every branch — it is a sibling
   * scalar of {@code sentimentPct}, independent of the series, so a short/absent series must not
   * suppress it. {@code decimal()} already maps a missing node to null, so an older market-data that
   * does not publish the key degrades to "no shadow verdict" with no branch of its own.
   */
  Sentiment deriveSentiment(JsonNode json) {
    BigDecimal level = decimal(json.path("sentimentPct"));
    BigDecimal levelBased = decimal(json.path("sentimentLevelPct"));
    JsonNode series = json.path("sentimentSeries");
    if (!series.isArray() || series.size() < 2) {
      return new Sentiment(level, null, levelBased);
    }
    BigDecimal first = decimal(series.get(0).path("sentimentPct"));
    BigDecimal last = decimal(series.get(series.size() - 1).path("sentimentPct"));
    if (first == null || last == null) {
      return new Sentiment(level, null, levelBased);
    }
    return new Sentiment(level, last.subtract(first), levelBased);
  }

  /** §A6 carrier: the underlying quadrant plus the representative spurt OI/price %-changes. */
  record Spurt(OiQuadrant quadrant, BigDecimal oiPct, BigDecimal pricePct) {
    static final Spurt EMPTY = new Spurt(OiQuadrant.NEUTRAL, null, null);
  }

  /**
   * §A6: the underlying quadrant (from {@code summary.interpretation}) plus the spurt OI/price
   * magnitudes ({@code summary.oiChangePct} / {@code summary.priceChangePct}) — one read. null
   * magnitudes when absent; the quadrant degrades to NEUTRAL.
   */
  Spurt deriveSpurt(JsonNode json) {
    JsonNode summary = json.path("summary");
    OiQuadrant quadrant = OiQuadrant.fromInterpretation(text(summary.path("interpretation")));
    return new Spurt(
        quadrant, decimal(summary.path("oiChangePct")), decimal(summary.path("priceChangePct")));
  }

  /** §A4 carrier: the 6-strike CE/PE IV averages (3 strikes above + 3 below the ATM). */
  record IvPair(BigDecimal ceIvAvg6, BigDecimal peIvAvg6) {
    static final IvPair EMPTY = new IvPair(null, null);
  }

  /** E3 directional VIX: the INDIA VIX level + whether it is rising (change vs the prior close > 0). */
  record Vix(BigDecimal level, Boolean rising) {
    static final Vix EMPTY = new Vix(null, null);
  }

  /** Maps the {@code /vix} quote to the level + a rising flag; a null/zero change leaves direction unknown. */
  Vix deriveVix(JsonNode quote) {
    BigDecimal level = decimal(quote.path("ltp"));
    BigDecimal change = decimal(quote.path("change"));
    Boolean rising = change == null || change.signum() == 0 ? null : change.signum() > 0;
    return new Vix(level, rising);
  }

  /** Maps the {@code /global/dow} quote's signed direction to up/down/unknown (0/flat ⇒ null = neutral). */
  Boolean deriveDowUp(JsonNode quote) {
    JsonNode dir = quote.path("direction");
    if (!dir.isNumber()) {
      return null;
    }
    int d = dir.asInt();
    return d == 0 ? null : d > 0;
  }

  /** The two values derived from a single {@code /options/chain} read: the 6-strike IV pair + the skew. */
  record ChainDerived(IvPair ivPair, BigDecimal premiumSkewPct) {
    static final ChainDerived EMPTY = new ChainDerived(IvPair.EMPTY, null);
  }

  /** Derive both the §A4 IV pair and the E7 premium skew from one chain payload (no extra round-trip). */
  ChainDerived deriveChain(JsonNode chain) {
    return new ChainDerived(deriveIvPair(chain), derivePremiumSkew(chain));
  }

  /**
   * E7 §3.7/§6.7 per-side premium skew = (CE ATM ltp − PE ATM ltp) / PE ltp × 100, at the strike nearest
   * the spot. Positive ⇒ the CE side is the richer (more-expensive) side, negative ⇒ PE is. null when
   * the chain/spot/ATM premiums are absent (or the PE leg is zero) → the premium-skew dot degrades to
   * neutral, never blocking on a missing feed.
   */
  BigDecimal derivePremiumSkew(JsonNode chain) {
    BigDecimal spot = decimal(chain.path("spot"));
    JsonNode rows = chain.path("rows");
    if (spot == null || !rows.isArray() || rows.isEmpty()) {
      return null;
    }
    JsonNode atmRow = null;
    BigDecimal best = null;
    for (JsonNode row : rows) {
      BigDecimal strike = decimal(row.path("strike"));
      if (strike == null) {
        continue;
      }
      BigDecimal diff = strike.subtract(spot).abs();
      if (best == null || diff.compareTo(best) < 0) {
        best = diff;
        atmRow = row;
      }
    }
    if (atmRow == null) {
      return null;
    }
    BigDecimal ceLtp = decimal(atmRow.path("ce").path("ltp"));
    BigDecimal peLtp = decimal(atmRow.path("pe").path("ltp"));
    if (ceLtp == null || peLtp == null || peLtp.signum() == 0) {
      return null;
    }
    return ceLtp.subtract(peLtp).divide(peLtp, 4, RoundingMode.HALF_UP).multiply(HUNDRED);
  }

  /**
   * §A4: the mean CE IV and mean PE IV over the 3 strikes ABOVE plus the 3 BELOW the ATM (the strike
   * nearest to {@code spot}) — 6 strikes. null for a side when fewer than 6 usable strikes exist or
   * any needed IV on that side is missing. IVs are fractions ({@code rows[].ce.iv}/{@code .pe.iv}).
   */
  IvPair deriveIvPair(JsonNode chain) {
    BigDecimal spot = decimal(chain.path("spot"));
    JsonNode rows = chain.path("rows");
    if (spot == null || !rows.isArray() || rows.size() < 6) {
      return IvPair.EMPTY;
    }
    // Sort the rows by strike, then locate the ATM (nearest to spot) and take 3 below + 3 above.
    List<JsonNode> sorted = new ArrayList<>();
    for (JsonNode row : rows) {
      if (decimal(row.path("strike")) != null) {
        sorted.add(row);
      }
    }
    if (sorted.size() < 6) {
      return IvPair.EMPTY;
    }
    sorted.sort((a, b) -> decimal(a.path("strike")).compareTo(decimal(b.path("strike"))));
    int atm = 0;
    BigDecimal best = null;
    for (int i = 0; i < sorted.size(); i++) {
      BigDecimal diff = decimal(sorted.get(i).path("strike")).subtract(spot).abs();
      if (best == null || diff.compareTo(best) < 0) {
        best = diff;
        atm = i;
      }
    }
    int from = atm - 3;
    int to = atm + 3; // exclusive end below, inclusive above → [atm-3 .. atm-1] + [atm+1 .. atm+3]
    if (from < 0 || to >= sorted.size()) {
      return IvPair.EMPTY; // not 3 full strikes on BOTH sides of the ATM
    }
    BigDecimal ceSum = BigDecimal.ZERO;
    BigDecimal peSum = BigDecimal.ZERO;
    int count = 0;
    for (int i = from; i <= to; i++) {
      if (i == atm) {
        continue; // ATM itself is excluded — 3 above + 3 below only
      }
      BigDecimal ceIv = decimal(sorted.get(i).path("ce").path("iv"));
      BigDecimal peIv = decimal(sorted.get(i).path("pe").path("iv"));
      if (ceIv == null || peIv == null) {
        return IvPair.EMPTY; // any missing IV across the 6 → cannot form the pair honestly
      }
      ceSum = ceSum.add(ceIv);
      peSum = peSum.add(peIv);
      count++;
    }
    BigDecimal n = BigDecimal.valueOf(count); // 6
    return new IvPair(
        ceSum.divide(n, 4, RoundingMode.HALF_UP), peSum.divide(n, 4, RoundingMode.HALF_UP));
  }

  /** E4 carrier: the signed CE/PE IV slope of the peak-OI strike over the active-strike window. */
  record IvSlope(BigDecimal ceSlope, BigDecimal peSlope) {
    static final IvSlope EMPTY = new IvSlope(null, null);
  }

  /**
   * E4 §4.6: the per-strike IV DIRECTION — the signed slope ({@code last − first}) of the peak-OI
   * strike's CE IV and PE IV over the {@code activeStrikeIvSeries} window (newest-last), the same
   * {@code last − first} shape {@link #deriveSentiment} uses. null per leg when the series is shorter
   * than 2 buckets or the leg's IV is absent on an endpoint bucket — so a short/empty series can never
   * confirm a side. The interpretation is the deck's strike-demand rule (RISING IV in the bought strike
   * confirms; the OPPOSITE sign from {@code ConnectingDotsService.ivFactor}'s index-fear read).
   */
  IvSlope deriveActiveStrikeIvSlope(JsonNode json) {
    JsonNode series = json.path("activeStrikeIvSeries");
    if (!series.isArray() || series.size() < 2) {
      return IvSlope.EMPTY;
    }
    BigDecimal ceFirst = decimal(series.get(0).path("ceIv"));
    BigDecimal ceLast = decimal(series.get(series.size() - 1).path("ceIv"));
    BigDecimal peFirst = decimal(series.get(0).path("peIv"));
    BigDecimal peLast = decimal(series.get(series.size() - 1).path("peIv"));
    return new IvSlope(
        ceFirst == null || ceLast == null ? null : ceLast.subtract(ceFirst),
        peFirst == null || peLast == null ? null : peLast.subtract(peFirst));
  }

  /** Advances/declines counts from the breadth summary; {@code {0,0}} when absent. */
  private int[] advanceDecline(JsonNode breadth) {
    JsonNode summary = breadth.path("summary");
    return new int[] {summary.path("advances").asInt(0), summary.path("declines").asInt(0)};
  }

  /** Latest FII index-future long share as a %; null if the envelope is empty. */
  private BigDecimal latestFiiLongPct(JsonNode envelope) {
    JsonNode items = envelope.path("items");
    if (!items.isArray() || items.isEmpty()) {
      return null;
    }
    JsonNode last = items.get(items.size() - 1);
    long fiiLong = last.path("fiiLong").asLong();
    long fiiShort = last.path("fiiShort").asLong();
    long total = fiiLong + fiiShort;
    if (total == 0) {
      return null;
    }
    return BigDecimal.valueOf(fiiLong)
        .multiply(HUNDRED)
        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
  }

  /**
   * One isolated GET → mapped value, or {@code fallback} on any failure (HTTP error, empty body,
   * parse error). Logs at debug so a missing primitive is visible without spamming live logs.
   */
  private <T> T get(
      Function<org.springframework.web.util.UriBuilder, java.net.URI> uri,
      Function<JsonNode, T> map,
      T fallback,
      String label) {
    try {
      // Resolve the URI up front so the memo can key on it (P1-12); an absolute URI overrides the
      // client's baseUrl with the identical value.
      java.net.URI resolved = uri.apply(uriFactory.builder());
      long now = System.currentTimeMillis();
      CachedBody hit = memo.get(resolved.toString());
      String body;
      if (hit != null && now < hit.expiresAtMs()) {
        body = hit.body();
      } else {
        body = restClient.get().uri(resolved).retrieve().body(String.class);
        if (body != null && !body.isBlank()) {
          if (memo.size() > 256) {
            memo.clear(); // tiny working set; a day of expiries/underlyings never nears this
          }
          memo.put(resolved.toString(), new CachedBody(body, now + MEMO_TTL_MS));
        }
      }
      if (body == null || body.isBlank()) {
        return fallback;
      }
      T value = map.apply(objectMapper.readTree(body));
      return value == null ? fallback : value;
    } catch (Exception e) {
      log.debug("scalper OI read {} unavailable — using conservative default: {}", label, e.getMessage());
      return fallback;
    }
  }

  /** A JSON node's text, or null when missing/null (never the literal string {@code "null"}). */
  private static String text(JsonNode node) {
    return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
  }

  /** A JSON node's decimal, or null when missing/null — decimal strings parse with no double hop. */
  private static BigDecimal decimal(JsonNode node) {
    String t = text(node);
    return t == null ? null : new BigDecimal(t);
  }
}
