package in.arthayantra.marketdata.options.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.options.OiInterpretation;
import in.arthayantra.marketdata.options.OiInterval;
import in.arthayantra.marketdata.options.OiQuery;
import in.arthayantra.marketdata.options.OptionsChainService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market/options")
public class OptionsAnalyticsController {

  private final OptionsSnapshotReader reader;
  private final OptionsChainService chainService;
  private final ActiveStrikeService activeStrikes;
  private final OiSpurtService spurtService;
  private final OiBigOiService bigOiService;
  private final OiPremiumService premiumService;
  private final OiTrendingService trendingService;
  private final OpenHighStatsService openHighStats;
  private final StraddleChartService straddleChartService;
  private final OptionsOiChartService optionsOiChartService;
  private final OiHeatmapService heatmapService;
  private final OiExpiryService expiryService;
  private final OpenHighStrategyService openHighStrategyService;
  private final ObjectProvider<UpstoxOptionAnalyticsSource> upstoxOptionAnalytics;
  private final MarketCalendar calendar;
  private final int bigOiTopN;
  private final int trendBuckets;
  private final int premiumBuckets;
  private final int heatmapWindow;
  private final int expiryLookbackDays;
  private final int defaultSessionIntervalMinutes;

  public OptionsAnalyticsController(
      OptionsSnapshotReader reader,
      OptionsChainService chainService,
      ActiveStrikeService activeStrikes,
      OiSpurtService spurtService,
      OiBigOiService bigOiService,
      OiPremiumService premiumService,
      OiTrendingService trendingService,
      OpenHighStatsService openHighStats,
      StraddleChartService straddleChartService,
      OptionsOiChartService optionsOiChartService,
      OiHeatmapService heatmapService,
      OiExpiryService expiryService,
      OpenHighStrategyService openHighStrategyService,
      ObjectProvider<UpstoxOptionAnalyticsSource> upstoxOptionAnalytics,
      MarketCalendar calendar,
      @Value("${artha.options.big-oi-top-n:10}") int bigOiTopN,
      @Value("${artha.options.trend-buckets:20}") int trendBuckets,
      @Value("${artha.options.premium-buckets:60}") int premiumBuckets,
      @Value("${artha.options.heatmap-window:10}") int heatmapWindow,
      @Value("${artha.options.expiry-lookback-days:15}") int expiryLookbackDays,
      @Value("${artha.options.snapshot-interval-ms:300000}") long snapshotIntervalMs) {
    this.reader = reader;
    this.chainService = chainService;
    this.activeStrikes = activeStrikes;
    this.spurtService = spurtService;
    this.bigOiService = bigOiService;
    this.premiumService = premiumService;
    this.trendingService = trendingService;
    this.openHighStats = openHighStats;
    this.straddleChartService = straddleChartService;
    this.optionsOiChartService = optionsOiChartService;
    this.heatmapService = heatmapService;
    this.expiryService = expiryService;
    this.openHighStrategyService = openHighStrategyService;
    this.upstoxOptionAnalytics = upstoxOptionAnalytics;
    this.calendar = calendar;
    this.bigOiTopN = bigOiTopN;
    this.trendBuckets = trendBuckets;
    this.premiumBuckets = premiumBuckets;
    this.heatmapWindow = heatmapWindow;
    this.expiryLookbackDays = expiryLookbackDays;
    // Default session-stats bucket = the snapshot capture cadence (ms -> minutes), min 1, default 5.
    long minutes = snapshotIntervalMs / 60000L;
    this.defaultSessionIntervalMinutes = minutes <= 0 ? 5 : (int) minutes;
  }

  public record OiStats(BigDecimal pcr, BigDecimal maxPain, long ceOi, long peOi, OffsetDateTime asOf) {}

  public record ActiveStrikesResponse(
      BigDecimal sentimentPct,
      List<StrikeView> items,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<ActiveStrikeService.SentimentPoint>
              sentimentSeries,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<ActiveStrikeService.ActiveStrikeOiPoint>
              activeStrikeOiSeries,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<ActiveStrikeService.ActiveStrikeIvPoint>
              activeStrikeIvSeries,
      OffsetDateTime asOf) {}

  public record StrikeView(BigDecimal strike, long ceOi, long peOi) {}

  /**
   * Interval deltas overlaid on a live chain leg (the §20.7.1 faithful columns the point-in-time
   * {@code /chain} cannot carry): computed from the latest snapshot pair via the spurt convention.
   * All-null when there is no prior snapshot bucket for this (strike, side).
   */
  public record LegDeltas(
      Long oiChange,
      BigDecimal oiChangePct,
      BigDecimal ltpChange,
      BigDecimal ltpChangePct,
      OiInterpretation interpretation) {}

  /** A live chain leg (greeks/IV/OI/LTP from {@code /chain}) plus its interval deltas (or null). */
  public record ChainTableLeg(OptionsChainService.Leg leg, LegDeltas deltas) {}

  /** One faithful-chain row: CE | strike | PE, each leg enriched with deltas. */
  public record ChainTableRow(BigDecimal strike, ChainTableLeg ce, ChainTableLeg pe) {}

  /** The faithful Options Chain feed: the live chain header + enriched rows + the delta interval. */
  public record ChainTable(
      String underlying,
      LocalDate expiry,
      BigDecimal spot,
      BigDecimal forward,
      String forwardSource,
      BigDecimal riskFreeRate,
      BigDecimal pcr,
      boolean stale,
      OffsetDateTime asOf,
      String interval,
      List<ChainTableRow> rows) {}

