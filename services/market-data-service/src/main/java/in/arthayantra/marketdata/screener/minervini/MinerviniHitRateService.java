package in.arthayantra.marketdata.screener.minervini;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * MV-8.1 — the screener hit-rate harness. Re-runs the point-in-time 8-gate Trend-Template screen at
 * weekly steps over a historical range and measures each passer's forward return at +5/+10/+21/+63
 * sessions against the NIFTY 50 benchmark, reporting a per-horizon hit-rate + mean excess return.
 * This is the "do the candidates actually go up" reliability evidence for the whole Minervini stack.
 *
 * <p><b>Data source = {@code candles}@1d</b> (native daily, ~11y+ depth for the backfilled/subscribed
 * equity universe), NOT the {@code nse_eod_bhavcopy} store the live screener reads — bhavcopy holds
 * only ~1y, far short of the 252-session warmup + 63-session forward window a backtest needs. The
 * gate + RS math is the SHARED {@link MinerviniGates} (identical to the live screener), so the
 * historical screen is faithful; only the {@code FROM} differs.
 *
 * <p><b>Price-gates ONLY</b> (§3-E): no fundamentals feed is point-in-time, so the low-cap /
 * free-float gates are never applied here — that would be lookahead. Survivorship is accepted +
 * surfaced in the report note (the candle universe is today's listed names; delisted losers are
 * absent, so a real forward book would fare somewhat worse).
 */
@Service
public class MinerviniHitRateService {

  private static final int[] HORIZONS = {5, 10, 21, 63};
  private static final int WARMUP_CALENDAR_DAYS = 420; // ≥252 sessions of MA/52w warmup

  /** Per-horizon reliability stats across every (asOf, passer) sample. Decimals ride as JSON strings. */
  public record HorizonStat(
      int horizonSessions,
      int sampleCount,
      @Schema(types = {"number", "null"}) BigDecimal winRatePct, // % of samples with a positive forward return
      @Schema(types = {"number", "null"}) BigDecimal beatBenchmarkRatePct, // % of samples that beat NIFTY over the same window
      @Schema(types = {"number", "null"}) BigDecimal meanReturnPct,
      @Schema(types = {"number", "null"}) BigDecimal meanBenchmarkReturnPct,
      @Schema(types = {"number", "null"}) BigDecimal meanExcessReturnPct,
      @Schema(types = {"number", "null"}) BigDecimal medianReturnPct) {}

  /** The full harness result: the range/cadence meta + one {@link HorizonStat} per forward horizon. */
  public record HitRateReport(
      @Schema(types = {"string", "null"}) LocalDate from,
      @Schema(types = {"string", "null"}) LocalDate to,
      int stepSessions,
      int asOfCount,
      int universeCount,
      int totalPasserSamples,
      String benchmark,
      String note,
      List<HorizonStat> horizons) {}

  private final NamedParameterJdbcTemplate npjdbc;
  private final int minSessions;
  private final BigDecimal minPrice;
  private final BigDecimal rsMin;
  private final BigDecimal pctAbove52wLow;
  private final BigDecimal within52wHigh;
  private final int sma200RisingSessions;
  private final BigDecimal liquidityThreshold;
  private final int defaultLookbackYears;
  private final int maxSpanYears;
  private final int defaultStepSessions;
  private final String benchmarkSymbol;

