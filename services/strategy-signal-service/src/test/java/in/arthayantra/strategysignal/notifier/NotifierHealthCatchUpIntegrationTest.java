package in.arthayantra.strategysignal.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategysignal.notifier.NotificationRepository.DeliveryStats;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;

/**
 * The boot-time replay of a notifier-health cron that ticked while the stack was down
 * (task_7e754e11 / E2E audit 2026-07-31 §2.1: the stack was down 02:29-08:56 IST, the 08:30 cron
 * ticked into the void, and Spring never replays a missed fire — so the check that exists to notice
 * a dead push channel is exactly what does not run on a late-boot morning).
 *
 * <p>Runs against the REAL V052 {@code canary_runs} table in the shared singleton container, so the
 * migration itself is exercised. Only {@code deliveryStats} is stubbed (the shared DB's
 * {@code notification_events} rows are written by other ITs and cannot be made deterministic); the
 * claim goes to the real database, because the atomic upsert IS the feature.
 *
 * <p>The protocol under test is {@code evaluate → claim → publish → complete}, run identically by
 * BOTH doors, plus the window anchoring that decides WHAT each run examines:
 *
 * <ul>
 *   <li>the window is anchored to the intended cron fire, so a late boot cannot skip the minutes
 *       around the fire it missed;
 *   <li>evaluation failing before the claim leaves the day retryable;
 *   <li>only a COMPLETED run suppresses a later door — an incomplete claim is reclaimed past its
 *       lease, a fresh one still suppresses;
 *   <li>a claim ERROR publishes anyway rather than letting a database hiccup silence the canary.
 * </ul>
 */
@SpringBootTest(properties = "spring.profiles.active=mock")
class NotifierHealthCatchUpIntegrationTest extends StrategySignalIntegrationTestBase {

  /** The production default — the same expression the {@code @Scheduled} carries. */
  private static final String CRON = "0 30 8 * * *";

  @Autowired ApplicationContext context;
  @Autowired JdbcTemplate jdbc;

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final TaskScheduler retryScheduler = mock(TaskScheduler.class);

  /**
   * The substrate pin: {@link NotifierHealthCatchUp} fires on {@code ApplicationReadyEvent} and
   * dispatches onto {@code notifierExecutor}, so a CACHED context would push and INSERT into
   * {@code canary_runs} inside whatever unrelated test is running at the time. This class never sets
   * the property — the bean being ABSENT proves the substrate default reaches contexts that never
   * asked for it.
   */
  @Test
  void theSharedSubstrateDisablesTheBootCatchUpBean() {
    assertThat(context.getBeansOfType(NotifierHealthCatchUp.class))
        .as("the substrate default must disable the boot catch-up bean in EVERY base-extending context")
        .isEmpty();
  }

  @Test
  void catchUpChecksWhenTodaysCronFireWasMissed() {
    LocalDate today = LocalDate.of(2026, 4, 6);
    clearCanaryRun(today);
    NotifierClient client = configuredClient();

    // the owner's machine came up at 08:56 — twenty-six minutes after the 08:30 fire
    boolean checked = check(fixedAt(today, 8, 56), unhealthy(), client, CRON).catchUpIfMissed();

    assertThat(checked).isTrue();
    verify(client).send(eq("NTFY"), anyString(), anyString());
    verify(client).send(eq("TELEGRAM"), anyString(), anyString());
    assertThat(canaryRunSource(today)).isEqualTo("BOOT_CATCHUP");
  }

  @Test
  void catchUpSkipsWhenTodaysRunIsAlreadyRecorded() {
    LocalDate today = LocalDate.of(2026, 4, 13);
    clearCanaryRun(today);
    seedCompletedRun(today, "SCHEDULED", istAt(today, 8, 30));
    NotifierClient client = configuredClient();

    // a real delivery gap exists; silence must come from the claim, not from a healthy verdict
    boolean checked = check(fixedAt(today, 8, 56), unhealthy(), client, CRON).catchUpIfMissed();

    assertThat(checked).isFalse();
    verifyNoInteractions(client);
    assertThat(canaryRunSource(today)).isEqualTo("SCHEDULED"); // the claim did not overwrite it
    // DONE is FINAL — unlike a held lease, it must not leave a retry behind
    verifyNoInteractions(retryScheduler);
  }

