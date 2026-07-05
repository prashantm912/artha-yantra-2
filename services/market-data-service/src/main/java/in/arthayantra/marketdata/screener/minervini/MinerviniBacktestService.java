package in.arthayantra.marketdata.screener.minervini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade;
import in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.Variant;
import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpDetector;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the Phase-9 Minervini swing BACKTEST ({@link MinerviniSwingBacktest}) over the dense
 * {@code candles}@1d equity universe (~11y depth) — the deep-history evidence a months-long strategy
 * needs (the buy-and-hold hit-rate harness only measures fixed-horizon forward returns). Streams each
 * EQ symbol's full daily series, runs all 4 setups, and aggregates every closed trade into per-setup
 * win-rate / payoff / expectancy / avg-hold.
 *
 * <p>It runs TWO variants for an A/B in one job:
 *
 * <ul>
 *   <li><b>v1 (technical-only)</b> — the price-structure Trend-Template + setup triggers; RS relaxed,
 *       no liquidity floor.
 *   <li><b>v2 (rs + turnover)</b> — adds the real cross-sectional RS-rank gate and a turnover floor.
 * </ul>
 *
 * The RS-rank is computed here (the sim is per-symbol and cannot see the universe): a WEEKLY
 * cross-sectional percentile of the §4.10 weighted trailing return (0.4·r63 + 0.2·r126 + 0.2·r189 +
 * 0.2·r252). Weekly keeps the whole distribution set to a few MB — market-data runs on a small heap
 * ({@code -Xmx448m}) and RS-rank is a slow-moving weekly quantity anyway. Compute is minutes, so it
 * runs on a background thread and persists both variant reports; the GET endpoints read the latest.
 * One run at a time (a guard rejects a concurrent trigger).
 */
@Service
public class MinerviniBacktestService {

  private static final Logger log = LoggerFactory.getLogger(MinerviniBacktestService.class);

  private static final int RANK_CADENCE = 5; // rank the universe every 5th session (weekly)
  private static final int RS_LOOKBACK = 252; // longest RS lag; also the 52-week gate warmup
  private static final int MIN_SERIES = 260; // min bars to be rankable AND tradable — ONE threshold
  private static final int MIN_DIST = 20; // don't trust a percentile from a degenerate thin cross-section

  // v6 turnover-floor sweep grids (₹): book sizes × candidate turnover floors.
  private static final long[] SWEEP_CAPITALS = {150_000, 1_000_000, 5_000_000, 10_000_000, 50_000_000};
  private static final long[] SWEEP_FLOORS = {
    0, 500_000, 1_000_000, 2_500_000, 3_750_000, 7_500_000, 15_000_000, 30_000_000
  };

  // v7 slot sweep: does adding concurrent positions capture more of the signal flood?
  private static final int[] SLOT_SWEEP = {8, 12, 16, 20, 24};

  /** Per-setup aggregate over the backtest window. Decimals ride as JSON strings. */
  public record SetupStat(
      String setup,
      int trades,
      int wins,
      int losses,
      BigDecimal winRatePct,
      BigDecimal avgWinPct,
      BigDecimal avgLossPct,
      BigDecimal payoffRatio,
      BigDecimal expectancyPct,
      BigDecimal profitFactor,
      BigDecimal avgBarsHeld,
      BigDecimal bestTradePct,
      BigDecimal worstTradePct,
      int longestHoldBars,
      int shortestHoldBars,
      int maxWinStreak,
      int maxLossStreak,
      BigDecimal stopOutPct) {}

  /** One calendar year's realised portfolio return + the trades that closed in it. */
  public record YearReturn(int year, BigDecimal returnPct, int trades) {}

  /** Portfolio-level stats for one variant (all setups combined through the slot-limited book). */
  public record PortfolioStat(
      int slots,
      BigDecimal totalReturnPct,
      BigDecimal cagrPct,
      BigDecimal maxDrawdownPct,
      BigDecimal sharpe,
      int tradesTaken,
      int tradesSkipped,
      BigDecimal avgExposurePct,
      int months,
      BigDecimal positiveMonthsPct,
      BigDecimal bestMonthPct,
      BigDecimal worstMonthPct,
      BigDecimal avgMonthPct,
      List<YearReturn> annual) {}

