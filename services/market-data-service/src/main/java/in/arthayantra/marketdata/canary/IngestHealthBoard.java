package in.arthayantra.marketdata.canary;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.canary.IngestCoverageCanary.ExpectedSource;
import in.arthayantra.marketdata.canary.IngestCoverageCanary.SourceCoverage;
import java.time.Clock;
import java.time.Duration;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The EOD ingest health board (app-platform audit 2026-07-10 §6.3 / §9.1 — audit item A11). The
 * operator read model over the {@code marketdata.ingest_runs} ledger rows: per expected source, its
 * last run (status / rows / timings / error), the health verdict for the most recent
 * settled trading day, and how many of the last N trading days it silently missed. This is what
 * "kills the silent permanent hole class" — the FII/DII / participant / screener families that had
 * nothing watching their per-day coverage.
 *
 * <p><b>Policy is not re-derived here.</b> The per-day GREEN/YELLOW/RED verdict is delegated to the
 * A5 {@link IngestCoverageCanary#evaluate(LocalDate)} (the single owner of the expected-source ×
 * trading-day policy: REQUIRE_SUCCESS / SCREENER-starved-YELLOW / CAPTURE-per-day, aged-RUNNING =
 * crashed). The board only <em>pivots</em> that per-day matrix across a window and joins each
 * source's true last run.
 *
 * <p><b>Window semantics.</b> The window is the last N settled trading days strictly before today
 * (IST) — an "EOD" board, so today's in-flight batches never false-RED the morning view; the newest
 * day is {@code previousTradingDay(today)}, exactly the day the canary alerts on. The trading-day
 * axis comes from {@link MarketCalendar#nse()} and the walk-back is cliff-guarded (truncates the
 * window rather than throwing when it would leave the bundled 2024–2026 coverage — the CD-2 horizon
 * canary owns the cliff warning).
 *
 * <p>IST-day derivation is from the clock instant via {@link Ist#ZONE}; the last-run query orders by
 * {@code started_at DESC} on the {@code ix_ingest_runs_source_started_at} index — never a
 * {@code CURRENT_DATE}-baked predicate (in-container {@code now()} is UTC).
 */
@Component
public class IngestHealthBoard {

  /** The most recent {@code ingest_runs} row for a source (raw status + a computed staleness flag). */
  public record LastRun(
      String status,
      @Schema(types = {"integer", "null"}) Long rowsWritten,
      Instant startedAt,
      @Schema(types = {"string", "null"}) Instant finishedAt,
      @Schema(types = {"string", "null"}) String error,
      boolean stale) {}

  /** One trading day's verdict for a source (aligned to the board's {@code fromDay}..{@code toDay}). */
  public record DayVerdict(LocalDate day, String status) {}

  /** One source's health: latest verdict + detail, missed-day count, the per-day strip, and last run. */
  public record SourceHealth(
      String source,
      String policy,
      String status,
      String detail,
      int missingDays,
      List<DayVerdict> days,
      LastRun lastRun) {}

  /** The whole board: the evaluated window plus one {@link SourceHealth} per expected source. */
  public record BoardReport(
      Instant generatedAt,
      @Schema(types = {"string", "null"}) LocalDate fromDay,
      @Schema(types = {"string", "null"}) LocalDate toDay,
      int tradingDays,
      List<SourceHealth> sources) {}

  /** Default look-back (trading days) — audit §6.3 "~10". */
  static final int DEFAULT_LOOKBACK = 10;

  /** Upper bound on the look-back: one {@code evaluate()} query per day, so keep the fan-out sane. */
  static final int MAX_LOOKBACK = 30;

  private static final String RED = "RED";
  private static final String GREEN = "GREEN";
  private static final String YELLOW = "YELLOW";

  private static final Logger log = LoggerFactory.getLogger(IngestHealthBoard.class);

  private final JdbcTemplate jdbc;
  private final IngestCoverageCanary canary;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final Duration runningStale;

  /**
   * Wires the ledger reader, the A5 coverage canary (policy owner), the NSE calendar and the clock.
   * The staleness threshold binds the SAME property as the canary ({@code
   * artha.ingest-canary.running-stale-minutes}) so the last-run "stale RUNNING = crashed" flag can
   * never drift from the canary's per-day aged-RUNNING rule.
   */
  public IngestHealthBoard(
      JdbcTemplate jdbc,
      IngestCoverageCanary canary,
      MarketCalendar calendar,
      Clock clock,
      @Value("${artha.ingest-canary.running-stale-minutes:120}") long runningStaleMinutes) {
    this.jdbc = jdbc;
    this.canary = canary;
    this.calendar = calendar;
    this.clock = clock;
    this.runningStale = Duration.ofMinutes(runningStaleMinutes);
  }

  /**
   * Build the board over the last {@code lookbackDays} settled trading days (clamped to
   * {@code [1, MAX_LOOKBACK]}). Delegates every per-day verdict to {@link
   * IngestCoverageCanary#evaluate(LocalDate)} and joins each source's last run. Read-only.
   */
  public BoardReport board(int lookbackDays) {
    int n = Math.max(1, Math.min(lookbackDays, MAX_LOOKBACK));
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    List<LocalDate> days = recentTradingDays(today, n); // newest first
    Map<String, LastRun> lastRuns = lastRunBySource();
    Instant generatedAt = clock.instant();

    if (days.isEmpty()) {
      // Calendar cliff (today outside the bundled coverage): no trading-day axis to evaluate, so
      // degrade to a last-run-only board rather than throw. The horizon canary owns the cliff alert.
      List<SourceHealth> sources = new ArrayList<>();
      for (ExpectedSource es : IngestCoverageCanary.EXPECTED) {
        LastRun lr = lastRuns.get(es.source());
        sources.add(
            new SourceHealth(
                es.source(),
                es.policy().name(),
                statusFromLastRun(lr),
                "trading calendar does not cover recent dates — showing last run only",
                0,
                List.of(),
                lr));
      }
      return new BoardReport(generatedAt, null, null, 0, List.copyOf(sources));
    }

    // Per-day verdict matrix: canary.evaluate(day) → source → verdict. One SQL query per day.
    Map<LocalDate, Map<String, SourceCoverage>> perDay = new HashMap<>();
    for (LocalDate day : days) {
      perDay.put(
          day,
          canary.evaluate(day).sources().stream()
              .collect(Collectors.toMap(SourceCoverage::source, Function.identity())));
    }

    LocalDate newest = days.get(0);
    LocalDate oldest = days.get(days.size() - 1);
    List<SourceHealth> sources = new ArrayList<>();
    for (ExpectedSource es : IngestCoverageCanary.EXPECTED) {
      List<DayVerdict> verdicts = new ArrayList<>();
      for (LocalDate day : days) {
        SourceCoverage cov = perDay.get(day).get(es.source());
        verdicts.add(new DayVerdict(day, cov == null ? RED : cov.status()));
      }
      SourceCoverage latest = perDay.get(newest).get(es.source());
      int missing = (int) verdicts.stream().filter(v -> RED.equals(v.status())).count();
      sources.add(
          new SourceHealth(
              es.source(),
              es.policy().name(),
              latest == null ? RED : latest.status(),
              latest == null ? "no verdict for the trading day" : latest.detail(),
              missing,
              List.copyOf(verdicts),
              lastRuns.get(es.source())));
    }
    return new BoardReport(generatedAt, oldest, newest, days.size(), List.copyOf(sources));
  }

  /**
   * The last N settled trading days (newest first) strictly before {@code today}. Cliff-guarded: if
   * walking back leaves the bundled calendar coverage (or {@code today} itself is uncovered), the
   * window is truncated rather than throwing — matches the canary's cliff handling.
   */
  private List<LocalDate> recentTradingDays(LocalDate today, int n) {
    List<LocalDate> out = new ArrayList<>();
    try {
      LocalDate cursor = calendar.previousTradingDay(today);
      while (out.size() < n) {
        out.add(cursor);
        cursor = calendar.previousTradingDay(cursor);
      }
    } catch (RuntimeException cliff) {
      log.warn(
          "ingest board: NSE calendar cliff walking back from {} — window truncated to {} day(s)",
          today,
          out.size());
    }
    return out;
  }

  /**
   * The most recent {@code ingest_runs} row per source (a tiny result — one row per distinct source),
   * with a {@code stale} flag for a RUNNING row that never finished and has aged past the shared
   * threshold (the "crashed mid-flight" signal, surfaced on the last-run cell).
   */
  private Map<String, LastRun> lastRunBySource() {
    Instant now = clock.instant();
    return jdbc.query(
        """
        SELECT DISTINCT ON (source) source, status, rows_written, started_at, finished_at, error
        FROM ingest_runs
        ORDER BY source, started_at DESC
        """,
        rs -> {
          Map<String, LastRun> map = new HashMap<>();
          while (rs.next()) {
            String source = rs.getString("source");
            String status = rs.getString("status");
            Instant startedAt = rs.getTimestamp("started_at").toInstant();
            java.sql.Timestamp finishedTs = rs.getTimestamp("finished_at");
            Instant finishedAt = finishedTs == null ? null : finishedTs.toInstant();
            boolean stale =
                "RUNNING".equals(status)
                    && finishedAt == null
                    && Duration.between(startedAt, now).compareTo(runningStale) > 0;
            map.put(
                source,
                new LastRun(
                    status,
                    (Long) rs.getObject("rows_written"),
                    startedAt,
                    finishedAt,
                    rs.getString("error"),
                    stale));
          }
          return map;
        });
  }

  /** Last-resort headline when there is no trading-day axis to evaluate (calendar cliff only). */
  private static String statusFromLastRun(LastRun lr) {
    if (lr == null || lr.stale()) {
      return RED;
    }
    return switch (lr.status()) {
      case "SUCCESS" -> GREEN;
      case "FAILURE" -> RED;
      default -> YELLOW; // an in-flight RUNNING row that has not yet aged
    };
  }
}
