package in.arthayantra.marketdata.options;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.options.analytics.ActiveStrikeService;
import in.arthayantra.marketdata.options.analytics.HistoricalOiReader;
import in.arthayantra.marketdata.options.analytics.MaxPainCalculator;
import in.arthayantra.marketdata.options.analytics.OiPremiumService;
import in.arthayantra.marketdata.options.analytics.OiSpurtService;
import in.arthayantra.marketdata.options.analytics.OptionsSnapshotReader.StrikePoint;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Options decision-support digest (intelligence-layer design 2026-07-10 §6.1.1) — a fold over the
 * EXISTING options analytics folds, no new raw data (§13 row 4). One read of the day's captured
 * {@code options_chain_snapshots} yields: PCR now / vs session-open / vs prior-day EOD; max-pain now
 * + drift; top OI gainer/loser strikes (spurt fold); active-strike set migration (top-N now vs open);
 * ATM straddle now vs open; ATM IV vs its trailing-window percentile (the existing {@link
 * IvAnalyticsService}, honest-null below the 60-session floor); and the 4-state OI-structure verdict
 * (the spurt summary).
 *
 * <p><b>History-honesty (§6.5).</b> No field is fabricated. When no snapshot is captured the digest
 * is a {@code BLOCKED} shell (200, never a 5xx) so the day-context one-call can still render. Missing
 * baselines (no session-open / prior-day bucket), an insufficient IV window, and a historical read
 * each degrade the {@code dataTrust} to {@code DEGRADED} with a named reason rather than inventing a
 * value. The "vs prior day" read folds the prior trading session's snapshots directly (the same
 * convention {@code /trending?baseline=peod} uses), so it is correct for any (name, expiry) without a
 * warm {@code market_context_days} row.
 */
@Service
public class OptionsDigestService {

  /** PCR now, at session-open, and at prior-day EOD, plus the two intraday/day deltas. */
  public record Pcr(
      @Schema(type = "string", types = {"string", "null"}) BigDecimal now,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal atOpen,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal priorEod,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal deltaVsOpen,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal deltaVsPriorEod) {}

  /** Max-pain now, at session-open, and the intraday drift (now − at-open, in index points). */
  public record MaxPain(
      @Schema(type = "string") BigDecimal now,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal atOpen,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal drift) {}

  /** One top-mover strike (label {@code "57400 PE"}) with its ΔOI-since-open + 4-state read. */
  // @Schema(name) is load-bearing: OptionsAnalyticsController.StrikeMove shares this simple name
  // (fields identical TODAY, but identical twins drift — task_1c04803f).
  @Schema(name = "OptionsDigestStrikeMove")
  public record StrikeMove(String strike, long oiChange, OiInterpretation interpretation) {}

  /**
   * Active-strike (top-N peak-OI) set migration now vs session-open: which strikes ENTERED / EXITED
   * the active set and how many changed. Empty {@code openTop} (no open baseline) leaves the diffs
   * empty and drops {@code dataTrust} to DEGRADED.
   */
  public record ActiveStrikeMigration(
      List<String> nowTop,
      List<String> openTop,
      List<String> entered,
      List<String> exited,
      int changedCount) {}

  /** ATM straddle premium now vs session-open (the §6.1.1 premium fold, window-anchored to 09:15). */
  public record Straddle(
      @Schema(type = "string", types = {"string", "null"}) BigDecimal atmStrike,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal now,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal atOpen,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal deltaPct) {}

  /** ATM IV + its trailing-window rank/percentile ({@code insufficientHistory} true below the floor). */
  public record AtmIv(
      @Schema(type = "string", types = {"string", "null"}) BigDecimal iv,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal rank,
      @Schema(types = {"integer", "null"}) Integer percentile,
      int windowSessions,
      boolean insufficientHistory) {}

  /** The underlying 4-state OI-structure verdict (spot direction × total ΔOI direction, since open). */
  public record OiStructure(
      OiInterpretation verdict,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal spotDelta,
      long oiChange) {}

