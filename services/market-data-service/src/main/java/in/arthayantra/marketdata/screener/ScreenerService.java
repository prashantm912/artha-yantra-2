package in.arthayantra.marketdata.screener;

import io.swagger.v3.oas.annotations.media.Schema;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The Phase-17 screener: parameterized SQL over the continuous aggregates — NEVER a Kite port
 * (B-18/B-21 PASS criterion). Presets: {@code momentum}, {@code long_term}, {@code oi_buildup}
 * (four price/OI delta quadrants off the 1d cagg for FUT contracts), {@code rs_rank} (return
 * percentile vs the benchmark index; ranks across cached active equities until Phase 22's
 * constituents land). {@code window=1w} answers from {@code candles_1w}.
 */
@Service
public class ScreenerService {

  /** One ranked row (B-1 shape: last close, period return, avg volume, 52-w-high distance). */
  public record Row(
      String exchange,
      String tradingsymbol,
      BigDecimal latestClose,
      BigDecimal pastClose,
      BigDecimal value,
      @Schema(types = {"number", "null"})
      BigDecimal avgVolume,
      @Schema(types = {"number", "null"})
      BigDecimal distanceFromHigh52w,
      @Schema(types = {"string", "null"})
      String label) {}

  /** The B-1 explicit-filter set (all optional). */
  public record Filters(
      BigDecimal minReturnPct,
      BigDecimal minAvgVolume,
      BigDecimal minPrice,
      BigDecimal maxPrice,
      String exchange) {

    /** No filters. */
    public static final Filters NONE = new Filters(null, null, null, null, null);
  }

  private static final Map<String, String> VIEWS =
      Map.of("1d", "candles_1d", "1h", "candles_1h", "1w", "candles_1w");

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public ScreenerService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Runs a preset OR the explicit-filter mode (preset null); 422 on unanswerable combos. */
  public List<Row> run(
      String preset, String window, Integer lookback, Filters filters, int limit, int offset) {
    String view = VIEWS.get(window == null ? "1d" : window);
    if (view == null) {
      throw new ApiException(
          422, ErrorCodes.VALIDATION_FAILED, "window must be one of " + VIEWS.keySet());
    }
    Filters f = filters == null ? Filters.NONE : filters;
    if (preset == null || preset.isBlank()) {
      // B-1 explicit-filter mode: return ranking constrained by the filter set
      return page(returns(view, lookback == null ? 5 : lookback, f, false), limit, offset);
    }
    return switch (preset) {
      case "momentum" -> page(returns(view, lookback == null ? 5 : lookback, f, false), limit, offset);
      case "long_term" ->
          page(returns(view, lookback == null ? 126 : lookback, f, false), limit, offset);
      case "rs_rank" -> page(rsRank(view, lookback == null ? 63 : lookback), limit, offset);
      case "oi_buildup" -> page(oiBuildup(), limit, offset);
      default ->
          throw new ApiException(
              422,
              ErrorCodes.VALIDATION_FAILED,
              "preset must be momentum|long_term|oi_buildup|rs_rank (or omitted for filters)");
    };
  }

  private static List<Row> page(List<Row> rows, int limit, int offset) {
    return rows.stream().skip(Math.max(0, offset)).limit(limit).toList();
  }

  /** Return over the last {@code lookback} bars of the view, filtered + ranked descending. */
  private List<Row> returns(String view, int lookback, Filters f, boolean includeIndices) {
    // per-symbol aggregates over the cagg — pure SQL, no Kite port anywhere
    List<Row> rows =
        jdbc.query(
            """
            WITH numbered AS (
              SELECT exchange, tradingsymbol, bucket, close, high, volume,
                     row_number() OVER (PARTITION BY exchange, tradingsymbol ORDER BY bucket DESC) AS rn
              FROM %s
            ),
            agg AS (
              SELECT exchange, tradingsymbol,
                     max(close) FILTER (WHERE rn = 1)  AS latest_close,
                     max(close) FILTER (WHERE rn = ?)  AS past_close,
                     avg(volume) FILTER (WHERE rn <= ?) AS avg_volume,
                     max(high) FILTER (WHERE rn <= 252) AS high_52w
              FROM numbered GROUP BY exchange, tradingsymbol
            )
            SELECT * FROM agg
            WHERE past_close > 0
              AND (?::text IS NULL OR exchange = ?)
              AND (?::numeric IS NULL OR latest_close >= ?)
              AND (?::numeric IS NULL OR latest_close <= ?)
              AND (?::numeric IS NULL OR avg_volume >= ?)
            """
                .formatted(view),
            (rs, n) -> {
              BigDecimal latest = rs.getBigDecimal("latest_close");
              BigDecimal past = rs.getBigDecimal("past_close");
              BigDecimal high52 = rs.getBigDecimal("high_52w");
              BigDecimal fromHigh =
                  high52 == null || high52.signum() == 0
                      ? null
                      : latest.subtract(high52).divide(high52, 6, RoundingMode.HALF_UP);
              return new Row(
                  rs.getString("exchange"),
                  rs.getString("tradingsymbol"),
                  latest,
                  past,
                  ret(latest, past),
                  rs.getBigDecimal("avg_volume"),
                  fromHigh,
                  null);
            },
            lookback + 1,
            lookback,
            f.exchange(), f.exchange(),
            f.minPrice(), f.minPrice(),
            f.maxPrice(), f.maxPrice(),
            f.minAvgVolume(), f.minAvgVolume());
    return rows.stream()
        .filter(r -> includeIndices || !r.tradingsymbol().endsWith("-FUT-CONT"))
        .filter(
            r ->
                f.minReturnPct() == null
                    || r.value()
                            .multiply(BigDecimal.valueOf(100))
                            .compareTo(f.minReturnPct())
                        >= 0)
        .sorted(Comparator.comparing(Row::value).reversed())
        .toList();
  }