  /** Wires the marketdata datasource + the SAME config-tunable thresholds the live screener uses. */
  public MinerviniHitRateService(
      JdbcTemplate jdbc,
      @Value("${artha.minervini.min-sessions:252}") int minSessions,
      @Value("${artha.minervini.min-price:30}") BigDecimal minPrice,
      @Value("${artha.minervini.rs-min:70}") BigDecimal rsMin,
      @Value("${artha.minervini.pct-above-52w-low:25}") BigDecimal pctAbove52wLow,
      @Value("${artha.minervini.within-52w-high:25}") BigDecimal within52wHigh,
      @Value("${artha.minervini.sma200-rising-sessions:21}") int sma200RisingSessions,
      @Value("${artha.minervini.capital:150000}") BigDecimal capital,
      @Value("${artha.minervini.max-name-pct:0.25}") BigDecimal maxNamePct,
      @Value("${artha.minervini.liquidity-multiple:25}") BigDecimal liquidityMultiple,
      @Value("${artha.minervini.hitrate.lookback-years:3}") int defaultLookbackYears,
      @Value("${artha.minervini.hitrate.max-span-years:8}") int maxSpanYears,
      @Value("${artha.minervini.hitrate.step-sessions:5}") int defaultStepSessions,
      @Value("${artha.minervini.hitrate.benchmark:NIFTY 50}") String benchmarkSymbol) {
    this.npjdbc = new NamedParameterJdbcTemplate(jdbc);
    this.minSessions = minSessions;
    this.minPrice = minPrice;
    this.rsMin = rsMin;
    this.pctAbove52wLow = pctAbove52wLow;
    this.within52wHigh = within52wHigh;
    this.sma200RisingSessions = sma200RisingSessions;
    this.liquidityThreshold = capital.multiply(maxNamePct).multiply(liquidityMultiple);
    this.defaultLookbackYears = defaultLookbackYears;
    this.maxSpanYears = maxSpanYears;
    this.defaultStepSessions = defaultStepSessions;
    this.benchmarkSymbol = benchmarkSymbol;
  }

  // The panel: all EQ daily bars from (from − warmup) to the latest, carrying the trailing MA/52w
  // window cols + the RS-lag closes + the forward-lead closes. Output filtered to the sampled asOf
  // dates with ≥minSessions of history, ordered by date so the caller can stream one asOf at a time.
  private static final String PANEL_SQL =
      """
      WITH base AS (
        SELECT c.tradingsymbol AS symbol, c.bucket::date AS d, c.close, c.high, c.low, c.volume
        FROM candles c
        JOIN instruments i
          ON i.exchange = c.exchange AND i.tradingsymbol = c.tradingsymbol
         AND i.instrument_type = 'EQ'
        WHERE c.interval = '1d' AND c.exchange = 'NSE'
          AND c.bucket >= :warmStart AND c.bucket < :endEx
      ),
      calc AS (
        SELECT symbol, d, close,
          avg(close) OVER w50  AS sma50,
          avg(close) OVER w150 AS sma150,
          avg(close) OVER w200 AS sma200,
          max(high)  OVER w252 AS high_52w,
          min(low)   OVER w252 AS low_52w,
          avg(close * volume) OVER w50 AS avg_turnover_50,
          lag(close, 63)  OVER pw AS c63,
          lag(close, 126) OVER pw AS c126,
          lag(close, 189) OVER pw AS c189,
          lag(close, 252) OVER pw AS c252,
          lead(close, 5)  OVER pw AS f5,
          lead(close, 10) OVER pw AS f10,
          lead(close, 21) OVER pw AS f21,
          lead(close, 63) OVER pw AS f63,
          lead(d, 5)  OVER pw AS f5d,
          lead(d, 10) OVER pw AS f10d,
          lead(d, 21) OVER pw AS f21d,
          lead(d, 63) OVER pw AS f63d,
          row_number() OVER pw AS rn
        FROM base
        WINDOW
          pw   AS (PARTITION BY symbol ORDER BY d),
          w50  AS (PARTITION BY symbol ORDER BY d ROWS BETWEEN 49  PRECEDING AND CURRENT ROW),
          w150 AS (PARTITION BY symbol ORDER BY d ROWS BETWEEN 149 PRECEDING AND CURRENT ROW),
          w200 AS (PARTITION BY symbol ORDER BY d ROWS BETWEEN 199 PRECEDING AND CURRENT ROW),
          w252 AS (PARTITION BY symbol ORDER BY d ROWS BETWEEN 251 PRECEDING AND CURRENT ROW)
      ),
      calc2 AS (
        SELECT calc.*, lag(sma200, :risingSessions) OVER (PARTITION BY symbol ORDER BY d) AS sma200_ago
        FROM calc
      )
      SELECT symbol, d, close, sma50, sma150, sma200, sma200_ago, high_52w, low_52w,
             c63, c126, c189, c252, f5, f10, f21, f63, f5d, f10d, f21d, f63d
      FROM calc2
      WHERE d IN (:asOfs)
        AND rn >= :minSessions
        AND close >= :minPrice
        AND avg_turnover_50 >= :liquidity
      ORDER BY d, symbol
      """;

