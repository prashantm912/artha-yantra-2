package in.arthayantra.strategysignal.minervini;

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
 * Settles the Minervini swing book at 16:00 IST — half an hour after the close, and four hours
 * earlier than the 20:00 batch this replaces. EXITS ONLY: entries moved to the 08:35 morning pass
 * (owner decision, 2026-08-10).
 *
 * <p><b>Why exits can run this early, and entries cannot.</b> They need different data. A stop on a
 * held name needs that name's own daily bar, which the batch fetches on demand through the
 * cache-first Kite path — measured on 2026-08-07, ELEVEN of the held symbols' 1d bars were written
 * by {@code KITE} at 20:00:47–20:05:33, i.e. by this batch as it ran, against only four from
 * {@code BHAVCOPY} at 19:30. Move the batch to 16:00 and it fetches at 16:00. Entries need the
 * SCREEN, which needs the whole ~1800-symbol NSE bhavcopy, which NSE publishes anywhere between
 * 17:52 and 19:30+. Waiting for that is what used to hold the entire book hostage until 20:00.
 *
 * <p><b>Entries are not dropped, they are deferred.</b> {@link
 * in.arthayantra.strategysignal.swing.SwingBatchCatchUp} runs at 08:35, before the open, and its own
 * doctrine is exactly what this needs: between a session's 15:30 close and the next 09:15 open that
 * session's bar is final, so a run in that window reads exactly the bar the on-time run would have,
 * and every pass is pinned + truncated to its own session. An entry taken at 08:35 for session D
 * fills at D's close — the same price the 20:00 run would have used.
 *
 * <p><b>⚠️ The marker this run writes is deliberately NOT the catch-up's skip signal.</b> An
 * exits-only run records a {@code swing_batch_runs} row because it genuinely evaluated every held
 * stop, which is what the 08:30 canary and the heartbeat ask about. The catch-up asks a different
 * question, and V060 gives it a different operand: {@code entries_enabled}. Before that column
 * existed a bare row-exists test would have skipped every session and the book would never have
 * taken another entry — the marker true, the inference from it false.
 *
 * <p>The schedule-intent row is written on EVERY tick, including a disarmed one, because the
 * missed-batch detector's population is "armed intent with no run row". It is also what the catch-up
 * requires: the only two rows ever written to {@code swing_catchup_runs} are 2026-07-17, both
 * {@code ABANDONED / NO_SCHEDULE_INTENT} — it refused for want of exactly this row.
 */
@Component
public class MinerviniSwingScheduler {

  private static final Logger log = LoggerFactory.getLogger(MinerviniSwingScheduler.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final SwingBatchRecorder recorder;
  private final MinerviniDoctrine doctrine;
  private final SwingBatchIntentRepository intents;
  private final Clock clock;

  /** Wires the recorder, doctrine, schedule-intent ledger, and clock. */
  public MinerviniSwingScheduler(
      SwingBatchRecorder recorder,
      MinerviniDoctrine doctrine,
      SwingBatchIntentRepository intents,
      Clock clock) {
    this.recorder = recorder;
    this.doctrine = doctrine;
    this.intents = intents;
    this.clock = clock;
  }

  /**
   * Records the session's arming HOURLY through the trading day, independently of the settle.
   *
   * <p>The intent row is what {@link in.arthayantra.strategysignal.swing.SwingBatchCatchUp} needs to
   * replay a session at all — without one it abandons with {@code NO_SCHEDULE_INTENT}, which is
   * precisely what happened to 2026-07-17 and is why that catch-up has never once run. Writing it
   * only from the 16:00 settle would mean a container down at 16:00 — the exact incident class this
   * whole design exists for — leaves no row and forfeits the session.
   *
   * <p>Hourly and idempotent ({@code ON CONFLICT DO NOTHING}), so any tick during the session is
   * enough and the first one wins among the ticks. ⚠️ These writes are PROVISIONAL: the 16:00 settle
   * calls {@link SwingBatchIntentRepository#recordSettled} and OVERWRITES them, because the flag can
   * move between 09:05 and 16:00 and a morning observation is only a guess about the settle-time
   * value. Letting the guess stand was a Critical in review — a family disarmed after 09:05 would
   * still have had its entries replayed by the catch-up.
   *
   * <p>⚠️ It does not close the gap for a stack that is down for the WHOLE session: nothing
   * in-process can record what it was never alive to see, and that residue is deliberately left to
   * abandon loudly rather than infer today's flag onto a past session.
   */
  @Scheduled(cron = "${artha.minervini.swing.intent-cron:0 5 9-15 * * MON-FRI}", zone = "Asia/Kolkata")
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
   * 18:52 IST settle: evaluate every held stop against this session's own daily bar.
   *
   * <p>⚠️ <b>18:52, not 16:00, and the hour is load-bearing</b> (ledger H27). Most cash equities
   * have NO intraday 1d bar at all — their session bar is written by the 18:45 bhavcopy EOD ingest.
   * Measured 2026-08-18: of the 15 held minervini symbols, 12 first appeared at 18:45:10 from
   * BHAVCOPY and only 3 ever carried a KITE 1d bar. So a 16:00 settle priced the stops off the
   * PREVIOUS session's close for 14 of 14 holdings, {@code MarketDataCandlesClient} logged
   * {@code candle response STALE ... data used unchanged}, and the batch still reported
   * "0 exits, 0 exit-skipped" — a clean run over a stale bar.
   */
  @Scheduled(cron = "${artha.minervini.swing.cron:0 52 18 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    // ⚠️ BEFORE the intent write, not after. The cron is MON-FRI so it fires on NSE holidays, and a
    // holiday has no close to settle. Recording an intent for a day the batch will never mark would
    // make SwingBatchCanary page "DID NOT RUN" for a session that never existed, repeatedly, and a
    // past session can never acquire a run marker. The previous session was settled on its own
    // evening; if it was genuinely missed, the 08:35 catch-up is the remediation path.
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
      // recordSettled, NOT recordScheduled: this is the AUTHORITATIVE reading and it must overwrite
      // whatever the intraday ticks provisionally observed. The flag can move between 09:05 and
      // the settle, and letting the morning guess stand meant a disarmed family could still take entries
      // through the catch-up (cross-vendor review, 2026-08-10).
      intents.recordSettled(doctrine.batchName(), session, doctrine.enabled());
    } catch (RuntimeException e) {
      // Fail-soft on purpose: the detector's bookkeeping must never cost a settle. Losing the row
      // costs visibility of a miss; skipping the run costs an unevaluated stop.
      log.warn(
          "{} swing schedule-intent record failed for {} — continuing: {}",
          doctrine.batchName(),
          session,
          e.getMessage());
    }
    // ⚠️ The comment that used to sit here read "sessionDate stays null — this IS the session's own
    // evening, so the batch prices off today's bar exactly as the 20:00 run did. Only the hour
    // moved." That premise was FALSE from the day it was written (#1333): at 16:00 today's daily bar
    // does not exist yet for a bhavcopy-only symbol, so a null sessionDate silently settled off the
    // previous session. runScheduled now PINS the settle session — see SwingBatchRecorder — so an
    // absent session bar is a counted, alerted skip the 08:35 catch-up retries, never a stale
    // evaluation.
    //
    // runScheduled, NOT a bare runAndRecord: it is the path that turns a thrown batch into a FAILED
    // ops alert instead of a lone log line, and a settle that dies silently means every held stop
    // goes unevaluated with nobody told. The first cut of this change called runAndRecord directly
    // and lost that envelope — caught in cross-vendor review, before merge.
    recorder.runScheduled(doctrine, false);
  }
}
