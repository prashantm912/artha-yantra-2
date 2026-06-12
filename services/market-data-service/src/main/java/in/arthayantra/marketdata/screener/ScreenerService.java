package in.arthayantra.marketdata.screener;

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

  /** One ranked row. */
  public record Row(
      String exchange,
      String tradingsymbol,
      BigDecimal latestClose,
      BigDecimal pastClose,
      BigDecimal value,
      String label) {}

  private static final Map<String, String> VIEWS =
      Map.of("1d", "candles_1d", "1h", "candles_1h", "1w", "candles_1w");

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public ScreenerService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Runs a preset; 422 on unanswerable combinations. */
  public List<Row> run(String preset, String window, Integer lookback, int limit) {
    String view = VIEWS.get(window == null ? "1d" : window);
    if (view == null) {
      throw new ApiException(
          422, ErrorCodes.VALIDATION_FAILED, "window must be one of " + VIEWS.keySet());
    }
    return switch (preset) {
      case "momentum" -> returns(view, lookback == null ? 5 : lookback, limit, false);
      case "long_term" -> returns(view, lookback == null ? 126 : lookback, limit, false);
      case "rs_rank" -> rsRank(view, lookback == null ? 63 : lookback, limit);
      case "oi_buildup" -> oiBuildup(limit);
      default ->
          throw new ApiException(
              422,
              ErrorCodes.VALIDATION_FAILED,
              "preset must be momentum|long_term|oi_buildup|rs_rank");
    };
  }

  /** Return over the last {@code lookback} bars of the view, ranked descending. */
  private List<Row> returns(String view, int lookback, int limit, boolean includeIndices) {
    // nth-newest bucket per symbol via row_number — pure cagg SQL, no Kite port anywhere
    List<Row> rows =
        jdbc.query(
            """
            WITH numbered AS (
              SELECT exchange, tradingsymbol, bucket, close,
                     row_number() OVER (PARTITION BY exchange, tradingsymbol ORDER BY bucket DESC) AS rn
              FROM %s
            ),
            latest AS (SELECT exchange, tradingsymbol, close FROM numbered WHERE rn = 1),
            past AS (SELECT exchange, tradingsymbol, close FROM numbered WHERE rn = ?)
            SELECT l.exchange, l.tradingsymbol, l.close AS latest_close, p.close AS past_close
            FROM latest l JOIN past p USING (exchange, tradingsymbol)
            WHERE p.close > 0
            """
                .formatted(view),
            (rs, n) ->
                new Row(
                    rs.getString("exchange"),
                    rs.getString("tradingsymbol"),
                    rs.getBigDecimal("latest_close"),
                    rs.getBigDecimal("past_close"),
                    ret(rs.getBigDecimal("latest_close"), rs.getBigDecimal("past_close")),
                    null),
            lookback + 1);
    return rows.stream()
        .filter(r -> includeIndices || !r.tradingsymbol().endsWith("-FUT-CONT"))
        .sorted(Comparator.comparing(Row::value).reversed())
        .limit(limit)
        .toList();
  }

  /** FP-20: stock-return percentile vs the NSE NIFTY 50 benchmark return. */
  private List<Row> rsRank(String view, int lookback, int limit) {
    List<Row> all = returns(view, lookback, Integer.MAX_VALUE, true);
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
    List<Row> stocks =
        all.stream()
            .filter(r -> !r.tradingsymbol().equals("NIFTY 50"))
            .map(
                r ->
                    new Row(
                        r.exchange(), r.tradingsymbol(), r.latestClose(), r.pastClose(),
                        r.value().subtract(benchmark), null))
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
          new Row(r.exchange(), r.tradingsymbol(), r.latestClose(), r.pastClose(), percentile, null));
    }
    return ranked.reversed().stream().limit(limit).toList();
  }

  /** FP-10: the four close/OI delta quadrants off the 1d cagg for FUT contracts. */
  private List<Row> oiBuildup(int limit) {
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
              priceDelta.signum() >= 0
                  ? (oiDelta >= 0 ? "LONG_BUILDUP" : "SHORT_COVERING")
                  : (oiDelta >= 0 ? "SHORT_BUILDUP" : "LONG_UNWINDING");
          rows.add(
              new Row(
                  exchanges.get(symbol), symbol, bars.get(0).close(), bars.get(1).close(),
                  priceDelta, label));
        });
    return rows.stream()
        .sorted(Comparator.comparing(Row::tradingsymbol))
        .limit(limit)
        .toList();
  }

  private static BigDecimal ret(BigDecimal latest, BigDecimal past) {
    return latest.subtract(past).divide(past, MathContext.DECIMAL64).setScale(6, RoundingMode.HALF_UP);
  }
}
