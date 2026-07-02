package in.arthayantra.marketdata.nse.preopen;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Equity pre-open FULL scanner (audit §9.4, Wave 5 — oipulse §equity/pre-open-market): one capture
 * per session at ~09:09:30 IST persists every F&O cash stock's pre-open price vs the previous
 * close + the prev-day High/Low Break badge, so the pre-open read stays reviewable all day and
 * accrues a history (the old 4-index card pivoted to live LTP at 09:15 and kept nothing).
 *
 * <p>Universe = the F&O stocks (distinct NFO FUT underlying names that have an EQ bhavcopy row —
 * indices fall out naturally). ONE batched {@link QuoteGateway} call (~200 equity keys, well under
 * the Kite quote batch cap); prev close/high/low come from the latest EQ bhavcopy BEFORE the
 * capture day. The row maths (change%, H/L break) mirror the futures pre-open fold but live HERE —
 * importing {@code futures.preopen} from an nse slice closes a Modulith cycle. Symbols without a
 * resolvable quote are skipped (nothing to preserve). Re-capture upserts (idempotent).
 */
@Service
public class PreOpenEquityScanService {

  /** One preserved scan row: the pre-open price vs prev close + the prev-day H/L break badge. */
  public record ScanRow(
      String symbol,
      BigDecimal preOpenPrice,
      BigDecimal prevClose,
      BigDecimal change,
      BigDecimal changePct,
      String prevDayBreak) {}

  /** The typed GET envelope: a day's rows + the captured-session list (history picker). */
  public record PreOpenScanView(LocalDate date, List<ScanRow> items, List<LocalDate> dates) {}

  /** The typed capture result. */
  public record CaptureResult(LocalDate date, int captured) {}

  private static final Logger log = LoggerFactory.getLogger(PreOpenEquityScanService.class);

  private final JdbcTemplate jdbc;
  private final QuoteGateway quoteGateway;
  private final MarketCalendar calendar;
  private final Clock clock;

  public PreOpenEquityScanService(
      JdbcTemplate jdbc, QuoteGateway quoteGateway, MarketCalendar calendar, Clock clock) {
    this.jdbc = jdbc;
    this.quoteGateway = quoteGateway;
    this.calendar = calendar;
    this.clock = clock;
  }

  /** The oipulse capture moment ("data updated on or after 09:09:30 AM"), IST, weekdays. */
  @Scheduled(cron = "${artha.equity.preopen-scan-cron:30 9 9 * * MON-FRI}", zone = "Asia/Kolkata")
  public void scheduledCapture() {
    LocalDate today = clock.instant().atZone(Ist.ZONE).toLocalDate();
    try {
      if (!calendar.isTradingDay(today)) {
        return;
      }
    } catch (IllegalArgumentException uncoveredYear) {
      return;
    }
    int rows = capture(today);
    log.info("pre-open equity scan captured {} rows for {}", rows, today);
  }

  /** Captures (upserts) the scan for {@code day}; returns the persisted row count. */
  public int capture(LocalDate day) {
    List<String> universe = fnoStockUniverse();
    if (universe.isEmpty()) {
      return 0;
    }
    List<InstrumentKey> keys = new ArrayList<>(universe.size());
    for (String symbol : universe) {
      keys.add(new InstrumentKey("NSE", symbol));
    }
    Map<InstrumentKey, QuoteGateway.Quote> quotes = quoteGateway.quotes(keys);
    Map<String, PrevDay> prev = prevDay(day);

    int persisted = 0;
    for (String symbol : universe) {
      QuoteGateway.Quote q = quotes.get(new InstrumentKey("NSE", symbol));
      if (q == null || q.lastPrice() == null) {
        continue; // no pre-open quote resolved — nothing to preserve
      }
      PrevDay p = prev.get(symbol);
      BigDecimal prevClose = p == null ? null : p.close();
      BigDecimal change = prevClose == null ? null : q.lastPrice().subtract(prevClose);
      jdbc.update(
          "INSERT INTO preopen_equity_snapshots "
              + "(trade_date, symbol, preopen_price, prev_close, change, change_pct, prev_day_break) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?) "
              + "ON CONFLICT (trade_date, symbol) DO UPDATE SET preopen_price = EXCLUDED.preopen_price, "
              + "prev_close = EXCLUDED.prev_close, change = EXCLUDED.change, "
              + "change_pct = EXCLUDED.change_pct, prev_day_break = EXCLUDED.prev_day_break, "
              + "captured_at = now()",
          Date.valueOf(day),
          symbol,
          q.lastPrice(),
          prevClose,
          change,
          changePct(change, prevClose),
          classifyBreak(q.lastPrice(), p == null ? null : p.high(), p == null ? null : p.low()));
      persisted++;
    }
    return persisted;
  }

