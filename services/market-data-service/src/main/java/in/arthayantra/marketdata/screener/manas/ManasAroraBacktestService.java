package in.arthayantra.marketdata.screener.manas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.screener.DeepSwingRunResult;
import in.arthayantra.marketdata.screener.DeepSwingTrade;
import in.arthayantra.marketdata.screener.manas.ManasAroraSwingBacktest.BtTrade;
import in.arthayantra.marketdata.screener.manas.ManasAroraSwingBacktest.Variant;
import in.arthayantra.marketdata.screener.minervini.SwingPortfolio;
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
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the Manas Arora swing BACKTEST ({@link ManasAroraSwingBacktest}) over the dense {@code
 * candles}@1d NSE-EQ universe (~11y depth) — the deep-history evidence a months-long momentum method
 * needs. Streams each EQ symbol's full daily series, runs both setups (breakout + vcp) with the Manas
 * ATR exit doctrine and pyramiding, and aggregates every closed lot into per-setup win-rate / payoff /
 * expectancy / avg-hold plus slot-limited portfolio stats. Mirrors the sibling {@code
 * MinerviniBacktestService} structure; reuses {@link SwingPortfolio} unchanged.
 *
 * <p>The phased variant grid isolates what actually sharpens the edge:
 *
 * <ul>
 *   <li><b>v1 technical</b> — the price-structure §4.1 gates + setup triggers; RS relaxed, no floors.
 *   <li><b>v2 rs</b> — adds the real weekly cross-sectional RS-rank≥{@code rsMin} gate.
 *   <li><b>v3 turnover</b> — adds a ₹/day liquidity/turnover floor (the only unbiased fundamentals
 *       proxy — real fundamentals have no point-in-time history).
 *   <li><b>v4 float</b> — adds the §4.4 free-float low-cap gate where fundamentals exist (degrades to
 *       v3 when the feed is absent — no point-in-time float history, so identical on backtest).
 *   <li><b>v5 pyramiding A/B</b> — the rs+turnover filter with add-to-winner ON vs OFF.
 *   <li><b>v6 slot sweep</b> — the RS-priority portfolio at 8/12/16/20 concurrent slots.
 *   <li><b>v7 portfolio</b> — FIFO vs RS-priority slot allocation, gross and net-of-cost.
 * </ul>
 *
 * The RS-rank is computed here (the sim is per-symbol and cannot see the universe): a weekly
 * cross-sectional percentile of the weighted trailing return (0.4·r63 + 0.2·r126 + 0.2·r189 +
 * 0.2·r252). Compute is minutes, so it runs on a background thread and persists the variant reports;
 * the GET endpoints read the latest. One run at a time (a guard rejects a concurrent trigger).
 */
@Service
public class ManasAroraBacktestService {

  private static final Logger log = LoggerFactory.getLogger(ManasAroraBacktestService.class);

  private static final int RANK_CADENCE = 5; // rank the universe every 5th session (weekly)
  private static final int RS_LOOKBACK = 252; // longest RS lag; also the 52-week gate warmup
  private static final int MIN_SERIES = 260; // min bars to be rankable AND tradable — ONE threshold
  private static final int MIN_DIST = 20; // don't trust a percentile from a degenerate thin cross-section

  // F2 N+1 fix: read the daily universe in fixed-size symbol PAGES (one query per page, grouped by
  // symbol) instead of one query per symbol. 50 bounds each page's result set to ~50×lifespan bars
  // (a few MB on the -Xmx448m heap) while cutting the ~2×N sequential round-trips to ~2×⌈N/50⌉.
  private static final int READ_CHUNK = 50;

  // v6 slot sweep: does adding concurrent positions capture more of the signal flood?
  private static final int[] SLOT_SWEEP = {8, 12, 16, 20};

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

  /** Portfolio-level stats for one variant (all setups + pyramid lots combined through the book). */
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
   * {@code portfolioRsPriorityNet} (RS-priority, NET of turnover-scaled transaction costs).
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

  /** One slot-sweep row: the RS-priority portfolio at {@code slots} concurrent positions (v6). */
  public record SlotCell(
      int slots,
      int tradesTaken,
      int tradesSkipped,
      BigDecimal grossCagrPct,
      BigDecimal netCagrPct,
      BigDecimal netDrawdownPct,
      BigDecimal netSharpe) {}

