package in.arthayantra.marketdata.canary;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * "Can I shut the machine down yet?" — the owner's own ask: a routine that keeps checking whether
 * TODAY's evening batch chain has finished, so they can close the stack at 19:00 (or extend it if
 * something is still running). This is deliberately a DIFFERENT question from {@link
 * IngestCoverageCanary}/{@link IngestHealthBoard}: those are a T+1 EOD board — they evaluate
 * YESTERDAY's coverage, the morning after, and {@link IngestHealthBoard} explicitly windows
 * strictly BEFORE today so an in-flight batch never false-REDs the morning view. Nothing in this
 * service answers "is TODAY's chain done yet" before this class; that gap is the whole point.
 *
 * <p><b>The expected-source list is the ONE place both consumers read.</b> {@link #report()} is
 * side-effect-free and is the single source of truth for both the scheduled ntfy push ({@link
 * #poll()}) and the {@code GET /api/v1/market/health/evening-chain} read surface the Data-Ops page
 * consumes — the source list ({@link #EXPECTED}) lives only here, not duplicated in the controller
 * or the frontend.
 *
 * <p><b>Deliberately NOT {@link IngestCoverageCanary#EXPECTED}.</b> That list also carries {@code
 * INSTRUMENT_SYNC} (08:30 IST, a morning job) and {@code OPTIONS_SNAPSHOT_CAPTURE} (a continuous
 * intraday capture that stops accumulating at the 15:30 close) — neither is an evening batch, so
 * reusing that list would report "still pending" on two sources that never run in the evening at
 * all. This list is the nine sources that write to {@code ingest_runs} AFTER the close (verified
 * against each job's {@code @Scheduled} cron 2026-08-11): the three {@code NseEodScheduler} pulls,
 * the bhavcopy backfill, the two swing screeners, and the three EOD analytics folds.
 *
 * <p><b>Never derives completion from the clock.</b> The evening chain's cron TIMES are being moved
 * earlier in a separate, unmerged change (poll-from-18:00 rather than fire-once-at-a-fixed-time) —
 * so "is source X done" is answered ONLY from its {@code ingest_runs} row for today, never from
 * "has it turned 19:45 yet". A change to when the jobs fire needs no change here.
 *
 * <p><b>Stuck vs never-started (the MANAS_SCREEN finding).</b> A source with no row yet today reads
 * PENDING; a source whose latest row today is {@code RUNNING} and still fresh also reads PENDING
 * (it may yet finish); but a {@code RUNNING} row aged past {@code
 * artha.ingest-canary.running-stale-minutes} (the SAME threshold {@link IngestHealthBoard} and
 * {@link IngestCoverageCanary} use — one knob, never let it drift) reads STUCK, not PENDING. Without
 * that distinction an orphaned RUNNING row (a container recreate mid-job, no reaper) would read
 * "in-flight" forever and the chain would never be reported complete.
 *
 * <p><b>The push fires at most once per IST day</b> (a plain Redis {@code setIfAbsent} marker — the
 * same lightweight idempotency {@link in.arthayantra.marketdata.upstox.canary.UpstoxContractCanary}
 * uses; the heavier claim/lease protocol in {@link IngestCoverageCanary} exists only for its
 * boot-catch-up door, which this canary does not need — if the stack is down nobody is waiting on a
 * push). {@link #poll()} runs every few minutes across an evening window: the FIRST poll that finds
 * the chain complete pushes immediately ("chain complete N/N"); if the window's final check time
 * arrives with sources still outstanding, that poll pushes once naming them ("still pending: ...")
 * so the owner is not left waiting on a push that will never come. Default ON — this is exactly what
 * the owner asked for and a nightly "chain complete" ping (or a same-day "still pending" flag) is the
 * intended behaviour, not a false alarm; {@code artha.evening-chain.alerts-enabled} still lets the
 * ntfy send be disabled independently (mirrors every other canary here) without disabling the
 * page-facing {@link #report()}, which is never gated — the page must always show live status.
 */
@Component
public class EveningChainCanary {

  /** Where a source's TODAY run stands. */
  public enum SourceState {
    /** No row yet today, or the latest row is {@code RUNNING} and not yet aged — may still arrive. */
    PENDING,
    /** The latest row is {@code RUNNING}, never finished, and aged past the shared stale threshold. */
    STUCK,
    /** The latest row today reached a terminal status ({@code SUCCESS} or {@code FAILURE}). */
    DONE
  }

  /** One expected source's today-progress: its state plus the raw last-run detail for display. */
  public record SourceProgress(
      String source,
      SourceState state,
      @Schema(types = {"string", "null"}) String status,
      @Schema(types = {"string", "null"}) Instant startedAt,
      @Schema(types = {"string", "null"}) Instant finishedAt) {}

  /**
   * Today's whole-chain report. {@code complete} is true iff no source is {@link SourceState#PENDING}
   * — a STUCK source does not block completion (nothing more is coming from it either), it is
   * reported so the owner can see it named. On a non-trading day {@code tradingDay} is false and
   * {@code sources} is empty (nothing was expected, so there is nothing to wait on).
   */
  public record ChainReport(
      Instant generatedAt,
      LocalDate day,
      boolean tradingDay,
      int total,
      int done,
      boolean complete,
      List<SourceProgress> sources) {}

  /** One raw {@code ingest_runs} row for today, projected for classification. */
  private record RunRow(String source, String status, Instant startedAt, Instant finishedAt) {}

  // The evening batch chain (owner's words: "followup jobs" to close the day). See the class
  // javadoc for why this is its own list rather than IngestCoverageCanary.EXPECTED.
  static final List<String> EXPECTED =
      List.of(
          IngestRunLedger.SOURCE_NSE_FII_DII,
          IngestRunLedger.SOURCE_NSE_PARTICIPANT_OI,
          IngestRunLedger.SOURCE_NSE_FII_DERIVATIVE,
          IngestRunLedger.SOURCE_BHAVCOPY,
          IngestRunLedger.SOURCE_MARKET_CONTEXT_DAY,
          IngestRunLedger.SOURCE_DATA_QUALITY,
          IngestRunLedger.SOURCE_MINERVINI_SCREEN,
          IngestRunLedger.SOURCE_MANAS_SCREEN,
          IngestRunLedger.SOURCE_EQUITY_BREADTH);

  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_FAILURE = "FAILURE";
  private static final String REDIS_KEY_PREFIX = "evening-chain:pushed:";
  private static final Duration REDIS_MARKER_TTL = Duration.ofDays(3);

  private static final Logger log = LoggerFactory.getLogger(EveningChainCanary.class);

  private final JdbcTemplate jdbc;
  private final NtfyClient ntfy;
  private final StringRedisTemplate redis;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final boolean live;
  private final boolean enabled;
  private final boolean alertsEnabled;
  private final Duration runningStale;
  private final LocalTime finalCheckTime;

  /** Wires the ledger reader, alerting, the once-per-day Redis marker, the calendar and clock. */
  public EveningChainCanary(
      JdbcTemplate jdbc,
      NtfyClient ntfy,
      StringRedisTemplate redis,
      MarketCalendar calendar,
      Clock clock,
      Environment environment,
      @Value("${artha.evening-chain.enabled:true}") boolean enabled,
      @Value("${artha.evening-chain.alerts-enabled:true}") boolean alertsEnabled,
      // Same property + default as IngestHealthBoard/IngestCoverageCanary's aged-RUNNING rule — one
      // knob, so "stuck" can never mean something different here than on the ingest-health page.
      @Value("${artha.ingest-canary.running-stale-minutes:120}") long runningStaleMinutes,
      @Value("${artha.evening-chain.final-check-time:21:55}") String finalCheckTime) {
    this.jdbc = jdbc;
    this.ntfy = ntfy;
    this.redis = redis;
    this.calendar = calendar;
    this.clock = clock;
    this.live = environment.matchesProfiles("live");
    this.enabled = enabled;
    this.alertsEnabled = alertsEnabled;
    this.runningStale = Duration.ofMinutes(runningStaleMinutes);
    this.finalCheckTime = LocalTime.parse(finalCheckTime);
  }

  /**
   * The poll (every 5 minutes, 18:00-21:59 IST, weekdays — generous enough to survive the evening
   * chain's cron times moving earlier without needing to change). Live-only and behind {@code
   * artha.evening-chain.enabled}; {@link #report()} itself is never gated — this only decides
   * whether/when to PUSH.
   */
  @Scheduled(
      cron = "${artha.evening-chain.poll-cron:0 */5 18-21 * * MON-FRI}",
      zone = "Asia/Kolkata")
  public void poll() {
    if (!live || !enabled) {
      return;
    }
    ZonedDateTime nowIst = clock.instant().atZone(Ist.ZONE);
    LocalDate today = nowIst.toLocalDate();
    ChainReport rep;
    try {
      rep = report();
    } catch (RuntimeException e) {
      log.warn("evening-chain poll failed: {}", e.getMessage());
      return;
    }
    if (!rep.tradingDay()) {
      return; // nothing was expected today; nothing to wait on
    }
    boolean finalCheck = !nowIst.toLocalTime().isBefore(finalCheckTime);
    if (!rep.complete() && !finalCheck) {
      return; // still waiting — try again on the next poll
    }
    maybePublish(rep, today);
  }

  /**
   * Today's chain report (IST). Side-effect-free — the GET and {@link #poll()} both call this. On a
   * non-trading day (weekend/holiday, or a calendar-cliff year) returns {@code tradingDay=false} and
   * an empty source list rather than reporting every source PENDING, which would misread a holiday as
   * a stall.
   */
  public ChainReport report() {
    Instant generatedAt = clock.instant();
    LocalDate today = generatedAt.atZone(Ist.ZONE).toLocalDate();
    if (!isTradingDaySafe(today)) {
      return new ChainReport(generatedAt, today, false, 0, 0, true, List.of());
    }
    Instant dayStart = today.atStartOfDay(Ist.ZONE).toInstant();
    Instant dayEnd = today.plusDays(1).atStartOfDay(Ist.ZONE).toInstant();
    Map<String, List<RunRow>> bySource =
        queryRows(dayStart, dayEnd).stream().collect(Collectors.groupingBy(RunRow::source));

    List<SourceProgress> sources = new ArrayList<>();
    for (String source : EXPECTED) {
      sources.add(classify(source, bySource.get(source), generatedAt));
    }
    int done = (int) sources.stream().filter(s -> s.state() != SourceState.PENDING).count();
    boolean complete = sources.stream().noneMatch(s -> s.state() == SourceState.PENDING);
    return new ChainReport(generatedAt, today, true, EXPECTED.size(), done, complete, List.copyOf(sources));
  }

  private SourceProgress classify(String source, List<RunRow> rows, Instant now) {
    if (rows == null || rows.isEmpty()) {
      return new SourceProgress(source, SourceState.PENDING, null, null, null);
    }
    RunRow latest = rows.stream().max(Comparator.comparing(RunRow::startedAt)).orElseThrow();
    if (STATUS_RUNNING.equals(latest.status()) && latest.finishedAt() == null) {
      boolean aged = Duration.between(latest.startedAt(), now).compareTo(runningStale) > 0;
      return new SourceProgress(
          source,
          aged ? SourceState.STUCK : SourceState.PENDING,
          latest.status(),
          latest.startedAt(),
          null);
    }
    return new SourceProgress(
        source, SourceState.DONE, latest.status(), latest.startedAt(), latest.finishedAt());
  }

  private List<RunRow> queryRows(Instant dayStart, Instant dayEnd) {
    return jdbc.query(
        "SELECT source, status, started_at, finished_at FROM ingest_runs "
            + "WHERE started_at >= ? AND started_at < ?",
        (rs, n) ->
            new RunRow(
                rs.getString("source"),
                rs.getString("status"),
                rs.getTimestamp("started_at").toInstant(),
                instantOrNull(rs.getTimestamp("finished_at"))),
        Timestamp.from(dayStart),
        Timestamp.from(dayEnd));
  }

  /** Builds and sends the one push for {@code today}, guarded by the once-per-day Redis marker. */
  private void maybePublish(ChainReport rep, LocalDate today) {
    if (!claimPushMarker(today)) {
      return; // already pushed today (or the marker write itself failed — see claimPushMarker)
    }
    List<SourceProgress> pending =
        rep.sources().stream().filter(s -> s.state() == SourceState.PENDING).toList();
    List<SourceProgress> stuck =
        rep.sources().stream().filter(s -> s.state() == SourceState.STUCK).toList();
    List<SourceProgress> failed =
        rep.sources().stream()
            .filter(s -> s.state() == SourceState.DONE && STATUS_FAILURE.equals(s.status()))
            .toList();

    String title;
    String message;
    if (pending.isEmpty()) {
      title = "ArthaYantra evening chain complete";
      StringBuilder sb = new StringBuilder("chain complete " + rep.done() + "/" + rep.total());
      appendIssues(sb, stuck, failed);
      message = sb.toString();
      log.info("evening chain: {}", message);
    } else {
      title = "ArthaYantra evening chain still pending";
      StringBuilder sb =
          new StringBuilder(
              "still pending: "
                  + pending.stream().map(SourceProgress::source).collect(Collectors.joining(", ")));
      appendIssues(sb, stuck, failed);
      message = sb.toString();
      log.warn("evening chain: {}", message);
    }
    if (alertsEnabled) {
      ntfy.send(title, "default", message);
    }
  }

  private static void appendIssues(
      StringBuilder sb, List<SourceProgress> stuck, List<SourceProgress> failed) {
    if (!stuck.isEmpty()) {
      sb.append(" — stuck: ")
          .append(stuck.stream().map(SourceProgress::source).collect(Collectors.joining(", ")));
    }
    if (!failed.isEmpty()) {
      sb.append(" — failed: ")
          .append(failed.stream().map(SourceProgress::source).collect(Collectors.joining(", ")));
    }
  }

  /** Atomically claims today's push slot; {@code false} means another poll already sent it. */
  private boolean claimPushMarker(LocalDate today) {
    try {
      Boolean first =
          redis.opsForValue().setIfAbsent(REDIS_KEY_PREFIX + today, "1", REDIS_MARKER_TTL);
      return Boolean.TRUE.equals(first);
    } catch (RuntimeException redisFailure) {
      // Fail toward NOT pushing again this poll cycle rather than risking a marker that never
      // commits and re-alerts every 5 minutes; the next poll retries the same claim.
      log.warn("evening-chain push marker write failed for {}: {}", today, redisFailure.getMessage());
      return false;
    }
  }

  private boolean isTradingDaySafe(LocalDate day) {
    try {
      return calendar.isTradingDay(day);
    } catch (IllegalArgumentException calendarCliff) {
      log.warn("evening-chain: NSE calendar does not cover {} — skipping (calendar-cliff)", day);
      return false;
    }
  }

  private static Instant instantOrNull(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