  /**
   * One variant's full backtest report — per-setup trade stats + three portfolio equity runs:
   * {@code portfolio} (FIFO, gross), {@code portfolioRsPriority} (RS-rank-priority slots, gross), and
   * {@code portfolioRsPriorityNet} (RS-priority, NET of turnover-scaled transaction costs — the
   * realistic-live estimate).
   */
  public record Report(
      String status,
      String variant,
      LocalDate fromDate,
      String runAt,
      int symbolsScanned,
      int totalTrades,
      List<SetupStat> setups,
      PortfolioStat portfolio,
      PortfolioStat portfolioRsPriority,
      PortfolioStat portfolioRsPriorityNet,
      String note) {}

  /**
   * One cell of the turnover-floor sweep: at a given {@code capital} (book) and {@code
   * floorTurnover}, the NET-of-cost RS-priority portfolio over the RS-gated signals kept above the
   * floor. Finds the liquidity threshold that maximises net CAGR for a given book size.
   */
  public record SweepCell(
      long capital,
      long floorTurnover,
      int trades,
      BigDecimal netCagrPct,
      BigDecimal netDrawdownPct,
      BigDecimal netSharpe) {}

  /** One slot-sweep row: the RS-priority portfolio at {@code slots} concurrent positions (v7). */
  public record SlotCell(
      int slots,
      int tradesTaken,
      int tradesSkipped,
      BigDecimal grossCagrPct,
      BigDecimal netCagrPct,
      BigDecimal netDrawdownPct,
      BigDecimal netSharpe) {}

  /**
   * The RS-rotation result (v7): the net-of-cost portfolio that evicts the weakest current holding for
   * a stronger newcomer (vs holding to the natural stop/trail exit), plus how many rotations fired.
   * Compare {@code net} against the same variant's {@code portfolioRsPriorityNet} (no-rotation baseline).
   */
  public record RotationResult(int slots, BigDecimal marginPct, int rotations, PortfolioStat net) {}

  /** The full multi-variant result: technical / rs-only / turnover-only / rs+turnover, side by side. */
  public record BacktestResult(
      String status,
      LocalDate fromDate,
      String runAt,
      List<Report> variants,
      List<SweepCell> sweep,
      List<SlotCell> slotSweep,
      RotationResult rotation,
      String note) {}

  /** The full-filter variant (the live-funnel analogue) — the one the plain GET returns. */
  private static final String PRIMARY_VARIANT = "rs-turnover";

  private final JdbcTemplate jdbc;
  private final VcpDetector detector;
  private final ObjectMapper objectMapper;
  private final int defaultYears;
  private final double turnoverFloor;
  private final double rsMin;
  private final int slots;
  private final SwingPortfolio.Costs costs;
  private final double rotationMargin;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile Report latest;
  private volatile BacktestResult latestResult;

  /** Wires the marketdata datasource + the VCP detector + config. */
  public MinerviniBacktestService(
      JdbcTemplate jdbc,
      VcpDetector detector,
      ObjectMapper objectMapper,
      @Value("${artha.minervini.backtest.years:11}") int defaultYears,
      @Value("${artha.minervini.backtest.min-turnover:3750000}") BigDecimal minTurnover,
      @Value("${artha.minervini.backtest.rs-min:70}") BigDecimal rsMin,
      @Value("${artha.minervini.backtest.slots:8}") int slots,
      @Value("${artha.minervini.backtest.capital:1000000}") double capital,
      @Value("${artha.minervini.backtest.cost.fixed-pct:0.25}") double costFixedPct,
      @Value("${artha.minervini.backtest.cost.spread-pct:0.05}") double costSpreadPct,
      @Value("${artha.minervini.backtest.cost.impact-coeff:0.10}") double costImpactCoeff,
      @Value("${artha.minervini.backtest.cost.impact-cap-pct:5.0}") double costImpactCapPct,
      @Value("${artha.minervini.backtest.rotation-margin:5}") double rotationMargin) {
    this.jdbc = jdbc;
    this.detector = detector;
    this.objectMapper = objectMapper;
    this.defaultYears = defaultYears;
    this.turnoverFloor = minTurnover.doubleValue();
    this.rsMin = rsMin.doubleValue();
    this.slots = slots;
    this.costs =
        new SwingPortfolio.Costs(
            capital, costFixedPct, costSpreadPct, costImpactCoeff, costImpactCapPct);
    this.rotationMargin = rotationMargin;
  }