  /** The options context digest for one (underlying, expiry). {@code dataTrust} = OK/DEGRADED/BLOCKED. */
  public record OptionsDigest(
      String underlying,
      LocalDate expiry,
      @Schema(types = {"object", "null"}) Pcr pcr,
      @Schema(types = {"object", "null"}) MaxPain maxPain,
      List<StrikeMove> oiGainers,
      List<StrikeMove> oiLosers,
      @Schema(types = {"object", "null"}) ActiveStrikeMigration activeStrikes,
      @Schema(types = {"object", "null"}) Straddle atmStraddle,
      @Schema(types = {"object", "null"}) AtmIv atmIv,
      @Schema(types = {"object", "null"}) OiStructure oiStructure,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf,
      String dataTrust,
      List<String> trustReasons) {

    static OptionsDigest blocked(String underlying, LocalDate expiry, String reason) {
      return new OptionsDigest(
          underlying, expiry, null, null, List.of(), List.of(), null, null, null, null, null,
          "BLOCKED", List.of(reason));
    }
  }

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final HistoricalOiReader reader;
  private final OiSpurtService spurtService;
  private final OiPremiumService premiumService;
  private final ActiveStrikeService activeStrikeService;
  private final IvAnalyticsService ivAnalytics;
  private final OptionsChainService chainService;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final OiInterval interval;
  private final int topStrikes;

  /** Wires the reused analytics folds + the digest's interval / top-N knobs. */
  public OptionsDigestService(
      HistoricalOiReader reader,
      OiSpurtService spurtService,
      OiPremiumService premiumService,
      ActiveStrikeService activeStrikeService,
      IvAnalyticsService ivAnalytics,
      OptionsChainService chainService,
      MarketCalendar calendar,
      Clock clock,
      @Value("${artha.context.oi-interval:15m}") String oiInterval,
      @Value("${artha.context.top-strikes:3}") int topStrikes) {
    this.reader = reader;
    this.spurtService = spurtService;
    this.premiumService = premiumService;
    this.activeStrikeService = activeStrikeService;
    this.ivAnalytics = ivAnalytics;
    this.chainService = chainService;
    this.calendar = calendar;
    this.clock = clock;
    this.interval = OiInterval.parse(oiInterval);
    this.topStrikes = topStrikes;
  }

