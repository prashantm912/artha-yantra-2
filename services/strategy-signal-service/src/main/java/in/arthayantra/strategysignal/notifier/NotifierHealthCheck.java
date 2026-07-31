package in.arthayantra.strategysignal.notifier;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategysignal.notifier.NotificationRepository.CanaryRunState;
import in.arthayantra.strategysignal.notifier.NotificationRepository.DeliveryStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * V15 notifier delivery-health check (app-platform audit 2026-07-10 §8 V15). The notifier already
 * RECORDS every give-up as a {@code notification_events} FAILED row (after {@code retry-max-attempts}
 * retries), but NOTHING watched those rows — a silently dead push channel meant every signal alert
 * was dropped with no alarm. This is the daily failure-rate check the audit prescribes: once a day it
 * asks {@link NotificationRepository} how many deliveries failed in the trailing window and, when the
 * failure rate crosses the threshold, raises the alarm.
 *
 * <p>The irony is intentional (audit §8 V15): the alarm goes out over BOTH channels — ntfy AND
 * Telegram — so a failure that is specific to the ntfy path cannot swallow its own alert. Each push
 * is best-effort and unconfigured channels are skipped (mock/dev have none).
 *
 * <p>SUPPRESSED rows are flood control, not delivery failures, so they are excluded from the
 * denominator; the rate is {@code failed / (sent + failed)}. Below {@code min-attempts} deliveries in
 * the window the sample is too small to judge (a quiet, signal-free day never false-alarms).
 *
 * <p>Modulith-clean: this lives INSIDE the notifier module and touches only {@link
 * NotificationRepository} + {@link NotifierClient} — never {@code signals}/{@code paper}, and never
 * the {@code SignalEngine} (so it loads safely in engine-disabled paper ITs).
 *
 * <p><b>Missed-cron catch-up (task_7e754e11).</b> Spring never replays a {@code @Scheduled} fire
 * that ticked while the process was down, and the owner's machine routinely boots ~08:56 IST — so
 * on every late boot the morning check that exists to catch a dead push channel is exactly the
 * thing that did not run (E2E audit 2026-07-31 §2.1: stack down 02:29–08:56, both morning canaries
 * silently skipped). {@link #catchUpIfMissed()} is the boot-time replay: if today's cron fire has
 * already passed, it runs the check once. A plain replay is sufficient BECAUSE {@link
 * #evaluate(Instant)} asks about a trailing window, so a run at 08:56 answers essentially the
 * question the 08:30 fire would have. The dead-man heartbeat still covers a full outage; this covers
 * the late-boot morning.
 *
 * <p><b>One publish per IST day, whichever door.</b> Both the cron and the catch-up run the same
 * {@code evaluate → claim → publish → complete} protocol over the durable {@code canary_runs} ledger
 * (V052) — never an in-memory flag, which is defeated by exactly the restart this exists for. The
 * claim sits immediately before the only side-effecting step, so a start that STRADDLES 08:30 (cron
 * firing while {@code ApplicationReadyEvent} dispatches the catch-up) still yields exactly one
 * alert. Evaluation happens first, so a failed evaluation never burns the day; and the claim is
 * PROVISIONAL until {@code complete} confirms publication, so a run that dies mid-flight is retried
 * rather than silencing the day.
 *
 * <p><b>And something always comes back for it.</b> A reclaimable lease is worthless without a
 * reclaimer: {@code NotifierHealthCatchUp} fires once per start, and compose restarts the container
 * {@code unless-stopped} — so "crash mid-publish, restart INSIDE the lease, correctly suppress" is
 * the LIKELY path, and it would leave the lease lapsing minutes later with no door left. So a losing
 * door distinguishes WHY it lost and, when the day is merely held, schedules its own retry at lease
 * expiry on this module's pool (see {@link #scheduleRetry}). That retry carries an immutable {@link
 * RunContext} — a deferred run resolves the day it was scheduled FOR, never the day it happens to
 * wake up on.
 *
 * <p>The trailing window is anchored to today's INTENDED cron fire, not to whenever the process came
 * up — see {@link #windowStart(ZonedDateTime)}. Anchoring to "now" would leave the minutes between
 * the previous run and a late boot permanently unexamined.
 */
@Component
public class NotifierHealthCheck {

  /** The trailing-window delivery-health verdict. */
  public record HealthReport(
      Instant since, long attempts, long failed, double failureRate, boolean healthy) {}

  private static final Logger log = LoggerFactory.getLogger(NotifierHealthCheck.class);

  /** {@code canary_runs.canary} key for this check's per-IST-day run marker (V052). */
  public static final String CANARY_KEY = "NOTIFIER_HEALTH";

  private static final String SOURCE_SCHEDULED = "SCHEDULED";
  private static final String SOURCE_BOOT_CATCHUP = "BOOT_CATCHUP";

  private static final String STATUS_DONE = "DONE";

  /**
   * How long a CLAIMED row holds the day before another door may reclaim it. It does TWO jobs, and
   * both are satisfied at five minutes:
   *
   * <ul>
   *   <li><b>Ceiling on a legitimate publish</b>, so a live holder is never pre-empted mid-flight.
   *       Worst case is two best-effort pushes bounded by the global 2 s connect / 30 s read
   *       timeouts ≈ 64 s, so this is ~4.7× the bound.
   *   <li><b>Floor on how long a silent day lasts before it self-heals</b>, now that {@link
   *       #scheduleRetry} hangs off lease expiry. Five minutes of delay on a check whose window is
   *       24 hours is immaterial — nobody reads the 08:30 alert at 08:30:04.
   * </ul>
   *
   * <p>They point in opposite directions, so a single number is only right while they do not
   * collide. What would force a split: a publish worst case that grew toward the lease (more
   * channels, longer timeouts), or a requirement for sub-minute self-healing. Neither holds, so one
   * constant it stays.
   */
  private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

  /**
   * Slack added to the retry instant. The claim predicate is a STRICT {@code claimed_at <
   * staleBefore}, so a retry firing exactly at expiry would lose by a nanosecond and defer again.
   */
  private static final Duration RETRY_MARGIN = Duration.ofSeconds(1);

  /** Retry budget per run, so a day held by a live-but-slow holder cannot spin forever. */
  private static final int MAX_RETRIES = 3;

  private final NotificationRepository repo;
  private final NotifierClient client;
  private final Clock clock;
  private final Counter alertCounter;
  private final long windowHours;
  private final long minAttempts;
  private final double failureRateThreshold;
  private final String cron;
  private final TaskScheduler retryScheduler;

  /** Wires the audit repo, the outbound client, the clock and the tunable window/threshold. */
  public NotifierHealthCheck(
      NotificationRepository repo,
      NotifierClient client,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${artha.notifier.health.window-hours:24}") long windowHours,
      @Value("${artha.notifier.health.min-attempts:1}") long minAttempts,
      @Value("${artha.notifier.health.failure-rate-threshold:0.5}") double failureRateThreshold,
      // Same property + default as the @Scheduled below — the catch-up derives "has today's fire
      // already passed?" from the ACTUAL schedule, so the two can never drift apart.
      @Value("${artha.notifier.health.cron:0 30 8 * * *}") String cron,
      @Qualifier("notifierRetryScheduler") TaskScheduler retryScheduler) {
    this.retryScheduler = retryScheduler;
    this.repo = repo;
    this.client = client;
    this.clock = clock;
    this.alertCounter = meterRegistry.counter("ay_notifier_delivery_health_alert_total");
    this.windowHours = windowHours;
    this.minAttempts = minAttempts;
    this.failureRateThreshold = failureRateThreshold;
    this.cron = cron;
  }

  /** Daily check (08:30 IST). Evaluates the trailing window and alerts over both channels on a gap. */
  @Scheduled(cron = "${artha.notifier.health.cron:0 30 8 * * *}", zone = "Asia/Kolkata")
  public void check() {
    runOnce(contextFor(clock.instant().atZone(Ist.ZONE)), SOURCE_SCHEDULED, MAX_RETRIES);
  }

  /**
   * Boot-time catch-up for a cron that ticked while the stack was down (task_7e754e11). Runs today's
   * check iff today's scheduled fire time has already passed; returns whether it published.
   *
   * <p>Everything after the fire-time gate is {@link #runOnce(String)}, the SAME protocol the cron
   * runs — this door adds a trigger, not a second behaviour. Gating is IST wall-clock off the
   * injected {@link Clock}: before today's fire time nothing has been missed, and the method returns
   * without claiming, so the cron's own fire is never pre-empted. There is deliberately NO
   * trading-day gate — the schedule is daily and this check has never had one, so adding one here
   * would change what the canary means, not just when it is triggered.
   */
  public boolean catchUpIfMissed() {
    ZonedDateTime nowIst = clock.instant().atZone(Ist.ZONE);
    if (!scheduledFireHasPassed(nowIst)) {
      return false;
    }
    log.info("notifier health catch-up: today's '{}' fire may have been missed — checking", cron);
    return runOnce(contextFor(nowIst), SOURCE_BOOT_CATCHUP, MAX_RETRIES);
  }

  /**
   * The ONE protocol both doors run: <b>evaluate → claim → publish → complete</b>. Returns whether
   * it published.
   *
   * <p><b>Why that order.</b> {@link #evaluate(Instant)} is side-effect-free, so evaluating first
   * costs nothing and buys everything: a transient evaluation failure returns before anything is
   * claimed, leaving the day retryable by a later boot, while the claim still gates the only
   * side-effecting step so two doors cannot both alert.
   *
   * <p><b>Why the claim is provisional.</b> Ordering alone fixes the EVALUATION half; it does not
   * fix the PUBLICATION half. A one-state claim proves the day was taken, never that an alert went
   * out, so a process dying between {@link #claim} and {@link #publish} would silence the day
   * forever. Hence {@link #complete}: {@code CLAIMED} holds a lease, only {@code DONE} suppresses a
   * later door, and an expired {@code CLAIMED} row is reclaimable.
   *
   * <p><b>Why both doors share it.</b> The cron and the boot catch-up can overlap — a start that
   * straddles 08:30 dispatches the catch-up onto {@code notifierExecutor} while the scheduled tick
   * is running. Recording the marker AFTER publishing would prevent nothing there, because both
   * pushes would already have gone out. With one shared claim ahead of every publish, exactly one of
   * them wins the {@code INSERT .. ON CONFLICT DO NOTHING} and exactly one alert leaves the JVM.
   */
  private boolean runOnce(RunContext run, String source, int retriesLeft) {
    HealthReport report;
    try {
      report = evaluate(run.windowStart());
    } catch (RuntimeException evalFailure) {
      // Nothing claimed yet, so the day stays retryable — that is the whole point of evaluating
      // before claiming.
      log.warn("notifier health check failed: {}", evalFailure.getMessage());
      return false;
    }
    Instant claimedAt = clock.instant();
    ClaimVerdict verdict = claim(run.istDay(), source, claimedAt);
    if (verdict.won()) {
      publish(report);
      complete(run.istDay(), claimedAt);
      return true;
    }
    if (verdict.retryAt() == null) {
      log.info(
          "notifier health: {} is already checked and published — {} publishes nothing",
          run.istDay(),
          source);
      return false;
    }
    scheduleRetry(run, source, verdict.retryAt(), retriesLeft);
    return false;
  }

  /**
   * One attempt at ONE IST day, fixed the moment the attempt starts and carried intact through
   * every retry.
   *
   * <p>This exists because a retry fires LATER — at lease expiry, and with the retry budget the
   * chain can run well past IST midnight, which is precisely the crash-restart case the retry was
   * built for. Re-deriving the day inside the retry would then do two wrong things at once: day D
   * would never be resolved (its row left {@code CLAIMED}, its alert never sent — the silence this
   * whole feature exists to prevent), and day D+1 would be stamped {@code DONE} by a run that never
   * evaluated it, suppressing D+1's own 08:30 canary. A retry is a RESUMPTION of day D's attempt,
   * never a fresh evaluation that happens to run later.
   *
   * <p>Same hazard class as {@code now()}/{@code ::date} being UTC in-container and {@code AT TIME
   * ZONE '+05:30'} inverting: anything that defers work across a day boundary must carry its day
   * rather than recompute it. {@code windowStart} rides along for the same reason — the window is
   * anchored to day D's cron fire, and re-deriving it after midnight would silently re-anchor to
   * D+1's.
   */
  private record RunContext(LocalDate istDay, Instant windowStart) {}

  /** Fixes the day and window for an attempt starting now. Called only at the two public doors. */
  private RunContext contextFor(ZonedDateTime nowIst) {
    return new RunContext(nowIst.toLocalDate(), windowStart(nowIst));
  }

  /**
   * Outcome of a claim attempt. {@code retryAt} is the crux: it is {@code null} when there is
   * nothing left to do (we won, or the day is provably {@code DONE}), and otherwise carries the
   * instant the current holder's lease lapses.
   */
  private record ClaimVerdict(boolean won, Instant retryAt) {}

  /**
   * Hands the day back to this module at lease expiry, because nothing else will come back for it.
   *
   * <p>This closes the gap the lease alone left open. {@code NotifierHealthCatchUp} fires once, on
   * {@code ApplicationReadyEvent} — so the sequence "claim → crash mid-publish → container restarts
   * INSIDE the five-minute lease (compose sets {@code restart: unless-stopped}, which makes that the
   * LIKELY case) → the new boot correctly sees a fresh claim and suppresses" would leave the lease
   * expiring minutes later with no door left to walk through, and the day silently unalerted. The
   * lease made the claim reclaimable; it did not create anything that reclaims. This does.
   *
   * <p>Bounded by {@code retriesLeft} so the chain provably terminates: each retry either wins (and
   * stops), finds {@code DONE} (and stops), or loses to a live holder and defers once more. A budget
   * of {@code MAX_RETRIES} covers a holder that is genuinely alive and slow without spinning.
   */
  private void scheduleRetry(RunContext run, String source, Instant at, int retriesLeft) {
    if (retriesLeft <= 0) {
      log.warn(
          "notifier health: {} still held after {} retries — giving up", run.istDay(), MAX_RETRIES);
      return;
    }
    try {
      // The context is captured, NOT recomputed in the retry — a retry that fires after IST
      // midnight must still resolve the day it was scheduled for.
      retryScheduler.schedule(() -> retry(run, source, retriesLeft - 1), at);
      log.info("notifier health: {} is held by another door — retrying at {}", run.istDay(), at);
    } catch (RuntimeException schedulingFailure) {
      log.warn(
          "notifier health: could not schedule the {} retry ({}) — the day may go unpublished",
          run.istDay(),
          schedulingFailure.getMessage());
    }
  }

  /** The scheduled retry body; never lets a failure escape onto the retry thread. */
  private void retry(RunContext run, String source, int retriesLeft) {
    try {
      runOnce(run, source, retriesLeft);
    } catch (RuntimeException failure) {
      log.warn("notifier health retry failed: {}", failure.getMessage());
    }
  }

  /**
   * Lower bound of the trailing window, anchored to TODAY'S INTENDED CRON FIRE rather than to "now"
   * (cross-vendor review, Critical 1).
   *
   * <p>Anchoring to now looks equivalent and is not: consecutive runs would each look back {@code
   * windowHours} from their own start, so a catch-up at 08:56 would evaluate from yesterday 08:56
   * while yesterday's run ended at 08:30 — leaving those 26 minutes permanently unexamined, and the
   * later the boot the wider the hole. A canary that skips the minutes around its own missed fire
   * fails exactly where it is supposed to work. Anchoring to the fire time makes today's window JOIN
   * yesterday's at yesterday's fire, and makes coverage independent of when the process happened to
   * come up. The upper bound stays open — {@code deliveryStats} reads through the present — so a
   * late boot still sees everything since.
   *
   * <p>Falls back to {@code now} when today's fire is unknowable or has not happened yet (an
   * unparseable/disabled cron, or a direct call before the fire time). For the scheduled tick the
   * two are the same instant, so its window is byte-identical to before this change.
   */
  private Instant windowStart(ZonedDateTime nowIst) {
    ZonedDateTime fire = todaysFire(nowIst);
    Instant anchor = fire != null && !fire.isAfter(nowIst) ? fire.toInstant() : clock.instant();
    return anchor.minus(Duration.ofHours(windowHours));
  }

  /**
   * Takes today's provisional claim; {@code true} means this caller may publish. Three outcomes, and
   * collapsing any two of them loses a property:
   *
   * <ul>
   *   <li><b>Won</b> (no row, or a lease-expired {@code CLAIMED} row reclaimed) — publish. The
   *       reclaim is what stops a run that died between claim and publication from silencing the day
   *       forever; only a {@code DONE} row is proof an alert actually went out.
   *   <li><b>Lost</b> (the row is {@code DONE}, or another door holds a FRESH lease) — publish
   *       nothing. This is the overlap guard: a start straddling 08:30 has the cron and the catch-up
   *       racing one atomic upsert, and the loser stays silent.
   *   <li><b>Error</b> — publish anyway, with a WARN. A conflict is positive evidence; an exception
   *       is absence of evidence, and for a canary whose failure mode IS silence, absence of
   *       evidence must never buy silence. The duplicate it can cause is noise; the alternative
   *       loses a real "your push channel is dead" page to a database hiccup. The window is tiny —
   *       {@link #evaluate(Instant)} just read the same database.
   * </ul>
   */
  private ClaimVerdict claim(LocalDate istDay, String source, Instant claimedAt) {
    try {
      if (repo.claimCanaryRun(CANARY_KEY, istDay, source, claimedAt, claimedAt.minus(CLAIM_LEASE))) {
        return new ClaimVerdict(true, null);
      }
    } catch (RuntimeException claimFailure) {
      log.warn(
          "notifier health: claim for {} failed ({}) — publishing anyway",
          istDay,
          claimFailure.getMessage());
      return new ClaimVerdict(true, null);
    }
    return lost(istDay, claimedAt);
  }

  /**
   * Reads WHY the claim was lost, because the two reasons have different futures: {@code DONE} is
   * final and needs nothing, while a live lease means "not yet" and needs somebody to come back.
   * Only a confirmed {@code DONE} ends the day — a missing row or an unreadable one defers instead
   * of going quiet, the same direction as every other rule here.
   */
  private ClaimVerdict lost(LocalDate istDay, Instant now) {
    CanaryRunState state;
    try {
      state = repo.canaryRunState(CANARY_KEY, istDay).orElse(null);
    } catch (RuntimeException readFailure) {
      log.warn(
          "notifier health: could not read the {} claim ({}) — assuming it is held",
          istDay,
          readFailure.getMessage());
      return new ClaimVerdict(false, now.plus(CLAIM_LEASE).plus(RETRY_MARGIN));
    }
    if (state != null && STATUS_DONE.equals(state.status())) {
      return new ClaimVerdict(false, null);
    }
    Instant heldFrom = state == null ? now : state.claimedAt();
    return new ClaimVerdict(false, heldFrom.plus(CLAIM_LEASE).plus(RETRY_MARGIN));
  }

  /**
   * Marks the day complete once publication has happened — the transition that actually suppresses
   * later doors. Fail-soft in the safe direction: if this never lands, the row stays {@code CLAIMED}
   * and a later door reclaims it after the lease and publishes again, so the failure mode is a
   * duplicate alert rather than a silent day.
   */
  private void complete(LocalDate istDay, Instant claimedAt) {
    try {
      if (!repo.completeCanaryRun(CANARY_KEY, istDay, claimedAt, clock.instant())) {
        log.warn("notifier health: claim for {} was superseded before completion", istDay);
      }
    } catch (RuntimeException completionFailure) {
      log.warn(
          "notifier health: {} published but not marked done ({}) — a later door may repeat it",
          istDay,
          completionFailure.getMessage());
    }
  }

  /** Emits the verdict: a quiet INFO when healthy, the two-channel alarm when not. */
  private void publish(HealthReport report) {
    if (report.healthy()) {
      log.info(
          "notifier delivery health OK — {}/{} attempts failed in the last {}h",
          report.failed(),
          report.attempts(),
          windowHours);
      return;
    }
    alert(report);
  }

  /**
   * Whether the cron's own fire for TODAY is at or before {@code nowIst}. Derived from the configured
   * expression rather than a hardcoded time, so the fire time can never drift from the schedule, and
   * a cron disabled with Spring's {@code "-"} (what the IT substrate pins) parses as unschedulable ⇒
   * no catch-up, which is the right reading: a schedule that never fires can never be missed.
   */
  private boolean scheduledFireHasPassed(ZonedDateTime nowIst) {
    ZonedDateTime fire = todaysFire(nowIst);
    return fire != null && !fire.isAfter(nowIst);
  }

  /**
   * The configured cron's fire time on {@code nowIst}'s IST date, or {@code null} when it does not
   * fire today at all (a disabled {@code "-"} expression, or a day the schedule excludes). Shared by
   * the catch-up's trigger gate and the window anchor so the two can never disagree about when the
   * check was supposed to run.
   */
  private ZonedDateTime todaysFire(ZonedDateTime nowIst) {
    CronExpression expression;
    try {
      expression = CronExpression.parse(cron);
    } catch (IllegalArgumentException disabled) {
      log.info("notifier health: cron '{}' is not a schedule", cron);
      return null;
    }
    ZonedDateTime fire = expression.next(nowIst.toLocalDate().atStartOfDay(Ist.ZONE).minusNanos(1));
    return fire != null && fire.toLocalDate().equals(nowIst.toLocalDate()) ? fire : null;
  }

  /**
   * Compute the delivery-health verdict for events since {@code since}. Healthy when the sample is
   * below {@code min-attempts} OR the failure rate is under the threshold. Side-effect-free — the
   * scheduled check and the tests both call it.
   */
  public HealthReport evaluate(Instant since) {
    DeliveryStats stats = repo.deliveryStats(since);
    long attempts = stats.sent() + stats.failed();
    double rate = attempts == 0 ? 0.0 : (double) stats.failed() / attempts;
    boolean healthy = attempts < minAttempts || rate < failureRateThreshold;
    return new HealthReport(since, attempts, stats.failed(), rate, healthy);
  }

  private void alert(HealthReport report) {
    alertCounter.increment();
    String title = "ArthaYantra notifier delivery health";
    String message =
        "Notifier deliveries failing: "
            + report.failed()
            + "/"
            + report.attempts()
            + " attempts failed ("
            + Math.round(report.failureRate() * 100)
            + "%) in the last "
            + windowHours
            + "h — signal alerts are being dropped.";
    log.error("notifier delivery health RED — {}", message);
    push("NTFY", title, message);
    push("TELEGRAM", title, message);
  }

  /** Best-effort push; skips an unconfigured channel and never lets a delivery failure propagate. */
  private void push(String channel, String title, String message) {
    try {
      if (client.configured(channel)) {
        client.send(channel, title, message);
      }
    } catch (RuntimeException e) {
      log.warn("notifier health {} push failed: {}", channel, e.getMessage());
    }
  }
}
