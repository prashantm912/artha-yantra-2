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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * "Can I shut the machine down yet?" — the owner's own ask: a routine that checks, once, shortly
 * before the hard 19:00 IST shutdown, whether TODAY's evening batch chain has actually finished —
 * and pushes exactly one ntfy message reporting either "chain complete N/N" or naming what is still
 * outstanding. This is deliberately a DIFFERENT question from {@link IngestCoverageCanary}/{@link
 * IngestHealthBoard}: those are a T+1 EOD board — they evaluate YESTERDAY's coverage, the morning
 * after, and {@link IngestHealthBoard} explicitly windows strictly BEFORE today so an in-flight
 * batch never false-REDs the morning view. Nothing in this service answers "is TODAY's chain done
 * yet" before this class; that gap is the whole point.
 *
 * <p><b>The expected-source list is the ONE place both consumers read.</b> {@link #report()} is
 * side-effect-free and is the single source of truth for both the scheduled ntfy push ({@link
 * #check()}) and the {@code GET /api/v1/market/health/evening-chain} read surface the Data-Ops page
 * consumes — the source list ({@link #EXPECTED}) lives only here, not duplicated in the controller
 * or the frontend.
 *
 * <p><b>Deliberately NOT {@link IngestCoverageCanary#EXPECTED}.</b> That list also carries {@code
 * INSTRUMENT_SYNC} (08:30 IST, a morning job) and {@code OPTIONS_SNAPSHOT_CAPTURE} (a continuous
 * intraday capture that stops accumulating at the 15:30 close) — neither is an evening batch, so
 * reusing that list would report "still pending" on two sources that never run in the evening at
 * all. This list is the nine sources that write to {@code ingest_runs} for the evening chain
 * (verified against each job's ledger call site 2026-08-11): the three {@code NseEodScheduler}
 * pulls, the bhavcopy backfill, the two swing screeners, and the three EOD analytics folds.
 * {@code MINERVINI_SCREEN}'s own {@code ingest_runs} boundary was moved (2026-08-11, same review)
 * to close only AFTER {@code MinerviniScheduler}'s plane-divergence probe finishes, specifically so
 * this class never has to know that sub-step exists — the ledger row already means "this leg is
 * fully done", not "the screen write happened".
 *
 * <p><b>Never derives completion from the clock.</b> "Is source X done" is answered ONLY from its
 * {@code ingest_runs} row for today, never from wall-clock time — a change to when the evening jobs
 * fire needs no change here.
 *
 * <p><b>A source counts as done only in {@link SourceState#DONE}.</b> {@link SourceState#STUCK} — a
 * {@code RUNNING} row aged past {@code artha.ingest-canary.running-stale-minutes} (the SAME
 * threshold {@link IngestHealthBoard} and {@link IngestCoverageCanary} use — one knob, never let it
 * drift) — is OUTSTANDING, exactly like {@link SourceState#PENDING}: it blocks {@link
 * ChainReport#complete()} and is named in the push. ⚠️ An earlier version of this class let STUCK
 * count as "resolved" (on the reasoning that nothing more is coming from a crashed job either way),
 * which is precisely backwards for what this report is FOR — an orphaned RUNNING row (a container
 * recreate mid-job, no reaper: the MANAS_SCREEN finding) would have produced "chain complete 9/9 —
 * safe to shut down" while a job never actually finished, which is the exact false-safe verdict the
 * whole feature exists to prevent. Caught in cross-vendor review before this shipped.
 *
 * <p><b>The push fires at most once per IST day</b>, via a provisional-claim protocol identical in
 * shape to {@link IngestCoverageCanary}'s (a {@code canary_runs} row goes {@code CLAIMED} then
 * {@code DONE}; a claim older than {@link #CLAIM_LEASE} is stealable) — but simpler, because there is
 * only ONE door here (a single scheduled fire, no boot-catchup: if the stack is down nobody is
 * waiting on a push). The row is confirmed {@code DONE} only once {@link NtfyClient#trySend} reports
 * the delivery actually succeeded (or alerts are administratively off, in which case there is
 * nothing to retry toward); a delivery failure leaves the claim {@code CLAIMED} and {@link
 * #scheduleReclaim} retries once the lease expires, up to {@link #MAX_RECLAIM_ATTEMPTS}. Without
 * this, a marker committed BEFORE send (the earlier design) could lose that evening's message
 * entirely to one failed POST, silently and permanently.
 *
 * <p><b>Runs on the dedicated {@code monitorTaskScheduler} pool</b> ({@link MonitorSchedulingConfig}
 * BEJ-01), not the shared default one — it is exactly the detector that must notice a hung batch
 * job, so it cannot itself be starved by one.
 *
 * <p>Default ON — this is exactly what the owner asked for; {@code
 * artha.evening-chain.alerts-enabled} still lets the ntfy send be disabled independently (mirrors
 * every other canary here) without disabling the page-facing {@link #report()}, which is never
 * gated — the page must always show live status.
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
   * Today's whole-chain report. {@code complete} is true iff EVERY source is {@link
   * SourceState#DONE} — PENDING and STUCK are both outstanding and both block completion. On a
   * non-trading day {@code tradingDay} is false and {@code sources} is empty (nothing was expected,
   * so there is nothing to wait on).
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

  /**
   * Why a claim attempt ended the way it did — same shape as {@link IngestCoverageCanary}'s, see
   * its javadoc for the FINISHED-vs-HELD rationale (a HELD claim just means "not yet", a FINISHED
   * one is final and correctly silences this door forever).
   */
  private record ClaimAttempt(boolean won, boolean finished, Instant heldUntil) {

    static ClaimAttempt taken() {
      return new ClaimAttempt(true, false, null);
    }

    static ClaimAttempt alreadyPublished() {
      return new ClaimAttempt(false, true, null);
    }

    static ClaimAttempt blockedUntil(Instant until) {
      return new ClaimAttempt(false, false, until);
    }
  }

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

  /**
   * Default single-shot check time. Owner decision (2026-08-11, since the original brief): a HARD
   * 19:00 IST shutdown, with the evening batch chain itself moving to a compressed 18:20-18:59
   * single-shot window (no polling). The only slot that can answer "is it done" without either
   * catching jobs mid-flight (a check inside 18:20-18:59 could fire before a job scheduled for
   * 18:58 has even started) or missing the shutdown deadline is the ~60s gap between the two:
   * right at 18:59, immediately after the jobs' own window closes, with up to a minute of margin
   * before 19:00. Pinned by {@link
   * in.arthayantra.marketdata.canary.EveningChainCanaryIntegrationTest#defaultCheckCronFiresAtTheEndOfThePreShutdownWindow}
   * so a future edit cannot silently drift it back outside the safe slot.
   */
  static final String DEFAULT_CHECK_CRON = "0 59 18 * * MON-FRI";

  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_FAILURE = "FAILURE";

  /** {@code canary_runs.canary} key for this canary's per-IST-day push marker. */
  public static final String CANARY_KEY = "EVENING_CHAIN";

  private static final String STATE_CLAIMED = "CLAIMED";
  private static final String STATE_DONE = "DONE";
  private static final String SOURCE_TAG = "EVENING_CHAIN_CHECK";

  /**
   * How long a {@code CLAIMED} row suppresses a reclaim before it is treated as an attempt that
   * died mid-publish and becomes stealable. Same sizing rationale as {@link
   * IngestCoverageCanary#CLAIM_LEASE}: it only has to exceed the longest a publish can legally
   * take (one best-effort ntfy POST, bounded by the module's HTTP client timeouts).
   */
  static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

  /** Margin added when re-checking a lease, since the claim's staleness test is strict ({@code <}). */
  static final Duration RECLAIM_SLACK = Duration.ofSeconds(5);

  /** Bound on the self-rescheduling reclaim chain — three attempts is generous for a single evening. */
  static final int MAX_RECLAIM_ATTEMPTS = 3;

  private static final Logger log = LoggerFactory.getLogger(EveningChainCanary.class);

  private final JdbcTemplate jdbc;
  private final NtfyClient ntfy;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final TaskScheduler reclaimScheduler;
  private final boolean live;
  private final boolean enabled;
  private final boolean alertsEnabled;
  private final Duration runningStale;

  /** Wires the ledger reader, alerting, the claim/lease marker, the calendar and clock. */
  public EveningChainCanary(
      JdbcTemplate jdbc,
      NtfyClient ntfy,
      MarketCalendar calendar,
      Clock clock,
      Environment environment,
      // The detector pool (MAJOR 4, BEJ-01): a bounded read + one best-effort push is exactly the
      // species that pool is fenced for, and this canary must not be starvable by the very batch
      // jobs it is checking on.
      @Qualifier("monitorTaskScheduler") TaskScheduler reclaimScheduler,
      @Value("${artha.evening-chain.enabled:true}") boolean enabled,
      @Value("${artha.evening-chain.alerts-enabled:true}") boolean alertsEnabled,
      // Same property + default as IngestHealthBoard/IngestCoverageCanary's aged-RUNNING rule — one
      // knob, so "stuck" can never mean something different here than on the ingest-health page.
      @Value("${artha.ingest-canary.running-stale-minutes:120}") long runningStaleMinutes) {
    this.jdbc = jdbc;
    this.ntfy = ntfy;
    this.calendar = calendar;
    this.clock = clock;
    this.reclaimScheduler = reclaimScheduler;
    this.live = environment.matchesProfiles("live");
    this.enabled = enabled;
    this.alertsEnabled = alertsEnabled;
    this.runningStale = Duration.ofMinutes(runningStaleMinutes);
  }

  /**
   * The single pre-shutdown check (default 18:59 IST, weekdays — see {@link #DEFAULT_CHECK_CRON}).
   * Live-only and behind {@code artha.evening-chain.enabled}; {@link #report()} itself is never
   * gated. Bound to {@code monitorTaskScheduler} (MAJOR 4) rather than the shared default pool.
   */
  @Scheduled(
      cron = "${artha.evening-chain.check-cron:" + DEFAULT_CHECK_CRON + "}",
      zone = "Asia/Kolkata",
      scheduler = "monitorTaskScheduler")
  public void check() {
    if (!live || !enabled) {
      return;
    }
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    runOnce(today, 0);
  }

  /**
   * Today's chain report (IST). Side-effect-free — the GET and {@link #check()} both call this. On a
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
    // Only DONE counts as finished — STUCK is outstanding, exactly like PENDING (review Critical 1:
    // an orphaned RUNNING row must never read as "safe to shut down").
    int done = (int) sources.stream().filter(s -> s.state() == SourceState.DONE).count();
    boolean complete = done == sources.size();
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

  /**
   * The evaluate → claim → publish protocol (mirrors {@link IngestCoverageCanary#runOnce}, minus
   * the boot-catchup door this canary does not need). {@code report()} runs first because it is
   * side-effect-free — a transient read failure returns before anything is claimed, so the check
   * stays retryable. The claim gates the single side-effecting step (the push); it is provisional
   * until {@link NtfyClient#trySend} confirms delivery, so a crash or a failed POST between claim
   * and confirm leaves the row {@code CLAIMED} and stealable once {@link #CLAIM_LEASE} elapses —
   * {@link #scheduleReclaim} is what comes back for it.
   */
  private void runOnce(LocalDate today, int attempt) {
    ChainReport rep;
    try {
      rep = report();
    } catch (RuntimeException e) {
      log.warn("evening-chain check failed: {}", e.getMessage());
      return;
    }
    if (!rep.tradingDay()) {
      return; // nothing was expected today; nothing to wait on
    }
    ClaimAttempt attempted;
    try {
      attempted = claim(today);
    } catch (RuntimeException claimFailure) {
      log.error(
          "evening-chain: run marker for {} not written ({}) - publishing anyway, de-duplication is"
              + " OFF",
          today,
          claimFailure.getMessage());
      publish(rep);
      return;
    }
    if (!attempted.won()) {
      if (attempted.finished()) {
        log.info("evening-chain: {} was already published - standing down", today);
      } else {
        scheduleReclaim(today, attempted.heldUntil(), attempt);
      }
      return;
    }
    boolean delivered = publish(rep);
    if (delivered) {
      confirmPublished(today);
    } else {
      // The claim stays CLAIMED — a genuine send failure, not a decision to skip. Retry once the
      // lease expires rather than losing the evening's message to one bad POST.
      scheduleReclaim(today, clock.instant().plus(CLAIM_LEASE), attempt);
    }
  }

  /**
   * Builds and sends today's one message. Returns whether the outcome is "confirmed" — either the
   * ntfy push actually succeeded, or alerts are administratively disabled (nothing to retry toward,
   * so the claim should still resolve to DONE rather than spinning reclaims forever).
   */
  private boolean publish(ChainReport rep) {
    List<SourceProgress> pending =
        rep.sources().stream().filter(s -> s.state() == SourceState.PENDING).toList();
    List<SourceProgress> stuck =
        rep.sources().stream().filter(s -> s.state() == SourceState.STUCK).toList();
    List<SourceProgress> failed =
        rep.sources().stream()
            .filter(s -> s.state() == SourceState.DONE && STATUS_FAILURE.equals(s.status()))
            .toList();
    boolean outstanding = !pending.isEmpty() || !stuck.isEmpty();

    String title;
    StringBuilder sb = new StringBuilder();
    if (!outstanding) {
      title = "ArthaYantra evening chain complete";
      sb.append("chain complete ").append(rep.done()).append('/').append(rep.total());
    } else {
      title = "ArthaYantra evening chain still pending";
      List<String> names = new ArrayList<>();
      pending.forEach(s -> names.add(s.source()));
      stuck.forEach(s -> names.add(s.source() + " (stuck)"));
      sb.append("still pending: ").append(String.join(", ", names));
    }
    if (!failed.isEmpty()) {
      sb.append(" — failed: ")
          .append(failed.stream().map(SourceProgress::source).collect(Collectors.joining(", ")));
    }
    String message = sb.toString();

    if (!alertsEnabled) {
      log.info("evening chain (alerts disabled, not sent): {}", message);
      return true; // nothing to deliver, by design — confirm rather than reclaim toward nothing
    }
    boolean sent = ntfy.trySend(title, "default", message);
    if (sent) {
      log.info("evening chain: {}", message);
    } else {
      log.warn("evening-chain push failed to send — will retry once the claim lease expires: {}", message);
    }
    return sent;
  }

  /**
   * Takes the provisional {@code CLAIMED} claim on {@code today}. Wins when there is no row at all,
   * or the existing row is a {@code CLAIMED} one whose lease has expired. Loses against {@code DONE}
   * (already published) and against a fresh {@code CLAIMED} (another door — realistically only a
   * concurrent reclaim — is live right now). Same shape as {@link IngestCoverageCanary#claim}.
   */
  private ClaimAttempt claim(LocalDate today) {
    Instant now = clock.instant();
    int taken =
        jdbc.update(
            """
            INSERT INTO canary_runs (canary, run_day, state, source, claimed_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (canary, run_day) DO UPDATE
               SET state = EXCLUDED.state, source = EXCLUDED.source, claimed_at = EXCLUDED.claimed_at
             WHERE canary_runs.state = ? AND canary_runs.claimed_at < ?
            """,
            CANARY_KEY,
            today,
            STATE_CLAIMED,
            SOURCE_TAG,
            Timestamp.from(now),
            STATE_CLAIMED,
            Timestamp.from(now.minus(CLAIM_LEASE)));
    if (taken == 1) {
      return ClaimAttempt.taken();
    }
    try {
      return jdbc.query(
          "SELECT state, claimed_at FROM canary_runs WHERE canary = ? AND run_day = ?",
          (ResultSetExtractor<ClaimAttempt>)
              rs -> {
                if (!rs.next()) {
                  return ClaimAttempt.blockedUntil(now);
                }
                if (STATE_DONE.equals(rs.getString("state"))) {
                  return ClaimAttempt.alreadyPublished();
                }
                return ClaimAttempt.blockedUntil(
                    rs.getTimestamp("claimed_at").toInstant().plus(CLAIM_LEASE));
              },
          CANARY_KEY,
          today);
    } catch (RuntimeException classificationFailure) {
      log.warn(
          "evening-chain: {} claim lost but the loss could not be classified ({}) - standing down"
              + " and assuming the freshest possible lease",
          today,
          classificationFailure.getMessage());
      return ClaimAttempt.blockedUntil(now.plus(CLAIM_LEASE));
    }
  }

  /** Comes back when the current holder's lease runs out, so a send failure actually gets retried. */
  private void scheduleReclaim(LocalDate today, Instant heldUntil, int attempt) {
    if (attempt >= MAX_RECLAIM_ATTEMPTS) {
      log.error(
          "evening-chain: {} still unresolved after {} reclaim attempts - giving up; the push may"
              + " not have gone out",
          today,
          attempt);
      return;
    }
    Instant retryAt = heldUntil.plus(RECLAIM_SLACK);
    log.warn(
        "evening-chain: {} is held or unsent - re-checking at {} (reclaim attempt {} of {})",
        today,
        retryAt,
        attempt + 1,
        MAX_RECLAIM_ATTEMPTS);
    try {
      reclaimScheduler.schedule(() -> reclaim(today, attempt + 1), retryAt);
    } catch (RuntimeException notQueued) {
      log.error(
          "evening-chain: could not queue the {} reclaim ({}) - will not self-heal this evening",
          today,
          notQueued.getMessage());
    }
  }

  /** The scheduled retry body; never lets a failure escape onto the shared detector pool. */
  private void reclaim(LocalDate today, int attempt) {
    try {
      runOnce(today, attempt);
    } catch (RuntimeException reclaimFailure) {
      log.warn("evening-chain reclaim for {} failed: {}", today, reclaimFailure.getMessage());
    }
  }

  /** Promotes the held claim to {@code DONE} — the only state that suppresses a later door. */
  private void confirmPublished(LocalDate today) {
    try {
      jdbc.update(
          "UPDATE canary_runs SET state = ?, completed_at = ? WHERE canary = ? AND run_day = ?",
          STATE_DONE,
          Timestamp.from(clock.instant()),
          CANARY_KEY,
          today);
    } catch (RuntimeException confirmFailure) {
      log.error(
          "evening-chain: {} published but NOT confirmed DONE ({}) - the claim stays provisional and"
              + " a later door may re-alert",
          today,
          confirmFailure.getMessage());
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