  // ── /multiple-oi (oipulse Multiple OI Chart): per-leg OI line + the underlying price line ────────────

  /** One bucket of a leg's OI line (null oi where the leg has no snapshot at that bucket). */
  public record OiLinePoint(OffsetDateTime bucket, Long oi) {}

  /** One selected leg ("57200 CE") and its per-bucket OI series. */
  public record OiLeg(String leg, List<OiLinePoint> points) {}

  /** One bucket of the underlying price (spot) reference line. */
  public record SpotPoint(OffsetDateTime bucket, BigDecimal spot) {}

  @GetMapping("/oi-stats")
  public OiStats oiStats(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> latest = reader.latest(q.name(), exp, q.interval(), q.date());
    if (latest.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    Map<BigDecimal, long[]> byStrike = foldByStrike(latest); // [ceOi, peOi]
    long ce = 0;
    long pe = 0;
    List<MaxPainCalculator.StrikeOi> chain = new ArrayList<>();
    for (Map.Entry<BigDecimal, long[]> e : byStrike.entrySet()) {
      ce += e.getValue()[0];
      pe += e.getValue()[1];
      chain.add(new MaxPainCalculator.StrikeOi(e.getKey(), e.getValue()[0], e.getValue()[1]));
    }
    OffsetDateTime asOf = latest.get(latest.size() - 1).bucket();
    BigDecimal pcr = OptionsChainService.pcr(ce, pe);
    BigDecimal maxPain = MaxPainCalculator.maxPain(chain);
    // source.optionanalytics=upstox: replace the band-computed PCR/max-pain with Upstox's full-chain
    // values (ceOi/peOi stay native — Upstox exposes only the ratio). Any Upstox miss keeps native.
    UpstoxOptionAnalyticsSource upstox = upstoxOptionAnalytics.getIfAvailable();
    if (upstox != null) {
      UpstoxOptionAnalyticsSource.Stats s = upstox.statsOrNull(q.name(), exp, q.date());
      if (s != null) {
        if (s.pcr() != null) {
          pcr = s.pcr();
        }
        if (s.maxPain() != null) {
          maxPain = s.maxPain();
        }
      }
    }
    return new OiStats(pcr, maxPain, ce, pe, asOf);
  }

  private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

  /**
   * Intraday PCR-vs-price series for the OI-Statistics chart. {@code source.optionanalytics=upstox}:
   * Upstox {@code /pcr} insights (full chain, 1-min capable); else the native per-bucket fold over
   * captured snapshots (same windowing as {@code /trending}). Uniform shape {@code (time HH:mm IST,
   * pcr 4dp, spot)}; a Upstox miss falls back to native.
   */
  @GetMapping("/pcr-series")
  public List<PcrSeriesPoint> pcrSeries(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    UpstoxOptionAnalyticsSource upstox = upstoxOptionAnalytics.getIfAvailable();
    if (upstox != null) {
      int bucketMinutes = (int) Math.max(1, q.interval().bucket().toMinutes());
      List<PcrSeriesPoint> series = upstox.pcrSeries(q.name(), exp, q.date(), bucketMinutes);
      if (!series.isEmpty()) {
        return series;
      }
    }
    // Native fold — anchor on the newest captured bucket, span the last trendBuckets buckets.
    List<OptionsSnapshotReader.StrikePoint> latest = reader.latest(q.name(), exp, q.interval(), q.date());
    if (latest.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    OffsetDateTime newest = latest.get(0).bucket();
    OffsetDateTime from = newest.minus(q.interval().bucket().multipliedBy(trendBuckets - 1L));
    OiTrendingService.TrendSeries trend =
        trendingService.trending(
            reader.series(q.name(), exp, q.interval(), from, newest.plus(q.interval().bucket())));
    List<PcrSeriesPoint> out = new ArrayList<>();
    for (OiTrendingService.TrendPoint tp : trend.items()) {
      out.add(
          new PcrSeriesPoint(
              tp.bucket().atZoneSameInstant(Ist.ZONE).format(HHMM),
              OptionsChainService.pcr(tp.ceOi(), tp.peOi()),
              tp.spot()));
    }
    return out;
  }

  /** One strike's interval OI move for the Interval-wise OI bars (e.g. {@code "57400 PE"}). */
  public record StrikeMove(String strike, long oiChange, OiInterpretation interpretation) {}

  /** Top OI gainer/loser strikes across three lookbacks (oipulse §options/interval-wise-oi). */
  public record IntervalWiseOi(
      List<StrikeMove> gainers15,
      List<StrikeMove> losers15,
      List<StrikeMove> gainers60,
      List<StrikeMove> losers60,
      List<StrikeMove> gainersDaily,
      List<StrikeMove> losersDaily,
      OffsetDateTime asOf) {}

  /**
   * Top OI gainer/loser strikes across three fixed lookbacks — 15 min, 60 min, and daily (vs the
   * prior trading day). Each reuses the spurt fold (per-strike OI delta + 4-state interpretation);
   * the 15m/60m windows are the latest bucket-pair, the daily window pairs the prior trading day's
   * last bucket with the current one. Empty lists when no snapshot is captured (no 422).
   */
  @GetMapping("/interval-wise-oi")
  public IntervalWiseOi intervalWiseOi(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    OiSpurtService.SpurtChain c15 =
        spurtService.spurts(reader.latestPair(q.name(), exp, OiInterval.M15, q.date()));
    OiSpurtService.SpurtChain c60 =
        spurtService.spurts(reader.latestPair(q.name(), exp, OiInterval.M60, q.date()));
    OiSpurtService.SpurtChain cDaily = spurtService.spurts(dailyPair(q.name(), exp, q.date()));
    OffsetDateTime asOf =
        c15.asOf() != null ? c15.asOf() : c60.asOf() != null ? c60.asOf() : cDaily.asOf();
    return new IntervalWiseOi(
        topMoves(c15, true),
        topMoves(c15, false),
        topMoves(c60, true),
        topMoves(c60, false),
        topMoves(cDaily, true),
        topMoves(cDaily, false),
        asOf);
  }

  /** The daily window: the prior trading day's last bucket paired with the current one. */
  private List<OptionsSnapshotReader.StrikePoint> dailyPair(String name, LocalDate exp, LocalDate date) {
    LocalDate today = date != null ? date : LocalDate.now(Ist.ZONE);
    LocalDate prior;
    try {
      prior = calendar.previousTradingDay(today);
    } catch (IllegalArgumentException uncoveredYear) {
      return List.of();
    }
    List<OptionsSnapshotReader.StrikePoint> pair = new ArrayList<>();
    pair.addAll(reader.latest(name, exp, OiInterval.M15, prior));
    pair.addAll(reader.latest(name, exp, OiInterval.M15, date));
    return pair;
  }

  /** Top-{@code bigOiTopN} strikes by OI rise (gainer) or fall (loser), largest magnitude first. */
  private List<StrikeMove> topMoves(OiSpurtService.SpurtChain chain, boolean gainers) {
    return chain.items().stream()
        .filter(s -> gainers ? s.oiChange() > 0 : s.oiChange() < 0)
        .sorted(
            Comparator.comparingLong((OiSpurtService.StrikeSpurt s) -> Math.abs(s.oiChange()))
                .reversed())
        .limit(bigOiTopN)
        .map(s -> new StrikeMove(strikeLabel(s), s.oiChange(), s.interpretation()))
        .toList();
  }

  private static String strikeLabel(OiSpurtService.StrikeSpurt s) {
    return s.strike().stripTrailingZeros().toPlainString() + " " + s.optionType();
  }

  @GetMapping("/active-strikes")
  public ActiveStrikesResponse activeStrikes(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry,
      @RequestParam(required = false) Integer buckets) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> latest = reader.latest(q.name(), exp, q.interval(), q.date());
    if (latest.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    List<ActiveStrikeService.StrikeOiSnap> snaps = toSnaps(latest);
    BigDecimal sentiment = activeStrikes.sentimentPct(snaps);
    List<StrikeView> items =
        activeStrikes.activeStrikes(snaps).stream()
            .map(s -> new StrikeView(s.strike(), s.ceOi(), s.peOi()))
            .toList();
    OffsetDateTime asOf = latest.get(latest.size() - 1).bucket();
    if (buckets == null) {
      // NON_NULL on all series omits the keys, keeping the absent-buckets response byte-identical.
      return new ActiveStrikesResponse(sentiment, items, null, null, null, asOf);
    }
    // Anchor on the newest captured bucket (clock-independent); span the last `buckets` buckets.
    OffsetDateTime newest = latest.get(0).bucket();
    OffsetDateTime from = newest.minus(q.interval().bucket().multipliedBy(buckets - 1L));
    List<OptionsSnapshotReader.StrikePoint> series =
        reader.series(q.name(), exp, q.interval(), from, newest.plus(q.interval().bucket()));
    // All three series fold the SAME `series` read (one DB round-trip): sentiment + top-N Call/Put OI
    // agree bucket-for-bucket; the IV series carries the single peak strike's IVs + price (unsummable).
    List<ActiveStrikeService.SentimentPoint> sentimentSeries =
        activeStrikes.sentimentSeries(series);
    List<ActiveStrikeService.ActiveStrikeOiPoint> activeStrikeOiSeries =
        activeStrikes.activeStrikeOiSeries(series);
    List<ActiveStrikeService.ActiveStrikeIvPoint> activeStrikeIvSeries =
        activeStrikes.activeStrikeIvSeries(series);
    return new ActiveStrikesResponse(
        sentiment, items, sentimentSeries, activeStrikeOiSeries, activeStrikeIvSeries, asOf);
  }

  /** /oi-analysis: the data-table archetype source (per-strike rows for the latest bucket). */
  @GetMapping("/oi-analysis")
  public Map<String, Object> oiAnalysis(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> latest = reader.latest(q.name(), exp, q.interval(), q.date());
    return Map.of("items", latest); // {items:[...]} envelope (CLAUDE.md)
  }

  /**
   * /chain-table: the faithful oipulse Options Chain feed (plan §20.7.1). Composes the LIVE chain
   * (greeks/IV/OI/LTP/bid/ask/volume/PCR, computed now via black76 — {@link OptionsChainService})
   * with the per-leg interval deltas (OI Chng/OI%/LTP Chg/LTP%/OI-interpretation) that a point-in-
   * time chain cannot carry, derived from the latest snapshot pair using the spurt convention. The
   * displayed OI/LTP stay LIVE (flash on change); the "Chng" columns are the snapshot interval delta.
   * Deltas are null (not a 422) when snapshots have not accrued, so the live chain still renders.
   * NOTE: greeks are always LIVE — history mode only date-scopes the deltas (Wave-1 limitation).
   */
  @GetMapping("/chain-table")
  public ChainTable chainTable(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    // expiry defaults to the nearest on/after today — parity with the core /chain endpoint.
    LocalDate exp = chainService.resolveExpiry(q.name(), q.expiry());
    OptionsChainService.Chain chain = chainService.chain(q.name(), exp);
    Map<String, LegDeltas> deltas =
        chainDeltas(reader.latestPair(q.name(), exp, q.interval(), q.date()));
    List<ChainTableRow> rows = new ArrayList<>(chain.rows().size());
    for (OptionsChainService.StrikeRow r : chain.rows()) {
      rows.add(
          new ChainTableRow(
              r.strike(),
              enrich(r.strike(), "CE", r.ce(), deltas),
              enrich(r.strike(), "PE", r.pe(), deltas)));
    }
    return new ChainTable(
        chain.underlying(),
        chain.expiry(),
        chain.spot(),
        chain.forward(),
        chain.forwardSource(),
        chain.riskFreeRate(),
        chain.pcr(),
        chain.stale(),
        chain.asOf(),
        q.interval().token(),
        rows);
  }

  /**
   * /oi-analysis/strike-series: the TRUE oipulse "Options OI Analysis" (plan §20.7.5) — per-strike
   * intraday, buckets-on-rows. Returns the CHOSEN {@code strike}'s CE+PE points for every bucket of
   * the session containing the latest snapshot; the FE folds CE/PE per row and derives the interval
   * ΔOI / LTP-change / 4-state interpretation / cumulative ΔOI columns. Empty {@code items} (200)
   * when that strike has no snapshots; 422 only when the underlying has none at all.
   */
  @GetMapping("/oi-analysis/strike-series")
  public Map<String, Object> strikeSeries(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry,
      @RequestParam BigDecimal strike) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> latest =
        reader.latest(q.name(), exp, q.interval(), q.date());
    if (latest.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    OffsetDateTime newest = latest.get(0).bucket();
    LocalDate day = newest.atZoneSameInstant(Ist.ZONE).toLocalDate();
    OffsetDateTime from = day.atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = newest.plus(q.interval().bucket());
    List<OptionsSnapshotReader.StrikePoint> series =
        reader.strikeSeries(q.name(), exp, strike, q.interval(), from, to);
    return Map.of(
        "items", series,
        "underlying", q.name(),
        "expiry", exp,
        "strike", strike,
        "interval", q.interval().token(),
        "asOf", newest);
  }

  /**
   * /multiple-oi: the oipulse Multiple OI Chart (plan §options/multiple-oi-chart) — overlay several
   * user-selected option legs' OI lines (right axis) + the underlying price line (left axis) over the
   * session. {@code leg} is a REPEATED param ({@code ?leg=57200 CE&leg=57100 PE}). One snapshot read
   * (all strikes) is folded per leg + a single spot line (the chain-wide underlying price per bucket).
   * Map envelope. 400 when no leg is given; 422 only when the underlying has no snapshot at all.
   */
  @GetMapping("/multiple-oi")
  public Map<String, Object> multipleOi(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry,
      @RequestParam(required = false) List<String> leg) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    if (leg == null || leg.isEmpty()) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "at least one leg is required");
    }
    List<OptionsSnapshotReader.StrikePoint> latest =
        reader.latest(q.name(), exp, q.interval(), q.date());
    if (latest.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    OffsetDateTime newest = latest.get(0).bucket();
    LocalDate day = newest.atZoneSameInstant(Ist.ZONE).toLocalDate();
    OffsetDateTime from = day.atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = newest.plus(q.interval().bucket());
    List<OptionsSnapshotReader.StrikePoint> series =
        reader.series(q.name(), exp, q.interval(), from, to);

    // Index the single read: (strike|side) -> bucket -> oi, plus the chain-wide spot per bucket.
    java.util.TreeSet<OffsetDateTime> buckets = new java.util.TreeSet<>();
    Map<String, Map<OffsetDateTime, Long>> oiByKey = new HashMap<>();
    Map<OffsetDateTime, BigDecimal> spotByBucket = new LinkedHashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : series) {
      buckets.add(p.bucket());
      oiByKey
          .computeIfAbsent(deltaKey(p.strike(), p.optionType()), k -> new HashMap<>())
          .put(p.bucket(), p.oi());
      if (spotByBucket.get(p.bucket()) == null && p.spot() != null) {
        spotByBucket.put(p.bucket(), p.spot());
      }
    }