  /** The full multi-variant result: technical / rs / turnover / float / pyramiding A/B, side by side. */
  public record BacktestResult(
      String status,
      LocalDate fromDate,
      String runAt,
      List<Report> variants,
      List<SlotCell> slotSweep,
      String note) {}

  /**
   * The full-filter variant (the live-funnel analogue) — the one the plain GET returns. Non-pyramid:
   * pyramiding was disarmed live + as the headline here (H4 Chandelier decision — under the canonical
   * highest-high trail the add-to-winner variant degrades Sharpe, so single-lot is the operative config).
   * The {@code rs-turnover-pyramid} variant is retained in the run set purely as a labeled A/B probe.
   */
  private static final String PRIMARY_VARIANT = "rs-turnover-nopyramid";

  private final JdbcTemplate jdbc;
  private final VcpDetector vcpDetector;
  private final ConsolidationBreakout breakoutDetector;
  private final ObjectMapper objectMapper;
  private final ManasAroraSwingBacktest sim;
  private final int defaultYears;
  private final double turnoverFloor;
  private final double rsMin;
  private final int slots;
  private final SwingPortfolio.Costs costs;
  private final in.arthayantra.marketdata.alerts.NtfyClient ntfy;
  private final in.arthayantra.marketdata.screener.SwingBacktestGate gate;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile Report latest;
  private volatile BacktestResult latestResult;

  /** Wires the marketdata datasource + both detectors + the sim (config-tunable) + config. */
  public ManasAroraBacktestService(
      JdbcTemplate jdbc,
      VcpDetector vcpDetector,
      ConsolidationBreakout breakoutDetector,
      ObjectMapper objectMapper,
      in.arthayantra.marketdata.alerts.NtfyClient ntfy,
      in.arthayantra.marketdata.screener.SwingBacktestGate gate,
      @Value("${artha.manas-arora.backtest.years:11}") int defaultYears,
      @Value("${artha.manas-arora.backtest.min-turnover:3750000}") BigDecimal minTurnover,
      @Value("${artha.manas-arora.backtest.rs-min:70}") BigDecimal rsMin,
      @Value("${artha.manas-arora.backtest.slots:8}") int slots,
      @Value("${artha.manas-arora.backtest.capital:1000000}") double capital,
      @Value("${artha.manas-arora.backtest.cost.fixed-pct:0.25}") double costFixedPct,
      @Value("${artha.manas-arora.backtest.cost.spread-pct:0.05}") double costSpreadPct,
      @Value("${artha.manas-arora.backtest.cost.impact-coeff:0.10}") double costImpactCoeff,
      @Value("${artha.manas-arora.backtest.cost.impact-cap-pct:5.0}") double costImpactCapPct,
      @Value("${artha.manas-arora.backtest.atr-mult:2.0}") double atrMult,
      @Value("${artha.manas-arora.backtest.atr-period:20}") int atrPeriod,
      @Value("${artha.manas-arora.backtest.stop-cap-pct:0.10}") double stopCapPct,
      @Value("${artha.manas-arora.backtest.trail-arm-pct:0.09}") double trailArmPct,
      @Value("${artha.manas-arora.backtest.fast-move-pct:0.35}") double fastMovePct,
      @Value("${artha.manas-arora.backtest.fast-move-bars:3}") int fastMoveBars,
      @Value("${artha.manas-arora.backtest.parabolic-pct:0.40}") double parabolicPct,
      @Value("${artha.manas-arora.backtest.parabolic-ma:10}") int parabolicMa,
      @Value("${artha.manas-arora.backtest.pyramid-arm-pct:0.06}") double pyramidArmPct,
      @Value("${artha.manas-arora.backtest.max-open-risk-pct:0.06}") double maxOpenRiskPct,
      @Value("${artha.manas-arora.backtest.max-lots:3}") int maxLots,
      @Value("${artha.manas-arora.backtest.vol-min:1.2}") double volMin) {
    this.jdbc = jdbc;
    this.vcpDetector = vcpDetector;
    this.breakoutDetector = breakoutDetector;
    this.objectMapper = objectMapper;
    this.ntfy = ntfy;
    this.gate = gate;
    this.sim =
        new ManasAroraSwingBacktest(
            atrMult, atrPeriod, stopCapPct, trailArmPct, fastMovePct, fastMoveBars, parabolicPct,
            parabolicMa, pyramidArmPct, maxOpenRiskPct, maxLots, volMin);
    this.defaultYears = defaultYears;
    this.turnoverFloor = minTurnover.doubleValue();
    this.rsMin = rsMin.doubleValue();
    this.slots = slots;
    this.costs =
        new SwingPortfolio.Costs(
            capital, costFixedPct, costSpreadPct, costImpactCoeff, costImpactCapPct);
  }

