package in.arthayantra.strategysignal.scalper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.black76.Black76;
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

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  /** Wires the configured market-data base URL (same bean pattern as the candle client). */
  public MarketOiClient(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.objectMapper = objectMapper;
  }

  /**
   * The full per-bar context: the engine-supplied {@code chart} plus the freshly-read OI and macro
   * halves. This is the §12.2 assembler — a thin composition, since the OI/macro reads each carry
   * their own failure isolation.
   *
   * @param underlying index name as market-data knows it (e.g. {@code "NIFTY 50"})
   * @param istTime the bar's IST wall-clock (drives the time-window gate)
   * @param eodDate the trade date for the EOD reads (breadth/FII). This is bhavcopy-sourced, so for
   *     a live intraday bar the caller should pass the most-recent COMPLETED session — today's date
   *     422s until the post-close bhavcopy lands, degrading breadth/FII to their inert defaults.
   * @param expiry the option expiry the scalp will trade (options analytics require it)
   * @param chart the chart dots already computed by the engine {@code IndicatorBank}
   */
  public ScalperGateContext context(
      String underlying,
      java.time.LocalTime istTime,
      LocalDate eodDate,
      LocalDate expiry,
      ScalperGateContext.Chart chart) {
    return new ScalperGateContext(underlying, istTime, chart, oi(underlying, expiry), macro(underlying, eodDate));
  }

  /** The nearest-expiry option chain flattened for {@link StrikePicker}: spot, forward, the candidates. */
  public record ChainSnapshot(
      LocalDate expiry, BigDecimal spot, BigDecimal forward, List<StrikePicker.Candidate> candidates) {

    /** Forward − spot — the StrikePicker basis (Black-76 is on the forward). */
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
    for (JsonNode row : chain.path("rows")) {
      BigDecimal strike = decimal(row.path("strike"));
      if (strike == null) {
        continue;
      }
      addLeg(candidates, strike, Black76.OptionType.CE, row.path("ce"));
      addLeg(candidates, strike, Black76.OptionType.PE, row.path("pe"));
    }
    if (candidates.isEmpty()) {
      return null;
    }
    return new ChainSnapshot(LocalDate.parse(expiryRaw), spot, forward, candidates);
  }

  private static void addLeg(
      List<StrikePicker.Candidate> out, BigDecimal strike, Black76.OptionType type, JsonNode leg) {
    BigDecimal ltp = decimal(leg.path("ltp"));
    BigDecimal iv = decimal(leg.path("iv"));
    if (ltp != null && iv != null && iv.signum() > 0) {
      out.add(new StrikePicker.Candidate(text(leg.path("tradingsymbol")), strike, type, ltp, iv));
    }
  }

  /** The OI confluence half: underlying + futures quadrants, sentiment, PE−CE cross, futures basis. */
  public Oi oi(String underlying, LocalDate expiry) {
    OiQuadrant underlyingQuadrant =
        get(
            uri ->
                uri.path("/api/v1/market/options/spurt")
                    .queryParam("name", underlying)
                    .queryParam("expiry", expiry)
                    .build(),
            json -> OiQuadrant.fromInterpretation(text(json.path("summary").path("interpretation"))),
            OiQuadrant.NEUTRAL,
            "options/spurt");

    OiQuadrant futuresQuadrant =
        get(
            uri ->
                uri.path("/api/v1/market/futures/banks").queryParam("name", underlying).build(),
            json -> frontFuturesQuadrant(json),
            OiQuadrant.NEUTRAL,
            "futures/banks");

    BigDecimal sentimentPct =
        get(
            uri ->
                uri.path("/api/v1/market/options/active-strikes")
                    .queryParam("name", underlying)
                    .queryParam("expiry", expiry)
                    .build(),
            json -> decimal(json.path("sentimentPct")),
            null,
            "options/active-strikes");

    BigDecimal trendingPeMinusCePct =
        get(
            uri ->
                uri.path("/api/v1/market/options/trending")
                    .queryParam("name", underlying)
                    .queryParam("expiry", expiry)
                    .build(),
            this::latestPeMinusCePct,
            null,
            "options/trending");

    BigDecimal futuresBasis =
        get(
            uri ->
                uri.path("/api/v1/market/futures/term-structure")
                    .queryParam("underlying", underlying)
                    .build(),
            this::frontContractBasis,
            null,
            "futures/term-structure");

    return new Oi(underlyingQuadrant, futuresQuadrant, sentimentPct, trendingPeMinusCePct, futuresBasis);
  }

  /** The macro confluence half: ATM IV + rank, breadth, FII positioning (VIX is a v1 gap → null). */
  public Macro macro(String underlying, LocalDate tradeDate) {
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

    int[] breadth =
        get(
            uri -> uri.path("/api/v1/market/breadth").queryParam("date", tradeDate).build(),
            this::advanceDecline,
            new int[] {0, 0},
            "breadth");

    BigDecimal fiiLongPct =
        get(
            uri ->
                uri.path("/api/v1/market/fii-dii/long-short")
                    .queryParam("from", tradeDate)
                    .build(),
            this::latestFiiLongPct,
            null,
            "fii-dii/long-short");

    // VIX has no market-data endpoint yet (§12.2 follow-up). null level + null direction; the vix
    // gate treats an unknown direction as non-blocking, so the macro stays honest, not falsely bull.
    return new Macro(atmIv, ivRank, null, null, breadth[0], breadth[1], fiiLongPct);
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

  /** Latest trending point's PE−CE tilt as a % of total OI; null if the series is empty. */
  private BigDecimal latestPeMinusCePct(JsonNode trending) {
    JsonNode items = trending.path("items");
    if (!items.isArray() || items.isEmpty()) {
      return null;
    }
    JsonNode last = items.get(items.size() - 1);
    long ceOi = last.path("ceOi").asLong();
    long peOi = last.path("peOi").asLong();
    long total = ceOi + peOi;
    if (total == 0) {
      return null;
    }
    return BigDecimal.valueOf(peOi - ceOi)
        .multiply(HUNDRED)
        .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
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
      String body = restClient.get().uri(uri).retrieve().body(String.class);
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
