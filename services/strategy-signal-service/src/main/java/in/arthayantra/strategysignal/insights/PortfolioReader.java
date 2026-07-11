package in.arthayantra.strategysignal.insights;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Assembles the portfolio-side reads for the HYGIENE + EXPIRY_EVENT + RISK_STALE_TICK generators (INT
 * design §2.2) — a READ-ONLY view over {@code journal_entries} + {@code paper_positions} + the
 * position's opening-signal {@code scalper_detail.expiry}, via {@link JdbcTemplate}. It NEVER imports
 * or mutates the {@code paper}/{@code journal} modules (the BookHeatReader precedent: same-schema SQL
 * is not a Java import). Fail-soft everywhere. The market calendar (holiday proximity) is the bundled
 * NSE list ({@link MarketCalendar#nse()}), already on the strategy-signal classpath.
 */
@Component
public class PortfolioReader {

  private static final Logger log = LoggerFactory.getLogger(PortfolioReader.class);

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final InsightProperties.Hygiene hygieneCfg;
  private final InsightProperties.Expiry expiryCfg;

  public PortfolioReader(JdbcTemplate jdbc, Clock clock, InsightProperties props) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.hygieneCfg = props.hygiene();
    this.expiryCfg = props.expiry();
  }

  /** The journal-hygiene counts over the trailing window (unrated entries + un-journaled closes). */
  public HygieneInputs hygieneScan() {
    try {
      OffsetDateTime from = OffsetDateTime.now(clock).minusDays(hygieneCfg.windowDays());
      Integer unrated =
          jdbc.queryForObject(
              "SELECT count(*) FROM journal_entries WHERE created_at >= ?"
                  + " AND discipline_rating IS NULL AND emotion_rating IS NULL",
              Integer.class, from);
      Integer unJournaled =
          jdbc.queryForObject(
              """
              SELECT count(*) FROM paper_positions p
              WHERE p.status = 'CLOSED' AND p.closed_at >= ?
                AND NOT EXISTS (SELECT 1 FROM journal_entries j WHERE j.paper_position_id = p.id)
              """,
              Integer.class, from);
      return new HygieneInputs(
          hygieneCfg.windowDays(), unrated == null ? 0 : unrated, unJournaled == null ? 0 : unJournaled);
    } catch (RuntimeException e) {
      log.debug("insight hygiene read unavailable: {}", e.getMessage());
      return new HygieneInputs(hygieneCfg.windowDays(), 0, 0);
    }
  }

  /** Open derivative positions expiring within the T-1 window, plus the next holiday's proximity. */
  public ExpirySnapshot expiryScan() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), Ist.ZONE);
    try {
      List<Object[]> rows =
          jdbc.query(
              """
              SELECT DISTINCT p.id, p.book, p.exchange, p.tradingsymbol, p.side, p.qty,
                     s.scalper_detail->>'expiry' AS expiry
              FROM paper_positions p
              JOIN paper_orders o
                ON o.book = p.book AND o.exchange = p.exchange AND o.tradingsymbol = p.tradingsymbol
                AND o.side = p.side AND o.signal_id IS NOT NULL
              JOIN signals s ON s.id = o.signal_id
              WHERE p.status = 'OPEN' AND s.scalper_detail->>'expiry' IS NOT NULL
              """,
              (rs, i) ->
                  new Object[] {
                    rs.getLong("id"), rs.getString("book"), rs.getString("exchange"),
                    rs.getString("tradingsymbol"), rs.getString("side"), rs.getLong("qty"),
                    rs.getString("expiry")
                  });
      List<ExpirySnapshot.ExpiringPosition> expiring = new ArrayList<>();
      for (Object[] r : rows) {
        LocalDate expiry;
        try {
          expiry = LocalDate.parse((String) r[6]);
        } catch (DateTimeParseException badDate) {
          continue;
        }
        long days = ChronoUnit.DAYS.between(today, expiry);
        if (days < 1 || days > expiryCfg.daysAhead()) {
          continue;
        }
        expiring.add(
            new ExpirySnapshot.ExpiringPosition(
                (Long) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4], (Long) r[5],
                expiry, days));
      }
      expiring.sort(
          Comparator.comparingLong(ExpirySnapshot.ExpiringPosition::daysToExpiry)
              .thenComparing(ExpirySnapshot.ExpiringPosition::tradingsymbol));
      MarketCalendar.Holiday next = nextHoliday(today);
      return new ExpirySnapshot(
          today, expiring, next == null ? null : next.date(), next == null ? null : next.name());
    } catch (RuntimeException e) {
      log.debug("insight expiry read unavailable: {}", e.getMessage());
      return new ExpirySnapshot(today, List.of(), null, null);
    }
  }

  /**
   * Open BRACKETED positions whose instrument the live-capture canary flags. {@code staleKeys} maps
   * the health/data {@code exchange:tradingsymbol} key → its problem detail (built by the engine from
   * {@link ContextClient#dataHealth()}).
   */
  public StaleTickSnapshot staleTickScan(Map<String, String> staleKeys) {
    if (staleKeys == null || staleKeys.isEmpty()) {
      return new StaleTickSnapshot(List.of());
    }
    try {
      List<StaleTickSnapshot.StaleBracket> stalled =
          jdbc.query(
              """
              SELECT id, book, exchange, tradingsymbol, side,
                     (stop_loss IS NOT NULL) AS has_stop, (take_profit IS NOT NULL) AS has_target
              FROM paper_positions
              WHERE status = 'OPEN' AND (stop_loss IS NOT NULL OR take_profit IS NOT NULL)
              """,
              (rs, i) ->
                  new Object[] {
                    rs.getLong("id"), rs.getString("book"), rs.getString("exchange"),
                    rs.getString("tradingsymbol"), rs.getString("side"),
                    rs.getBoolean("has_stop"), rs.getBoolean("has_target")
                  })
              .stream()
              .map(
                  r -> {
                    String key = r[2] + ":" + r[3];
                    String detail = staleKeys.get(key);
                    return detail == null
                        ? null
                        : new StaleTickSnapshot.StaleBracket(
                            (Long) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                            (Boolean) r[5], (Boolean) r[6], key, detail);
                  })
              .filter(b -> b != null)
              .toList();
      return new StaleTickSnapshot(stalled);
    } catch (RuntimeException e) {
      log.debug("insight stale-tick read unavailable: {}", e.getMessage());
      return new StaleTickSnapshot(List.of());
    }
  }

  private MarketCalendar.Holiday nextHoliday(LocalDate today) {
    try {
      return calendar.holidayList().stream()
          .filter(h -> h.date().isAfter(today))
          .min(Comparator.comparing(MarketCalendar.Holiday::date))
          .orElse(null);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