  /** The latest completed (or in-progress) single report — the full-filter (rs-turnover-nopyramid) variant. */
  public Report latest() {
    if (latest != null) {
      return latest;
    }
    return jdbc
        .query(
            "SELECT report FROM manas_arora_backtest_runs WHERE report->>'variant' = ?"
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
            "SELECT report FROM manas_arora_backtest_runs ORDER BY run_at DESC LIMIT 6",
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
        "completed", any.fromDate(), any.runAt(), variants, List.of(),
        "loaded from the last persisted run (slot sweep is in-memory only; re-run to populate)");
  }

  /**
   * Triggers a run on a background thread; returns false if one is already running (this family) OR
   * the other swing backtest holds the shared single-permit heap gate (audit M25) — either way the
   * caller reports "already running" rather than piling a second ~11-year sim onto the live heap.
   */
  public boolean trigger(Integer years) {
    if (!running.compareAndSet(false, true)) {
      return false;
    }
    if (!gate.tryAcquire()) {
      running.set(false); // the other family's deep sim is running — do not stack on the heap
      return false;
    }
    LocalDate from = from(years);
    latest = running(PRIMARY_VARIANT, from);
    latestResult =
        new BacktestResult("running", from, null, List.of(), List.of(), "backtest in progress");
    Thread t = new Thread(() -> runOnce(years), "manas-arora-backtest");
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
      recordFailed(years, e);
    } catch (Error e) {
      // OOME / StackOverflow etc. skip a RuntimeException catch — without this the daemon thread
      // dies and `latest` stays pinned at "running" forever with no operator signal (audit M26).
      recordFailed(years, e);
      throw e;
    } finally {
      running.set(false);
      gate.release(); // release the shared heap permit (paired with the trigger's tryAcquire)
    }
  }

  /** Marks the run failed (both report shapes) + fires one fail-soft ntfy alert (audit M26). */
  private void recordFailed(Integer years, Throwable t) {
    log.error("manas-arora swing backtest failed: {}", t.toString(), t);
    LocalDate from = from(years);
    latest =
        new Report(
            "failed", PRIMARY_VARIANT, from, null, 0, 0, List.of(), null, null, null, t.toString());
    latestResult = new BacktestResult("failed", from, null, List.of(), List.of(), t.toString());
    ntfy.send("ArthaYantra — Manas Arora backtest failed", "high", t.toString());
  }

  private static Report running(String variant, LocalDate from) {
    return new Report(
        "running", variant, from, null, 0, 0, List.of(), null, null, null, "backtest in progress");
  }

  /** Runs the full variant grid over {@code [from, now]} in one pass. Package-visible for tests. */
  BacktestResult run(LocalDate from) {
    // Production reads the candle universe in CHUNK-batched pages (the F2 N+1 fix): one query per
    // READ_CHUNK symbols instead of one per symbol, cutting ~2×N sequential round-trips to ~2×⌈N/50⌉.
    return run(from, this::readClosesBatched, this::readSeriesBatched);
  }

  /**
   * The equality-harness seam (F2). {@code closesReader}/{@code barsReader} each map a symbol chunk
   * + {@code from} to that chunk's per-symbol series; production supplies the CHUNK-batched readers,
   * while a same-package IT supplies the per-symbol serial readers ({@link #readCloses}/{@link
   * #readSeries}) to PROVE both strategies yield a decimal-identical {@link BacktestResult}. The
   * compute below is byte-identical across strategies — symbols are iterated in {@code eqSymbols()}
   * order and each symbol's bars are unchanged — so equality reduces to the readers returning the
   * same bars in the same order (verified directly by the IT's read-level assertions too).
   */
  BacktestResult run(
      LocalDate from,
      BiFunction<List<String>, LocalDate, Map<String, Series>> closesReader,
      BiFunction<List<String>, LocalDate, Map<String, List<DailyBar>>> barsReader) {
    Computed c = computeAll(from, closesReader, barsReader);
    List<BtTrade> all = c.all();
    int scanned = c.scanned();
    String runAt = nowIso();
    List<Report> reports = new ArrayList<>();
    for (Variant v : jobVariants()) {
      reports.add(report(v.name(), from, runAt, scanned, all));
    }
    List<SlotCell> slotSweep = slotSweep(all);
    log.info(
        "manas-arora swing backtest: {} symbols, {}, from {}",
        scanned,
        reports.stream()
            .map(r -> r.variant() + " " + r.totalTrades())
            .reduce((a, b) -> a + ", " + b)
            .orElse(""),
        from);
    return new BacktestResult(
        "completed", from, runAt, reports, slotSweep,
        "candles@1d ~11y over " + slots + " concurrent slots; variants isolate a weekly cross-sectional"
            + " RS-rank≥" + fmt(rsMin) + " gate, a ₹" + fmt(turnoverFloor) + "/day turnover floor, and"
            + " pyramiding on/off; open-at-end lots dropped; portfolio net-of-cost; survivorship-biased."
            + " The §4.4 float gate degrades to the turnover variant (no point-in-time float history).");
  }

  /**
   * The phased variant grid (v1..v5B). Hoisted so ONE definition feeds BOTH {@link #computeAll} (the
   * sim) and the DEEP_SWING job-variant validation — the job path and the direct /compare path score
   * the exact same variant set.
   */
  private List<Variant> jobVariants() {
    return List.of(
        new Variant("technical", false, 0, 0, false), // v1: neither filter, single-lot
        new Variant("rs", true, rsMin, 0, false), // v2: RS-rank gate alone
        new Variant("turnover", false, 0, turnoverFloor, false), // v3: liquidity floor alone
        new Variant("rs-turnover", true, rsMin, turnoverFloor, false), // v4: both (float degrades)
        new Variant("rs-turnover-nopyramid", true, rsMin, turnoverFloor, false), // v5 A: no adds
        new Variant("rs-turnover-pyramid", true, rsMin, turnoverFloor, true), // v5 B: add-to-winner
        new Variant(
            "rs-turnover-nopyramid-nextopen", true, rsMin, turnoverFloor, false, "next_open"));
  }

  /** The deep-sim compute output shared by {@link #run} and {@link #runForJob} (audit P0-3). */
  private record Computed(List<BtTrade> all, int scanned) {}

  /**
   * The deep-sim compute — pass 1 (weekly cross-sectional RS distribution) + pass 2 (variant-grid
   * replay over the SAME bars) — extracted so {@link #run} (the direct multi-variant result) and
   * {@link #runForJob} (the DEEP_SWING job path) share ONE compute path with ZERO hand-mirrored drift.
   * Returns every closed lot across all variants + the scanned-symbol count; behaviourally identical
   * to the pre-extraction inline body (the F2 equality IT pins it byte-for-byte).
   */
  private Computed computeAll(
      LocalDate from,
      BiFunction<List<String>, LocalDate, Map<String, Series>> closesReader,
      BiFunction<List<String>, LocalDate, Map<String, List<DailyBar>>> barsReader) {
    LocalDate warmStart = from.minusDays(600); // ≥252 sessions + geometry-lookback warmup before `from`
    List<String> symbols = eqSymbols();

    // Pass 1 — the cross-sectional RS distribution at each weekly rank date (memory-lean: primitive).
    LocalDate[] rankDates = weeklyRankDates(warmStart);
    Map<LocalDate, DoubleBag> bags = new HashMap<>();
    for (LocalDate d : rankDates) {
      bags.put(d, new DoubleBag());
    }
    for (int i = 0; i < symbols.size(); i += READ_CHUNK) {
      List<String> chunk = symbols.subList(i, Math.min(i + READ_CHUNK, symbols.size()));
      Map<String, Series> byCloses = closesReader.apply(chunk, warmStart);
      for (String symbol : chunk) {
        Series s = byCloses.get(symbol);
        if (s == null || s.close().length < MIN_SERIES) {
          continue;
        }
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
    }
    Map<LocalDate, double[]> dist = new HashMap<>();
    bags.forEach((d, bag) -> dist.put(d, bag.sorted()));
    bags.clear();

    // Pass 2 — replay the phased variant grid over the SAME bars, so the variants are a clean
    // apples-to-apples isolation (identical geometry / MAs / setup triggers; only the filter differs).
    List<Variant> variants = jobVariants();
    List<BtTrade> all = new ArrayList<>();
    int scanned = 0;
    for (int i = 0; i < symbols.size(); i += READ_CHUNK) {
      List<String> chunk = symbols.subList(i, Math.min(i + READ_CHUNK, symbols.size()));
      Map<String, List<DailyBar>> byBars = barsReader.apply(chunk, warmStart);
      for (String symbol : chunk) {
        List<DailyBar> bars = byBars.get(symbol);
        if (bars == null || bars.size() < MIN_SERIES) {
          continue;
        }
        scanned++;
        double[] rsRank = perBarRsRank(bars, rankDates, dist);
        all.addAll(
            sim.simulate(symbol, bars, vcpDetector, breakoutDetector, from, rsRank, variants));
      }
    }
    return new Computed(all, scanned);
  }

  /**
   * The DEEP_SWING job path (research-fidelity audit P0-3): runs the SAME deep-sim compute as {@link
   * #run} (production CHUNK-batched readers) and returns ONE variant's per-trade rows + portfolio
   * report as the shared {@link DeepSwingRunResult} wire shape, so the backtest-service worker persists
   * it into the backtest lineage (a {@code backtest_runs} row + {@code backtest_trades}). Reuses {@link
   * #computeAll} + {@link #report}, so the job numbers ARE the numbers the direct
   * {@code /swing-backtest/compare} endpoint reports for the same variant. Headline metrics map from
   * the RS-priority NET portfolio (the realistic-live estimate). {@code variant} defaults to the
   * primary full-filter variant; an unknown variant throws (422 at the endpoint).
   */
  public DeepSwingRunResult runForJob(LocalDate from, String variant) {
    String v = (variant == null || variant.isBlank()) ? PRIMARY_VARIANT : variant;
    List<String> names = jobVariants().stream().map(Variant::name).toList();
    if (!names.contains(v)) {
      throw new IllegalArgumentException(
          "unknown Manas Arora deep-swing variant '" + v + "'; valid: " + names);
    }
    Computed c = computeAll(from, this::readClosesBatched, this::readSeriesBatched);
    String runAt = nowIso();
    Report rep = report(v, from, runAt, c.scanned(), c.all());
    List<DeepSwingTrade> trades =
        c.all().stream()
            .filter(t -> t.variant().equals(v))
            .map(ManasAroraBacktestService::toDeepSwingTrade)
            .toList();
    PortfolioStat net = rep.portfolioRsPriorityNet();
    SetupStat allSetup =
        rep.setups().stream().filter(s -> "ALL".equals(s.setup())).findFirst().orElse(null);
    return new DeepSwingRunResult(
        "manas",
        v,
        from,
        runAt,
        c.scanned(),
        BigDecimal.valueOf(costs.capital()),
        net.totalReturnPct(),
        net.cagrPct(),
        net.maxDrawdownPct(),
        net.sharpe(),
        net.tradesTaken(),
        net.tradesSkipped(),
        allSetup == null ? z() : allSetup.winRatePct(),
        allSetup == null ? z() : allSetup.profitFactor(),
        objectMapper.valueToTree(rep),
        trades);
  }

  /** Maps one Manas lot to the shared DEEP_SWING wire trade (a NaN RS-rank rides as {@code null}). */
  private static DeepSwingTrade toDeepSwingTrade(BtTrade t) {
    return new DeepSwingTrade(
        t.symbol(),
        t.setup(),
        t.entryDate(),
        BigDecimal.valueOf(t.entryPrice()),
        t.exitDate(),
        BigDecimal.valueOf(t.exitPrice()),
        BigDecimal.valueOf(t.pnlPct()),
        t.barsHeld(),
        t.exitReason(),
        Double.isNaN(t.rsRankAtEntry()) ? null : BigDecimal.valueOf(t.rsRankAtEntry()));
  }

  /** v6 slot sweep: the RS-priority portfolio at 8/12/16/20 slots, gross + net (over the pyramid variant). */
  private List<SlotCell> slotSweep(List<BtTrade> all) {
    List<in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade> book =
        adapt(all, PRIMARY_VARIANT);
    List<SlotCell> out = new ArrayList<>();
    for (int k : SLOT_SWEEP) {
      SwingPortfolio.Result gross = SwingPortfolio.simulate(book, k, true, null);
      SwingPortfolio.Result net = SwingPortfolio.simulate(book, k, true, costs);
      out.add(
          new SlotCell(
              k, net.tradesTaken(), net.tradesSkipped(), bd2(gross.cagrPct()), bd2(net.cagrPct()),
              bd2(net.maxDrawdownPct()), bd2(net.sharpe())));
    }
    return out;
  }

  /** Aggregates every trade for one {@code variant} into per-setup trade stats + a portfolio equity run. */
  private Report report(String variant, LocalDate from, String runAt, int scanned, List<BtTrade> all) {
    Map<String, List<BtTrade>> bySetup = new LinkedHashMap<>();
    ManasAroraSwingBacktest.SETUPS.forEach(s -> bySetup.put(s, new ArrayList<>()));
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
    List<in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade> book =
        adapt(bySetup.get("ALL"), variant);
    PortfolioStat fifo = toStat(SwingPortfolio.simulate(book, slots, false, null), slots);
    PortfolioStat rsPriority = toStat(SwingPortfolio.simulate(book, slots, true, null), slots);
    PortfolioStat rsPriorityNet = toStat(SwingPortfolio.simulate(book, slots, true, costs), slots);
    return new Report(
        "completed", variant, from, runAt, scanned, total, stats, fifo, rsPriority, rsPriorityNet,
        null);
  }

  /**
   * Adapts the Manas lots of one variant to the {@link SwingPortfolio}'s {@code BtTrade} shape (the
   * portfolio consumes only entry/exit dates, pnl%, RS-rank, and turnover — the fields both records
   * share). Each pyramid lot is a distinct book entry, so the portfolio counts adds against slots.
   */
  private static List<in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade> adapt(
      List<BtTrade> trades, String variant) {
    List<in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade> out =
        new ArrayList<>();
    for (BtTrade t : trades) {
      if (!t.variant().equals(variant)) {
        continue;
      }
      out.add(
          new in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade(
              t.variant(), t.setup(), t.symbol(), t.entryDate(), t.entryPrice(), t.exitDate(),
              t.exitPrice(), t.pnlPct(), t.barsHeld(), t.exitReason(), t.rsRankAtEntry(),
              t.avgTurnoverAtEntry()));
    }
    return out;
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

  /** Weighted trailing return as a double (0.4·r63 + 0.2·r126 + 0.2·r189 + 0.2·r252). */
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
   * {@code 100·(below + 0.5·equal)/len}. Package-visible for a direct unit test. {@code sorted} must
   * be non-empty (callers gate on {@link #MIN_DIST}).
   */
  static double percentile(double[] sorted, double x) {
    int below = lowerBound(sorted, x);
    int above = upperBound(sorted, x);
    int equal = above - below;
    return 100.0 * (below + 0.5 * equal) / sorted.length;
  }

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
            // Deterministic symbol order: the trade index (its position in `all`) is the final tiebreak
            // in SwingPortfolio's same-day slot contest, so a stable order makes the portfolio stats
            // (CAGR/DD/Sharpe, taken/skipped) byte-reproducible run-to-run.
            + " ORDER BY c.tradingsymbol",
        String.class);
  }

  /** A symbol's aligned (date, close) series. */
  record Series(LocalDate[] dates, double[] close) {}

  /**
   * The (date, close) series for {@code symbol} from {@code from}. {@code bucket::date} carries the
   * documented IST→UTC shift, but EVERY query in this service applies the same shift, so all dates
   * share one calendar and relative ordering / cross-symbol alignment are exact.
   *
   * <p>Retained as the F2 equality REFERENCE reader (the per-symbol serial path the batched readers
   * must reproduce to the decimal); production goes through {@link #readClosesBatched}.
   */
  Series readCloses(String symbol, LocalDate from) {
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
    return toSeries(rows);
  }

  /** Reference per-symbol series reader (see {@link #readCloses}); production uses {@link #readSeriesBatched}. */
  List<DailyBar> readSeries(String symbol, LocalDate from) {
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

  /** Builds a {@link Series} from bucket-ascending {@code (date, close)} rows (shared by both readers). */
  private static Series toSeries(List<Object[]> rows) {
    LocalDate[] dates = new LocalDate[rows.size()];
    double[] close = new double[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      dates[i] = (LocalDate) rows.get(i)[0];
      close[i] = (double) rows.get(i)[1];
    }
    return new Series(dates, close);
  }

  /**
   * The F2 batched counterpart of {@link #readCloses}: ONE query for a whole symbol {@code chunk},
   * returned as a {@code symbol -> Series} map. Same filter/casts/ordering as the per-symbol reader,
   * widened to {@code tradingsymbol IN (chunk)} and {@code ORDER BY tradingsymbol, bucket ASC} — so
   * within each symbol the rows stay bucket-ascending and every symbol's series is byte-identical to
   * the serial read. Symbols with no bars in-window are simply absent (the caller treats absent as an
   * empty series, exactly as the serial length gate does).
   */
  Map<String, Series> readClosesBatched(List<String> chunk, LocalDate from) {
    Map<String, List<Object[]>> acc = new LinkedHashMap<>();
    jdbc.query(
        "SELECT tradingsymbol, bucket::date AS d, close FROM candles"
            + " WHERE exchange='NSE' AND interval='1d' AND bucket >= ?"
            + " AND tradingsymbol IN ("
            + inClause(chunk.size())
            + ") ORDER BY tradingsymbol, bucket ASC",
        (RowCallbackHandler)
            rs ->
                acc.computeIfAbsent(rs.getString("tradingsymbol"), k -> new ArrayList<>())
                    .add(
                        new Object[] {
                          rs.getObject("d", LocalDate.class),
                          rs.getBigDecimal("close").doubleValue()
                        }),
        chunkArgs(from, chunk));
    Map<String, Series> out = new LinkedHashMap<>();
    acc.forEach((symbol, rows) -> out.put(symbol, toSeries(rows)));
    return out;
  }

  /** The F2 batched counterpart of {@link #readSeries} (see {@link #readClosesBatched} for the contract). */
  Map<String, List<DailyBar>> readSeriesBatched(List<String> chunk, LocalDate from) {
    Map<String, List<DailyBar>> out = new LinkedHashMap<>();
    jdbc.query(
        "SELECT tradingsymbol, bucket::date AS d, open, high, low, close, volume FROM candles"
            + " WHERE exchange='NSE' AND interval='1d' AND bucket >= ?"
            + " AND tradingsymbol IN ("
            + inClause(chunk.size())
            + ") ORDER BY tradingsymbol, bucket ASC",
        (RowCallbackHandler)
            rs ->
                out.computeIfAbsent(rs.getString("tradingsymbol"), k -> new ArrayList<>())
                    .add(
                        new DailyBar(
                            rs.getObject("d", LocalDate.class),
                            rs.getBigDecimal("open").doubleValue(),
                            rs.getBigDecimal("high").doubleValue(),
                            rs.getBigDecimal("low").doubleValue(),
                            rs.getBigDecimal("close").doubleValue(),
                            rs.getLong("volume"))),
        chunkArgs(from, chunk));
    return out;
  }

  /** {@code "?,?,…"} of {@code n} placeholders for an {@code IN (…)} list. */
  private static String inClause(int n) {
    return String.join(",", java.util.Collections.nCopies(n, "?"));
  }

  /** The positional args for a batched read: {@code from} then the chunk's symbols, in order. */
  private static Object[] chunkArgs(LocalDate from, List<String> chunk) {
    Object[] args = new Object[chunk.size() + 1];
    args[0] = java.sql.Date.valueOf(from);
    for (int i = 0; i < chunk.size(); i++) {
      args[i + 1] = chunk.get(i);
    }
    return args;
  }

  private void persist(Report report) {
    try {
      jdbc.update(
          "INSERT INTO manas_arora_backtest_runs (from_date, run_at, report) VALUES (?, now(), ?::jsonb)",
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
