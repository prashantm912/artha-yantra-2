package in.arthayantra.backtest.testsupport;

import in.arthayantra.backtest.regime.RegimeLabeler;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds a DETERMINISTIC synthetic daily benchmark series ({@code NSE:NIFTY 50}, {@code
 * interval='1d'}) into {@code marketdata.candles} so regime attribution (guard 6) has the warm-up
 * depth its pre-flight demands plus a daily bar on every in-window date (so OOS entry days resolve a
 * label). The series is seeded on CONSECUTIVE CALENDAR DAYS — the {@link RegimeLabeler} is purely
 * index-based (it never consults {@code MarketCalendar}, so it does not care whether a benchmark
 * session lands on a weekend), and the bundled {@code MarketCalendar} only covers the current year,
 * which the ~13-month warm-up depth would otherwise overrun. Closes follow a fixed deterministic
 * path (a gentle drift + bounded oscillation) so the same call always produces the same labels.
 */
public final class BenchmarkSeeder {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private BenchmarkSeeder() {}

  /**
   * Seeds {@code totalSessions} synthetic daily benchmark candles ending the calendar day BEFORE
   * {@code windowEnd} (so the warm-up prefix before any in-window date plus every in-window date are
   * present). Idempotent: a no-op when the benchmark already has any {@code 1d} bar.
   */
  public static void seed(JdbcTemplate jdbc, LocalDate windowEnd, int totalSessions) {
    Long existing =
        jdbc.queryForObject(
            "SELECT count(*) FROM marketdata.candles WHERE exchange='NSE' "
                + "AND tradingsymbol='NIFTY 50' AND \"interval\"='1d'",
            Long.class);
    if (existing != null && existing > 0) {
      return;
    }

    LocalDate start = windowEnd.minusDays(totalSessions);
    double base = 20000.0;
    List<Object[]> rows = new ArrayList<>(totalSessions);
    for (int i = 0; i < totalSessions; i++) {
      // deterministic close path: drift + bounded oscillation; no RNG, no MarketCalendar.
      double close = base + i * 3.0 + 150.0 * Math.sin(i / 11.0) + 40.0 * Math.sin(i / 2.3);
      BigDecimal c = BigDecimal.valueOf(Math.round(close * 100.0) / 100.0);
      rows.add(new Object[] {start.plusDays(i), c});
    }

    jdbc.batchUpdate(
        "INSERT INTO marketdata.candles "
            + "(exchange, tradingsymbol, \"interval\", bucket, open, high, low, close, volume, "
            + "source) VALUES ('NSE','NIFTY 50','1d', ?, ?, ?, ?, ?, 0, 'MOCK')",
        rows,
        rows.size(),
        (ps, row) -> {
          LocalDate day = (LocalDate) row[0];
          BigDecimal close = (BigDecimal) row[1];
          ps.setObject(1, day.atStartOfDay().atOffset(IST));
          ps.setBigDecimal(2, close); // open == close (synthetic; only close drives labels)
          ps.setBigDecimal(3, close.add(BigDecimal.valueOf(20))); // high
          ps.setBigDecimal(4, close.subtract(BigDecimal.valueOf(20))); // low
          ps.setBigDecimal(5, close);
        });
  }

  /** Seeds with the standard depth: warm-up sessions plus a generous in-window buffer. */
  public static void seedDefault(JdbcTemplate jdbc, LocalDate windowEnd) {
    seed(jdbc, windowEnd, RegimeLabeler.WARMUP_SESSIONS + 30);
  }
}