  @Test
  void catchUpSkipsWhenBootIsBeforeTodaysFireTime() {
    LocalDate today = LocalDate.of(2026, 4, 20);
    clearCanaryRun(today);
    NotifierClient client = configuredClient();

    // booted 08:20 — the 08:30 cron is still ahead, so nothing has been missed, and the marker must
    // stay UNWRITTEN or the cron's own fire would find the day already claimed and skip itself.
    boolean checked = check(fixedAt(today, 8, 20), unhealthy(), client, CRON).catchUpIfMissed();

    assertThat(checked).isFalse();
    verifyNoInteractions(client);
    assertThat(canaryRunSource(today)).isNull();
  }

  @Test
  void theScheduledCheckMarksTheDaySoTheNextBootDoesNotRepeatIt() {
    LocalDate today = LocalDate.of(2026, 4, 27);
    clearCanaryRun(today);

    NotifierClient scheduled = configuredClient();
    check(fixedAt(today, 8, 30), unhealthy(), scheduled, CRON).check();
    verify(scheduled).send(eq("NTFY"), anyString(), anyString());
    assertThat(canaryRunSource(today)).isEqualTo("SCHEDULED");

    // restart at 09:10 — the cron already ran, so the boot catch-up must not alert a second time
    NotifierClient afterRestart = configuredClient();
    boolean checked = check(fixedAt(today, 9, 10), unhealthy(), afterRestart, CRON).catchUpIfMissed();

    assertThat(checked).isFalse();
    verifyNoInteractions(afterRestart);
  }

  @Test
  void disabledCronNeverCatchesUp() {
    LocalDate today = LocalDate.of(2026, 5, 4);
    clearCanaryRun(today);
    NotifierClient client = configuredClient();

    boolean checked = check(fixedAt(today, 8, 56), unhealthy(), client, "-").catchUpIfMissed();

    assertThat(checked).isFalse();
    verifyNoInteractions(client);
    assertThat(canaryRunSource(today)).isNull();
  }

  /**
   * The reorder that lets one protocol give both properties: evaluation runs BEFORE the claim, so a
   * transient read failure returns having claimed nothing and a later boot still gets its check.
   * Claim-first would have marked the day and lost the morning to a database blip.
   */
  @Test
  void evaluationFailureNeverBurnsTheDay() {
    LocalDate today = LocalDate.of(2026, 5, 18);
    clearCanaryRun(today);
    NotifierClient firstBoot = configuredClient();

    boolean published =
        check(fixedAt(today, 8, 56), evaluationFails(), firstBoot, CRON).catchUpIfMissed();

    assertThat(published).isFalse();
    verifyNoInteractions(firstBoot);
    assertThat(canaryRunSource(today)).as("a failed evaluation must claim nothing").isNull();

    // the retry a later boot gets PRECISELY BECAUSE nothing was claimed
    NotifierClient secondBoot = configuredClient();
    boolean retried = check(fixedAt(today, 9, 20), unhealthy(), secondBoot, CRON).catchUpIfMissed();

    assertThat(retried).isTrue();
    verify(secondBoot).send(eq("NTFY"), anyString(), anyString());
    assertThat(canaryRunSource(today)).isEqualTo("BOOT_CATCHUP");
  }

  /**
   * The shared-gate invariant in the direction the old code got wrong: the SCHEDULED tick is gated
   * by the same claim, so a catch-up that already published this morning silences it. Together with
   * {@link #theScheduledCheckMarksTheDaySoTheNextBootDoesNotRepeatIt()} (the reverse direction) this
   * pins that BOTH doors route through one claim before any push.
   *
   * <p>This pins the PROTOCOL, not the race — it cannot drive the cron and {@code
   * ApplicationReadyEvent} into true overlap. What it proves is that neither door can publish
   * without first winning the claim, which is what makes the overlap safe.
   */
  @Test
  void theScheduledTickAlsoPublishesOnlyIfItWinsTheClaim() {
    LocalDate today = LocalDate.of(2026, 5, 25);
    clearCanaryRun(today);
    // the catch-up got there first this morning AND completed — only DONE may silence the cron
    seedCompletedRun(today, "BOOT_CATCHUP", istAt(today, 8, 20));
    NotifierClient client = configuredClient();

    check(fixedAt(today, 8, 30), unhealthy(), client, CRON).check();

    verifyNoInteractions(client);
    assertThat(canaryRunSource(today)).isEqualTo("BOOT_CATCHUP");
  }

