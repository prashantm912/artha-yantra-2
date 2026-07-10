package in.arthayantra.marketdata.canary;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The T+1 ingest-coverage canary (app-platform audit 2026-07-10 §8 V9 / §9.1). Every trading
 * weekday morning it asks the {@link IngestRunLedger} the one question the ledger exists to answer:
 * did each expected batch source actually produce its rows for the last trading day? bhavcopy
 * self-heals so it can't tell an error from a holiday; FII/DII/participant/screeners had NOTHING
 * watching them at all. This closes that hole with an expected-source × trading-day matrix and one
 * ntfy push on any gap.
 *
 * <p>Per-source expectation (verified against the A4 writers' live semantics, 2026-07-10):
 *
 * <ul>
 *   <li><b>REQUIRE_SUCCESS</b> (NSE FII/DII, participant-OI, FII-derivative, bhavcopy, instrument
 *       sync) — ≥1 {@code SUCCESS} row in the day (boot-pull count varies with restarts, so the
 *       floor is one, not two). No success ⇒ RED.
 *   <li><b>SCREENER</b> (Minervini, Manas) — a {@code SUCCESS} with {@code rows_written > 0} is
 *       GREEN; a {@code SUCCESS} with 0 rows is a data-starved skip ⇒ YELLOW (audit reviewer note:
 *       an empty screen is not the same as a healthy one). No success ⇒ RED.
 *   <li><b>CAPTURE</b> (options snapshot) — exactly one accumulating row per IST session day; RED
 *       unless it is present with {@code rows_written > 0}.
 * </ul>
 *
 * <p>Universal to every source: a stuck {@code RUNNING} row (never finished, older than
 * {@code running-stale-minutes}) with no matching {@code SUCCESS} means the run crashed mid-flight —
 * RED. A healthy {@code SUCCESS} in the same day wins over a sibling crash (the data landed).
 *
 * <p>IST-day derivation is from the clock instant via {@link Ist#ZONE} — never {@code ::date} /
 * {@code CURRENT_DATE} in SQL (the container clock is UTC, so a 02:xx-IST row is the previous
 * calendar day there). The window is explicit {@code +05:30} instant bounds.
 *
 * <p>Like every {@code @Scheduled} canary here, a missed cron while the stack is down NEVER fires
 * later (batch-liveness trap) — the dead-man heartbeat, not catch-up logic, covers a full outage.
 * The sweep is live-only (the audited sources run only in the live profile); {@link
 * #evaluate(LocalDate)} is profile-agnostic so it stays unit-testable.
 */
@Component
public class IngestCoverageCanary {

  /** A checked ingest source and the rule its rows are judged by. */
  public enum Policy {
    /** ≥1 SUCCESS row required (latest-only pulls + full dumps). */
    REQUIRE_SUCCESS,
    /** SUCCESS with rows &gt; 0 is green; SUCCESS with 0 rows is a data-starved YELLOW. */
    SCREENER,
    /** Exactly one accumulating per-day row; green only when it captured rows. */
    CAPTURE
  }

  /** One expected source in the T+1 matrix. */
  public record ExpectedSource(String source, Policy policy) {}

  /** The verdict for one source: {@code GREEN} / {@code YELLOW} / {@code RED} + a human detail. */
  public record SourceCoverage(String source, String status, String detail) {}

  /** The whole morning report for {@code tradingDay}: worst-of the per-source statuses. */
  public record IngestCoverageReport(
      LocalDate tradingDay, String status, List<SourceCoverage> sources) {}

  /** One raw ledger row projected for evaluation. */
  private record RunRow(
      String source, String status, Long rowsWritten, Instant startedAt, Instant finishedAt) {}

  static final String GREEN = "GREEN";
  static final String YELLOW = "YELLOW";
  static final String RED = "RED";

  // The expected-source × trading-day matrix (audit §8 V9). Order drives the report + alert message.
  static final List<ExpectedSource> EXPECTED =
      List.of(
          new ExpectedSource(IngestRunLedger.SOURCE_NSE_FII_DII, Policy.REQUIRE_SUCCESS),
          new ExpectedSource(IngestRunLedger.SOURCE_NSE_PARTICIPANT_OI, Policy.REQUIRE_SUCCESS),
          new ExpectedSource(IngestRunLedger.SOURCE_NSE_FII_DERIVATIVE, Policy.REQUIRE_SUCCESS),
          new ExpectedSource(IngestRunLedger.SOURCE_BHAVCOPY, Policy.REQUIRE_SUCCESS),
          new ExpectedSource(IngestRunLedger.SOURCE_INSTRUMENT_SYNC, Policy.REQUIRE_SUCCESS),
          new ExpectedSource(IngestRunLedger.SOURCE_MINERVINI_SCREEN, Policy.SCREENER),
          new ExpectedSource(IngestRunLedger.SOURCE_MANAS_SCREEN, Policy.SCREENER),
          new ExpectedSource(IngestRunLedger.SOURCE_OPTIONS_SNAPSHOT_CAPTURE, Policy.CAPTURE));

  private static final Logger log = LoggerFactory.getLogger(IngestCoverageCanary.class);

  private final JdbcTemplate jdbc;
  private final NtfyClient ntfy;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final Counter gapCounter;
  private final boolean live;
  private final boolean alertsEnabled;
  private final Duration runningStale;

  /** Wires the ledger reader, alerting, the trading-calendar and the tunable thresholds. */
  public IngestCoverageCanary(
      JdbcTemplate jdbc,
      NtfyClient ntfy,
      MarketCalendar calendar,
      Clock clock,
      MeterRegistry meterRegistry,
      Environment environment,
      @Value("${artha.ingest-canary.alerts-enabled:true}") boolean alertsEnabled,
      @Value("${artha.ingest-canary.running-stale-minutes:120}") long runningStaleMinutes) {
    this.jdbc = jdbc;
    this.ntfy = ntfy;
    this.calendar = calendar;
    this.clock = clock;
    this.gapCounter = meterRegistry.counter("ay_ingest_coverage_gap_total");
    this.live = environment.matchesProfiles("live");
    this.alertsEnabled = alertsEnabled;
    this.runningStale = Duration.ofMinutes(runningStaleMinutes);
  }

  /**
   * The morning sweep (08:45 IST, weekdays). Checks the last trading day strictly before today, and
   * skips entirely on a non-trading day (a weekday holiday). Live-only: the audited sources run only
   * in the live profile, so a mock stack would false-RED every morning.
   */
  @Scheduled(cron = "${artha.ingest-canary.cron:0 45 8 * * MON-FRI}", zone = "Asia/Kolkata")
  public void sweep() {
    if (!live) {
      return;
    }
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    // Trading-day gating: skip on a non-trading day, and derive the target within coverage. Both
    // calls throw once dates leave the bundled calendar (CD-2 cliff) — guard so a stale calendar
    // disables THIS canary quietly rather than killing the @Scheduled tick (the cliff has its own
    // horizon canary).
    LocalDate target;
    try {
      if (!calendar.isTradingDay(today)) {
        return;
      }
      target = calendar.previousTradingDay(today);
    } catch (RuntimeException calendarCliff) {
      log.warn("ingest canary: NSE calendar does not cover {} — skipping (calendar-cliff)", today);
      return;
    }
    IngestCoverageReport report;
    try {
      report = evaluate(target);
    } catch (RuntimeException evalFailure) {
      log.warn("ingest canary sweep failed: {}", evalFailure.getMessage());
      return;
    }
    publish(report);
  }

  /**
   * Evaluate the expected-source matrix for {@code tradingDay}: query that day's ledger rows (batch
   * sources by {@code started_at}, the capture source by its per-day {@code window_start} key) and
   * apply each source's policy. Profile-agnostic and side-effect-free — the sweep and the tests both
   * call it.
   */
  public IngestCoverageReport evaluate(LocalDate tradingDay) {
    Instant dayStart = tradingDay.atStartOfDay(Ist.ZONE).toInstant();
    Instant dayEnd = tradingDay.plusDays(1).atStartOfDay(Ist.ZONE).toInstant();
    Map<String, List<RunRow>> bySource =
        queryRows(dayStart, dayEnd).stream().collect(Collectors.groupingBy(RunRow::source));
    Instant now = clock.instant();

    List<SourceCoverage> results = new ArrayList<>();
    for (ExpectedSource expected : EXPECTED) {
      results.add(assess(expected, bySource.getOrDefault(expected.source(), List.of()), now));
    }
    String overall =
        results.stream().anyMatch(r -> RED.equals(r.status()))
            ? RED
            : results.stream().anyMatch(r -> YELLOW.equals(r.status())) ? YELLOW : GREEN;
    return new IngestCoverageReport(tradingDay, overall, List.copyOf(results));
  }

  private List<RunRow> queryRows(Instant dayStart, Instant dayEnd) {
    // The capture source is keyed by its IST session day (window_start); every other source is
    // stamped by started_at. A single query covers both with source-scoped predicates.
    return jdbc.query(
        """
        SELECT source, status, rows_written, started_at, finished_at
        FROM ingest_runs
        WHERE (source <> 'OPTIONS_SNAPSHOT_CAPTURE' AND started_at >= ? AND started_at < ?)
           OR (source =  'OPTIONS_SNAPSHOT_CAPTURE' AND window_start >= ? AND window_start < ?)
        """,
        (rs, n) ->
            new RunRow(
                rs.getString("source"),
                rs.getString("status"),
                (Long) rs.getObject("rows_written"),
                rs.getTimestamp("started_at").toInstant(),
                instantOrNull(rs.getTimestamp("finished_at"))),
        Timestamp.from(dayStart),
        Timestamp.from(dayEnd),
        Timestamp.from(dayStart),
        Timestamp.from(dayEnd));
  }

  private SourceCoverage assess(ExpectedSource expected, List<RunRow> rows, Instant now) {
    List<RunRow> success = rows.stream().filter(r -> "SUCCESS".equals(r.status())).toList();
    if (!success.isEmpty()) {
      long maxRows =
          success.stream().mapToLong(r -> r.rowsWritten() == null ? 0 : r.rowsWritten()).max().orElse(0);
      return switch (expected.policy()) {
        case REQUIRE_SUCCESS -> green(expected, success.size() + " SUCCESS run(s), " + maxRows + " rows");
        case SCREENER ->
            maxRows > 0
                ? green(expected, "screen wrote " + maxRows + " rows")
                : yellow(expected, "SUCCESS but rows_written=0 — data-starved screen skip");
        case CAPTURE ->
            maxRows > 0
                ? green(expected, "captured " + maxRows + " option rows")
                : red(expected, "capture-session recorded 0 rows on a trading day");
      };
    }
    // No SUCCESS row: distinguish a crashed run from an outright miss.
    boolean agedRunning =
        rows.stream()
            .anyMatch(
                r ->
                    "RUNNING".equals(r.status())
                        && r.finishedAt() == null
                        && Duration.between(r.startedAt(), now).compareTo(runningStale) > 0);
    if (agedRunning) {
      return red(
          expected,
          "a run is stuck RUNNING (> " + runningStale.toMinutes() + "m, never finished) — crashed mid-flight");
    }
    if (rows.isEmpty()) {
      return red(expected, "no ingest run recorded for the trading day");
    }
    String statuses = rows.stream().map(RunRow::status).distinct().collect(Collectors.joining(","));
    return red(expected, rows.size() + " run(s) but none SUCCESS (statuses: " + statuses + ")");
  }

  private void publish(IngestCoverageReport report) {
    List<SourceCoverage> gaps =
        report.sources().stream().filter(s -> !GREEN.equals(s.status())).toList();
    if (gaps.isEmpty()) {
      log.info(
          "ingest coverage GREEN for {} — all {} sources healthy",
          report.tradingDay(),
          report.sources().size());
      return;
    }
    gapCounter.increment(gaps.size());
    String message =
        gaps.stream()
            .map(s -> s.source() + ": " + s.status() + " — " + s.detail())
            .collect(Collectors.joining("\n"));
    boolean anyRed = gaps.stream().anyMatch(s -> RED.equals(s.status()));
    if (anyRed) {
      log.error("ingest coverage RED for {}: {}", report.tradingDay(), message.replace('\n', ';'));
      sendAlert(
          "ArthaYantra ingest coverage: " + gaps.size() + " source gap(s) for " + report.tradingDay(),
          "urgent",
          message);
    } else {
      log.warn("ingest coverage YELLOW for {}: {}", report.tradingDay(), message.replace('\n', ';'));
      sendAlert(
          "ArthaYantra ingest coverage: " + gaps.size() + " source(s) degraded for " + report.tradingDay(),
          "default",
          message);
    }
  }

  private void sendAlert(String title, String priority, String message) {
    if (!alertsEnabled) {
      return;
    }
    ntfy.send(title, priority, message);
  }

  private SourceCoverage green(ExpectedSource e, String detail) {
    return new SourceCoverage(e.source(), GREEN, detail);
  }

  private SourceCoverage yellow(ExpectedSource e, String detail) {
    return new SourceCoverage(e.source(), YELLOW, detail);
  }

  private SourceCoverage red(ExpectedSource e, String detail) {
    return new SourceCoverage(e.source(), RED, detail);
  }

  private static Instant instantOrNull(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