  /** {@code change / prevClose × 100} (2 dp), or {@code null} when either is absent / prevClose=0. */
  static BigDecimal changePct(BigDecimal change, BigDecimal prevClose) {
    if (change == null || prevClose == null || prevClose.signum() == 0) {
      return null;
    }
    return change.multiply(BigDecimal.valueOf(100)).divide(prevClose, 2, RoundingMode.HALF_UP);
  }

  /** "H" above the prev-day high, "L" below the prev-day low, else null (the break badge). */
  static String classifyBreak(BigDecimal price, BigDecimal prevHigh, BigDecimal prevLow) {
    if (price == null) {
      return null;
    }
    if (prevHigh != null && price.compareTo(prevHigh) > 0) {
      return "H";
    }
    if (prevLow != null && price.compareTo(prevLow) < 0) {
      return "L";
    }
    return null;
  }

  /** The preserved scan for {@code day} (advances first by change%, then declines). */
  public List<ScanRow> scan(LocalDate day) {
    return jdbc.query(
        "SELECT symbol, preopen_price, prev_close, change, change_pct, prev_day_break "
            + "FROM preopen_equity_snapshots WHERE trade_date = ? ORDER BY change_pct DESC NULLS LAST",
        (rs, n) ->
            new ScanRow(
                rs.getString("symbol"),
                rs.getBigDecimal("preopen_price"),
                rs.getBigDecimal("prev_close"),
                rs.getBigDecimal("change"),
                rs.getBigDecimal("change_pct"),
                rs.getString("prev_day_break")),
        Date.valueOf(day));
  }

  /** The typed GET envelope for {@code day}. */
  public PreOpenScanView view(LocalDate day) {
    return new PreOpenScanView(day, scan(day), capturedDates());
  }

  /** Captured session dates, newest first (the page's history picker). */
  public List<LocalDate> capturedDates() {
    return jdbc.query(
        "SELECT DISTINCT trade_date FROM preopen_equity_snapshots ORDER BY trade_date DESC LIMIT 60",
        (rs, n) -> rs.getObject("trade_date", LocalDate.class));
  }

  /** F&O cash universe: distinct NFO FUT underlying names that have an EQ bhavcopy row. */
  private List<String> fnoStockUniverse() {
    return jdbc.queryForList(
        "SELECT DISTINCT i.name FROM instruments i "
            + "WHERE i.exchange = 'NFO' AND i.instrument_type = 'FUT' AND i.is_active "
            + "AND EXISTS (SELECT 1 FROM nse_eod_bhavcopy b WHERE b.symbol = i.name AND b.series = 'EQ') "
            + "ORDER BY i.name",
        String.class);
  }

  private record PrevDay(BigDecimal close, BigDecimal high, BigDecimal low) {}

  /** Latest EQ bhavcopy row strictly BEFORE {@code day}, per symbol. */
  private Map<String, PrevDay> prevDay(LocalDate day) {
    Map<String, PrevDay> map = new HashMap<>();
    jdbc.query(
        "WITH ranked AS ("
            + "  SELECT symbol, close_price, high_price, low_price, "
            + "    ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date DESC) AS rn "
            + "  FROM nse_eod_bhavcopy WHERE series = 'EQ' AND trade_date < ?) "
            + "SELECT symbol, close_price, high_price, low_price FROM ranked WHERE rn = 1",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs ->
                map.put(
                    rs.getString("symbol"),
                    new PrevDay(
                        rs.getBigDecimal("close_price"),
                        rs.getBigDecimal("high_price"),
                        rs.getBigDecimal("low_price"))),
        Date.valueOf(day));
    return map;
  }
}