    List<OiLeg> items = new ArrayList<>(leg.size());
    for (String label : leg) {
      String key = legKey(label); // throws 400 on a malformed leg
      Map<OffsetDateTime, Long> byBucket = oiByKey.getOrDefault(key, Map.of());
      List<OiLinePoint> points = new ArrayList<>(buckets.size());
      for (OffsetDateTime b : buckets) {
        points.add(new OiLinePoint(b, byBucket.get(b)));
      }
      items.add(new OiLeg(label, points));
    }
    List<SpotPoint> spot = new ArrayList<>(buckets.size());
    for (OffsetDateTime b : buckets) {
      spot.add(new SpotPoint(b, spotByBucket.get(b)));
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("items", items);
    out.put("spot", spot);
    out.put("underlying", q.name());
    out.put("expiry", exp);
    out.put("interval", q.interval().token());
    out.put("asOf", newest);
    return out;
  }

  /** Parses a leg label ("57200 CE") into the snapshot join key; 400 on a malformed leg. */
  private static String legKey(String label) {
    int sp = label == null ? -1 : label.trim().lastIndexOf(' ');
    if (sp < 0) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "leg must be '<strike> CE|PE'");
    }
    String trimmed = label.trim();
    String side = trimmed.substring(sp + 1).trim().toUpperCase(java.util.Locale.ROOT);
    if (!"CE".equals(side) && !"PE".equals(side)) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "leg side must be CE or PE: " + label);
    }
    try {
      return deltaKey(new BigDecimal(trimmed.substring(0, sp).trim()), side);
    } catch (NumberFormatException badStrike) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "leg strike not numeric: " + label);
    }
  }

  /** /spurt: oipulse Options OI Spurt — per-strike interval buildup + the underlying 4-state rollup. */
  @GetMapping("/spurt")
  public OiSpurtService.SpurtChain spurt(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> pair =
        reader.latestPair(q.name(), exp, q.interval(), q.date());
    if (pair.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    return spurtService.spurts(pair);
  }

  /** /big-oi: oipulse Big OI — the latest bucket's legs ranked by |interval OI-change|. */
  @GetMapping("/big-oi")
  public OiBigOiService.BigOi bigOi(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> latest =
        reader.latest(q.name(), exp, q.interval(), q.date());
    if (latest.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    return bigOiService.bigOi(latest, bigOiTopN);
  }

  /** /premium: oipulse Premium — per-strike straddle premium + the ATM straddle (latest bucket). */
  @GetMapping("/premium")
  public OiPremiumService.PremiumChain premium(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> latest =
        reader.latest(q.name(), exp, q.interval(), q.date());
    if (latest.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    return premiumService.premium(latest);
  }

  /** /premium-series: oipulse Option Premium decay — the ATM straddle per bucket over the last N buckets. */
  @GetMapping("/premium-series")
  public OiPremiumService.PremiumSeries premiumSeries(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    // Anchor on the newest captured bucket (clock-independent); span the last premiumBuckets buckets.
    List<OptionsSnapshotReader.StrikePoint> latest =
        reader.latest(q.name(), exp, q.interval(), q.date());
    if (latest.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    OffsetDateTime newest = latest.get(0).bucket();
    OffsetDateTime from = newest.minus(q.interval().bucket().multipliedBy(premiumBuckets - 1L));
    List<OptionsSnapshotReader.StrikePoint> series =
        reader.series(q.name(), exp, q.interval(), from, newest.plus(q.interval().bucket()));
    return premiumService.premiumSeries(series);
  }

  /**
   * /straddle-chart: oipulse Straddle Chart (plan §20.7.6) — the combined CE+PE premium candle series
   * for a strike over the session. {@code interval} is RAW MINUTES (1/3/5/10/15/30/60 — wider than the
   * OI pages' {@code OiInterval}); {@code strike} is the straddle strike; {@code callStrike}/
   * {@code putStrike} override it for a strangle. Live mode → today IST; history → {@code date}.
   */
  @GetMapping("/straddle-chart")
  public StraddleChartService.StraddleChart straddleChart(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String expiry,
      @RequestParam BigDecimal strike,
      @RequestParam(required = false) BigDecimal callStrike,
      @RequestParam(required = false) BigDecimal putStrike,
      @RequestParam(required = false, defaultValue = "3") int interval) {
    OiQuery q = OiQuery.of(mode, name, date, null, expiry);
    return straddleChartService.chart(
        q.name(), q.expiry(), strike, callStrike, putStrike, interval, q.date());
  }

  /**
   * /options-chart: the oipulse Options Chart (plan §options/options-chart) — per LEG (Call + Put of one
   * {@code strike}) the option-premium candlestick + OI line (the FE adds VWAP + day H/L). One fetch
   * returns BOTH legs; the FE Show toggle picks which render. {@code interval} is RAW MINUTES
   * (1/3/5/10/15/30/60). Map envelope (no typed schema). 422 on an unlisted strike, 400 on an off-set
   * interval. The nullable header quote (off-hours) rides a LinkedHashMap (Map.of rejects nulls).
   */
  @GetMapping("/options-chart")
  public Map<String, Object> optionsChart(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String expiry,
      @RequestParam BigDecimal strike,
      @RequestParam(required = false, defaultValue = "3") int interval) {
    OiQuery q = OiQuery.of(mode, name, date, null, expiry);
    OptionsOiChartService.OptOiChart chart =
        optionsOiChartService.chart(q.name(), q.expiry(), strike, interval, q.date());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ce", chart.ce());
    out.put("pe", chart.pe());
    out.put("underlying", chart.underlying());
    out.put("expiry", chart.expiry());
    out.put("strike", chart.strike());
    out.put("ceTradingsymbol", chart.ceTradingsymbol());
    out.put("peTradingsymbol", chart.peTradingsymbol());
    out.put("interval", chart.interval());
    out.put("underlyingLtp", chart.underlyingLtp()); // nullable off-hours
    out.put("underlyingDayOpen", chart.underlyingDayOpen()); // nullable off-hours
    out.put("asOf", chart.asOf());
    return out;
  }

  /** /trending: oipulse OI Trending — per-bucket total/CE/PE OI + UP/DOWN/FLAT over the last N buckets. */
  @GetMapping("/trending")
  public OiTrendingService.TrendSeries trending(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    // Anchor on the newest captured bucket (clock-independent); span the last trendBuckets buckets.
    List<OptionsSnapshotReader.StrikePoint> latest =
        reader.latest(q.name(), exp, q.interval(), q.date());
    if (latest.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    OffsetDateTime newest = latest.get(0).bucket();
    OffsetDateTime from = newest.minus(q.interval().bucket().multipliedBy(trendBuckets - 1L));
    List<OptionsSnapshotReader.StrikePoint> series =
        reader.series(q.name(), exp, q.interval(), from, newest.plus(q.interval().bucket()));
    return trendingService.trending(series);
  }

  /**
   * /strike-session-stats: per-strike session OH/OL grading (Siva #2) for the {@code 2*window+1}
   * strikes nearest the ATM. {@code window} default 3; {@code interval} (minutes) default = the
   * snapshot capture cadence; {@code session} default = today IST. Empty items -> 200 (no error).
   */
  @GetMapping("/strike-session-stats")
  public OpenHighStatsService.StrikeSessionStats strikeSessionStats(
      @RequestParam String underlying,
      @RequestParam String expiry,
      @RequestParam(required = false) Integer window,
      @RequestParam(required = false) Integer interval,
      @RequestParam(required = false) String session) {
    LocalDate exp = LocalDate.parse(expiry);
    int win = window == null ? 3 : window;
    int intervalMinutes = interval == null ? defaultSessionIntervalMinutes : interval;
    LocalDate sess = session == null ? LocalDate.now(Ist.ZONE) : LocalDate.parse(session);

    List<OptionsSnapshotReader.PerStrikeSessionStat> stats =
        reader.sessionStats(underlying, exp, sess, intervalMinutes);
    // ATM = listed strike nearest the latest spot (history-scoped to the session).
    OiInterval oi = OiInterval.parse(intervalMinutes + "m");
    List<OptionsSnapshotReader.StrikePoint> latest = reader.latest(underlying, exp, oi, sess);
    BigDecimal spot = latestSpot(latest);
    BigDecimal atm = atmStrike(stats, spot);
    OffsetDateTime asOf = latest.isEmpty() ? null : latest.get(latest.size() - 1).bucket();
    return openHighStats.grade(underlying, exp, intervalMinutes, spot, atm, win, asOf, stats);
  }

  /**
   * /oi-heatmap: the oipulse-style OI-change heatmap (plan §20 breadth, grid-heatmap archetype) — a
   * strike × time grid of per-bucket interval OI change, split CE/PE, for the {@code 2*window+1}
   * strikes nearest the ATM over the selected IST session. Folds ONE
   * {@link OptionsSnapshotReader#series} read of the day's downsampled buckets (zero new capture). The
   * grid spans the calendar day up to (and including) the newest captured bucket — its first column
   * carries no delta and so is dropped by the fold. Empty grid (not a 422) when no snapshot has
   * accrued, so the page renders its empty state.
   */
  @GetMapping("/oi-heatmap")
  public OiHeatmapService.Heatmap oiHeatmap(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> latest =
        reader.latest(q.name(), exp, q.interval(), q.date());
    if (latest.isEmpty()) {
      return heatmapService.fold(List.of(), null, heatmapWindow);
    }
    OffsetDateTime newest = latest.get(0).bucket();
    LocalDate day = newest.atZoneSameInstant(Ist.ZONE).toLocalDate();
    OffsetDateTime from = day.atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = newest.plus(q.interval().bucket());
    List<OptionsSnapshotReader.StrikePoint> series =
        reader.series(q.name(), exp, q.interval(), from, to);
    BigDecimal spot = latestSpot(latest);
    BigDecimal atm = nearestListedStrike(series, spot);
    return heatmapService.fold(series, atm, heatmapWindow);
  }

  /**
   * /oi-expiry: the oipulse "Options EOD OI Analysis" / OI Expiry Strategy (plan
   * §options/oi-expiry-strategy) — per-strike, last-N-session EOD OHLC + OI + Volume tables (CE and PE)
   * that feed the expiry-day trading plan. Folds ONE {@link OptionsSnapshotReader#eodSeries} read of
   * the day-rollup over the last {@code expiry-lookback-days} calendar days up to (and including) the
   * latest captured session (live) or the {@code date} (history), windowed to the {@code 2*window+1}
   * strikes nearest the ATM. {@code items} envelope; empty list (not a 422) when no snapshot accrued.
   */
  @GetMapping("/oi-expiry")
  public Map<String, Object> oiExpiry(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> latest =
        reader.latest(q.name(), exp, q.interval(), q.date());
    if (latest.isEmpty()) {
      return Map.of("items", List.of(), "underlying", q.name(), "expiry", exp);
    }
    LocalDate lastDay = latest.get(0).bucket().atZoneSameInstant(Ist.ZONE).toLocalDate();
    OffsetDateTime from =
        lastDay.minusDays(expiryLookbackDays).atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = lastDay.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);
    List<OptionsSnapshotReader.OptionEodRow> eod = reader.eodSeries(q.name(), exp, from, to);
    List<OiExpiryService.StrikeExpiry> all = expiryService.fold(eod);
    BigDecimal spot = latestSpot(latest);
    List<OiExpiryService.StrikeExpiry> windowed = nearestExpiryStrikes(all, spot, heatmapWindow);
    return Map.of(
        "items", windowed,
        "underlying", q.name(),
        "expiry", exp,
        "asOf", latest.get(0).bucket());
  }

  /**
   * The {@code 2*window+1} folded strikes nearest {@code spot} (by |strike - spot|), returned in
   * ascending strike order. When {@code spot} is null (or no strikes) the lowest strikes are kept.
   */
  private static List<OiExpiryService.StrikeExpiry> nearestExpiryStrikes(
      List<OiExpiryService.StrikeExpiry> all, BigDecimal spot, int window) {
    int limit = 2 * window + 1;
    if (all.size() <= limit) {
      return all;
    }
    Comparator<OiExpiryService.StrikeExpiry> order =
        spot == null
            ? Comparator.comparing(OiExpiryService.StrikeExpiry::strike)
            : Comparator.<OiExpiryService.StrikeExpiry, BigDecimal>comparing(
                    s -> s.strike().subtract(spot).abs())
                .thenComparing(OiExpiryService.StrikeExpiry::strike);
    return all.stream()
        .sorted(order)
        .limit(limit)
        .sorted(Comparator.comparing(OiExpiryService.StrikeExpiry::strike))
        .toList();
  }

  /**
   * /open-high-strategy: the oipulse "Open &amp; High Strategy" (§strategies/open-high-strategy, Siva
   * #2) — the per-strike Open=High (Call) / Open=Low (Put) premium-reversion scan with the historical
   * TRIGGER PROBABILITY (hits over the prior captured sessions) that the single-session OH/OL grader
   * ({@code /strike-session-stats}) cannot carry. Folds ONE {@link OptionsSnapshotReader#eodSeries}
   * read of the day-rollup over the last {@code expiry-lookback-days} days up to the latest captured
   * session (live) or the {@code date} (history), windowed to the {@code 2*window+1} ATM strikes.
   * {@code items} envelope; empty list (not a 422) when no snapshot accrued.
   */
  @GetMapping("/open-high-strategy")
  public Map<String, Object> openHighStrategy(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> latest =
        reader.latest(q.name(), exp, q.interval(), q.date());
    if (latest.isEmpty()) {
      return Map.of("items", List.of(), "underlying", q.name(), "expiry", exp);
    }
    LocalDate lastDay = latest.get(0).bucket().atZoneSameInstant(Ist.ZONE).toLocalDate();
    OffsetDateTime from =
        lastDay.minusDays(expiryLookbackDays).atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = lastDay.plusDays(1).atStartOfDay().atOffset(Ist.OFFSET);
    List<OptionsSnapshotReader.OptionEodRow> eod = reader.eodSeries(q.name(), exp, from, to);
    List<OpenHighStrategyService.StrikeOpenHigh> all = openHighStrategyService.fold(eod);
    BigDecimal spot = latestSpot(latest);
    List<OpenHighStrategyService.StrikeOpenHigh> windowed =
        nearestOpenHighStrikes(all, spot, heatmapWindow);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("items", windowed);
    out.put("underlying", q.name());
    out.put("expiry", exp);
    out.put("spot", spot); // nullable off-hours (LinkedHashMap permits null; Map.of would not)
    out.put("asOf", latest.get(0).bucket());
    return out;
  }

  /**
   * The {@code 2*window+1} folded Open=High strikes nearest {@code spot}, ascending. When {@code spot}
   * is null (or no strikes) the lowest strikes are kept. Mirrors {@link #nearestExpiryStrikes}.
   */
  private static List<OpenHighStrategyService.StrikeOpenHigh> nearestOpenHighStrikes(
      List<OpenHighStrategyService.StrikeOpenHigh> all, BigDecimal spot, int window) {
    int limit = 2 * window + 1;
    if (all.size() <= limit) {
      return all;
    }
    Comparator<OpenHighStrategyService.StrikeOpenHigh> order =
        spot == null
            ? Comparator.comparing(OpenHighStrategyService.StrikeOpenHigh::strike)
            : Comparator.<OpenHighStrategyService.StrikeOpenHigh, BigDecimal>comparing(
                    s -> s.strike().subtract(spot).abs())
                .thenComparing(OpenHighStrategyService.StrikeOpenHigh::strike);
    return all.stream()
        .sorted(order)
        .limit(limit)
        .sorted(Comparator.comparing(OpenHighStrategyService.StrikeOpenHigh::strike))
        .toList();
  }

  /** The listed strike nearest {@code spot} among the series points; null when spot/strikes absent. */
  private static BigDecimal nearestListedStrike(
      List<OptionsSnapshotReader.StrikePoint> series, BigDecimal spot) {
    if (spot == null) {
      return null;
    }
    return series.stream()
        .map(OptionsSnapshotReader.StrikePoint::strike)
        .distinct()
        .min(Comparator.comparing(s -> s.subtract(spot).abs()))
        .orElse(null);
  }

  private static BigDecimal latestSpot(List<OptionsSnapshotReader.StrikePoint> latest) {
    for (OptionsSnapshotReader.StrikePoint p : latest) {
      if (p.spot() != null) {
        return p.spot();
      }
    }
    return null;
  }

  /** The listed (session) strike nearest {@code spot}; null when spot or strikes are absent. */
  private static BigDecimal atmStrike(
      List<OptionsSnapshotReader.PerStrikeSessionStat> stats, BigDecimal spot) {
    if (spot == null) {
      return null;
    }
    return stats.stream()
        .map(OptionsSnapshotReader.PerStrikeSessionStat::strike)
        .distinct()
        .min(java.util.Comparator.comparing(s -> s.subtract(spot).abs()))
        .orElse(null);
  }

  private LocalDate requireExpiry(OiQuery q) {
    if (q.expiry() == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "expiry is required");
    }
    return q.expiry();
  }

  /** Pairs a live leg with its deltas (null leg → null ChainTableLeg; absent deltas → null). */
  private static ChainTableLeg enrich(
      BigDecimal strike,
      String optionType,
      OptionsChainService.Leg leg,
      Map<String, LegDeltas> deltas) {
    if (leg == null) {
      return null;
    }
    return new ChainTableLeg(leg, deltas.get(deltaKey(strike, optionType)));
  }

  /**
   * Per-(strike, optionType) interval deltas from the latest snapshot pair (the chain-table
   * overlay). Mirrors {@link OiSpurtService}: diff the newest captured bucket against the prior one;
   * {@code null}-ltp counts as zero for the delta (so the 4-state interpretation still classifies),
   * {@code oiChangePct}/{@code ltpChangePct} are null when the prior value is absent or zero. Empty
   * map when there is no prior bucket (only one or zero snapshots).
   */
  private static Map<String, LegDeltas> chainDeltas(List<OptionsSnapshotReader.StrikePoint> pair) {
    OffsetDateTime newest =
        pair.stream()
            .map(OptionsSnapshotReader.StrikePoint::bucket)
            .max(Comparator.naturalOrder())
            .orElse(null);
    if (newest == null) {
      return Map.of();
    }
    OffsetDateTime prior =
        pair.stream()
            .map(OptionsSnapshotReader.StrikePoint::bucket)
            .filter(b -> b.isBefore(newest))
            .max(Comparator.naturalOrder())
            .orElse(null);
    if (prior == null) {
      return Map.of();
    }
    Map<String, OptionsSnapshotReader.StrikePoint> newAt = new HashMap<>();
    Map<String, OptionsSnapshotReader.StrikePoint> oldAt = new HashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : pair) {
      String key = deltaKey(p.strike(), p.optionType());
      if (p.bucket().equals(newest)) {
        newAt.put(key, p);
      } else if (p.bucket().equals(prior)) {
        oldAt.put(key, p);
      }
    }
    Map<String, LegDeltas> out = new HashMap<>();
    for (Map.Entry<String, OptionsSnapshotReader.StrikePoint> e : newAt.entrySet()) {
      OptionsSnapshotReader.StrikePoint cur = e.getValue();
      OptionsSnapshotReader.StrikePoint old = oldAt.get(e.getKey());
      if (old == null) {
        continue; // no prior bucket for this strike -> no interval delta
      }
      long priorOi = old.oi() == null ? 0 : old.oi();
      long curOi = cur.oi() == null ? 0 : cur.oi();
      long oiDelta = curOi - priorOi;
      BigDecimal ltpChange =
          (cur.ltp() == null ? BigDecimal.ZERO : cur.ltp())
              .subtract(old.ltp() == null ? BigDecimal.ZERO : old.ltp());
      BigDecimal oiChangePct =
          priorOi == 0
              ? null
              : BigDecimal.valueOf(oiDelta)
                  .multiply(BigDecimal.valueOf(100))
                  .divide(BigDecimal.valueOf(priorOi), 2, RoundingMode.HALF_UP);
      out.put(
          e.getKey(),
          new LegDeltas(
              oiDelta,
              oiChangePct,
              ltpChange,
              pct(old.ltp(), cur.ltp()),
              OiInterpretation.classify(ltpChange, oiDelta)));
    }
    return out;
  }

  /** Join key normalising strike scale (matches {@link OptionsSnapshotReader}'s key style). */
  private static String deltaKey(BigDecimal strike, String optionType) {
    return strike.stripTrailingZeros().toPlainString() + "|" + optionType;
  }

  /** Signed % change ((cur - prior) / prior * 100), 2dp; null when prior is absent or zero. */
  private static BigDecimal pct(BigDecimal prior, BigDecimal cur) {
    if (prior == null || cur == null || prior.signum() == 0) {
      return null;
    }
    return cur.subtract(prior)
        .multiply(BigDecimal.valueOf(100))
        .divide(prior, 2, RoundingMode.HALF_UP);
  }

  private static Map<BigDecimal, long[]> foldByStrike(List<OptionsSnapshotReader.StrikePoint> pts) {
    Map<BigDecimal, long[]> m = new LinkedHashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : pts) {
      long[] v = m.computeIfAbsent(p.strike(), k -> new long[2]);
      long oi = p.oi() == null ? 0 : p.oi();
      if ("CE".equals(p.optionType())) {
        v[0] += oi;
      } else {
        v[1] += oi;
      }
    }
    return m;
  }

  private static List<ActiveStrikeService.StrikeOiSnap> toSnaps(
      List<OptionsSnapshotReader.StrikePoint> pts) {
    Map<BigDecimal, long[]> m = new LinkedHashMap<>(); // [ceOi, ceChg, peOi, peChg]
    for (OptionsSnapshotReader.StrikePoint p : pts) {
      long[] v = m.computeIfAbsent(p.strike(), k -> new long[4]);
      long oi = p.oi() == null ? 0 : p.oi();
      long chg = p.oiChange() == null ? 0 : p.oiChange();
      if ("CE".equals(p.optionType())) {
        v[0] += oi;
        v[1] += chg;
      } else {
        v[2] += oi;
        v[3] += chg;
      }
    }
    List<ActiveStrikeService.StrikeOiSnap> out = new ArrayList<>();
    m.forEach(
        (strike, v) -> out.add(new ActiveStrikeService.StrikeOiSnap(strike, v[0], v[1], v[2], v[3])));
    return out;
  }
}
