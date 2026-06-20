package in.arthayantra.marketdata.options.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.options.OiQuery;
import in.arthayantra.marketdata.options.OptionsChainService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market/options")
public class OptionsAnalyticsController {

  private final OptionsSnapshotReader reader;
  private final ActiveStrikeService activeStrikes;
  private final OiSpurtService spurtService;
  private final OiBigOiService bigOiService;
  private final OiPremiumService premiumService;
  private final OiTrendingService trendingService;
  private final int bigOiTopN;
  private final int trendBuckets;
  private final int premiumBuckets;

  public OptionsAnalyticsController(
      OptionsSnapshotReader reader,
      ActiveStrikeService activeStrikes,
      OiSpurtService spurtService,
      OiBigOiService bigOiService,
      OiPremiumService premiumService,
      OiTrendingService trendingService,
      @Value("${artha.options.big-oi-top-n:10}") int bigOiTopN,
      @Value("${artha.options.trend-buckets:20}") int trendBuckets,
      @Value("${artha.options.premium-buckets:60}") int premiumBuckets) {
    this.reader = reader;
    this.activeStrikes = activeStrikes;
    this.spurtService = spurtService;
    this.bigOiService = bigOiService;
    this.premiumService = premiumService;
    this.trendingService = trendingService;
    this.bigOiTopN = bigOiTopN;
    this.trendBuckets = trendBuckets;
    this.premiumBuckets = premiumBuckets;
  }

  public record OiStats(BigDecimal pcr, BigDecimal maxPain, long ceOi, long peOi, OffsetDateTime asOf) {}

  public record ActiveStrikesResponse(
      BigDecimal sentimentPct,
      List<StrikeView> items,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<ActiveStrikeService.SentimentPoint>
              sentimentSeries,
      OffsetDateTime asOf) {}

  public record StrikeView(BigDecimal strike, long ceOi, long peOi) {}

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
    return new OiStats(OptionsChainService.pcr(ce, pe), MaxPainCalculator.maxPain(chain), ce, pe, asOf);
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
      // NON_NULL on sentimentSeries omits the key, keeping the absent-buckets response byte-identical.
      return new ActiveStrikesResponse(sentiment, items, null, asOf);
    }
    // Anchor on the newest captured bucket (clock-independent); span the last `buckets` buckets.
    OffsetDateTime newest = latest.get(0).bucket();
    OffsetDateTime from = newest.minus(q.interval().bucket().multipliedBy(buckets - 1L));
    List<OptionsSnapshotReader.StrikePoint> series =
        reader.series(q.name(), exp, q.interval(), from, newest.plus(q.interval().bucket()));
    List<ActiveStrikeService.SentimentPoint> sentimentSeries =
        activeStrikes.sentimentSeries(series);
    return new ActiveStrikesResponse(sentiment, items, sentimentSeries, asOf);
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

  private LocalDate requireExpiry(OiQuery q) {
    if (q.expiry() == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "expiry is required");
    }
    return q.expiry();
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