  /**
   * A claim ERROR is absence of evidence, not evidence of absence — and a canary whose failure mode
   * is silence must not let a database hiccup buy silence. Distinct from a PK conflict, which IS
   * evidence and does suppress.
   */
  @Test
  void claimErrorPublishesAnywayRatherThanGoingSilent() {
    LocalDate today = LocalDate.of(2026, 6, 1);
    clearCanaryRun(today);
    NotifierClient client = configuredClient();

    boolean published =
        check(fixedAt(today, 8, 56), unhealthyWithFailingClaim(), client, CRON).catchUpIfMissed();

    assertThat(published).isTrue();
    verify(client).send(eq("NTFY"), anyString(), anyString());
    assertThat(canaryRunSource(today)).isNull(); // the marker genuinely did not land
  }

  /**
   * CRITICAL 1: the trailing window is anchored to today's intended cron fire, not to boot time.
   *
   * <p>Anchored to "now", a 10:30 boot would evaluate from yesterday 10:30 while yesterday's run
   * ended at 08:30 — leaving two hours permanently unexamined, widening with every later boot. The
   * anchor must therefore be the SAME instant whichever door runs and however late the process came
   * up, and it must join the previous run's window rather than slide past it.
   */
  @Test
  void theWindowIsAnchoredToTheCronFireNotTheBootTime() {
    LocalDate today = LocalDate.of(2026, 6, 8);
    clearCanaryRun(today);
    Instant expected = istAt(today, 8, 30).minus(Duration.ofHours(24)); // = yesterday's 08:30 fire

    NotificationRepository lateBoot = unhealthy();
    check(fixedAt(today, 10, 30), lateBoot, configuredClient(), CRON).catchUpIfMissed();
    ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
    verify(lateBoot).deliveryStats(since.capture());

    assertThat(since.getValue())
        .as("a 10:30 boot must still look back from the 08:30 fire, joining yesterday's window")
        .isEqualTo(expected);

    // and the on-time door agrees, so coverage does not depend on when the process started
    clearCanaryRun(today);
    NotificationRepository onTime = unhealthy();
    check(fixedAt(today, 8, 30), onTime, configuredClient(), CRON).check();
    ArgumentCaptor<Instant> scheduledSince = ArgumentCaptor.forClass(Instant.class);
    verify(onTime).deliveryStats(scheduledSince.capture());

    assertThat(scheduledSince.getValue()).isEqualTo(expected);
  }

  /**
   * CRITICAL 2: a claim proves "claimed", not "published". A run that dies between the two leaves a
   * CLAIMED row; suppressing on it would silence the day forever, so once the lease expires a later
   * door must reclaim and publish.
   *
   * <p>The death is driven, not seeded: the completion write fails, which is exactly the state a
   * process killed after its push would leave behind.
   */
  @Test
  void incompleteClaimIsRetriedByTheNextDoor() {
    LocalDate today = LocalDate.of(2026, 6, 15);
    clearCanaryRun(today);

    // first door publishes but never manages to mark the day done
    NotifierClient died = configuredClient();
    check(fixedAt(today, 8, 56), unhealthyWithFailingCompletion(), died, CRON).catchUpIfMissed();
    verify(died).send(eq("NTFY"), anyString(), anyString());
    assertThat(canaryRunStatus(today)).isEqualTo("CLAIMED"); // never reached DONE

    // a later boot, past the lease, must NOT read that as "already handled"
    NotifierClient afterRestart = configuredClient();
    boolean published =
        check(fixedAt(today, 9, 30), unhealthy(), afterRestart, CRON).catchUpIfMissed();

    assertThat(published).as("an incomplete claim must never silence the day").isTrue();
    verify(afterRestart).send(eq("NTFY"), anyString(), anyString());
    assertThat(canaryRunStatus(today)).isEqualTo("DONE");
  }

