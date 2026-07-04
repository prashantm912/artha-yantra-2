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
      BigDecimal avgBarsHeld) {}

  /** One variant's full backtest report. */
  public record Report(
      String status,
      String variant,
      LocalDate fromDate,
      String runAt,
      int symbolsScanned,
      int totalTrades,
      List<SetupStat> setups,
      String note) {}

  /** The A/B: the technical-only baseline (v1) vs the RS-rank + turnover filter (v2). */
  public record Compare(
      String status, LocalDate fromDate, String runAt, Report v1, Report v2, String note) {}

  private final JdbcTemplate jdbc;
  private final VcpDetector detector;
  private final ObjectMapper objectMapper;
  private final int defaultYears;
  private final double turnoverFloor;
  private final double rsMin;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile Report latest;
  private volatile Compare latestCompare;

  /** Wires the marketdata datasource + the VCP detector + config. */
  public MinerviniBacktestService(
      JdbcTemplate jdbc,
      VcpDetector detector,
      ObjectMapper objectMapper,
      @Value("${artha.minervini.backtest.years:11}") int defaultYears,
      @Value("${artha.minervini.backtest.min-turnover:3750000}") BigDecimal minTurnover,
      @Value("${artha.minervini.backtest.rs-min:70}") BigDecimal rsMin) {
    this.jdbc = jdbc;
    this.detector = detector;
    this.objectMapper = objectMapper;
    this.defaultYears = defaultYears;
    this.turnoverFloor = minTurnover.doubleValue();
    this.rsMin = rsMin.doubleValue();
  }

  /** The latest completed (or in-progress) single report — the v2 variant. */
  public Report latest() {
    if (latest != null) {
      return latest;
    }
    return jdbc.query(
            "SELECT report FROM minervini_backtest_runs ORDER BY run_at DESC LIMIT 1",
            (rs, n) -> parse(rs.getString("report")))
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** The latest A/B comparison (v1 vs v2); reads the two newest persisted rows on a cold boot. */
  public Compare latestCompare() {
    if (latestCompare != null) {
      return latestCompare;
    }
    List<Report> recent =
        jdbc.query(
            "SELECT report FROM minervini_backtest_runs ORDER BY run_at DESC LIMIT 2",
            (rs, n) -> parse(rs.getString("report")));
    Report v1 = recent.stream().filter(r -> r != null && "v1-technical".equals(r.variant())).findFirst().orElse(null);
    Report v2 = recent.stream().filter(r -> r != null && "v2-rs-turnover".equals(r.variant())).findFirst().orElse(null);
    if (v1 == null && v2 == null) {
      return null;
    }
    return new Compare(
        "completed", v1 != null ? v1.fromDate() : v2.fromDate(),
        v2 != null ? v2.runAt() : v1.runAt(), v1, v2, "loaded from the last persisted run");
  }

  /** Triggers a run on a background thread; returns false if one is already running. */
  public boolean trigger(Integer years) {
    if (!running.compareAndSet(false, true)) {
      return false;
    }
    LocalDate from = from(years);
    latest = new Report("running", "v2-rs-turnover", from, null, 0, 0, List.of(), "backtest in progress");
    latestCompare = new Compare("running", from, null, null, null, "backtest in progress");
    Thread t = new Thread(() -> runOnce(years), "minervini-backtest");
    t.setDaemon(true);
    t.start();
    return true;
  }

  private void runOnce(Integer years) {
    try {
      Compare compare = run(from(years));
      latestCompare = compare;
      latest = compare.v2();
      persist(compare.v1());
      persist(compare.v2());
    } catch (RuntimeException e) {
      log.error("minervini swing backtest failed: {}", e.getMessage(), e);
      LocalDate from = from(years);
      latest = new Report("failed", "v2-rs-turnover", from, null, 0, 0, List.of(), e.getMessage());
      latestCompare = new Compare("failed", from, null, null, null, e.getMessage());
    } finally {
      running.set(false);
    }
  }

  /** Runs both variants over {@code [from, now]} in one pass. Package-visible for tests. */
  Compare run(LocalDate from) {
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

    // Pass 2 — replay both variants over the full bars, using the per-bar RS percentile.
    List<Variant> variants =
        List.of(MinerviniSwingBacktest.V1, new Variant("v2-rs-turnover", true, rsMin, turnoverFloor));
    List<BtTrade> all = new ArrayList<>();
    int scanned = 0;
    for (String symbol : symbols) {
      List<DailyBar> bars = readSeries(symbol, warmStart);
      if (bars.size() < MIN_SERIES) {
        continue;
      }
      scanned++;
      double[] rsRank = perBarRsRank(bars, rankDates, dist);
      all.addAll(MinerviniSwingBacktest.simulate(symbol, bars, detector, from, rsRank, variants));
    }

    String runAt = nowIso();
    Report v1 = report("v1-technical", from, runAt, scanned, all);
    Report v2 = report("v2-rs-turnover", from, runAt, scanned, all);
    log.info(
        "minervini swing backtest: {} symbols, v1 {} trades, v2 {} trades, from {}",
        scanned, v1.totalTrades(), v2.totalTrades(), from);
    return new Compare(
        "completed", from, runAt, v1, v2,
        "candles@1d ~11y; v2 adds a weekly cross-sectional RS-rank≥" + fmt(rsMin)
            + " gate + a ₹" + fmt(turnoverFloor) + "/day turnover floor; open-at-end positions"
            + " dropped; costs/slippage not modelled; survivorship-biased (currently-listed names).");
  }

  /** Aggregates every trade for one {@code variant} into per-setup + ALL stats. */
  private static Report report(String variant, LocalDate from, String runAt, int scanned, List<BtTrade> all) {
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
    return new Report("completed", variant, from, runAt, scanned, total, stats, null);
  }

  private static SetupStat aggregate(String setup, List<BtTrade> trades) {
    int n = trades.size();
    if (n == 0) {
      return new SetupStat(setup, 0, 0, 0, z(), z(), z(), z(), z(), z());
    }
    int w = 0;
    int l = 0;
    BigDecimal sumPct = BigDecimal.ZERO;
    BigDecimal sumWin = BigDecimal.ZERO;
    BigDecimal sumLoss = BigDecimal.ZERO;
    long sumBars = 0;
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
    }
    BigDecimal avgWin = w == 0 ? z() : div(sumWin, w);
    BigDecimal avgLoss = l == 0 ? z() : div(sumLoss, l);
    BigDecimal payoff = avgLoss.signum() == 0 ? z() : div(avgWin, avgLoss.abs());
    return new SetupStat(
        setup, n, w, l, div(BigDecimal.valueOf(w * 100L), n), avgWin, avgLoss, payoff,
        div(sumPct, n), div(BigDecimal.valueOf(sumBars), n));
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
            + " WHERE c.interval='1d' AND c.exchange='NSE'",
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
