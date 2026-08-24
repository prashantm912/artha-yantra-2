package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;

/**
 * Ledger H18: since the 16:00/08:35 split the 08:35 sweep is the ONLY path that takes swing entries,
 * it was {@code @Scheduled}-only, and this machine boots ~08:00 on weekdays — so a boot at 08:38
 * skipped it silently and forfeited a whole session's entries. Measured 2026-08-14: booted 08:38,
 * {@code swing_catchup_runs} held NO ROW at all for {@code session_date=2026-08-13}.
 *
 * <p>⚠️ <b>That "no row" is the property these tests exist for.</b> A missed cron never seeded, so
 * {@code retryableSessions} is EMPTY — a boot path that merely drained existing non-terminal rows
 * would find nothing to do, log nothing, and reproduce the defect while looking like it worked. The
 * ledger fake below is therefore STATEFUL and starts empty: it returns only what was actually seeded
 * through it, so a drain-only boot door cannot pass {@link #bootAfterTheCronSweepsASessionThatHasNoLedgerRowAtAll()}.
 *
 * <p>No clock here reads real time and nothing writes to a database — no fixture can rot into a
 * different test class when real time reaches a date (ledger H19). Most are {@code Clock.fixed}; the
 * mid-sweep case uses {@link WindableClock}, which still starts from a fixed instant and only ever
 * moves when a test explicitly winds it.
 */
class SwingBatchBootCatchUpTest {

  /** Friday 2026-08-14, 08:38 IST (03:08Z) — the real incident: three minutes past the 08:35 cron. */
  private static final Clock BOOTED_0838 = fixed("2026-08-14T03:08:00Z");
  /** Friday 2026-08-14, 07:00 IST — before the cron, so nothing has been missed yet. */
  private static final Clock BOOTED_0700 = fixed("2026-08-14T01:30:00Z");
  /**
   * Friday 2026-08-14, 09:04 IST — one minute of clear air outside the reserve, and squarely inside
   * the blanket 09:00–09:15 band the door used to refuse outright.
   */
  private static final Clock BOOTED_0904 = fixed("2026-08-14T03:34:00Z");
  /** Friday 2026-08-14, 22:14 IST — the EVENING boot the original boot-catch-up rejection cited. */
  private static final Clock BOOTED_2214 = fixed("2026-08-14T16:44:00Z");
  /** Saturday 2026-08-15, 10:00 IST — a non-trading day, so ONLY the cron's weekday clause refuses. */
  private static final Clock BOOTED_SATURDAY = fixed("2026-08-15T04:30:00Z");
  /** Friday 2026-08-14, 09:05 IST — EXACTLY the cutoff (09:15 open less the 10-minute reserve). */
  private static final Clock BOOTED_0905 = fixed("2026-08-14T03:35:00Z");

  /**
   * 09:06 IST — INSIDE the gap between the boot door's 09:05 pre-open reserve and the cron's own
   * 09:15 market-open deadline. The only window in which the two stop-checks disagree, and
   * therefore the only window that can tell them apart.
   */
  private static final Clock CRON_0906 = fixed("2026-08-14T03:36:00Z");
  /** Friday 2026-08-14, 08:59 IST — comfortably clear of the reserve on the accepting side. */
  private static final Clock BOOTED_0859 = fixed("2026-08-14T03:29:00Z");
  /**
   * Monday 2026-09-14, 18:30 IST — Ganesh Chaturthi, a WEEKDAY exchange holiday. The cron is
   * {@code MON-FRI} and holiday-blind so its fire "passed", while {@code isTradingDay} is false all
   * day — which is what made the old trading-day-conditional deadline gate structurally inert here.
   */
  private static final Clock BOOTED_HOLIDAY_EVENING = fixed("2026-09-14T13:00:00Z");
  /** Monday 2026-09-14, 08:38 IST — the same weekday holiday, but inside the cron's own window. */
  private static final Clock BOOTED_HOLIDAY_MORNING = fixed("2026-09-14T03:08:00Z");

  private static final String CRON = "0 35 8 * * MON-FRI";
  private static final String BATCH = "manas-arora";
  /** Thursday 2026-08-13 — the session whose entries the missed 08:35 fire would have forfeited. */
  private static final LocalDate MISSED = LocalDate.of(2026, 8, 13);
  /** Tuesday 2026-08-11 — a SECOND missed session, so a sweep has two runs to get through. */
  private static final LocalDate MISSED_EARLIER = LocalDate.of(2026, 8, 11);
  /**
   * Friday 2026-09-11 — the trading session before the 2026-09-14 holiday, and the only date the
   * holiday-morning boot can meaningfully owe entries for: it is the one that sits inside
   * {@code sessionWindow(2026-09-14)}.
   */
  private static final LocalDate MISSED_BEFORE_HOLIDAY = LocalDate.of(2026, 9, 11);
  /** Wednesday 2026-08-12 — the last recorded run, i.e. this deployment owns the rolling history. */
  private static final LocalDate LAST_RUN = LocalDate.of(2026, 8, 12);

