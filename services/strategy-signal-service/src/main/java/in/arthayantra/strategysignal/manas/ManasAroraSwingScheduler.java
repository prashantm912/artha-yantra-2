package in.arthayantra.strategysignal.manas;

import in.arthayantra.strategysignal.swing.SwingBatchIntentRepository;
import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Settles the Manas Arora swing book at 16:02 IST, on the same contract as its Minervini twin: EXITS
 * ONLY at the close, entries deferred to the 08:35 morning pass. See {@link
 * in.arthayantra.strategysignal.minervini.MinerviniSwingScheduler} for why the two halves need
 * different data and therefore different hours, and for why the run marker this writes is
 * deliberately not the catch-up's skip signal.
 *
 * <p>Two minutes after Minervini, not the same minute: both share the single-thread scheduler, and a
 * settle that queues behind its twin would report a run time that is not when it read the tape.
 */
@Component
public class ManasAroraSwingScheduler {

  private static final Logger log = LoggerFactory.getLogger(ManasAroraSwingScheduler.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final SwingBatchRecorder recorder;
  private final ManasDoctrine doctrine;
  private final SwingBatchIntentRepository intents;
  private final Clock clock;

  /** Wires the recorder, doctrine, schedule-intent ledger, and clock. */
  public ManasAroraSwingScheduler(
      SwingBatchRecorder recorder,
      ManasDoctrine doctrine,
      SwingBatchIntentRepository intents,
      Clock clock) {
    this.recorder = recorder;
    this.doctrine = doctrine;
    this.intents = intents;
    this.clock = clock;
  }

  /**
   * Records the session's arming hourly through the trading day, independently of the settle — see
   * the Minervini twin for why the settle alone is not enough (a container down at 16:02 would leave
   * the catch-up with no intent row and forfeit the session's entries).
   */
  @Scheduled(cron = "${artha.manas-arora.swing.intent-cron:0 7 9-15 * * MON-FRI}", zone = "Asia/Kolkata")
  public void recordIntent() {
    LocalDate session = LocalDate.now(clock.withZone(IST));
    try {
      intents.recordScheduled(doctrine.batchName(), session, doctrine.enabled());
    } catch (RuntimeException e) {
      log.debug(
          "{} swing intent tick failed for {} — a later tick or the settle will retry: {}",
          doctrine.batchName(),
          session,
          e.getMessage());
    }
  }

  /**
   * 18:53 IST settle: evaluate every held stop against this session's own daily bar. One minute
   * behind the Minervini twin, as 16:00/16:02 were — see it for why the hour moved (ledger H27).
   */
  @Scheduled(cron = "${artha.manas-arora.swing.cron:0 53 18 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    // Non-trading-day guard BEFORE the intent write — see the Minervini twin for the canary
    // false-page this prevents.
    java.util.Optional<LocalDate> settle = recorder.scheduledSettleSession();
    if (settle.isEmpty()) {
      log.info(
          "{} swing settle skipped — {} is not an NSE trading day (no close to settle)",
          doctrine.batchName(),
          LocalDate.now(clock.withZone(IST)));
      return;
    }
    LocalDate session = settle.get();
    try {
      // recordSettled — the authoritative reading, overwriting the intraday ticks. See the
      // Minervini twin for the transition defect this closes.
      intents.recordSettled(doctrine.batchName(), session, doctrine.enabled());
    } catch (RuntimeException e) {
      log.warn(
          "{} swing schedule-intent record failed for {} — continuing: {}",
          doctrine.batchName(),
          session,
          e.getMessage());
    }
    // runScheduled, not a bare runAndRecord — it keeps the FAILED-alert envelope, and it gates
    // execution on doctrine.enabled(). See the Minervini twin for why both matter.
    recorder.runScheduled(doctrine, false);
  }
}