  /**
   * The other side of the lease, which must survive the Critical-2 fix: a FRESH claim still
   * suppresses. Without this the reclaim rule would dissolve the overlap guard and a start
   * straddling 08:30 would alert twice. It must still leave a retry behind, though — suppressing is
   * "not yet", never "never".
   */
  @Test
  void freshClaimStillSuppressesTheConcurrentDoor() {
    LocalDate today = LocalDate.of(2026, 6, 22);
    clearCanaryRun(today);
    Instant held = istAt(today, 8, 56);
    seedIncompleteClaim(today, "SCHEDULED", held); // the other door is mid-publish
    NotifierClient client = configuredClient();

    boolean published = check(fixedAt(today, 8, 57), unhealthy(), client, CRON).catchUpIfMissed();

    assertThat(published).isFalse();
    verifyNoInteractions(client);
    assertThat(canaryRunStatus(today)).isEqualTo("CLAIMED");
    scheduledRetry(held.plus(Duration.ofMinutes(5)).plusSeconds(1));
  }

  /**
   * The gap a reclaimable lease leaves on its own: nothing ever comes back to reclaim it.
   *
   * <p>Full trace — a door claims and dies mid-publish; compose ({@code restart: unless-stopped})
   * brings the process back INSIDE the five-minute lease, so that boot sees a live claim and
   * correctly suppresses; and {@code ApplicationReadyEvent} has now had its only turn. Without a
   * self-scheduled retry the lease lapses minutes later with no door left, and the day stays
   * silent — exactly the failure this whole feature exists to prevent.
   */
  @Test
  void theHeldDayIsRetriedByThisModuleWhenTheLeaseExpires() {
    LocalDate today = LocalDate.of(2026, 6, 29);
    clearCanaryRun(today);

    // 1. a door claims, publishes, and dies before it can mark the day done
    NotifierClient crashed = configuredClient();
    check(fixedAt(today, 8, 56), unhealthyWithFailingCompletion(), crashed, CRON).catchUpIfMissed();
    verify(crashed).send(eq("NTFY"), anyString(), anyString());
    assertThat(canaryRunStatus(today)).isEqualTo("CLAIMED"); // never reached DONE

    // 2. the container restarts two minutes later — INSIDE the lease, so this boot must suppress
    MovableClock clock = new MovableClock(istAt(today, 8, 58));
    NotifierClient afterRestart = configuredClient();
    NotifierHealthCheck restarted = check(clock, unhealthy(), afterRestart, CRON);

    assertThat(restarted.catchUpIfMissed()).isFalse();
    verifyNoInteractions(afterRestart);

    // 3. ...but it must leave a retry behind, because ApplicationReadyEvent will not fire again
    Runnable retry = scheduledRetry(istAt(today, 8, 56).plus(Duration.ofMinutes(5)).plusSeconds(1));

    // 4. the lease lapses and the module's own retry reclaims and publishes
    clock.advanceTo(istAt(today, 9, 1).plusSeconds(1));
    retry.run();

    verify(afterRestart).send(eq("NTFY"), anyString(), anyString());
    assertThat(canaryRunStatus(today)).isEqualTo("DONE");
  }