  /**
   * The options digest for (name, expiry). {@code expiry} null resolves to the nearest listed expiry
   * on/after today (parity with the core chain). {@code date} null = live (native snapshots); a past
   * IST date reads that session (candle-derived fallback for fully-past empties, per HistoricalOiReader).
   */
  public OptionsDigest digest(String name, LocalDate expiry, LocalDate date) {
    LocalDate exp = expiry != null ? expiry : chainService.resolveExpiry(name, null);
    List<StrikePoint> latest = reader.latest(name, exp, interval, date);
    if (latest.isEmpty()) {
      return OptionsDigest.blocked(name, exp, "no options snapshot captured for " + name + " " + exp);
    }
    OffsetDateTime newest = latest.get(0).bucket();
    LocalDate sessionDay = newest.atZoneSameInstant(Ist.ZONE).toLocalDate();
    List<StrikePoint> openBucket = sessionOpenBucket(name, exp, sessionDay, newest, date);
    List<StrikePoint> priorEod = priorEodBucket(name, exp, sessionDay, date);

    Fold now = fold(latest);
    Fold open = openBucket.isEmpty() ? null : fold(openBucket);
    Fold prior = priorEod.isEmpty() ? null : fold(priorEod);

    // Spurt since session-open (cumulative): the [open, newest] two-bucket window when a distinct
    // open bucket exists, else the interval pair (which yields no rows just after open — honest empty).
    boolean distinctOpen = !openBucket.isEmpty() && !openBucket.get(0).bucket().equals(newest);
    List<StrikePoint> spurtPair;
    if (distinctOpen) {
      spurtPair = new ArrayList<>(openBucket);
      spurtPair.addAll(latest);
    } else {
      spurtPair = reader.latestPair(name, exp, interval, date);
    }
    OiSpurtService.SpurtChain spurt = spurtService.spurts(spurtPair);

    IvAnalyticsService.IvHistory iv = ivAnalytics.ivHistory(name);

    final Pcr pcr =
        new Pcr(
            now.pcr,
            open == null ? null : open.pcr,
            prior == null ? null : prior.pcr,
            sub(now.pcr, open == null ? null : open.pcr),
            sub(now.pcr, prior == null ? null : prior.pcr));
    final MaxPain maxPain =
        new MaxPain(
            now.maxPain,
            open == null ? null : open.maxPain,
            sub(now.maxPain, open == null ? null : open.maxPain));
    final Straddle straddle =
        new Straddle(
            now.atmStrike,
            now.atmStraddle,
            open == null ? null : open.atmStraddle,
            pctChange(open == null ? null : open.atmStraddle, now.atmStraddle));
    final ActiveStrikeMigration migration =
        migration(now.active, open == null ? List.of() : open.active);
    final OiStructure structure =
        spurt.summary() == null
            ? null
            : new OiStructure(
                spurt.summary().interpretation(),
                spurt.summary().spotDelta(),
                spurt.summary().oiChange());
    final AtmIv atmIv =
        new AtmIv(
            iv.currentIv(), iv.rank(), iv.percentile(), iv.windowDays(), iv.insufficientHistory());

    List<String> reasons = new ArrayList<>();
    // Stale-anchor honesty (§6.5, the 2026-07-08 outage class): a LIVE read (date == null) with NO
    // capture today anchors reader.latest on the newest bucket OVERALL — i.e. a PRIOR session. Those
    // values are yesterday's truth, never today's; name it and degrade rather than serve them as OK.
    if (date == null) {
      LocalDate todayIst = LocalDate.ofInstant(clock.instant(), Ist.ZONE);
      if (!sessionDay.equals(todayIst)) {
        reasons.add("stale snapshot - newest bucket is " + sessionDay + ", not today");
      }
    }
    if (open == null) {
      reasons.add("no session-open snapshot baseline");
    }
    if (prior == null) {
      reasons.add("no prior-session snapshot baseline");
    }
    if (iv.insufficientHistory()) {
      reasons.add(
          "ATM IV rank over " + iv.windowDays() + " sessions (< " + iv.floorDays() + "-session floor)");
    }
    if (date != null) {
      reasons.add("historical read");
    }
    String trust = reasons.isEmpty() ? "OK" : "DEGRADED";

    return new OptionsDigest(
        name,
        exp,
        pcr,
        maxPain,
        topMoves(spurt, true),
        topMoves(spurt, false),
        migration,
        straddle,
        atmIv,
        structure,
        newest,
        trust,
        List.copyOf(reasons));
  }

  /** Everything foldable from one snapshot bucket's rows (all strikes). */
  private record Fold(
      BigDecimal pcr,
      BigDecimal maxPain,
      BigDecimal atmStrike,
      BigDecimal atmStraddle,
      List<String> active) {}

