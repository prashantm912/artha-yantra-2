package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
 * <p>Every clock here is fixed, and nothing writes to a database — no fixture can rot into a
 * different test class when real time reaches a date (ledger H19).
 */
class SwingBatchBootCatchUpTest {

  /** Friday 2026-08-14, 08:38 IST (03:08Z) — the real incident: three minutes past the 08:35 cron. */
  private static final Clock BOOTED_0838 = fixed("2026-08-14T03:08:00Z");
  /** Friday 2026-08-14, 07:00 IST — before the cron, so nothing has been missed yet. */
  private static final Clock BOOTED_0700 = fixed("2026-08-14T01:30:00Z");
  /** Friday 2026-08-14, 09:15 IST exactly — the market-open deadline, inclusive. */
  private static final Clock BOOTED_0915 = fixed("2026-08-14T03:45:00Z");
  /** Friday 2026-08-14, 22:14 IST — the EVENING boot the original boot-catch-up rejection cited. */
  private static final Clock BOOTED_2214 = fixed("2026-08-14T16:44:00Z");
  /** Saturday 2026-08-15, 10:00 IST — a non-trading day, so ONLY the cron's weekday clause refuses. */
  private static final Clock BOOTED_SATURDAY = fixed("2026-08-15T04:30:00Z");

  private static final String CRON = "0 35 8 * * MON-FRI";
  private static final String BATCH = "manas-arora";
  /** Thursday 2026-08-13 — the session whose entries the missed 08:35 fire would have forfeited. */
  private static final LocalDate MISSED = LocalDate.of(2026, 8, 13);
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
          if (!terminal.contains(session) && !runs.hasRunWithEntries(BATCH, session)) {
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
    when(runs.lastRunDate(BATCH)).thenReturn(Optional.of(LAST_RUN));
    // Every window session has complete entries EXCEPT the missed one — Mockito's boolean default is
    // false, so state it explicitly rather than relying on the absence of a stub.
    when(runs.hasRunWithEntries(any(), any())).thenReturn(true);
    when(runs.hasRunWithEntries(BATCH, MISSED)).thenReturn(false);
    when(runs.hasRun(BATCH, MISSED)).thenReturn(true); // the 16:00 exits-only pass wrote its marker
    when(intents.findIntent(BATCH, MISSED))
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
                Optional.of(new SwingDoctrine.CandidateSnapshot(MISSED, List.of()))));
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
    return new SwingBatchBootCatchUp(swingCatchUp(clock, cron, doctrines), inlineScheduler(), bootEnabled);
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
  @DisplayName("the 22:14 EVENING boot the original rejection cited is STILL refused")
  void anEveningBootIsStillRefused() {
    // The 2026-07-17 reasoning ("it fires once, at the moment inputs are least likely to exist")
    // stays honoured: at 22:14 today's fire HAS passed, so only the market-open deadline refuses it.
    // If that gate were dropped, this test — not the morning ones — is what would catch it.
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    bootCatchUp(BOOTED_2214, CRON, doctrine).onStartup();

    assertThat(ledger.seeded)
        .as("an END-OF-DAY batch may not price off a session's partial daily bar")
        .isEmpty();
    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
  }

  @Test
  @DisplayName("09:15 exactly is already too late — the deadline is inclusive")
  void aBootAtTheOpeningBellIsRefused() {
    SwingDoctrine doctrine = armedFamilyMissingThursdaysEntries();

    bootCatchUp(BOOTED_0915, CRON, doctrine).onStartup();

    assertThat(ledger.seeded).isEmpty();
    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any(), any());
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
  @DisplayName("the boot one-shot is dispatched onto the pool SwingRunActivity watches")
  void theBootSweepRunsOnTheObservedCatchUpPool() throws Exception {
    // Not decoration. That pool is poolSize=1 and already carries catchUp()'s @Scheduled, so this
    // is what makes a boot and an 08:35 tick in the same second SERIALIZE rather than race. It is
    // also what keeps a boot sweep visible to SwingRunActivity, whose contract the pre-open
    // reconciler's wait depends on — a boot sweep on any other thread would leave that gate
    // reporting idle mid-sweep, silently, with every other test still green.
    assertThat(
            SwingBatchBootCatchUp.class
                .getDeclaredConstructor(
                    SwingBatchCatchUp.class, ThreadPoolTaskScheduler.class, boolean.class)
                .getParameters()[1]
                .getAnnotation(Qualifier.class)
                .value())
        .isEqualTo("swingCatchUpTaskScheduler");
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