  /** The latest completed (or in-progress) single report — the full-filter (rs-turnover) variant. */
  public Report latest() {
    if (latest != null) {
      return latest;
    }
    return jdbc
        .query(
            "SELECT report FROM minervini_backtest_runs WHERE report->>'variant' = ?"
                + " ORDER BY run_at DESC LIMIT 1",
            (rs, n) -> parse(rs.getString("report")), PRIMARY_VARIANT)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** The latest multi-variant result; reads the newest run's rows (one per variant) on a cold boot. */
  public BacktestResult latestResult() {
    if (latestResult != null) {
      return latestResult;
    }
    List<Report> recent =
        jdbc.query(
            "SELECT report FROM minervini_backtest_runs ORDER BY run_at DESC LIMIT 4",
            (rs, n) -> parse(rs.getString("report")));
    List<Report> variants = new ArrayList<>();
    for (Report r : recent) {
      if (r != null && variants.stream().noneMatch(v -> v.variant().equals(r.variant()))) {
        variants.add(r);
      }
    }
    if (variants.isEmpty()) {
      return null;
    }
    Report any = variants.get(0);
    return new BacktestResult(
        "completed", any.fromDate(), any.runAt(), variants, List.of(), List.of(), null,
        "loaded from the last persisted run (sweep/slots/rotation are in-memory only; re-run to populate)");
  }

  /** Triggers a run on a background thread; returns false if one is already running. */
  public boolean trigger(Integer years) {
    if (!running.compareAndSet(false, true)) {
      return false;
    }
    LocalDate from = from(years);
    latest = running(PRIMARY_VARIANT, from);
    latestResult =
        new BacktestResult(
            "running", from, null, List.of(), List.of(), List.of(), null, "backtest in progress");
    Thread t = new Thread(() -> runOnce(years), "minervini-backtest");
    t.setDaemon(true);
    t.start();
    return true;
  }

  private void runOnce(Integer years) {
    try {
      BacktestResult result = run(from(years));
      latestResult = result;
      result.variants().stream()
          .filter(r -> r.variant().equals(PRIMARY_VARIANT))
          .findFirst()
          .ifPresent(r -> latest = r);
      result.variants().forEach(this::persist);
    } catch (RuntimeException e) {
      log.error("minervini swing backtest failed: {}", e.getMessage(), e);
      LocalDate from = from(years);
      latest =
          new Report(
              "failed", PRIMARY_VARIANT, from, null, 0, 0, List.of(), null, null, null, e.getMessage());
      latestResult =
          new BacktestResult(
              "failed", from, null, List.of(), List.of(), List.of(), null, e.getMessage());
    } finally {
      running.set(false);
    }
  }

  private static Report running(String variant, LocalDate from) {
    return new Report(
        "running", variant, from, null, 0, 0, List.of(), null, null, null, "backtest in progress");
  }

  /** Runs all four variants over {@code [from, now]} in one pass. Package-visible for tests. */
  BacktestResult run(LocalDate from) {
    LocalDate warmStart = from.minusDays(600); // ≥252 sessions + VCP-lookback warmup before `from`
    List<String> symbols = eqSymbols();

    // Pass 1 — the cross-sectional RS distribution at each weekly rank date (memory-lean: primitive).
    LocalDate[] rankDates = weeklyRankDates(warmStart);
    Map<LocalDate, DoubleBag> bags = new HashMap<>();
    for (LocalDate d : rankDates) {
      bags.put(d, new DoubleBag());
    }
    for (String symbol : symbols) {
      Series s = readCloses(symbol, warmStart);
      // ONE membership threshold with the Pass-2 ranked set (else a name pollutes the distribution
      // it is never itself ranked against — the "cross-section" would not be its own population).
      if (s.close().length < MIN_SERIES) {
        continue;
      }
      // Contribute at each rank date within this symbol's lifespan, measured at its AS-OF bar (its
      // last bar ≤ the rank date) — a true cross-section, not "only names that printed on that exact
      // session". The early warm rank dates stay thin (few names have 252 bars yet) but sit entirely
      // before `from`, so no trade is ranked against them (a floor in perBarRsRank guards the rest).
      for (LocalDate rd : rankDates) {
        int idx = asOfIndex(s.dates(), rd);
        if (idx < RS_LOOKBACK) {
          continue;
        }
        double rs = weightedRs(s.close(), idx);
        if (!Double.isNaN(rs)) {
          bags.get(rd).add(rs);
        }
      }
    }
    Map<LocalDate, double[]> dist = new HashMap<>();
    bags.forEach((d, bag) -> dist.put(d, bag.sorted()));
    bags.clear();

    // Pass 2 — replay the 2×2 of {RS gate off/on} × {turnover floor off/on} over the SAME bars, so the
    // four variants are a clean apples-to-apples isolation (identical geometry/MAs/triggers).
    List<Variant> variants =
        List.of(
            new Variant("technical", false, 0, 0), // v1: neither filter
            new Variant("rs-only", true, rsMin, 0), // RS-rank gate alone
            new Variant("turnover-only", false, 0, turnoverFloor), // liquidity floor alone
            new Variant("rs-turnover", true, rsMin, turnoverFloor)); // both (the live-funnel analogue)
    List<BtTrade> all = new ArrayList<>();
    // per-symbol weekly (RS, close) series for the rotation sim's held-position lookups — captured only
    // for symbols that produce rs-only signals (bounds the memory on the small heap).
    Map<String, SwingRotationPortfolio.WeeklySeries> weekly = new HashMap<>();
    int scanned = 0;
    for (String symbol : symbols) {
      List<DailyBar> bars = readSeries(symbol, warmStart);
      if (bars.size() < MIN_SERIES) {
        continue;
      }
      scanned++;
      double[] rsRank = perBarRsRank(bars, rankDates, dist);
      List<BtTrade> got =
          MinerviniSwingBacktest.simulate(symbol, bars, detector, from, rsRank, variants);
      all.addAll(got);
      if (got.stream().anyMatch(t -> t.variant().equals("rs-only"))) {
        weekly.put(symbol, downsampleWeekly(bars, rsRank));
      }
    }

    String runAt = nowIso();
    List<Report> reports = new ArrayList<>();
    for (Variant v : variants) {
      reports.add(report(v.name(), from, runAt, scanned, all));
    }
    List<SweepCell> sweep = turnoverSweep(all);
    List<SlotCell> slotSweep = slotSweep(all);
    RotationResult rotation = rotation(all, weekly);
    log.info(
        "minervini swing backtest: {} symbols, {}, rotations {}, from {}",
        scanned,
        reports.stream().map(r -> r.variant() + " " + r.totalTrades()).reduce((a, b) -> a + ", " + b).orElse(""),
        rotation.rotations(),
        from);
    return new BacktestResult(
        "completed", from, runAt, reports, sweep, slotSweep, rotation,
        "candles@1d ~11y over " + slots + " concurrent slots; variants isolate a weekly cross-sectional"
            + " RS-rank≥" + fmt(rsMin) + " gate and a ₹" + fmt(turnoverFloor) + "/day turnover floor;"
            + " open-at-end positions dropped; portfolio is net-of-cost; survivorship-biased.");
  }

  /** v7 slot sweep: the RS-priority portfolio at 8/12/16/20/24 slots, gross + net. */
  private List<SlotCell> slotSweep(List<BtTrade> all) {
    List<BtTrade> rsGated = all.stream().filter(t -> t.variant().equals("rs-only")).toList();
    List<SlotCell> out = new ArrayList<>();
    for (int k : SLOT_SWEEP) {
      SwingPortfolio.Result gross = SwingPortfolio.simulate(rsGated, k, true, null);
      SwingPortfolio.Result net = SwingPortfolio.simulate(rsGated, k, true, costs);
      out.add(
          new SlotCell(
              k, net.tradesTaken(), net.tradesSkipped(), bd2(gross.cagrPct()), bd2(net.cagrPct()),
              bd2(net.maxDrawdownPct()), bd2(net.sharpe())));
    }
    return out;
  }

  /** v7 RS-rotation: the net-of-cost rotating book (evict weakest holding for a stronger newcomer). */
  private RotationResult rotation(
      List<BtTrade> all, Map<String, SwingRotationPortfolio.WeeklySeries> weekly) {
    List<BtTrade> rsGated = all.stream().filter(t -> t.variant().equals("rs-only")).toList();
    SwingRotationPortfolio.Outcome o =
        SwingRotationPortfolio.simulate(rsGated, weekly, slots, costs, rotationMargin);
    return new RotationResult(slots, bd2(rotationMargin), o.rotations(), toStat(o.result(), slots));
  }

  /** Weekly (every 5th session) downsample of a symbol's (RS, close) series for as-of lookups. */
  private static SwingRotationPortfolio.WeeklySeries downsampleWeekly(
      List<DailyBar> bars, double[] rsRank) {
    int n = bars.size();
    int m = (n + RANK_CADENCE - 1) / RANK_CADENCE;
    long[] days = new long[m];
    double[] rs = new double[m];
    double[] close = new double[m];
    int j = 0;
    for (int i = 0; i < n; i += RANK_CADENCE) {
      days[j] = bars.get(i).date().toEpochDay();
      rs[j] = rsRank[i];
      close[j] = bars.get(i).close();
      j++;
    }
    return new SwingRotationPortfolio.WeeklySeries(days, rs, close);
  }

  /**
   * The turnover-floor sweep (v6): over the RS-gated signal set, for each (book size, turnover floor)
   * it keeps signals above the floor and runs the NET-of-cost RS-priority portfolio — showing the
   * liquidity threshold that maximises net CAGR per book size. This is a portfolio-level (allocation)
   * filter over the {@code rs-only} signals, not a per-floor re-run of the setup books, so it is an
   * approximation: a true re-run would let a floored-out entry free its slot for the next signal.
   */
  private List<SweepCell> turnoverSweep(List<BtTrade> all) {
    List<BtTrade> rsGated = all.stream().filter(t -> t.variant().equals("rs-only")).toList();
    List<SweepCell> out = new ArrayList<>();
    for (long capital : SWEEP_CAPITALS) {
      SwingPortfolio.Costs c =
          new SwingPortfolio.Costs(
              capital, costs.fixedPct(), costs.spreadPct(), costs.impactCoeff(), costs.impactCapPct());
      for (long floor : SWEEP_FLOORS) {
        List<BtTrade> kept = rsGated.stream().filter(t -> t.avgTurnoverAtEntry() >= floor).toList();
        SwingPortfolio.Result r = SwingPortfolio.simulate(kept, slots, true, c);
        out.add(
            new SweepCell(
                capital, floor, r.tradesTaken(), bd2(r.cagrPct()), bd2(r.maxDrawdownPct()),
                bd2(r.sharpe())));
      }
    }
    return out;
  }

  /** Aggregates every trade for one {@code variant} into per-setup trade stats + a portfolio equity run. */
  private Report report(String variant, LocalDate from, String runAt, int scanned, List<BtTrade> all) {
    Map<String, List<BtTrade>> bySetup = new LinkedHashMap<>();
    MinerviniSwingBacktest.SETUPS.forEach(s -> bySetup.put(s, new ArrayList<>()));
    bySetup.put("ALL", new ArrayList<>());
    int total = 0;
    for (BtTrade t : all) {
      if (!t.variant().equals(variant)) {
        continue;
      }
      bySetup.get(t.setup()).add(t);
      bySetup.get("ALL").add(t);
      total++;
    }
    List<SetupStat> stats = new ArrayList<>();
    bySetup.forEach((setup, trades) -> stats.add(aggregate(setup, trades)));
    List<BtTrade> allTrades = bySetup.get("ALL");
    PortfolioStat fifo = portfolio(allTrades, false, null);
    PortfolioStat rsPriority = portfolio(allTrades, true, null);
    PortfolioStat rsPriorityNet = portfolio(allTrades, true, costs);
    return new Report(
        "completed", variant, from, runAt, scanned, total, stats, fifo, rsPriority, rsPriorityNet, null);
  }

  /** Runs the slot-limited portfolio over a variant's combined trade stream (FIFO/RS-priority, gross/net). */
  private PortfolioStat portfolio(List<BtTrade> trades, boolean rsPriority, SwingPortfolio.Costs c) {
    return toStat(SwingPortfolio.simulate(trades, slots, rsPriority, c), slots);
  }

  /** Maps a raw {@link SwingPortfolio.Result} to the JSON {@link PortfolioStat} (decimals → strings). */
  private PortfolioStat toStat(SwingPortfolio.Result r, int slotCount) {
    List<YearReturn> annual =
        r.annual().stream().map(y -> new YearReturn(y.year(), bd2(y.returnPct()), y.trades())).toList();
    return new PortfolioStat(
        slotCount, bd2(r.totalReturnPct()), bd2(r.cagrPct()), bd2(r.maxDrawdownPct()), bd2(r.sharpe()),
        r.tradesTaken(), r.tradesSkipped(), bd2(r.avgExposurePct()), r.months(),
        bd2(r.positiveMonthsPct()), bd2(r.bestMonthPct()), bd2(r.worstMonthPct()), bd2(r.avgMonthPct()),
        annual);
  }

  private static SetupStat aggregate(String setup, List<BtTrade> trades) {
    int n = trades.size();
    if (n == 0) {
      return new SetupStat(
          setup, 0, 0, 0, z(), z(), z(), z(), z(), z(), z(), z(), z(), 0, 0, 0, 0, z());
    }
    int w = 0;
    int l = 0;
    BigDecimal sumPct = BigDecimal.ZERO;
    BigDecimal sumWin = BigDecimal.ZERO;
    BigDecimal sumLoss = BigDecimal.ZERO;
    long sumBars = 0;
    double best = Double.NEGATIVE_INFINITY;
    double worst = Double.POSITIVE_INFINITY;
    int longest = 0;
    int shortest = Integer.MAX_VALUE;
    int stopOuts = 0;
    for (BtTrade t : trades) {
      BigDecimal p = BigDecimal.valueOf(t.pnlPct());
      sumPct = sumPct.add(p);
      sumBars += t.barsHeld();
      if (t.pnlPct() > 0) {
        w++;
        sumWin = sumWin.add(p);
      } else {
        l++;
        sumLoss = sumLoss.add(p);
      }
      best = Math.max(best, t.pnlPct());
      worst = Math.min(worst, t.pnlPct());
      longest = Math.max(longest, t.barsHeld());
      shortest = Math.min(shortest, t.barsHeld());
      if ("STOP_LOSS".equals(t.exitReason())) {
        stopOuts++;
      }
    }
    // streaks in the order the trades were entered
    List<BtTrade> chron = new ArrayList<>(trades);
    chron.sort(Comparator.comparing(BtTrade::entryDate));
    int maxWinStreak = 0;
    int maxLossStreak = 0;
    int curWin = 0;
    int curLoss = 0;
    for (BtTrade t : chron) {
      if (t.pnlPct() > 0) {
        curWin++;
        curLoss = 0;
      } else {
        curLoss++;
        curWin = 0;
      }
      maxWinStreak = Math.max(maxWinStreak, curWin);
      maxLossStreak = Math.max(maxLossStreak, curLoss);
    }
    BigDecimal avgWin = w == 0 ? z() : div(sumWin, w);
    BigDecimal avgLoss = l == 0 ? z() : div(sumLoss, l);
    BigDecimal payoff = avgLoss.signum() == 0 ? z() : div(avgWin, avgLoss.abs());
    BigDecimal profitFactor = sumLoss.signum() == 0 ? z() : div(sumWin, sumLoss.abs());
    return new SetupStat(
        setup, n, w, l, div(BigDecimal.valueOf(w * 100L), n), avgWin, avgLoss, payoff,
        div(sumPct, n), profitFactor, div(BigDecimal.valueOf(sumBars), n), bd2(best), bd2(worst),
        longest, shortest, maxWinStreak, maxLossStreak, div(BigDecimal.valueOf(stopOuts * 100L), n));
  }

  // ---- RS-rank helpers --------------------------------------------------------------------------

  /** The distinct daily sessions across the NSE EQ universe from {@code from}, sampled every 5th (weekly). */
  private LocalDate[] weeklyRankDates(LocalDate from) {
    List<LocalDate> distinct =
        jdbc.query(
            "SELECT DISTINCT bucket::date AS d FROM candles"
                + " WHERE exchange='NSE' AND interval='1d' AND bucket >= ? ORDER BY d",
            (rs, n) -> rs.getObject("d", LocalDate.class), java.sql.Date.valueOf(from));
    List<LocalDate> weekly = new ArrayList<>();
    for (int i = 0; i < distinct.size(); i += RANK_CADENCE) {
      weekly.add(distinct.get(i));
    }
    return weekly.toArray(new LocalDate[0]);
  }

  /** §4.10 weighted trailing return as a double (0.4·r63 + 0.2·r126 + 0.2·r189 + 0.2·r252). */
  private static double weightedRs(double[] close, int i) {
    double r63 = ret(close[i], close[i - 63]);
    double r126 = ret(close[i], close[i - 126]);
    double r189 = ret(close[i], close[i - 189]);
    double r252 = ret(close[i], close[i - 252]);
    return 0.4 * r63 + 0.2 * r126 + 0.2 * r189 + 0.2 * r252;
  }

  private static double ret(double now, double past) {
    return past > 0 ? (now - past) / past : Double.NaN;
  }

  /** The per-bar cross-sectional RS percentile (0..100) for {@code bars}, NaN before the warmup. */
  private static double[] perBarRsRank(
      List<DailyBar> bars, LocalDate[] rankDates, Map<LocalDate, double[]> dist) {
    int n = bars.size();
    double[] close = new double[n];
    for (int i = 0; i < n; i++) {
      close[i] = bars.get(i).close();
    }
    double[] out = new double[n];
    Arrays.fill(out, Double.NaN);
    for (int i = RS_LOOKBACK; i < n; i++) {
      LocalDate rd = asOfRankDate(rankDates, bars.get(i).date());
      if (rd == null) {
        continue;
      }
      double[] d = dist.get(rd);
      if (d == null || d.length < MIN_DIST) {
        continue;
      }
      double my = weightedRs(close, i);
      if (Double.isNaN(my)) {
        continue;
      }
      out[i] = percentile(d, my);
    }
    return out;
  }

  /** The latest rank date ≤ {@code date} (binary search); null if none. */
  private static LocalDate asOfRankDate(LocalDate[] rankDates, LocalDate date) {
    int lo = 0;
    int hi = rankDates.length - 1;
    LocalDate best = null;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (!rankDates[mid].isAfter(date)) {
        best = rankDates[mid];
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return best;
  }

  /** The largest local index whose date ≤ {@code d} in ascending {@code dates}; -1 if none. */
  private static int asOfIndex(LocalDate[] dates, LocalDate d) {
    int lo = 0;
    int hi = dates.length - 1;
    int best = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (!dates[mid].isAfter(d)) {
        best = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return best;
  }

  /**
   * The MIDPOINT percentile rank of {@code x} in the ascending {@code sorted} array:
   * {@code 100·(below + 0.5·equal)/len}. The midpoint convention ranks ties symmetrically, so the sole
   * top name clears a high gate (in {@code [1,2,3]} the 3 scores 83.3, not the strictly-below 66.7) and
   * an all-equal block scores 50 — the IBD-style RS-rank the ≥70 gate expects. Package-visible for a
   * direct unit test. {@code sorted} must be non-empty (callers gate on {@link #MIN_DIST}).
   */
  static double percentile(double[] sorted, double x) {
    int below = lowerBound(sorted, x); // first index with sorted[i] >= x → count strictly below
    int above = upperBound(sorted, x); // first index with sorted[i] > x
    int equal = above - below;
    return 100.0 * (below + 0.5 * equal) / sorted.length;
  }

  /** First index {@code i} with {@code a[i] >= x} (count of elements strictly below {@code x}). */
  private static int lowerBound(double[] a, double x) {
    int lo = 0;
    int hi = a.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (a[mid] < x) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  /** First index {@code i} with {@code a[i] > x} (count of elements ≤ {@code x}). */
  private static int upperBound(double[] a, double x) {
    int lo = 0;
    int hi = a.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (a[mid] <= x) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  // ---- data access ------------------------------------------------------------------------------

  private List<String> eqSymbols() {
    return jdbc.queryForList(
        "SELECT DISTINCT c.tradingsymbol FROM candles c JOIN instruments i"
            + " ON i.exchange=c.exchange AND i.tradingsymbol=c.tradingsymbol AND i.instrument_type='EQ'"
            + " WHERE c.interval='1d' AND c.exchange='NSE'"
            + " ORDER BY c.tradingsymbol", // stable symbol order → run-to-run reproducible portfolio stats
        String.class);
  }

  /** A symbol's aligned (date, close) series. */
  private record Series(LocalDate[] dates, double[] close) {}

  /**
   * The (date, close) series for {@code symbol} from {@code from}. {@code bucket::date} carries the
   * documented IST→UTC shift (a 1d bucket is IST midnight), but EVERY query in this service applies the
   * same shift, so all dates share one calendar and relative ordering / cross-symbol alignment are
   * exact (only the reported calendar day is one early — cosmetic). Do not "fix" one query in isolation.
   */
  private Series readCloses(String symbol, LocalDate from) {
    List<Object[]> rows =
        jdbc.query(
            "SELECT bucket::date AS d, close FROM candles"
                + " WHERE exchange='NSE' AND tradingsymbol=? AND interval='1d' AND bucket >= ?"
                + " ORDER BY bucket ASC",
            (rs, n) ->
                new Object[] {
                  rs.getObject("d", LocalDate.class), rs.getBigDecimal("close").doubleValue()
                },
            symbol, java.sql.Date.valueOf(from));
    LocalDate[] dates = new LocalDate[rows.size()];
    double[] close = new double[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      dates[i] = (LocalDate) rows.get(i)[0];
      close[i] = (double) rows.get(i)[1];
    }
    return new Series(dates, close);
  }

  private List<DailyBar> readSeries(String symbol, LocalDate from) {
    return jdbc.query(
        "SELECT bucket::date AS d, open, high, low, close, volume FROM candles"
            + " WHERE exchange='NSE' AND tradingsymbol=? AND interval='1d' AND bucket >= ?"
            + " ORDER BY bucket ASC",
        (rs, n) ->
            new DailyBar(
                rs.getObject("d", LocalDate.class), rs.getBigDecimal("open").doubleValue(),
                rs.getBigDecimal("high").doubleValue(), rs.getBigDecimal("low").doubleValue(),
                rs.getBigDecimal("close").doubleValue(), rs.getLong("volume")),
        symbol, java.sql.Date.valueOf(from));
  }

  private void persist(Report report) {
    try {
      jdbc.update(
          "INSERT INTO minervini_backtest_runs (from_date, run_at, report) VALUES (?, now(), ?::jsonb)",
          java.sql.Date.valueOf(report.fromDate()), objectMapper.writeValueAsString(report));
    } catch (JsonProcessingException | RuntimeException e) {
      log.warn("backtest result persist skipped: {}", e.getMessage());
    }
  }

  private Report parse(String json) {
    try {
      return objectMapper.readValue(json, Report.class);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private LocalDate from(Integer years) {
    int y = years == null || years <= 0 ? defaultYears : Math.min(years, 20);
    return LocalDate.now().minusYears(y);
  }

  private static String fmt(double v) {
    return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
  }

  private static String nowIso() {
    return java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHoursMinutes(5, 30)).toString();
  }

  private static BigDecimal z() {
    return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
  }

  /** A double → 2dp BigDecimal (portfolio %/ratio fields); non-finite → 0. */
  private static BigDecimal bd2(double v) {
    if (!Double.isFinite(v)) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal div(BigDecimal num, long den) {
    return num.divide(BigDecimal.valueOf(den), 4, RoundingMode.HALF_UP);
  }

  private static BigDecimal div(BigDecimal num, BigDecimal den) {
    return num.divide(den, 4, RoundingMode.HALF_UP);
  }

  /** A minimal primitive growable double buffer — avoids ~1M boxed Doubles on the small heap. */
  private static final class DoubleBag {
    private double[] a = new double[16];
    private int size;

    void add(double v) {
      if (size == a.length) {
        a = Arrays.copyOf(a, a.length * 2);
      }
      a[size++] = v;
    }

    double[] sorted() {
      double[] out = Arrays.copyOf(a, size);
      Arrays.sort(out);
      return out;
    }
  }
}
