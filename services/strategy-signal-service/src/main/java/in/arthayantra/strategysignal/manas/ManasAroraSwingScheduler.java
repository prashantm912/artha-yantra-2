package in.arthayantra.strategysignal.manas;

import in.arthayantra.strategysignal.swing.SwingBatchIntentRepository;
import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import java.time.Clock;
import in.arthayantra.strategysignal.swing.SwingDoctrine;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the Manas Arora swing batch on a POLL across the evening window, running as soon as the day's
 * screen actually lands rather than at a fixed hour chosen to sit safely after it.
 *
 * <p><b>Why a poll.</b> The screens are event-chained to the bhavcopy backfill in market-data, so
 * their completion time is data-dependent — it moved from a dependable ~19:31 to "whenever NSE
 * publishes" once the bhavcopy job began polling. A fixed swing cron can only be safe by leaving a
 * gap big enough for the worst case, which is exactly the ~29 minutes this used to burn every night.
 *
 * <p><b>⚠️ Why entries are gated and exits are not.</b> The funnel SILENTLY serves the latest
 * persisted screen — there is no error when today's has not landed, just yesterday's names. The
 * previous fixed-time run passed {@code entriesEnabled = true} unconditionally, so running it before
 * the screen landed would have entered off the WRONG DAY'S names, silently. Entries therefore run
 * only when the snapshot's {@code screenDate} IS this session.
 *
 * <p>Exits get the opposite treatment, and this is the half that makes the poll safe to add at all:
 * by {@code ENTRY_DEADLINE} the batch runs whether or not the screen arrived, with entries
 * suppressed. A held stop MUST be evaluated — you can always decline to ENTER, you cannot decline to
 * LEAVE (the #694 doctrine). Without that deadline a screen that never lands would mean stops were
 * never evaluated and open positions sat unmanaged all night, which is strictly worse than the
 * fixed-time run this replaces.
 *
 * <p>Idempotency is the schedule-intent row: it is written only on the poll that actually runs, so
 * an earlier poll that declined to run leaves the session retryable. Execution stays inert when the
 * family flag is false via {@code SwingBatchEngine.runDaily}'s own {@code doctrine.enabled()} check.
 */
@Component
public class ManasAroraSwingScheduler {

  private static final Logger log = LoggerFactory.getLogger(ManasAroraSwingScheduler.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  /** The wall-clock past which exits run regardless — a held stop cannot wait for a late screen. */
  private static final LocalTime ENTRY_DEADLINE = LocalTime.of(18, 45);

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

  /** Evening poll: runs as soon as this session's screen lands, and by the deadline regardless. */
  @Scheduled(cron = "${artha.manas-arora.swing.cron:0 12,27,42,57 18-19 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    LocalDate session = LocalDate.now(clock.withZone(IST));
    // Intent on the FIRST tick, unconditionally: the missed-batch detector's population is
    // "armed intent with no swing_batch_runs row", so an evening where every poll declined must
    // still leave an intent row or the detector goes blind to exactly the miss it exists for.
    // recordScheduled is ON CONFLICT DO NOTHING, so later ticks are no-ops.
    try {
      intents.recordScheduled(doctrine.batchName(), session, doctrine.enabled());
    } catch (RuntimeException e) {
      log.warn(
          "{} swing schedule-intent record failed for {} - continuing: {}",
          doctrine.batchName(),
          session,
          e.getMessage());
    }
    if (alreadyRan(session)) {
      return;
    }
    Optional<SwingDoctrine.CandidateSnapshot> snapshot = doctrine.candidateSnapshot().snapshot();
    boolean screenIsForThisSession =
        snapshot.isPresent() && session.equals(snapshot.get().screenDate());
    boolean pastDeadline = LocalTime.now(clock.withZone(IST)).isAfter(ENTRY_DEADLINE);
    if (!screenIsForThisSession && !pastDeadline) {
      log.debug(
          "{} swing batch waiting for {}'s screen (funnel currently serves {})",
          doctrine.batchName(),
          session,
          snapshot.map(s -> s.screenDate().toString()).orElse("nothing"));
      return; // a later poll picks it up — do NOT enter off the previous day's names
    }
    if (!screenIsForThisSession) {
      log.warn(
          "{} swing batch running at the deadline WITHOUT {}'s screen — exits only, no entries",
          doctrine.batchName(),
          session);
    }
    // sessionDate stays null: this is a SCHEDULE change, not a change to how exits are priced.
    recorder.runAndRecord(
        doctrine, null, screenIsForThisSession, SwingBatchRecorder.MarkerPolicy.ALWAYS, snapshot);
  }

  /** True when the batch already RAN this session — a swing_batch_runs row, not the intent row. */
  private boolean alreadyRan(LocalDate session) {
    try {
      return intents.hasRunFor(doctrine.batchName(), session);
    } catch (RuntimeException e) {
      log.warn(
          "{} swing intent lookup failed for {} — skipping this poll: {}",
          doctrine.batchName(),
          session,
          e.getMessage());
      return true; // fail CLOSED: a lookup failure must not cause a duplicate batch
    }
  }
}
