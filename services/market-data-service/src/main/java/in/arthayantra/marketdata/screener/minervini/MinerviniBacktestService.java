package in.arthayantra.marketdata.screener.minervini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade;
import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpDetector;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * win-rate / payoff / expectancy / avg-hold. Compute is minutes, so it runs {@link Async} on trigger
 * and persists the report; the GET endpoint reads the latest. One run at a time (a guard rejects a
 * concurrent trigger).
 */
@Service
public class MinerviniBacktestService {

  private static final Logger log = LoggerFactory.getLogger(MinerviniBacktestService.class);

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

  /** The full backtest report. */
  public record Report(
      String status,
      LocalDate fromDate,
      String runAt,
      int symbolsScanned,
      int totalTrades,
      List<SetupStat> setups,
      String note) {}

  private final JdbcTemplate jdbc;
  private final VcpDetector detector;
  private final ObjectMapper objectMapper;
  private final int defaultYears;
  private final BigDecimal minTurnover;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile Report latest;

  /** Wires the marketdata datasource + the VCP detector + config. */
  public MinerviniBacktestService(
      JdbcTemplate jdbc,
      VcpDetector detector,
      ObjectMapper objectMapper,
      @Value("${artha.minervini.backtest.years:11}") int defaultYears,
      @Value("${artha.minervini.backtest.min-turnover:3750000}") BigDecimal minTurnover) {
    this.jdbc = jdbc;
    this.detector = detector;
    this.objectMapper = objectMapper;
    this.defaultYears = defaultYears;
    this.minTurnover = minTurnover;
  }

  /** The latest completed (or in-progress) report; {@code null} until the first run is triggered. */
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

  /** Triggers a run on a background thread; returns false if one is already running. */
  public boolean trigger(Integer years) {
    if (!running.compareAndSet(false, true)) {
      return false;
    }
    latest = new Report("running", from(years), null, 0, 0, List.of(), "backtest in progress");
    Thread t = new Thread(() -> runOnce(years), "minervini-backtest");
    t.setDaemon(true);
    t.start();
    return true;
  }

  private void runOnce(Integer years) {
    try {
      Report report = run(from(years));
      latest = report;
      persist(report);
    } catch (RuntimeException e) {
      log.error("minervini swing backtest failed: {}", e.getMessage(), e);
      latest = new Report("failed", from(years), null, 0, 0, List.of(), e.getMessage());
    } finally {
      running.set(false);
    }
  }

  /** Runs the backtest synchronously over {@code [from, now]}. Package-visible for tests. */
  Report run(LocalDate from) {
    LocalDate warmStart = from.minusDays(600); // ≥252 sessions + VCP-lookback warmup before `from`
    List<String> symbols = eqSymbols();
    List<BtTrade> all = new ArrayList<>();
    int scanned = 0;
    for (String symbol : symbols) {
      List<DailyBar> bars = readSeries(symbol, warmStart);
      if (bars.size() < 260) {
        continue;
      }
      scanned++;
      all.addAll(MinerviniSwingBacktest.simulate(symbol, bars, detector, from));
    }
    List<SetupStat> stats = new ArrayList<>();
    Map<String, List<BtTrade>> bySetup = new LinkedHashMap<>();
    MinerviniSwingBacktest.SETUPS.forEach(s -> bySetup.put(s, new ArrayList<>()));
    bySetup.put("ALL", new ArrayList<>());
    for (BtTrade t : all) {
      bySetup.get(t.setup()).add(t);
      bySetup.get("ALL").add(t);
    }
    bySetup.forEach((setup, trades) -> stats.add(aggregate(setup, trades)));
    log.info(
        "minervini swing backtest: {} symbols, {} trades, from {}", scanned, all.size(), from);
    return new Report(
        "completed", from, nowIso(), scanned, all.size(), stats,
        "candles@1d ~11y; v1 relaxes the cross-sectional RS gate; open-at-end positions dropped;"
            + " costs/slippage not modelled");
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

  private List<String> eqSymbols() {
    return jdbc.queryForList(
        "SELECT DISTINCT c.tradingsymbol FROM candles c JOIN instruments i"
            + " ON i.exchange=c.exchange AND i.tradingsymbol=c.tradingsymbol AND i.instrument_type='EQ'"
            + " WHERE c.interval='1d' AND c.exchange='NSE'",
        String.class);
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
}
