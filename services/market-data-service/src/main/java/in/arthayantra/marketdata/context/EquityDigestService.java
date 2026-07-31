package in.arthayantra.marketdata.context;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.constituents.StaticIndexWeights;
import in.arthayantra.marketdata.constituents.StockSectorMap;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Equity decision-support digest (intelligence-layer design 2026-07-10 §6.1.3) — the bhavcopy-backed
 * market-breadth context read, assembled from {@link EquityContextRepository} folds (three of them
 * NEW: above-MA counts, universe delivery-z, and the date-parameterized sector rotation) plus the
 * {@code constituents} sector/weight reference. No new raw data (§13 row 4).
 *
 * <p><b>Trust.</b> Bhavcopy has real depth (§6.5 has no forward-only cap here). The honest failure
 * mode is <em>staleness</em>: the digest resolves its session to {@code date} (or the latest EQ
 * session) and, when that session is behind the last settled trading day, degrades with a
 * "stale — newest is &lt;day&gt;" reason (the batch analogue of #745's live stale-anchor rule).
 * No bhavcopy at all ⇒ a {@code BLOCKED} shell (200, never a 5xx).
 */
@Service
public class EquityDigestService {

  /** A/D counts + ratio for the session, with the day-over-day ratio delta. */
  public record AdvanceDecline(
      LocalDate tradeDate,
      int advances,
      int declines,
      int unchanged,
      int total,
      @Schema(types = {"number", "null"}) BigDecimal adRatio,
      @Schema(types = {"number", "null"}) BigDecimal adRatioPrior,
      @Schema(types = {"number", "null"}) BigDecimal adRatioDelta) {}

  /** Universe-wide above-20/50-DMA counts + the share of the qualifying universe. */
  public record AboveMa(
      int universe20,
      int above20,
      @Schema(types = {"number", "null"}) BigDecimal pctAbove20,
      int universe50,
      int above50,
      @Schema(types = {"number", "null"}) BigDecimal pctAbove50) {}

  /** The 10-session A/D moving average vs the Zweig thrust threshold (config-pinned). */
  public record BreadthThrust(
      int windowSessions,
      @Schema(types = {"number", "null"}) BigDecimal advRatioMa,
      BigDecimal threshold,
      boolean thrust) {}

  /** One sector's session performance + its day-over-day rank movement. */
  public record SectorRotation(
      String sector,
      BigDecimal avgChangePct,
      int stocks,
      int rankToday,
      @Schema(types = {"integer", "null"}) Integer rankPrior,
      @Schema(types = {"integer", "null"}) Integer rankDelta) {}

  /** One delivery-% z-score outlier (unusually high delivery vs the symbol's trailing 20 sessions). */
  public record DeliveryOutlier(String symbol, BigDecimal deliveryPct, BigDecimal mean20, BigDecimal z) {}

  /** One returns leader for the digest window (default 1-week). */
  public record ReturnLeader(String symbol, String sector, BigDecimal returnPct) {}

  /** Index-move concentration: the top-N names' share of the session's total absolute contribution. */
  public record IndexConcentration(
      String index,
      @Schema(types = {"number", "null"}) BigDecimal indexChangePct,
      int topN,
      @Schema(types = {"number", "null"}) BigDecimal topShare) {}

  /** The equity context digest. {@code dataTrust} = OK/DEGRADED/BLOCKED. */
  public record EquityDigest(
      @Schema(types = {"string", "null"}) LocalDate tradeDate,
      @Schema(types = {"object", "null"}) AdvanceDecline advanceDecline,
      @Schema(types = {"object", "null"}) AboveMa aboveMa,
      @Schema(types = {"object", "null"}) BreadthThrust breadthThrust,
      List<SectorRotation> topSectors,
      List<SectorRotation> bottomSectors,
      List<DeliveryOutlier> deliveryOutliers,
      @Schema(types = {"string", "null"}) String returnsWindow,
      List<ReturnLeader> returnWinners,
      List<ReturnLeader> returnLosers,
      @Schema(types = {"object", "null"}) IndexConcentration indexConcentration,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf,
      String dataTrust,
      List<String> trustReasons) {

    static EquityDigest blocked(String reason) {
      return new EquityDigest(
          null, null, null, null, List.of(), List.of(), List.of(), null, List.of(), List.of(), null,
          null, "BLOCKED", List.of(reason));
    }
  }

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final EquityContextRepository repo;
  private final StockSectorMap sectors;
  private final StaticIndexWeights indexWeights;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final int thrustWindow;
  private final BigDecimal thrustThreshold;
  private final BigDecimal deliveryMinZ;
  private final int deliveryLimit;
  private final int returnWindowRn;
  private final int topN;
  private final String concentrationIndex;
  private final int concentrationTopN;

  /** Wires the fold repo, the sector/weight reference, the calendar, and the digest knobs. */
  public EquityDigestService(
      EquityContextRepository repo,
      StockSectorMap sectors,
      StaticIndexWeights indexWeights,
      MarketCalendar calendar,
      Clock clock,
      @Value("${artha.context.equity.thrust-window:10}") int thrustWindow,
      @Value("${artha.context.equity.thrust-threshold:0.615}") BigDecimal thrustThreshold,
      @Value("${artha.context.equity.delivery-min-z:2.0}") BigDecimal deliveryMinZ,
      @Value("${artha.context.equity.delivery-limit:10}") int deliveryLimit,
      @Value("${artha.context.equity.return-window-rn:6}") int returnWindowRn,
      @Value("${artha.context.equity.top-n:3}") int topN,
      @Value("${artha.context.index-symbol:NIFTY 50}") String concentrationIndex,
      @Value("${artha.context.equity.concentration-top-n:5}") int concentrationTopN) {
    this.repo = repo;
    this.sectors = sectors;
    this.indexWeights = indexWeights;
    this.calendar = calendar;
    this.clock = clock;
    this.thrustWindow = thrustWindow;
    this.thrustThreshold = thrustThreshold;
    this.deliveryMinZ = deliveryMinZ;
    this.deliveryLimit = deliveryLimit;
    this.returnWindowRn = returnWindowRn;
    this.topN = topN;
    this.concentrationIndex = concentrationIndex;
    this.concentrationTopN = concentrationTopN;
  }

  /** The equity digest for {@code date} (null ⇒ the latest EQ bhavcopy session). */
  public EquityDigest digest(LocalDate date) {
    LocalDate session = date != null ? date : repo.latestSession();
    if (session == null) {
      return EquityDigest.blocked("no equity bhavcopy captured");
    }
    EquityContextRepository.AdCounts ad = repo.advanceDecline(session);
    if (ad.total() == 0) {
      return EquityDigest.blocked("no equity bhavcopy for " + session);
    }
    List<String> reasons = new ArrayList<>();
    stale(session, reasons);

    LocalDate prior = repo.priorSession(session);
    AdvanceDecline advDec = advanceDecline(session, ad, prior);
    AboveMa aboveMa = aboveMa(session);
    BreadthThrust thrust = breadthThrust(session);
    Pair<SectorRotation> sectorPair = sectorRotation(session, prior);
    List<DeliveryOutlier> outliers = deliveryOutliers(session);
    Pair<ReturnLeader> returnPair = returnLeaders(session);
    IndexConcentration concentration = concentration(session);

    OffsetDateTime asOf = session.atTime(MarketCalendar.SESSION_CLOSE).atOffset(Ist.OFFSET);
    String trust = reasons.isEmpty() ? "OK" : "DEGRADED";
    return new EquityDigest(
        session,
        advDec,
        aboveMa,
        thrust,
        sectorPair.first(),
        sectorPair.second(),
        outliers,
        "1w",
        returnPair.first(),
        returnPair.second(),
        concentration,
        asOf,
        trust,
        List.copyOf(reasons));
  }

  /** Degrade when the resolved session is behind the last settled trading day (batch stale-anchor). */
  private void stale(LocalDate session, List<String> reasons) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), Ist.ZONE);
    try {
      LocalDate floor = calendar.previousTradingDay(today);
      if (session.isBefore(floor)) {
        reasons.add("stale - newest bhavcopy session is " + session + ", last settled trading day is " + floor);
      }
    } catch (IllegalArgumentException uncoveredYear) {
      // outside calendar coverage — skip the stale check rather than fabricate a verdict.
    }
  }

  private AdvanceDecline advanceDecline(
      LocalDate session, EquityContextRepository.AdCounts ad, LocalDate prior) {
    BigDecimal ratio = adRatio(ad.advances(), ad.declines());
    BigDecimal priorRatio = null;
    if (prior != null) {
      EquityContextRepository.AdCounts p = repo.advanceDecline(prior);
      if (p.total() > 0) {
        priorRatio = adRatio(p.advances(), p.declines());
      }
    }
    BigDecimal delta = ratio != null && priorRatio != null ? ratio.subtract(priorRatio) : null;
    return new AdvanceDecline(
        session, ad.advances(), ad.declines(), ad.unchanged(), ad.total(), ratio, priorRatio, delta);
  }

  private static BigDecimal adRatio(int advances, int declines) {
    return declines == 0
        ? null
        : BigDecimal.valueOf(advances).divide(BigDecimal.valueOf(declines), 2, RoundingMode.HALF_UP);
  }

  private AboveMa aboveMa(LocalDate session) {
    EquityContextRepository.AboveMaCounts c = repo.aboveMa(session);
    return new AboveMa(
        c.universe20(),
        c.above20(),
        pct(c.above20(), c.universe20()),
        c.universe50(),
        c.above50(),
        pct(c.above50(), c.universe50()));
  }

  private BreadthThrust breadthThrust(LocalDate session) {
    List<EquityContextRepository.AdSession> series = repo.advDecSeries(session, thrustWindow);
    if (series.isEmpty()) {
      return new BreadthThrust(0, null, thrustThreshold, false);
    }
    BigDecimal sum = BigDecimal.ZERO;
    int n = 0;
    for (EquityContextRepository.AdSession s : series) {
      int denom = s.advances() + s.declines();
      if (denom == 0) {
        continue;
      }
      sum = sum.add(BigDecimal.valueOf(s.advances()).divide(BigDecimal.valueOf(denom), 6, RoundingMode.HALF_UP));
      n++;
    }
    if (n == 0) {
      return new BreadthThrust(0, null, thrustThreshold, false);
    }
    BigDecimal ma = sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    return new BreadthThrust(n, ma, thrustThreshold, ma.compareTo(thrustThreshold) >= 0);
  }

  /** A first/second list pair (top/bottom or winners/losers). */
  private record Pair<T>(List<T> first, List<T> second) {}

  private Pair<SectorRotation> sectorRotation(LocalDate session, LocalDate prior) {
    SectorAgg today = sectorAgg(repo.sectorSessionChange(session));
    SectorAgg priorAgg =
        prior == null ? new SectorAgg(Map.of(), Map.of()) : sectorAgg(repo.sectorSessionChange(prior));

    List<String> todayRankOrder = rankOrder(today.avg());
    List<String> priorRankOrder = rankOrder(priorAgg.avg());
    Map<String, Integer> priorRank = new LinkedHashMap<>();
    for (int i = 0; i < priorRankOrder.size(); i++) {
      priorRank.put(priorRankOrder.get(i), i + 1);
    }

    List<SectorRotation> ranked = new ArrayList<>();
    for (int i = 0; i < todayRankOrder.size(); i++) {
      String sector = todayRankOrder.get(i);
      int rankToday = i + 1;
      Integer rankPrior = priorRank.get(sector);
      Integer rankDelta = rankPrior == null ? null : rankPrior - rankToday; // + = climbed
      ranked.add(
          new SectorRotation(
              sector, today.avg().get(sector), today.count().getOrDefault(sector, 0), rankToday, rankPrior, rankDelta));
    }
    List<SectorRotation> top = ranked.stream().limit(topN).toList();
    List<SectorRotation> bottom =
        ranked.stream().skip(Math.max(0, ranked.size() - topN)).toList();
    return new Pair<>(top, bottom);
  }

  /** Per-sector average session change% and the contributing-stock count. */
  private record SectorAgg(Map<String, BigDecimal> avg, Map<String, Integer> count) {}

  /** Average session change% per sector (symbols mapped via {@link StockSectorMap}; unmapped dropped). */
  private SectorAgg sectorAgg(List<EquityContextRepository.SessionChange> rows) {
    Map<String, BigDecimal> sum = new LinkedHashMap<>();
    Map<String, Integer> count = new LinkedHashMap<>();
    for (EquityContextRepository.SessionChange r : rows) {
      String sector = sectors.sector(r.symbol());
      if (sector == null) {
        continue;
      }
      BigDecimal chg = changePct(r.close(), r.prevClose());
      if (chg == null) {
        continue;
      }
      sum.merge(sector, chg, BigDecimal::add);
      count.merge(sector, 1, Integer::sum);
    }
    Map<String, BigDecimal> avg = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> e : sum.entrySet()) {
      avg.put(e.getKey(), e.getValue().divide(BigDecimal.valueOf(count.get(e.getKey())), 2, RoundingMode.HALF_UP));
    }
    return new SectorAgg(avg, count);
  }

  private static List<String> rankOrder(Map<String, BigDecimal> avg) {
    return avg.entrySet().stream()
        .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
        .map(Map.Entry::getKey)
        .toList();
  }

  private List<DeliveryOutlier> deliveryOutliers(LocalDate session) {
    return repo.deliveryZ(session, deliveryMinZ, deliveryLimit).stream()
        .map(r -> new DeliveryOutlier(r.symbol(), r.deliveryPct(), r.mean20(), r.z()))
        .toList();
  }

  private Pair<ReturnLeader> returnLeaders(LocalDate session) {
    List<ReturnLeader> all = new ArrayList<>();
    for (EquityContextRepository.ReturnBase b : repo.returnBases(session, returnWindowRn)) {
      String sector = sectors.sector(b.symbol());
      if (sector == null) {
        continue; // restrict to the curated sector-mapped universe (matches EquityReturnsService)
      }
      BigDecimal ret = changePct(b.c0(), b.cPrior());
      if (ret == null) {
        continue;
      }
      all.add(new ReturnLeader(b.symbol(), sector, ret));
    }
    all.sort(Comparator.comparing(ReturnLeader::returnPct).reversed());
    List<ReturnLeader> winners = all.stream().limit(topN).toList();
    List<ReturnLeader> losers =
        all.stream().skip(Math.max(0, all.size() - topN)).toList().reversed();
    return new Pair<>(winners, losers);
  }

  private IndexConcentration concentration(LocalDate session) {
    Map<String, BigDecimal> weights = indexWeights.weights(concentrationIndex);
    if (weights.isEmpty()) {
      return new IndexConcentration(concentrationIndex, null, concentrationTopN, null);
    }
    List<EquityContextRepository.SessionChange> rows =
        repo.indexMemberChange(session, List.copyOf(weights.keySet()));
    List<BigDecimal> contributions = new ArrayList<>();
    BigDecimal indexChange = BigDecimal.ZERO;
    for (EquityContextRepository.SessionChange r : rows) {
      BigDecimal w = weights.get(r.symbol());
      BigDecimal chg = changePct(r.close(), r.prevClose());
      if (w == null || chg == null) {
        continue;
      }
      BigDecimal contribution = w.multiply(chg).divide(HUNDRED, 6, RoundingMode.HALF_UP);
      contributions.add(contribution);
      indexChange = indexChange.add(contribution);
    }
    if (contributions.isEmpty()) {
      return new IndexConcentration(concentrationIndex, null, concentrationTopN, null);
    }
    List<BigDecimal> byMagnitude =
        contributions.stream().map(BigDecimal::abs).sorted(Comparator.reverseOrder()).toList();
    BigDecimal totalAbs = byMagnitude.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal topAbs =
        byMagnitude.stream().limit(concentrationTopN).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal share =
        totalAbs.signum() == 0 ? null : topAbs.multiply(HUNDRED).divide(totalAbs, 2, RoundingMode.HALF_UP);
    return new IndexConcentration(
        concentrationIndex, indexChange.setScale(2, RoundingMode.HALF_UP), concentrationTopN, share);
  }

  private static BigDecimal changePct(BigDecimal close, BigDecimal prevClose) {
    if (close == null || prevClose == null || prevClose.signum() == 0) {
      return null;
    }
    return close.subtract(prevClose).multiply(HUNDRED).divide(prevClose, 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal pct(int part, int whole) {
    return whole == 0
        ? null
        : BigDecimal.valueOf(part).multiply(HUNDRED).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
  }
}