  /**
   * A door that LOST the claim must publish nothing even if scheduling its retry then fails.
   *
   * <p>Two failure modes meet here and their correct responses are OPPOSITE: a claim ERROR is
   * absence of evidence and publishes anyway (the ratified fail-open), while a claim LOSS is
   * POSITIVE evidence another door holds the day, so publishing is exactly wrong. A {@code catch}
   * wide enough to span both — e.g. one that also covered the scheduling call — would silently
   * reclassify the second as the first, and the live holder plus the loser would both alert.
   *
   * <p>The worst case here is a missed retry, recoverable on the next boot; a duplicate alert is
   * what the shared claim exists to prevent.
   */
  @Test
  void schedulingFailureOnLostClaimStillPublishesNothing() {
    LocalDate today = LocalDate.of(2026, 7, 13);
    clearCanaryRun(today);
    seedIncompleteClaim(today, "SCHEDULED", istAt(today, 8, 56)); // a live holder owns the day
    doThrow(new TaskRejectedException("retry pool gone"))
        .when(retryScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    NotifierClient client = configuredClient();

    boolean published = check(fixedAt(today, 8, 57), unhealthy(), client, CRON).catchUpIfMissed();

    assertThat(published).isFalse();
    verifyNoInteractions(client);
    assertSoftly(
        softly -> {
          softly.assertThat(canaryRunStatus(today)).isEqualTo("CLAIMED");
          softly
              .assertThat(canaryRunSource(today))
              .as("the holder's row must be untouched by the loser")
              .isEqualTo("SCHEDULED");
        });
  }

  /**
   * A retry that wakes up after IST midnight must still resolve the day it was scheduled FOR.
   *
   * <p>Re-deriving the day inside the retry fails twice over, and the second failure is the worse
   * one: day D is never resolved (row stuck {@code CLAIMED}, alert never sent), AND day D+1 is
   * stamped {@code DONE} by a run that never evaluated it — which then suppresses D+1's own 08:30
   * canary. A stale retry would silence a FUTURE day's genuine check.
   *
   * <p>Not exotic: the retry fires at lease expiry and the budgeted chain can extend well past
   * midnight, which is exactly the crash-restart case the retry exists for.
   */
  @Test
  void theRetryResolvesItsOwnDayEvenWhenItFiresAfterMidnight() {
    LocalDate today = LocalDate.of(2026, 7, 6);
    LocalDate tomorrow = today.plusDays(1);
    clearCanaryRun(today);
    clearCanaryRun(tomorrow);

    // a door holds day D late in the evening and never completes
    Instant held = istAt(today, 23, 58);
    seedIncompleteClaim(today, "SCHEDULED", held);

    MovableClock clock = new MovableClock(istAt(today, 23, 59));
    NotifierClient client = configuredClient();
    assertThat(check(clock, unhealthy(), client, CRON).catchUpIfMissed()).isFalse();
    Runnable retry = scheduledRetry(held.plus(Duration.ofMinutes(5)).plusSeconds(1));

    // the lease lapses at 00:03:01 — the NEXT IST day
    clock.advanceTo(istAt(tomorrow, 0, 3).plusSeconds(1));
    retry.run();

    verify(client).send(eq("NTFY"), anyString(), anyString());
    // SOFT so a regression reports BOTH halves — recomputing the day fails them together, and the
    // D+1 half (a stale retry silencing a future day's genuine canary) is the worse of the two.
    assertSoftly(
        softly -> {
          softly
              .assertThat(canaryRunStatus(today))
              .as("the retry must resolve day D, the day it was scheduled for")
              .isEqualTo("DONE");
          softly
              .assertThat(canaryRunStatus(tomorrow))
              .as("day D+1 must be untouched, so its own 08:30 canary still runs")
              .isNull();
        });
  }

  /**
   * The run day is the IST calendar day, not the container's UTC one. 00:30 IST is 19:00 UTC on the
   * PREVIOUS date, so a {@code now()::date} marker would land on 05-10 and a later 08:30 IST fire on
   * 05-11 would re-alert — the off-by-one this lineage's IST rule exists to prevent.
   */
  @Test
  void theRunDayIsTheIstCalendarDayNotTheUtcOne() {
    LocalDate istDay = LocalDate.of(2026, 5, 11);
    clearCanaryRun(istDay);
    clearCanaryRun(istDay.minusDays(1));

    check(fixedAt(istDay, 0, 30), unhealthy(), configuredClient(), CRON).check();

    assertThat(canaryRunSource(istDay)).isEqualTo("SCHEDULED");
    assertThat(canaryRunSource(istDay.minusDays(1))).isNull();
  }

  // ---- fixtures ---------------------------------------------------------------------------------

  private NotifierHealthCheck check(
      Clock clock, NotificationRepository repo, NotifierClient client, String cron) {
    return new NotifierHealthCheck(repo, client, clock, meters, 24, 1, 0.5, cron, retryScheduler);
  }

  /** Captures the retry the losing door schedules, so the lease can be driven deterministically. */
  private Runnable scheduledRetry(Instant expectedAt) {
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
    verify(retryScheduler).schedule(task.capture(), at.capture());
    assertThat(at.getValue()).as("the retry must land just past the holder's lease").isEqualTo(expectedAt);
    return task.getValue();
  }

  /** A clock the test can move, so a scheduled retry can be run with the lease genuinely expired. */
  private static final class MovableClock extends Clock {
    private Instant now;

    private MovableClock(Instant start) {
      this.now = start;
    }

    private void advanceTo(Instant later) {
      this.now = later;
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  /** A real repository (the claim hits the real table) whose delivery verdict is forced to RED. */
  private NotificationRepository unhealthy() {
    NotificationRepository repo = spy(new NotificationRepository(jdbc));
    doReturn(new DeliveryStats(0, 4, 0)).when(repo).deliveryStats(any()); // 4/4 = 1.0
    return repo;
  }

  /** A repository whose window read fails — the claim must never be reached. */
  private NotificationRepository evaluationFails() {
    NotificationRepository repo = spy(new NotificationRepository(jdbc));
    doThrow(new DataAccessResourceFailureException("db blip")).when(repo).deliveryStats(any());
    return repo;
  }

  /** A RED verdict whose claim write fails — the deliberate fail-open. */
  private NotificationRepository unhealthyWithFailingClaim() {
    NotificationRepository repo = unhealthy();
    doThrow(new DataAccessResourceFailureException("marker gone"))
        .when(repo)
        .claimCanaryRun(anyString(), any(), anyString(), any(), any());
    return repo;
  }

  /**
   * A RED verdict that claims and publishes but never completes — the state a process killed
   * between its push and its bookkeeping leaves behind.
   */
  private NotificationRepository unhealthyWithFailingCompletion() {
    NotificationRepository repo = unhealthy();
    doThrow(new DataAccessResourceFailureException("died after publishing"))
        .when(repo)
        .completeCanaryRun(anyString(), any(), any(), any());
    return repo;
  }

  private static NotifierClient configuredClient() {
    NotifierClient client = mock(NotifierClient.class);
    when(client.configured(anyString())).thenReturn(true);
    return client;
  }

  /** A fixed clock at an IST wall-clock time on {@code day} (the JVM zone stays UTC deliberately). */
  private static Clock fixedAt(LocalDate day, int hour, int minute) {
    return Clock.fixed(day.atTime(hour, minute).atZone(Ist.ZONE).toInstant(), ZoneOffset.UTC);
  }

  private void clearCanaryRun(LocalDate day) {
    jdbc.update(
        "DELETE FROM canary_runs WHERE canary = ? AND run_day = ?",
        NotifierHealthCheck.CANARY_KEY,
        day);
  }

  /** A COMPLETED run: the only state that suppresses a later door. */
  private void seedCompletedRun(LocalDate day, String source, Instant at) {
    seedCanaryRun(day, source, "DONE", at);
  }

  /**
   * A run that claimed and never completed — the post-crash state Critical 2 is about. Whether it
   * suppresses depends entirely on whether {@code claimedAt} is inside the lease.
   */
  private void seedIncompleteClaim(LocalDate day, String source, Instant claimedAt) {
    seedCanaryRun(day, source, "CLAIMED", claimedAt);
  }

  private void seedCanaryRun(LocalDate day, String source, String status, Instant claimedAt) {
    jdbc.update(
        "INSERT INTO canary_runs (canary, run_day, source, status, claimed_at) VALUES (?,?,?,?,?)",
        NotifierHealthCheck.CANARY_KEY,
        day,
        source,
        status,
        Timestamp.from(claimedAt));
  }

  /** The recorded status for {@code day}, or null when no row exists. */
  private String canaryRunStatus(LocalDate day) {
    List<String> statuses =
        jdbc.queryForList(
            "SELECT status FROM canary_runs WHERE canary = ? AND run_day = ?",
            String.class,
            NotifierHealthCheck.CANARY_KEY,
            day);
    return statuses.isEmpty() ? null : statuses.get(0);
  }

  /** An IST wall-clock instant on {@code day} — for seeding claim timestamps. */
  private static Instant istAt(LocalDate day, int hour, int minute) {
    return day.atTime(hour, minute).atZone(Ist.ZONE).toInstant();
  }

  /** The recorded run marker for {@code day}, or null when the check never ran that IST day. */
  private String canaryRunSource(LocalDate day) {
    List<String> sources =
        jdbc.queryForList(
            "SELECT source FROM canary_runs WHERE canary = ? AND run_day = ?",
            String.class,
            NotifierHealthCheck.CANARY_KEY,
            day);
    return sources.isEmpty() ? null : sources.get(0);
  }
}