  private final SwingBatchRecorder recorder = mock(SwingBatchRecorder.class);
  private final SwingBatchRunRepository runs = mock(SwingBatchRunRepository.class);
  private final SwingBatchIntentRepository intents = mock(SwingBatchIntentRepository.class);
  private final SwingPaperEffectRepository effects = mock(SwingPaperEffectRepository.class);
  private final SignalRepository signals = mock(SignalRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final Ledger ledger = new Ledger(runs);

  private static Clock fixed(String instant) {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
  }

  /**
   * A stateful stand-in for {@code swing_catchup_runs}. It starts EMPTY — which is exactly the state
   * a missed cron leaves behind — and {@link #retryableSessions} returns only sessions that were
   * genuinely seeded through it. A Mockito mock with a canned {@code retryableSessions} list cannot
   * express this defect at all: it hands the sweep the very rows whose absence IS the bug.
   */
  private static final class Ledger {

    private final Set<LocalDate> pending = new TreeSet<>();
    private final Set<LocalDate> terminal = new TreeSet<>();
    /** Sessions a dead process still holds a RUNNING claim on — unclaimable until released. */
    private final Set<LocalDate> orphaned = new TreeSet<>();
    private final List<LocalDate> seeded = new ArrayList<>();
    private final SwingCatchUpStateRepository repo = mock(SwingCatchUpStateRepository.class);
    private final SwingBatchRunRepository runs;

    Ledger(SwingBatchRunRepository runs) {
      this.runs = runs;
      doAnswer(seed()).when(repo).seedWindow(any(), any());
      doAnswer(seed()).when(repo).seedPending(any(), any());
      when(repo.retryableSessions(any(), anyInt())).thenAnswer(call -> List.copyOf(pending));
      when(repo.claim(any(), any(), anyInt()))
          .thenAnswer(
              call -> {
                LocalDate session = call.getArgument(1);
                // A claim moves the row to RUNNING, so it leaves the retryable set; a terminal or
                // unseeded row is never claimable. This is the durable idempotency the boot door
                // reuses rather than replaces.
                return pending.remove(session)
                    ? Optional.of(new SwingCatchUpStateRepository.Claim(1))
                    : Optional.empty();
              });
      // An orphaned RUNNING claim: seeded, not pending, and NOT claimable — the state a hard death
      // leaves behind. Only releaseClaimsFrom may move it back, which is what the Critical is about.
      when(repo.releaseClaimsFrom(any()))
          .thenAnswer(
              call -> {
                int released = orphaned.size();
                pending.addAll(orphaned);
                orphaned.clear();
                return released;
              });
      doAnswer(terminalWrite()).when(repo).markDone(any(), any());
      doAnswer(terminalWrite()).when(repo).markAbandoned(any(), any());
      doAnswer(terminalWrite()).when(repo).markAbandoned(any(), any(), any());
      doAnswer(terminalWrite()).when(repo).recordDisarmed(any(), any());
    }

    private org.mockito.stubbing.Answer<Void> seed() {
      return call -> {
        List<LocalDate> sessions = call.getArgument(1);
        for (LocalDate session : sessions) {
          seeded.add(session);
          // Mirrors seedMissing's `WHERE NOT EXISTS (... COALESCE(entries_enabled, true))`: a
          // session whose ENTRIES already ran is not seeded. `hasRun` alone is the 16:00 exits-only
          // marker and would skip every session — the V060 distinction.
          if (!terminal.contains(session)
              && !orphaned.contains(session)
              && !runs.hasRunWithEntries(BATCH, session)) {
            pending.add(session);
          }
        }
        return null;
      };
    }

    private org.mockito.stubbing.Answer<Void> terminalWrite() {
      return call -> {
        LocalDate session = call.getArgument(1);
        pending.remove(session);
        terminal.add(session);
        return null;
      };
    }
  }

  /** An armed family whose 2026-08-13 entries never ran, with a funnel serving that day's screen. */
  private SwingDoctrine armedFamilyMissingThursdaysEntries() {
    return armedFamilyMissingEntriesFor(MISSED);
  }

  /**
   * The same fixture pinned to any session, because the session MUST lie inside the boot day's own
   * {@code sessionWindow} or the sweep has nothing to claim and every assertion about it is vacuous.
   */
  private SwingDoctrine armedFamilyMissingEntriesFor(LocalDate session) {
    when(runs.lastRunDate(BATCH)).thenReturn(Optional.of(LAST_RUN));
    // Every window session has complete entries EXCEPT the missed one — Mockito's boolean default is
    // false, so state it explicitly rather than relying on the absence of a stub.
    when(runs.hasRunWithEntries(any(), any())).thenReturn(true);
    when(runs.hasRunWithEntries(BATCH, session)).thenReturn(false);
    when(runs.hasRun(BATCH, session)).thenReturn(true); // the 16:00 exits-only pass wrote its marker
    when(intents.findIntent(BATCH, session))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, true)));
    when(effects.allConfirmed(any(), any())).thenReturn(true);
    when(effects.repairable(any(), any())).thenReturn(List.of());
    when(effects.pendingSessions(any())).thenReturn(List.of());
    when(recorder.runAndRecord(any(), any(), anyBoolean(), any(), any(), any()))
        .thenReturn(
            new SwingBatchRecorder.RunOutcome(
                new SwingBatchEngine.SwingRun(
                    4, 12, 4, 0, 0, SwingBatchEngine.AdmissionProbe.empty()),
                true));

    SwingDoctrine doctrine = mock(SwingDoctrine.class);
    when(doctrine.batchName()).thenReturn(BATCH);
    when(doctrine.alertLabel()).thenReturn("Manas swing");
    when(doctrine.enabled()).thenReturn(true);
    when(doctrine.candidateSnapshot())
        .thenReturn(
            new SwingDoctrine.CandidateSnapshotRead(
                Optional.of(new SwingDoctrine.CandidateSnapshot(session, List.of()))));
    return doctrine;
  }

  /** The 08-13 fixture plus a SECOND owed session, so the sweep has two runs queued oldest-first. */
  private SwingDoctrine armedFamilyMissingTwoSessions() {
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();
    when(runs.hasRunWithEntries(BATCH, MISSED_EARLIER)).thenReturn(false);
    when(runs.hasRun(BATCH, MISSED_EARLIER)).thenReturn(true);
    when(intents.findIntent(BATCH, MISSED_EARLIER))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, true)));
    return doctrine;
  }

  private SwingBatchBootCatchUp bootCatchUp(Clock clock, String cron, SwingDoctrine... doctrines) {
    return bootCatchUp(clock, cron, true, doctrines);
  }

  /**
   * @param bootEnabled {@code artha.swing.boot-catchup-enabled} — this door's own arming, default
   *     false in production. {@code artha.swing.catchup-enabled} is passed true throughout, which is
   *     the LIVE state: {@code .env} sets it true to arm the 08:35 entry pass.
   */
  private SwingBatchBootCatchUp bootCatchUp(
      Clock clock, String cron, boolean bootEnabled, SwingDoctrine... doctrines) {
    return new SwingBatchBootCatchUp(
        swingCatchUp(clock, cron, doctrines), inlineScheduler(), ledger.repo, clock, bootEnabled);
  }

  /** The sweep itself, always with {@code catchup-enabled=true} — the live production value. */
  private SwingBatchCatchUp swingCatchUp(Clock clock, String cron, SwingDoctrine... doctrines) {
    return new SwingBatchCatchUp(
        recorder,
        runs,
        ledger.repo,
        intents,
        effects,
        signals,
        new SwingRunMutex(),
        List.of(doctrines),
        events,
        clock,
        true,
        5,
        cron);
  }

  /**
   * A scheduler that runs the submitted task on the calling thread. Overriding {@code execute} means
   * the real pool is never started, so nothing here leaks a thread across the suite.
   */
  private static ThreadPoolTaskScheduler inlineScheduler() {
    return new ThreadPoolTaskScheduler() {
      @Override
      public void execute(Runnable task) {
        task.run();
      }
    };
  }

  /**
   * A scheduler that RECORDS the submitted task instead of running it. That distinction is the whole
   * point: with an inline scheduler a door that calls the sweep DIRECTLY is indistinguishable from
   * one that dispatches, because both leave the same effects behind by the time the test looks.
   */
  private static final class RecordingScheduler extends ThreadPoolTaskScheduler {

    private final transient List<Runnable> submitted = new ArrayList<>();

    @Override
    public void execute(Runnable task) {
      submitted.add(task);
    }
  }

  /**
   * A clock a test can wind forward. {@code Clock.fixed} cannot express the mid-sweep case at all:
   * the pre-open reserve is spent BY the sweep's own work, so what has to be asserted is a clock
   * that moves while the session loop is running.
   */
  private static final class WindableClock extends Clock {

    /** Shared across every {@link #withZone} copy — the sweep re-reads the clock on every gate. */
    private final AtomicReference<Instant> now;

    private final ZoneId zone;

    WindableClock(String start) {
      this(new AtomicReference<>(Instant.parse(start)), ZoneOffset.UTC);
    }

    private WindableClock(AtomicReference<Instant> now, ZoneId zone) {
      this.now = now;
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
      return new WindableClock(now, other);
    }

    @Override
    public Instant instant() {
      return now.get();
    }

    void wind(Duration by) {
      now.updateAndGet(instant -> instant.plus(by));
    }
  }

  @Test
  @DisplayName("THE defect: a boot at 08:38 sweeps a session that has NO swing_catchup_runs row")
  void bootAfterTheCronSweepsASessionThatHasNoLedgerRowAtAll() {
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();
    // The premise, asserted rather than assumed: the missed cron left the ledger completely empty.
    assertThat(ledger.repo.retryableSessions(BATCH, 30))
        .as("the fixture must start with NO row, or it is not reproducing the 2026-08-14 state")
        .isEmpty();

    bootCatchUp(BOOTED_0838, CRON, doctrine).onStartup();

    assertThat(ledger.seeded)
        .as(
            "the boot door must SEED the session window like the cron path does. A missed cron"
                + " creates no row, so a boot sweep that only drains existing rows finds nothing to"
                + " do and forfeits the entries exactly as the bug did")
        .contains(MISSED);
    ArgumentCaptor<LocalDate> session = ArgumentCaptor.forClass(LocalDate.class);
    ArgumentCaptor<Boolean> entriesReady = ArgumentCaptor.forClass(Boolean.class);
    verify(recorder)
        .runAndRecord(
            any(), session.capture(), entriesReady.capture(), any(), any(), any());
    assertThat(session.getValue())
        .as("the sweep must be PINNED to the missed session, not run against today's funnel")
        .isEqualTo(MISSED);
    assertThat(entriesReady.getValue())
        .as(
            "the whole point of H18 — the 16:00 pass is exits-only, so a boot sweep that does not"
                + " take ENTRIES recovers nothing that matters")
        .isTrue();
  }

  @Test
  @DisplayName("THE SHIPPED STATE: boot-catchup-enabled=false + catchup-enabled=true is fully inert")
  void theShippedProductionStateIsInert() {
    // Exactly what merging this produces before the owner arms it: the boot door's own flag is
    // false, while artha.swing.catchup-enabled is TRUE because .env sets it true to arm the live
    // 08:35 entry pass. Every other condition is satisfied — it is the 08:38 incident morning — so
    // this flag is the ONLY thing standing between merge and a live money effect.
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    bootCatchUp(BOOTED_0838, CRON, false, doctrine).onStartup();

    assertThat(ledger.seeded)
        .as("the boot door must not even SEED while its own flag is off")
        .isEmpty();
    verify(ledger.repo, never()).claim(any(), any(), anyInt());
    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("the CRON keeps its 09:15 deadline — the boot door's 09:05 reserve is not shared")
  void theCronKeepsItsOwnMarketOpenDeadline() {
    // ⚠️ Cross-vendor review Major, 2026-08-20. The boot door wants a stricter wall-clock reserve,
    // but catchUpIfMissed() reaches the family loop by calling catchUp() — the SAME method the
    // 08:35 cron calls — so a single shared predicate silently moved the LIVE cutoff from 09:15 to
    // 09:05. A slow cron would then stop ten minutes early and forfeit entries, on a change whose
    // PR claimed to be inert to the cron path.
    //
    // 09:06 is the whole test: past the boot reserve, before the market-open deadline. Under the
    // shared predicate the loop returns before touching a single session; under the fix it sweeps.
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    swingCatchUp(CRON_0906, CRON, doctrine).catchUp();

    verify(recorder)
        .runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("a claim orphaned by a hard death is released on boot, so this sweep can retake it")
  void anOrphanedRunningClaimIsReleasedBeforeTheBootSweep() {
    // ⚠️ Cross-vendor review Critical, 2026-08-20. A RuntimeException inside a claimed run is
    // already handled — the caller holds the claim and writes PENDING in its catch. A HARD death
    // (JVM kill, container stop, power loss) is not: the row stays RUNNING and claim() refuses it
    // for STALE_LEASE_MINUTES (30). With an 08:35 cron and a boot door that shuts at 09:05, that
    // lease CANNOT expire while there is still time to act — so restarting the service does
    // nothing and the session's entries are forfeited.
    //
    // The fixture models exactly that: MISSED is seeded but held RUNNING by a dead process, so it
    // is not in the retryable set and cannot be claimed. Only the release can free it.
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();
    ledger.orphaned.add(MISSED);

    bootCatchUp(BOOTED_0838, CRON, doctrine).onStartup();

    verify(ledger.repo).releaseClaimsFrom(any());
    verify(ledger.repo).claim(BATCH, MISSED, 30);
    verify(recorder).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("the 08:35 CRON path is untouched by the boot flag, in both of its positions")
  void theCronPathIsUnaffectedByTheBootFlag() {
    // The boot flag is read in SwingBatchBootCatchUp, never inside catchUp(), so the cron path
    // cannot see it. Driving catchUp() directly is what proves that rather than asserting it: this
    // is the 08:35 tick, and it must sweep identically whatever the boot door is set to.
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    swingCatchUp(BOOTED_0838, CRON, doctrine).catchUp();

    assertThat(ledger.seeded)
        .as("the cron sweep still seeds its window with the boot door disarmed")
        .contains(MISSED);
    verify(recorder).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("a boot BEFORE the cron leaves the job to the scheduler")
  void bootBeforeTheCronDoesNothing() {
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    bootCatchUp(BOOTED_0700, CRON, doctrine).onStartup();

    assertThat(ledger.seeded)
        .as("07:00 is before the 08:35 fire — nothing has been missed, so the boot door must not run")
        .isEmpty();
    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("a WEEKDAY-HOLIDAY evening boot is refused — the day type that defeated the old gate")
  void aWeekdayHolidayEveningBootIsRefused() {
    // ⚠️ MAJOR 1 (cross-vendor review). On Ganesh Chaturthi the cron still "fired" (MON-FRI is
    // holiday-blind) while isTradingDay is false ALL DAY — so the previous
    // `isTradingDay(today) && time >= SESSION_OPEN` gate could never be true and this boot took real
    // entries at 18:30 on a holiday evening. 16 weekday holidays in the bundled 2026 calendar have
    // this shape. The wall-clock cutoff refuses it without consulting the calendar at all.
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    bootCatchUp(BOOTED_HOLIDAY_EVENING, CRON, doctrine).onStartup();

    assertThat(ledger.seeded)
        .as("a weekday-holiday evening boot must not sweep — this is the exact 22:14 shape the"
            + " original boot-catch-up rejection was reasoned from, arriving on the one day type"
            + " where the trading-day-conditional deadline gate is structurally inert")
        .isEmpty();
    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("a weekday-holiday MORNING boot still sweeps — it mirrors the holiday-blind cron")
  void aWeekdayHolidayMorningBootStillSweeps() {
    // The deliberate half of the Major 1 decision, pinned so it cannot be "tightened" by accident.
    // The 08:35 cron IS holiday-blind and fires on this day, so refusing here would make the boot
    // door STRICTER than the schedule it stands in for, and the prior sessions in its window are
    // real sessions that may still owe entries. Holidays sweep; holiday EVENINGS do not.
    //
    // ⚠️ The owed session MUST sit inside sessionWindow(2026-09-14). This test used to reuse the
    // AUGUST fixture, which does not — so every September window session read hasRunWithEntries=true,
    // nothing was ever claimed, and no sweep was exercised at all. `ledger.seeded` was satisfied by
    // seedWindow alone, which means it asserted that a holiday-morning boot SEEDS and never that it
    // SWEEPS: the test stayed green with the sweep doing nothing.
    SwingDoctrine doctrine = armedFamilyMissingEntriesFor(MISSED_BEFORE_HOLIDAY);

    bootCatchUp(BOOTED_HOLIDAY_MORNING, CRON, doctrine).onStartup();

    ArgumentCaptor<LocalDate> ran = ArgumentCaptor.forClass(LocalDate.class);
    verify(recorder, atLeastOnce())
        .runAndRecord(any(), ran.capture(), anyBoolean(), any(), any(), any());
    assertThat(ran.getAllValues())
        .as("a weekday holiday inside the cron's own window must actually RUN the prior session's"
            + " pass, pinned to that session, exactly as the holiday-blind 08:35 cron would")
        .containsExactly(MISSED_BEFORE_HOLIDAY);
  }

  @Test
  @DisplayName("the 22:14 EVENING boot the original rejection cited is STILL refused")
  void anEveningBootIsStillRefused() {
    // The 2026-07-17 reasoning ("it fires once, at the moment inputs are least likely to exist")
    // stays honoured: at 22:14 today's fire HAS passed, so a time gate is the only thing refusing it.
    //
    // ⚠️ MINOR 1 (review): an earlier comment here claimed "if that gate were dropped, this test —
    // not the morning ones — is what would catch it." Red-proof 3 MEASURED that to be false: while
    // the gate was trading-day-conditional, removing the boot door's copy left this test GREEN
    // because catchUp() carried an identical one. Since the Major 1 fix the boot door's gate is
    // WALL-CLOCK and strictly stronger than catchUp()'s, so it is no longer redundant at all — and
    // the weekday-holiday test above is the one that proves the difference.
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    bootCatchUp(BOOTED_2214, CRON, doctrine).onStartup();

    assertThat(ledger.seeded)
        .as("an END-OF-DAY batch may not price off a session's partial daily bar")
        .isEmpty();
    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("09:05 — exactly at the reserve cutoff, so refused; the bound is inclusive")
  void aBootInsideThePreOpenReserveIsRefused() {
    // ⚠️ MAJOR 2 (cross-vendor review). This band was entirely untested: the old gate accepted any
    // instant strictly before 09:15. Crossing 09:15 mid-entry-pass is not merely late —
    // SwingBatchEngine skips the EXIT pass entirely when the entry pass trips the deadline, so it
    // takes a partial entry set AND leaves every held stop unevaluated, and the half-done session
    // can never be completed.
    //
    // 09:05 is now the cutoff EXACTLY (09:15 open minus the 10-minute per-session-run reserve), so
    // this also pins the bound as inclusive. The reserve shrank from 15 minutes because the figure
    // it was sized against — "up to 14 session runs" — was an assumption, not a bound; what the
    // reserve actually protects is ONE run, and the loop re-checks it at every session boundary.
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    bootCatchUp(BOOTED_0905, CRON, doctrine).onStartup();

    assertThat(ledger.seeded)
        .as("a sweep without one session run's worth of clear air must not START — a partial entry"
            + " set with the exit pass skipped is strictly worse than not running")
        .isEmpty();
    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("08:59 — clear of the reserve on the accepting side — still sweeps")
  void aBootJustInsideTheReserveBoundarySweeps() {
    // The other side of the boundary, so the reserve cannot be silently widened into uselessness:
    // a guard that refuses everything would pass every refusal test above while forfeiting every
    // real recovery, including the 08:38 incident this whole change exists for. (The LAST accepting
    // minute is 09:04, pinned separately below — this one holds the band that was already safe
    // under the old 15-minute door, so re-widening the reserve to 16 minutes or more, which would
    // put the cutoff at or before 08:59, is caught here too.)
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    bootCatchUp(BOOTED_0859, CRON, doctrine).onStartup();

    assertThat(ledger.seeded).contains(MISSED);
    verify(recorder).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("09:04 — inside the OLD blanket 09:00 door, outside the reserve — now sweeps")
  void aBootJustOutsideTheReserveStillSweeps() {
    // This replaces a 09:15 test that could not fail: 09:15 was never the governing bound on either
    // side of this change (the door refused from 09:00, and now from 09:05), so it passed
    // identically before and after and pinned nothing.
    //
    // 09:04 is the case the blanket 15-minute door got WRONG. Cold start on this machine is 6.1 /
    // 7.8 / 13.1 s measured, so the whole 09:00–09:15 refused band is reachable by a ~09:00
    // power-on — and the refusal is PERMANENT, not deferred: the next morning's 08:35 sweep
    // re-claims the session but the funnel now serves a newer screen, so entriesReady is false
    // (SCREEN_NOT_AS_OF_SESSION) until the attempt budget runs out and the session is ABANDONED
    // "UNRECOVERABLE by the catch-up". The modal backlog is one session per family — 81 s measured
    // — against eleven minutes of clear air here, so this is not a marginal call.
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    bootCatchUp(BOOTED_0904, CRON, doctrine).onStartup();

    assertThat(ledger.seeded)
        .as("a boot with a full session run's reserve in hand must sweep — refusing it forfeits the"
            + " session's entries permanently")
        .contains(MISSED);
    verify(recorder).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("the reserve is re-checked at EVERY session boundary, not only at the boot door")
  void theSweepStopsBetweenSessionsOnceTheReserveIsSpent() {
    // ⚠️ THE M1 DEFECT. The reserve used to be a START gate and nothing else: once past the door,
    // every in-flight gate was marketOpenDeadlinePassed() — 09:15, and trading-day-conditional. So a
    // sweep admitted at 08:59 kept CLAIMING new sessions right up to 09:15 with no reserve left,
    // and the run that straddled the open took a partial entry set with its exit pass skipped. The
    // door's own ceiling could never bound that: retryableSessions returns every retryable row,
    // including ones older than the rolling window, so the sweep's length is not knowable when it
    // starts.
    SwingDoctrine doctrine = armedFamilyMissingTwoSessions();
    WindableClock clock = new WindableClock("2026-08-14T03:29:00Z"); // 08:59 IST — full headroom
    // The sweep's own work is what spends the reserve. One long session run puts the clock at 09:09
    // — past the 09:05 cutoff but still before the 09:15 open, which is exactly the band the old
    // trading-day-conditional deadline gate waved through.
    when(recorder.runAndRecord(any(), any(), anyBoolean(), any(), any(), any()))
        .thenAnswer(
            call -> {
              clock.wind(Duration.ofMinutes(10));
              return new SwingBatchRecorder.RunOutcome(
                  new SwingBatchEngine.SwingRun(
                      4, 12, 4, 0, 0, SwingBatchEngine.AdmissionProbe.empty()),
                  true);
            });

    bootCatchUp(clock, CRON, doctrine).onStartup();

    ArgumentCaptor<LocalDate> ran = ArgumentCaptor.forClass(LocalDate.class);
    verify(recorder, atLeastOnce())
        .runAndRecord(any(), ran.capture(), anyBoolean(), any(), any(), any());
    assertThat(ran.getAllValues())
        .as("only the OLDEST owed session may run: after it the clock reads 09:09, so the reserve is"
            + " spent and no NEW session run may start")
        .containsExactly(MISSED_EARLIER);
    assertThat(ledger.pending)
        .as("the session that did not start must stay retryable for the next sweep — stopping"
            + " BETWEEN sessions is safe, being cut off INSIDE one is not")
        .contains(MISSED);
  }

  @Test
  @DisplayName("a weekend boot is refused by the cron's own weekday clause, not by the calendar")
  void aWeekendBootIsRefused() {
    // Saturday is NOT a trading day, so marketOpenDeadlinePassed() returns FALSE and cannot refuse
    // anything. The only thing standing between a Saturday boot and a full sweep is that MON-FRI
    // puts the next fire on Monday — which is precisely why the gate reads the cron EXPRESSION
    // rather than comparing against a hardcoded 08:35.
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    bootCatchUp(BOOTED_SATURDAY, CRON, doctrine).onStartup();

    assertThat(ledger.seeded).isEmpty();
    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("boot armed but catchup-enabled off: reports NOT-run rather than announcing a replay")
  void aDisarmedSweepIsNotAnnouncedAsAReplay() {
    // ⚠️ MINOR 2 (review). This combination used to log "replaying the missed sweep on boot" at WARN
    // and then call catchUp(), which returned having done nothing — an operator-facing sentence that
    // was simply false, on the one path where someone is most likely to be reading the log to work
    // out why nothing happened. catchUpIfMissed now returns false, honestly.
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();
    SwingBatchCatchUp disarmedSweep =
        new SwingBatchCatchUp(
            recorder, runs, ledger.repo, intents, effects, signals, new SwingRunMutex(),
            List.of(doctrine), events, BOOTED_0838, false, 5, CRON);

    assertThat(disarmedSweep.catchUpIfMissed())
        .as("a sweep that cannot emit must not report itself as having run")
        .isFalse();
    assertThat(ledger.seeded).isEmpty();
    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("Spring's documented cron-disable value yields no schedule and so no boot catch-up")
  void aDisabledCronNeverFires() {
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    bootCatchUp(BOOTED_0838, Scheduled.CRON_DISABLED, doctrine).onStartup();

    assertThat(ledger.seeded)
        .as("a schedule that never fires cannot have been missed")
        .isEmpty();
    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("a sweep that throws cannot take the service's startup down with it")
  void aFailingSweepIsFailSoft() {
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();
    when(recorder.runAndRecord(any(), any(), anyBoolean(), any(), any(), any()))
        .thenThrow(new IllegalStateException("funnel exploded"));

    bootCatchUp(BOOTED_0838, CRON, doctrine).onStartup();

    // Reaching here at all is the assertion: ApplicationReadyEvent must not propagate this.
    assertThat(ledger.seeded).contains(MISSED);
  }

  @Test
  @DisplayName("the boot one-shot is DISPATCHED onto the pool SwingRunActivity watches, not run inline")
  void theBootSweepRunsOnTheObservedCatchUpPool() throws Exception {
    // Not decoration. That pool is poolSize=1 and already carries catchUp()'s @Scheduled, so this
    // is what makes a boot and an 08:35 tick in the same second SERIALIZE rather than race. It is
    // also what keeps a boot sweep visible to SwingRunActivity, whose contract the pre-open
    // reconciler's wait depends on — a boot sweep on any other thread would leave that gate
    // reporting idle mid-sweep, silently, with every other test still green.
    //
    // ⚠️ The @Qualifier assertion below says WHICH pool and nothing whatever about the door USING
    // it: rewriting onStartup() as a direct runIfMissed() call left this test green. The recording
    // scheduler closes that half — it captures the task WITHOUT running it, so an inline dispatch
    // shows up as a sweep that has already happened before the captured task is ever run.
    assertThat(
            SwingBatchBootCatchUp.class
                .getDeclaredConstructor(
                    SwingBatchCatchUp.class,
                    ThreadPoolTaskScheduler.class,
                    SwingCatchUpStateRepository.class,
                    Clock.class,
                    boolean.class)
                .getParameters()[1]
                .getAnnotation(Qualifier.class)
                .value())
        .isEqualTo("swingCatchUpTaskScheduler");

    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();
    RecordingScheduler scheduler = new RecordingScheduler();

    new SwingBatchBootCatchUp(
            swingCatchUp(BOOTED_0838, CRON, doctrine), scheduler, ledger.repo, BOOTED_0838, true)
        .onStartup();

    assertThat(scheduler.submitted)
        .as("onStartup must hand the sweep to the qualified scheduler rather than running it on the"
            + " ApplicationReadyEvent thread")
        .hasSize(1);
    assertThat(ledger.seeded)
        .as("nothing may have run yet — a sweep that has already happened was dispatched INLINE,"
            + " which is invisible to SwingRunActivity and races the 08:35 tick on the same pool")
        .isEmpty();

    scheduler.submitted.get(0).run();

    assertThat(ledger.seeded)
        .as("and the task the scheduler was handed must be the real sweep, not a no-op")
        .contains(MISSED);
  }

  @Test
  @DisplayName("the boot gate reads the SAME cron default as the @Scheduled it stands in for")
  void theBootGateAndTheScheduleCannotDrift() throws IOException {
    // Two copies of one literal in one file — the same shape IngestCoverageCanary and
    // NotifierHealthCheck already carry. A drift makes the boot door compute "has today's fire
    // passed" against a time the scheduler does not use, and NOTHING else in the repo compares
    // them: OperatingWindowTest and CronPassthroughParityTest both search for the needle
    // `cron = "${artha.swing.catchup-cron:`, which only the annotation carries.
    Path source =
        repoRoot()
            .resolve(
                "services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/"
                    + "swing/SwingBatchCatchUp.java");
    // ⚠️ The enclosing QUOTES are part of the pattern, and the first version of this test lacked
    // them: a `//` comment in the same file names `${artha.swing.catchup-cron:` on purpose (to
    // record why the two schedule guards cannot see the @Value), and a bare `[^}]*` matched from
    // inside that comment across the newline into the annotation below it — reporting a drift
    // between a comment and a literal. Requiring `"` on both sides matches only real string values.
    Matcher m =
        Pattern.compile("\"\\$\\{artha\\.swing\\.catchup-cron:([^}\"]*)\\}\"")
            .matcher(Files.readString(source, StandardCharsets.UTF_8));
    List<String> defaults = new ArrayList<>();
    while (m.find()) {
      defaults.add(m.group(1));
    }
    assertThat(defaults)
        .as("expected exactly two sites: the @Scheduled cron and the @Value the boot gate parses")
        .hasSize(2);
    assertThat(defaults.get(0))
        .as("the @Scheduled default and the boot gate's @Value default have drifted apart")
        .isEqualTo(defaults.get(1));
    assertThat(CronExpression.isValidExpression(defaults.get(0)))
        .as("the shared default must parse, or the boot gate silently disables itself")
        .isTrue();
  }

  @Test
  @DisplayName("ARTHA_SWING_BOOT_CATCHUP_ENABLED actually resolves the property the listener reads")
  void theBootFlagEnvNameResolvesItsProperty() {
    // ⚠️ The #653 trap, and it is the live one for this change: an env name that does not byte-match
    // the property's relaxed binding is SILENTLY SWALLOWED — no error, no warning, the code default
    // simply wins. Here the code default is `false`, so a mistyped passthrough would leave the owner
    // unable to arm the door at all while every log line and every test still looked correct.
    Map<String, Object> env =
        Map.of("ARTHA_SWING_BOOT_CATCHUP_ENABLED", "RESOLVED_BY_THE_ENV_VAR");
    StandardEnvironment spring = new StandardEnvironment();
    spring
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));

    assertThat(
            spring.resolveRequiredPlaceholders("${artha.swing.boot-catchup-enabled:FELL_BACK}"))
        .as(
            "ARTHA_SWING_BOOT_CATCHUP_ENABLED does not resolve artha.swing.boot-catchup-enabled —"
                + " the passthrough is inert and the owner cannot arm the boot door")
        .isEqualTo("RESOLVED_BY_THE_ENV_VAR");
  }

  @Test
  @DisplayName("the boot flag is declared in the code, in compose, and in .env.example, byte-identically")
  void theBootFlagPassthroughExistsEverywhere() throws IOException {
    // A passthrough sitting under another service's block, or under this service's labels/build.args,
    // is indistinguishable from being absent: the container never sees it.
    assertThat(serviceEnvironment())
        .as("ARTHA_SWING_BOOT_CATCHUP_ENABLED must sit under services.strategy-signal-service.environment")
        .containsEntry(
            "ARTHA_SWING_BOOT_CATCHUP_ENABLED", "${ARTHA_SWING_BOOT_CATCHUP_ENABLED:-false}");
    assertThat(Files.readString(repoRoot().resolve(".env.example"), StandardCharsets.UTF_8))
        .as("the owner arms this from .env, so .env.example must document the exact name")
        .contains("ARTHA_SWING_BOOT_CATCHUP_ENABLED");
    assertThat(
            Files.readString(
                repoRoot()
                    .resolve(
                        "services/strategy-signal-service/src/main/java/in/arthayantra/"
                            + "strategysignal/swing/SwingBatchBootCatchUp.java"),
                StandardCharsets.UTF_8))
        .as("the listener must read the property the compose name resolves to, defaulting OFF")
        .contains("${artha.swing.boot-catchup-enabled:false}");
  }

  /** Every {@code KEY: value} under {@code services.strategy-signal-service.environment}. */
  private static Map<String, String> serviceEnvironment() throws IOException {
    List<String> compose =
        Files.readAllLines(repoRoot().resolve("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
    int from = compose.indexOf("  strategy-signal-service:");
    if (from < 0) {
      return fail("no '  strategy-signal-service:' block in deploy/docker-compose.yml");
    }
    Map<String, String> out = new java.util.HashMap<>();
    boolean inEnvironment = false;
    for (int i = from + 1; i < compose.size(); i++) {
      String line = compose.get(i);
      if (line.isBlank() || line.trim().startsWith("#")) {
        continue;
      }
      int indent = line.length() - line.stripLeading().length();
      if (indent <= 2) {
        break; // next service
      }
      if (indent == 4) {
        inEnvironment = "environment:".equals(line.trim());
        continue;
      }
      if (inEnvironment && indent == 6) {
        int colon = line.indexOf(':');
        if (colon > 0) {
          out.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
      }
    }
    return out;
  }

  /** Walks up to the repo root. FAILS rather than skipping, or this becomes a guard checking nothing. */
  private static Path repoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
      if (Files.exists(dir.resolve("deploy/docker-compose.yml"))) {
        return dir;
      }
    }
    return fail("could not locate the repo root from " + Paths.get("").toAbsolutePath());
  }
}