  /** Runs the harness over {@code [from, to]} at a {@code step}-session cadence (null → defaults). */
  public HitRateReport run(LocalDate from, LocalDate to, Integer step) {
    LocalDate dataMax = npjdbc.getJdbcTemplate().queryForObject(
        "SELECT max(bucket)::date FROM marketdata.candles WHERE interval='1d' AND tradingsymbol=?",
        LocalDate.class, benchmarkSymbol);
    if (dataMax == null) {
      return empty(from, to, step);
    }
    int stepSessions = step != null && step > 0 ? step : defaultStepSessions;
    LocalDate end = to != null && to.isBefore(dataMax) ? to : dataMax;
    LocalDate start = from != null ? from : end.minusYears(defaultLookbackYears);
    if (start.isBefore(end.minusYears(maxSpanYears))) {
      start = end.minusYears(maxSpanYears); // hard span cap — this is a heavy full-universe read
    }

    // The benchmark trades every session → use it as the trading-calendar spine. Load its full
    // series through dataMax so forward closes resolve, index it, and sample every step-th session.
    List<LocalDate> benchDates = new ArrayList<>();
    List<Double> benchClose = new ArrayList<>();
    npjdbc.getJdbcTemplate().query(
        "SELECT bucket::date d, close FROM marketdata.candles WHERE interval='1d' AND tradingsymbol=?"
            + " AND bucket < ? ORDER BY bucket",
        rs -> {
          benchDates.add(rs.getObject("d", LocalDate.class));
          benchClose.add(rs.getBigDecimal("close").doubleValue());
        },
        benchmarkSymbol, java.sql.Date.valueOf(dataMax.plusDays(1)));

    Map<LocalDate, Integer> benchIdx = new HashMap<>();
    for (int i = 0; i < benchDates.size(); i++) {
      benchIdx.put(benchDates.get(i), i);
    }
    List<LocalDate> asOfs = new ArrayList<>();
    for (int i = 0; i < benchDates.size(); i++) {
      LocalDate d = benchDates.get(i);
      if (!d.isBefore(start) && !d.isAfter(end) && (asOfs.size() == 0 || indexGap(benchIdx, asOfs, i, stepSessions))) {
        asOfs.add(d);
      }
    }
    if (asOfs.isEmpty()) {
      return empty(start, end, stepSessions);
    }

    Accumulator[] acc = new Accumulator[HORIZONS.length];
    int[] dropped = new int[HORIZONS.length]; // samples skipped for stock/benchmark session misalignment
    for (int h = 0; h < HORIZONS.length; h++) {
      acc[h] = new Accumulator();
    }
    java.util.Set<String> universe = new java.util.HashSet<>();

    MapSqlParameterSource p = new MapSqlParameterSource()
        .addValue("warmStart", java.sql.Date.valueOf(start.minusDays(WARMUP_CALENDAR_DAYS)))
        .addValue("endEx", java.sql.Date.valueOf(dataMax.plusDays(1)))
        .addValue("asOfs", asOfs.stream().map(java.sql.Date::valueOf).toList())
        .addValue("risingSessions", sma200RisingSessions)
        .addValue("minSessions", minSessions)
        .addValue("minPrice", minPrice)
        .addValue("liquidity", liquidityThreshold);

    // Stream ordered by date; buffer one asOf's rows, flush the cross-sectional screen when d changes.
    List<PanelRow> buf = new ArrayList<>();
    LocalDate[] cur = {null};
    npjdbc.query(PANEL_SQL, p, rs -> {
      LocalDate d = rs.getObject("d", LocalDate.class);
      if (cur[0] != null && !d.equals(cur[0])) {
        flush(cur[0], buf, benchDates, benchClose, benchIdx, acc, dropped, universe);
        buf.clear();
      }
      cur[0] = d;
      buf.add(readRow(rs));
    });
    if (!buf.isEmpty()) {
      flush(cur[0], buf, benchDates, benchClose, benchIdx, acc, dropped, universe);
    }

    List<HorizonStat> stats = new ArrayList<>();
    int totalSamples = 0;
    int totalDropped = 0;
    for (int h = 0; h < HORIZONS.length; h++) {
      stats.add(acc[h].toStat(HORIZONS[h]));
      totalSamples += acc[h].n;
      totalDropped += dropped[h];
    }
    String note =
        "price-gates only (no lookahead fundamentals); benchmark=" + benchmarkSymbol
            + "; source=candles@1d; each passer's forward return is measured to the SAME benchmark"
            + " session date, so " + totalDropped + " halt/gap-misaligned samples were dropped;"
            + " survivorship = today's listed universe (delisted losers absent → a real forward book"
            + " fares somewhat worse).";
    return new HitRateReport(
        start, end, stepSessions, asOfs.size(), universe.size(), totalSamples,
        benchmarkSymbol, note, stats);
  }

