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
import java.time.ZonedDateTime;
import java.util.ArrayList;
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
import org.springframework.scheduling.support.CronExpression;
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
 * <p><b>Missed-cron catch-up (task_e2e01c).</b> Spring never replays a {@code @Scheduled} fire that
 * ticked while the stack was down, and the owner's machine routinely boots ~08:56 IST — so on every
 * late boot the morning canary that exists to catch a broken overnight batch was exactly the thing
 * that did not run (E2E audit 2026-07-31 §2.1: stack down 02:29–08:56). {@link #catchUpIfMissed()}
 * is the boot-time replay: if today's cron fire has already passed and no run is recorded for today,
 * it sweeps once. "Already ran" is grounded in the durable {@code canary_runs} row (V052 — renumbered from V050 after marketdata V051 merged first via #1156), never an
 * in-memory flag — an in-memory flag is defeated by exactly the restart this exists for. The
 * dead-man heartbeat still covers a full outage; this covers the late-boot morning.
 *
 * <p>The scheduled fire and the boot catch-up are two doors onto ONE protocol, {@link #runOnce} —
 * evaluate, then claim, then publish, then confirm — and neither reaches {@code publish} any other
 * way. That ordering is what makes an overlapping boot and cron publish exactly once between them,
 * and the claim is provisional ({@code CLAIMED} → {@code DONE}) so that only a CONFIRMED publication
 * ever suppresses a later door. Nothing here may turn "we meant to alert" into "we already did".
 *
 * <p>The sweep is live-only (the audited sources run only in the live profile); {@link
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

  /**
   * Why a claim attempt ended the way it did. Losing is NOT one outcome: {@code FINISHED} is final
   * and correctly silences this door forever, whereas {@code HELD} only means "not yet" and must be
   * revisited when the holder's lease runs out.
   */
  private record ClaimAttempt(boolean won, boolean finished, Instant heldUntil) {

    // NB: factory names must differ from the component accessors (won/finished/heldUntil) — a
    // same-name static in a record is rejected as an "invalid accessor method".
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

  static final String GREEN = "GREEN";
  static final String YELLOW = "YELLOW";
  static final String RED = "RED";

  /** {@code canary_runs.canary} key for this canary's per-IST-day run marker (V052 — renumbered from V050 after marketdata V051 merged first via #1156). */
  public static final String CANARY_KEY = "INGEST_COVERAGE";

  /**
   * How long a {@code CLAIMED} row suppresses other doors before it is treated as an attempt that
   * died mid-publish and becomes stealable. It only has to exceed the longest a publish can legally
   * take: one best-effort ntfy POST, bounded by the module's {@code spring.http.client} 3 s connect +
   * 60 s read backstop. ⚠️ If that HTTP read timeout is ever raised past this, raise this with it.
   *
   * <p>This constant now sizes TWO unrelated things, and they happen to want the same answer. Too
   * SHORT and a live publisher gets cut off (duplicate alert); too LONG and a crashed morning stays
   * silent that much longer, because {@link #scheduleReclaim} cannot act before the lease expires.
   * Both are minimised by the same choice — the shortest interval that safely exceeds one publish —
   * so they do not want different numbers. Five minutes is ~5× the 63 s bound, and five minutes of
   * delay on a pre-open batch-health alert costs nothing (the sweep runs ~08:45, the market opens at
   * 09:15). If they ever diverge — say the publish bound grows to tens of minutes — split them: the
   * lease stays sized to the publish, and the reclaim gets its own shorter poll.
   */
  static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

  /**
   * Margin added when re-checking a lease, since the claim's staleness test is strict ({@code <}) and
   * a scheduler fires approximately.
   */
  static final Duration RECLAIM_SLACK = Duration.ofSeconds(5);

  /**
   * Bound on the self-rescheduling reclaim chain. Each extra hop needs ANOTHER door to have taken the
   * claim in the meantime, so three is already generous; the cap exists so a pathological loop cannot
   * outlive the morning. Exhaustion is an ERROR, not a shrug.
   */
  static final int MAX_RECLAIM_ATTEMPTS = 3;

  private static final String STATE_CLAIMED = "CLAIMED";
  private static final String STATE_DONE = "DONE";
  private static final String SOURCE_SCHEDULED = "SCHEDULED";
  private static final String SOURCE_BOOT_CATCHUP = "BOOT_CATCHUP";

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
  private final String cron;
  private final TaskScheduler reclaimScheduler;

  /** Wires the ledger reader, alerting, the trading-calendar and the tunable thresholds. */
  public IngestCoverageCanary(
      JdbcTemplate jdbc,
      NtfyClient ntfy,
      MarketCalendar calendar,
      Clock clock,
      MeterRegistry meterRegistry,
      Environment environment,
      // the detector pool, same one MorningCanaryCatchUp dispatches on — a reclaim is a bounded
      // read + one best-effort push, exactly the species that pool is fenced for
      @Qualifier("monitorTaskScheduler") TaskScheduler reclaimScheduler,
      @Value("${artha.ingest-canary.alerts-enabled:true}") boolean alertsEnabled,
      @Value("${artha.ingest-canary.running-stale-minutes:120}") long runningStaleMinutes,
      // Same property + default as the @Scheduled below — the catch-up derives "has today's fire
      // already passed?" from the ACTUAL schedule, so the two can never drift apart.
      @Value("${artha.ingest-canary.cron:0 45 8 * * MON-FRI}") String cron) {
    this.jdbc = jdbc;
    this.ntfy = ntfy;
    this.calendar = calendar;
    this.clock = clock;
    this.gapCounter = meterRegistry.counter("ay_ingest_coverage_gap_total");
    this.live = environment.matchesProfiles("live");
    this.alertsEnabled = alertsEnabled;
    this.runningStale = Duration.ofMinutes(runningStaleMinutes);
    this.cron = cron;
    this.reclaimScheduler = reclaimScheduler;
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
    LocalDate target = targetFor(today);
    if (target == null) {
      return;
    }
    runOnce(today, target, SOURCE_SCHEDULED, 0);
  }

  /**
   * Boot-time catch-up for a cron that ticked while the stack was down (task_e2e01c). Runs today's
   * sweep iff today's scheduled fire time has already passed AND no run is recorded for today;
   * returns whether it published.
   *
   * <p>Gating is IST wall-clock off the injected {@link Clock} and the shared trading calendar:
   * before the fire time nothing has been missed; on a non-trading day the sweep would have skipped
   * anyway, so nothing is claimed and a real weekday run is never suppressed. Once past those gates
   * the decision is {@link #runOnce}'s — the SAME protocol the scheduled fire uses, which is what
   * makes the two doors mutually exclusive rather than merely unlikely to collide.
   */
  public boolean catchUpIfMissed() {
    if (!live) {
      return false;
    }
    ZonedDateTime nowIst = clock.instant().atZone(Ist.ZONE);
    LocalDate today = nowIst.toLocalDate();
    if (!scheduledFireHasPassed(nowIst)) {
      return false;
    }
    LocalDate target = targetFor(today);
    if (target == null) {
      return false;
    }
    boolean swept = runOnce(today, target, SOURCE_BOOT_CATCHUP, 0);
    if (swept) {
      log.warn(
          "ingest canary catch-up: today's {} fire was missed (stack down?) - swept {} on boot",
          cron,
          target);
    }
    return swept;
  }

  /**
   * The ONE publish protocol both doors go through: <b>evaluate → claim → publish</b>, in that order
   * and by no other path (review round 1, Majors 1+2).
   *
   * <p><b>Evaluate first</b> because {@link #evaluate(LocalDate)} is side-effect-free. A transient
   * failure reading the ledger therefore returns before anything is claimed, and the morning stays
   * retryable — claiming first would burn the day's marker for a check that never happened.
   *
   * <p><b>Claim immediately before publish</b> because the claim's only job is to gate the single
   * side-effecting step. The scheduled fire and a boot catch-up run on DIFFERENT threads and can
   * genuinely overlap (an {@code ApplicationReadyEvent} straddling 08:45), so a publish outside this
   * gate would already have pushed its duplicate alert by the time the PK conflict was observed. A
   * grace window would only make that collision unlikely; routing both doors through one atomic
   * claim makes it impossible.
   *
   * <p><b>The claim is PROVISIONAL until publication confirms it</b> (review round 2, Critical). The
   * row commits before {@code publish}, so a process that dies — or a publish that throws — in
   * between would, with a single-state marker, leave the day permanently claimed and every later
   * door suppressing an alert that never went out. Silence for that day, forever, from the component
   * whose whole job is to break silence. So the claim writes {@code CLAIMED} and only a completed
   * publication promotes it to {@code DONE}: <b>suppression requires DONE</b>, and a {@code CLAIMED}
   * row that outlives {@link #CLAIM_LEASE} is stealable, so the day is retried rather than muted.
   *
   * <p>A claim EXCEPTION is not a conflict — it means the ledger itself is unusable, realistically
   * {@code canary_runs} missing because the migration was not deployed. Publishing wins there and is
   * logged at ERROR: a broken marker table must not silently suppress every morning alert, and the
   * duplicate it risks is bounded by a condition that is itself loud. A conflict LOSS (no exception,
   * zero rows) is the real de-duplication path and stays quiet. Both rules are the same rule from
   * two directions — absence of evidence never buys silence.
   */
  private boolean runOnce(LocalDate today, LocalDate target, String source, int attempt) {
    IngestCoverageReport report;
    try {
      report = evaluate(target);
    } catch (RuntimeException evalFailure) {
      log.warn("ingest canary sweep failed: {}", evalFailure.getMessage());
      return false;
    }
    // ⚠️ This try wraps claim() and NOTHING else (review round 4, Major). It used to span the
    // loss-handling below too, so a TaskRejectedException from scheduling the reclaim landed in the
    // claim-failure catch and fell through to publish() — a door that had explicitly LOST the atomic
    // claim published anyway, which is the exact double-alert the shared claim exists to prevent.
    // The two failures look alike and want OPPOSITE responses: a claim ERROR is absence of evidence
    // (publish), a claim LOSS is positive evidence another door holds the day (do not publish). One
    // catch cannot serve both, so it serves only the first. And claim() itself upholds the same
    // invariant one layer down (round 5): fail-open wraps ONLY the atomic upsert — a loss that
    // cannot be CLASSIFIED resolves to stand-down inside claim() and never reaches this catch,
    // because by then the upsert has already proven a holder exists.
    ClaimAttempt attempted;
    try {
      attempted = claim(today, source);
    } catch (RuntimeException claimFailure) {
      log.error(
          "ingest canary: run marker for {} not written ({}) - publishing anyway, de-duplication is OFF",
          today,
          claimFailure.getMessage());
      publish(report);
      return true; // nothing was claimed, so there is nothing to confirm
    }
    if (!attempted.won()) {
      if (attempted.finished()) {
        log.info("ingest canary: {} was already published - standing down", today);
      } else {
        scheduleReclaim(today, target, source, attempted.heldUntil(), attempt);
      }
      return false;
    }
    publish(report);
    confirmPublished(today);
    return true;
  }

  /**
   * Today's target trading day, or {@code null} when today is a non-trading day or outside the
   * bundled calendar (CD-2 cliff) — both calendar calls throw past the cliff, so a stale calendar
   * disables THIS canary quietly rather than killing the {@code @Scheduled} tick (the cliff has its
   * own horizon canary).
   *
   * <p>⚠️ Both callers' {@code target == null} checks are DEFENCE IN DEPTH since the evaluate-first
   * reordering, not load-bearing gates, and no test can tell them apart any more: {@code
   * evaluate(null)} NPEs on its first line, {@code runOnce} catches that before claiming or
   * publishing, so deleting either check changes no observable behaviour — only a clean skip becomes
   * a spurious "sweep failed" WARN every holiday morning. Keep them; do not assume a red test guards
   * them (mutation M6, review round 1).
   */
  private LocalDate targetFor(LocalDate today) {
    try {
      return calendar.isTradingDay(today) ? calendar.previousTradingDay(today) : null;
    } catch (RuntimeException calendarCliff) {
      log.warn("ingest canary: NSE calendar does not cover {} — skipping (calendar-cliff)", today);
      return null;
    }
  }

  /**
   * Takes the provisional {@code CLAIMED} claim on {@code istDay}. Wins when there is no row at all,
   * or when the existing row is a {@code CLAIMED} one whose lease has expired (an attempt that died
   * mid-publish). Loses against {@code DONE} — the day really was published — and against a fresh
   * {@code CLAIMED}, which is another door publishing right now.
   *
   * <p>The upsert is ONE atomic statement, because that is the entire mutual exclusion: a
   * read-then-write would let two doors both see "free" and both publish. Both timestamps come from
   * the injected {@link Clock}, never {@code now()}, so the lease is deterministic under a fixed
   * clock and independent of the container's UTC wall clock.
   *
   * <p>Only on a LOSS does it read the row back, to learn WHICH loss it was — the follow-up cannot
   * weaken the exclusion because the claim is already decided. A benign race here (the holder
   * confirming, or a third door stealing, between the two statements) at worst schedules a reclaim
   * that later finds {@code DONE} and stands down. A classification-read FAILURE is handled inside
   * this method (stand down, freshest-possible lease) and never propagates — see the inline note.
   */
  private ClaimAttempt claim(LocalDate istDay, String source) {
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
            istDay,
            STATE_CLAIMED,
            source,
            Timestamp.from(now),
            STATE_CLAIMED,
            Timestamp.from(now.minus(CLAIM_LEASE)));
    if (taken == 1) {
      return ClaimAttempt.taken();
    }
    // ⚠️ taken == 0 is POSITIVE EVIDENCE — the upsert has already proven a DONE or live-CLAIMED
    // row exists; the read below only asks WHICH. So its failure must NEVER escape into the
    // caller's fail-open catch (review round 5, Major): fail-open may wrap ONLY the atomic upsert,
    // and everything after a zero-row result is post-evidence bookkeeping that resolves toward
    // stand-down. On a failed read, assume the freshest possible lease — the holder is treated as
    // live and the reclaim comes back once even that must have expired.
    try {
      return jdbc.query(
          "SELECT state, claimed_at FROM canary_runs WHERE canary = ? AND run_day = ?",
          (ResultSetExtractor<ClaimAttempt>)
              rs -> {
                if (!rs.next()) {
                  // the row vanished between the two statements (nothing deletes rows in
                  // production); treat it as free-again rather than final, re-check immediately
                  return ClaimAttempt.blockedUntil(now);
                }
                if (STATE_DONE.equals(rs.getString("state"))) {
                  return ClaimAttempt.alreadyPublished();
                }
                return ClaimAttempt.blockedUntil(
                    rs.getTimestamp("claimed_at").toInstant().plus(CLAIM_LEASE));
              },
          CANARY_KEY,
          istDay);
    } catch (RuntimeException classificationFailure) {
      log.warn(
          "ingest canary: {} claim lost but the loss could not be classified ({}) - standing down"
              + " and assuming the freshest possible lease",
          istDay,
          classificationFailure.getMessage());
      return ClaimAttempt.blockedUntil(now.plus(CLAIM_LEASE));
    }
  }

  /**
   * Comes back when the current holder's lease runs out (review round 3). ⚠️ Without this the lease
   * made a dead claim RECLAIMABLE but created nothing that RECLAIMS it. {@code compose} sets {@code
   * restart: unless-stopped}, so a crash mid-publish brings the service back INSIDE the five-minute
   * lease; that boot sees a fresh {@code CLAIMED} row and correctly stands down — but {@link
   * MorningCanaryCatchUp} fires once on {@code ApplicationReadyEvent} and has now had its only turn,
   * the 08:45 cron is long past, and when the lease expires minutes later nothing is left to notice.
   * A silent day, which is the exact failure this whole feature exists to prevent. A one-shot boot
   * listener structurally cannot cover an expiry that happens after it ran, so the retry is owned
   * HERE: waiting for another boot is waiting for something that may never come.
   *
   * <p>Scheduled on the detector pool, bounded by {@link #MAX_RECLAIM_ATTEMPTS}, and re-entering
   * {@link #runOnce} so the retry re-evaluates against fresh ledger rows rather than replaying a
   * stale verdict.
   */
  private void scheduleReclaim(
      LocalDate today, LocalDate target, String source, Instant heldUntil, int attempt) {
    if (attempt >= MAX_RECLAIM_ATTEMPTS) {
      log.error(
          "ingest canary: {} still held after {} reclaim attempts - giving up; the day may go"
              + " unreported",
          today,
          attempt);
      return;
    }
    Instant retryAt = heldUntil.plus(RECLAIM_SLACK);
    log.warn(
        "ingest canary: {} is held by a live claim - re-checking at {} (reclaim attempt {} of {})",
        today,
        retryAt,
        attempt + 1,
        MAX_RECLAIM_ATTEMPTS);
    try {
      reclaimScheduler.schedule(() -> reclaim(today, target, source, attempt + 1), retryAt);
    } catch (RuntimeException notQueued) {
      // A rejected task must never escape into the caller's claim handling (review round 4, Major):
      // we LOST this claim, so the one thing that must not happen is publishing anyway. Losing the
      // reclaim only costs the day its self-heal; the next boot's catch-up is still the fallback.
      log.error(
          "ingest canary: could not queue the {} reclaim ({}) - the day stays held and will not"
              + " self-heal; the next boot's catch-up is the fallback",
          today,
          notQueued.getMessage());
    }
  }

  /** The scheduled retry body; never lets a failure escape onto the shared detector pool. */
  private void reclaim(LocalDate today, LocalDate target, String source, int attempt) {
    try {
      runOnce(today, target, source, attempt);
    } catch (RuntimeException reclaimFailure) {
      log.warn("ingest canary reclaim for {} failed: {}", today, reclaimFailure.getMessage());
    }
  }

  /**
   * Promotes the held claim to {@code DONE} — the only state that suppresses a later door. Fail-soft
   * and loud: if this write is lost the row stays {@code CLAIMED}, its lease expires, and a later
   * door re-publishes. A duplicate alert is the correct direction to fail here; a silent day is not.
   */
  private void confirmPublished(LocalDate istDay) {
    try {
      jdbc.update(
          "UPDATE canary_runs SET state = ?, completed_at = ? WHERE canary = ? AND run_day = ?",
          STATE_DONE,
          Timestamp.from(clock.instant()),
          CANARY_KEY,
          istDay);
    } catch (RuntimeException confirmFailure) {
      log.error(
          "ingest canary: {} published but NOT confirmed {} ({}) - the claim stays provisional and a"
              + " later door may re-alert",
          istDay,
          STATE_DONE,
          confirmFailure.getMessage());
    }
  }

  /**
   * Whether the cron's own fire for TODAY is at or before {@code nowIst}. Derived from the configured
   * expression rather than a hardcoded time, so the weekday clause is honoured for free (on a
   * Saturday the next fire is Monday ⇒ nothing was missed) and a disabled cron ({@code "-"}, which
   * the IT substrate uses) parses as unschedulable ⇒ no catch-up.
   */
  private boolean scheduledFireHasPassed(ZonedDateTime nowIst) {
    CronExpression expression;
    try {
      expression = CronExpression.parse(cron);
    } catch (IllegalArgumentException disabled) {
      log.info("ingest canary catch-up: cron '{}' is not a schedule — no catch-up", cron);
      return false;
    }
    ZonedDateTime fire =
        expression.next(nowIst.toLocalDate().atStartOfDay(Ist.ZONE).minusNanos(1));
    return fire != null && fire.toLocalDate().equals(nowIst.toLocalDate()) && !fire.isAfter(nowIst);
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