  private Fold fold(List<StrikePoint> bucket) {
    Map<BigDecimal, long[]> byStrike = new LinkedHashMap<>(); // strike -> [ceOi, ceChg, peOi, peChg]
    for (StrikePoint p : bucket) {
      long[] v = byStrike.computeIfAbsent(p.strike(), k -> new long[4]);
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
    long ce = 0;
    long pe = 0;
    List<MaxPainCalculator.StrikeOi> chain = new ArrayList<>();
    List<ActiveStrikeService.StrikeOiSnap> snaps = new ArrayList<>();
    for (Map.Entry<BigDecimal, long[]> e : byStrike.entrySet()) {
      long[] v = e.getValue();
      ce += v[0];
      pe += v[2];
      chain.add(new MaxPainCalculator.StrikeOi(e.getKey(), v[0], v[2]));
      snaps.add(new ActiveStrikeService.StrikeOiSnap(e.getKey(), v[0], v[1], v[2], v[3]));
    }
    OiPremiumService.PremiumChain premium = premiumService.premium(bucket);
    List<String> active =
        activeStrikeService.activeStrikes(snaps).stream()
            .map(s -> s.strike().stripTrailingZeros().toPlainString())
            .toList();
    return new Fold(
        OptionsChainService.pcr(ce, pe),
        chain.isEmpty() ? null : MaxPainCalculator.maxPain(chain),
        premium.atmStrike(),
        premium.atmStraddle(),
        active);
  }

  /** The all-strikes points of the SESSION-OPEN bucket (09:15 IST); empty when the session is empty. */
  private List<StrikePoint> sessionOpenBucket(
      String name, LocalDate exp, LocalDate sessionDay, OffsetDateTime newest, LocalDate date) {
    OffsetDateTime from = sessionDay.atTime(MarketCalendar.SESSION_OPEN).atOffset(Ist.OFFSET);
    List<StrikePoint> session =
        reader.series(name, exp, interval, from, newest.plus(interval.bucket()));
    if (session.isEmpty()) {
      return List.of();
    }
    OffsetDateTime open =
        session.stream()
            .map(StrikePoint::bucket)
            .min(Comparator.naturalOrder())
            .orElse(newest);
    return session.stream().filter(p -> p.bucket().equals(open)).toList();
  }

  /** The prior trading session's last captured bucket (the "prior-day EOD" baseline); empty if none. */
  private List<StrikePoint> priorEodBucket(
      String name, LocalDate exp, LocalDate sessionDay, LocalDate date) {
    LocalDate prior;
    try {
      prior = calendar.previousTradingDay(sessionDay);
    } catch (IllegalArgumentException uncoveredYear) {
      return List.of();
    }
    return reader.latest(name, exp, interval, prior);
  }

  /** Top-{@code topStrikes} strikes by |ΔOI| that gained (or lost) OI, largest magnitude first. */
  private List<StrikeMove> topMoves(OiSpurtService.SpurtChain chain, boolean gainers) {
    return chain.items().stream()
        .filter(s -> gainers ? s.oiChange() > 0 : s.oiChange() < 0)
        .sorted(Comparator.comparingLong((OiSpurtService.StrikeSpurt s) -> Math.abs(s.oiChange())).reversed())
        .limit(topStrikes)
        .map(
            s ->
                new StrikeMove(
                    s.strike().stripTrailingZeros().toPlainString() + " " + s.optionType(),
                    s.oiChange(),
                    s.interpretation()))
        .toList();
  }

  private static ActiveStrikeMigration migration(List<String> nowTop, List<String> openTop) {
    return new ActiveStrikeMigration(
        nowTop, openTop, minus(nowTop, openTop), minus(openTop, nowTop), minus(nowTop, openTop).size());
  }

  /** {@code a} minus {@code b}, order-preserving. */
  private static List<String> minus(List<String> a, List<String> b) {
    Set<String> remove = new LinkedHashSet<>(b);
    return a.stream().filter(x -> !remove.contains(x)).toList();
  }

  /** {@code a − b}; null when either side is null. */
  private static BigDecimal sub(BigDecimal a, BigDecimal b) {
    return a == null || b == null ? null : a.subtract(b);
  }

  /** Signed % change ((cur − prior) / prior · 100), scale 2 HALF_UP; null when prior is absent/zero. */
  private static BigDecimal pctChange(BigDecimal prior, BigDecimal cur) {
    if (prior == null || cur == null || prior.signum() == 0) {
      return null;
    }
    return cur.subtract(prior).multiply(HUNDRED).divide(prior, 2, RoundingMode.HALF_UP);
  }
}