  // A sampled asOf is kept when it is ≥ step sessions past the previously kept one (weekly cadence
  // measured in trading sessions, not calendar days — the benchmark index is the session clock).
  private static boolean indexGap(
      Map<LocalDate, Integer> idx, List<LocalDate> kept, int i, int step) {
    int lastIdx = idx.get(kept.get(kept.size() - 1));
    return i - lastIdx >= step;
  }

  private record PanelRow(
      String symbol, double close, BigDecimal sma50, BigDecimal sma150, BigDecimal sma200,
      BigDecimal sma200Ago, BigDecimal high52w, BigDecimal low52w, BigDecimal c63, BigDecimal c126,
      BigDecimal c189, BigDecimal c252, Double f5, Double f10, Double f21, Double f63,
      LocalDate f5d, LocalDate f10d, LocalDate f21d, LocalDate f63d) {}

  private static PanelRow readRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new PanelRow(
        rs.getString("symbol"), rs.getBigDecimal("close").doubleValue(),
        rs.getBigDecimal("sma50"), rs.getBigDecimal("sma150"), rs.getBigDecimal("sma200"),
        rs.getBigDecimal("sma200_ago"), rs.getBigDecimal("high_52w"), rs.getBigDecimal("low_52w"),
        rs.getBigDecimal("c63"), rs.getBigDecimal("c126"), rs.getBigDecimal("c189"),
        rs.getBigDecimal("c252"), obj(rs, "f5"), obj(rs, "f10"), obj(rs, "f21"), obj(rs, "f63"),
        rs.getObject("f5d", LocalDate.class), rs.getObject("f10d", LocalDate.class),
        rs.getObject("f21d", LocalDate.class), rs.getObject("f63d", LocalDate.class));
  }

  private static Double obj(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
    BigDecimal v = rs.getBigDecimal(col);
    return v == null ? null : v.doubleValue();
  }

  // Cross-sectional screen for one asOf: rank the universe by weighted RS → percentile, apply the 8
  // gates, and book each passer's forward return (vs the benchmark) into the horizon accumulators.
  // The passer forward return is measured to the SAME benchmark session date (benchDates[idx+h]) so
  // the stock and NIFTY cover an identical calendar window; a halted/gapped stock whose forward bar
  // lands on a different date is dropped (surfaced in the report note). A single denominator (the
  // admitted sample count) backs every stat, so the win-rate and beat-benchmark rate stay consistent.
  private void flush(
      LocalDate asOf, List<PanelRow> rows, List<LocalDate> benchDates, List<Double> benchClose,
      Map<LocalDate, Integer> benchIdx, Accumulator[] acc, int[] dropped,
      java.util.Set<String> universe) {
    int n = rows.size();
    if (n == 0) {
      return;
    }
    Integer idxObj = benchIdx.get(asOf);
    if (idxObj == null) {
      return; // asOf is not a benchmark session (asOfs are drawn from the benchmark, so unreachable)
    }
    int idx = idxObj;
    double benchBase = benchClose.get(idx);
    record Scored(PanelRow r, BigDecimal rs) {}
    List<Scored> scored = new ArrayList<>(n);
    for (PanelRow r : rows) {
      universe.add(r.symbol());
      scored.add(new Scored(r, MinerviniGates.weightedRs(
          BigDecimal.valueOf(r.close()), r.c63(), r.c126(), r.c189(), r.c252())));
    }
    scored.sort(Comparator.comparing(Scored::rs));
    for (int i = 0; i < n; i++) {
      Scored s = scored.get(i);
      BigDecimal rsRank = n <= 1
          ? BigDecimal.valueOf(100)
          : BigDecimal.valueOf(i).multiply(BigDecimal.valueOf(100))
              .divide(BigDecimal.valueOf(n - 1L), 2, RoundingMode.HALF_UP);
      boolean[] g = MinerviniGates.gates(
          BigDecimal.valueOf(s.r().close()), s.r().sma50(), s.r().sma150(), s.r().sma200(),
          s.r().sma200Ago(), s.r().high52w(), s.r().low52w(), rsRank, rsMin, pctAbove52wLow,
          within52wHigh);
      if (MinerviniGates.passed(g) != 8) {
        continue; // only the full 8/8 passers are the actionable candidates
      }
      Double[] fwd = {s.r().f5(), s.r().f10(), s.r().f21(), s.r().f63()};
      LocalDate[] fwdDate = {s.r().f5d(), s.r().f10d(), s.r().f21d(), s.r().f63d()};
      if (s.r().close() <= 0 || benchBase <= 0) {
        continue;
      }
      for (int h = 0; h < HORIZONS.length; h++) {
        int fi = idx + HORIZONS[h];
        if (fwd[h] == null || fi >= benchClose.size()) {
          continue; // stock or benchmark has no forward bar → cannot measure this horizon
        }
        if (fwdDate[h] == null || !fwdDate[h].equals(benchDates.get(fi))) {
          dropped[h]++; // stock's forward bar is a different calendar session than the benchmark's
          continue;
        }
        double ret = (fwd[h] - s.r().close()) / s.r().close();
        double bench = (benchClose.get(fi) - benchBase) / benchBase;
        acc[h].add(ret, bench);
      }
    }
  }

  /**
   * Streaming per-horizon accumulator (means + hit-rates + the median). Every admitted sample has a
   * calendar-aligned benchmark, so all four rate/mean stats share the single denominator {@code n} —
   * the win-rate and the beat-benchmark rate are directly comparable.
   */
  private static final class Accumulator {
    int n;
    int positive;
    int beat;
    double sumRet;
    double sumBench;
    double sumExcess;
    final List<Double> rets = new ArrayList<>();

    void add(double ret, double bench) {
      n++;
      sumRet += ret;
      sumBench += bench;
      sumExcess += ret - bench;
      rets.add(ret);
      if (ret > 0) {
        positive++;
      }
      if (ret > bench) {
        beat++;
      }
    }

    HorizonStat toStat(int horizon) {
      if (n == 0) {
        return new HorizonStat(horizon, 0, null, null, null, null, null, null);
      }
      List<Double> sorted = new ArrayList<>(rets);
      java.util.Collections.sort(sorted);
      double median = sorted.get(sorted.size() / 2);
      return new HorizonStat(
          horizon, n,
          pct((double) positive / n * 100),
          pct((double) beat / n * 100),
          pct(sumRet / n * 100),
          pct(sumBench / n * 100),
          pct(sumExcess / n * 100),
          pct(median * 100));
    }
  }

  private static BigDecimal pct(double v) {
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
  }

  private HitRateReport empty(LocalDate from, LocalDate to, Integer step) {
    List<HorizonStat> stats = new ArrayList<>();
    for (int hz : HORIZONS) {
      stats.add(new HorizonStat(hz, 0, null, null, null, null, null, null));
    }
    return new HitRateReport(
        from, to, step != null ? step : defaultStepSessions, 0, 0, 0, benchmarkSymbol,
        "no benchmark/candle coverage for the requested range", stats);
  }
}