  /** FP-20: stock-return percentile vs the NSE NIFTY 50 benchmark return. */
  private List<Row> rsRank(String view, int lookback) {
    List<Row> all = returns(view, lookback, Filters.NONE, true);
    BigDecimal benchmark =
        all.stream()
            .filter(r -> r.exchange().equals("NSE") && r.tradingsymbol().equals("NIFTY 50"))
            .map(Row::value)
            .findFirst()
            .orElseThrow(
                () ->
                    new ApiException(
                        422,
                        ErrorCodes.VALIDATION_FAILED,
                        "rs_rank needs cached benchmark history for NSE:NIFTY 50 at this window"));
    // the interim FP-20 universe: cached ACTIVE EQUITIES only — FUTs, indices and synthetic
    // CONT rows must never enter the stock percentile (index_constituents arrives in Phase 22)
    java.util.Set<String> equities = new java.util.HashSet<>();
    jdbc.query(
        "SELECT exchange, tradingsymbol FROM instruments WHERE is_active AND instrument_type = 'EQ'"
            + " AND segment NOT IN ('INDICES','SYN-CONT')",
        rs -> {
          equities.add(rs.getString("exchange") + ":" + rs.getString("tradingsymbol"));
        });
    List<Row> stocks =
        all.stream()
            .filter(r -> equities.contains(r.exchange() + ":" + r.tradingsymbol()))
            .map(
                r ->
                    new Row(
                        r.exchange(), r.tradingsymbol(), r.latestClose(), r.pastClose(),
                        r.value().subtract(benchmark), r.avgVolume(), r.distanceFromHigh52w(), null))
            .sorted(Comparator.comparing(Row::value))
            .toList();
    int n = stocks.size();
    List<Row> ranked = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      Row r = stocks.get(i);
      BigDecimal percentile =
          n == 1
              ? BigDecimal.ONE
              : BigDecimal.valueOf(i).divide(BigDecimal.valueOf(n - 1), 4, RoundingMode.HALF_UP);
      ranked.add(
          new Row(
              r.exchange(), r.tradingsymbol(), r.latestClose(), r.pastClose(), percentile,
              r.avgVolume(), r.distanceFromHigh52w(), null));
    }
    return ranked.reversed();
  }

  /** FP-10: the four close/OI delta quadrants off the 1d cagg for FUT contracts. */
  private List<Row> oiBuildup() {
    record DayBar(BigDecimal close, Long oi) {}
    Map<String, List<DayBar>> bySymbol = new HashMap<>();
    Map<String, String> exchanges = new HashMap<>();
    jdbc.query(
        """
        SELECT d.exchange, d.tradingsymbol, d.bucket, d.close, d.oi
        FROM candles_1d d
        JOIN instruments i ON i.exchange = d.exchange AND i.tradingsymbol = d.tradingsymbol
        WHERE i.instrument_type = 'FUT' AND i.segment <> 'SYN-CONT' AND i.is_active
        ORDER BY d.tradingsymbol, d.bucket DESC
        """,
        rs -> {
          String symbol = rs.getString("tradingsymbol");
          exchanges.put(symbol, rs.getString("exchange"));
          bySymbol
              .computeIfAbsent(symbol, s -> new ArrayList<>())
              .add(new DayBar(rs.getBigDecimal("close"), rs.getObject("oi", Long.class)));
        });
    List<Row> rows = new ArrayList<>();
    bySymbol.forEach(
        (symbol, bars) -> {
          if (bars.size() < 2 || bars.get(0).oi() == null || bars.get(1).oi() == null) {
            return;
          }
          BigDecimal priceDelta = bars.get(0).close().subtract(bars.get(1).close());
          long oiDelta = bars.get(0).oi() - bars.get(1).oi();
          String label =
              in.arthayantra.marketdata.options.OiInterpretation.classify(priceDelta, oiDelta)
                  .name();
          rows.add(
              new Row(
                  exchanges.get(symbol), symbol, bars.get(0).close(), bars.get(1).close(),
                  priceDelta, null, null, label));
        });
    return rows.stream().sorted(Comparator.comparing(Row::tradingsymbol)).toList();
  }

  private static BigDecimal ret(BigDecimal latest, BigDecimal past) {
    return latest.subtract(past).divide(past, MathContext.DECIMAL64).setScale(6, RoundingMode.HALF_UP);
  }
}
