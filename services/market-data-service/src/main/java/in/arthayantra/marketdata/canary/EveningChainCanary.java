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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
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
 * all. This list is the twelve legs of the evening chain: the three {@code NseEodScheduler} pulls,
 * the bhavcopy backfill, the two swing screeners, the three EOD analytics folds, and — since review
 * Major C (2026-08-17) — the 18:56-18:58 tail: two strategy-signal insight sweeps reported over HTTP
 * and this service's own bhavcopy-close canary.
 * {@code MINERVINI_SCREEN}'s own {@code ingest_runs} boundary was moved (2026-08-11) to close only
 * AFTER {@code MinerviniScheduler}'s plane-divergence probe finishes, specifically so this class
 * never has to know that sub-step exists — when there IS a ledger row it already means "this leg is
 * fully done", not "the screen write happened".
 *
 * <p><b>⚠️ Every leg but two is judged by its {@code ingest_runs} row; the two SCREENERS cannot
 * be, and an earlier version of this class judging them that way was a Critical.</b> That javadoc
 * used to claim they were "verified against each job's ledger call site", and they were not:
 * {@code MinerviniScheduler:116-125} and {@code ManasScheduler:79-83} both RETURN on the dedup skip
 * (screen already current with the bhavcopy watermark) BEFORE {@code ledger.start} is ever reached —
 * their own comments say "opened only after the dedup skip below, so a no-op run records nothing".
 * So a screener that has nothing left to do writes NO row, is indistinguishable from one that has
 * not run, and the push says {@code still pending: MINERVINI_SCREEN, MANAS_SCREEN} on a chain where
 * nothing is coming. An alert that can never resolve is one nobody reads on the night it matters —
 * the same lesson {@link IngestCoverageCanary.Policy#SCREENER} learned from the other direction
 * (#1366). Measured: {@code ingest_runs} carried NO screen row for 2026-08-12 at all.
 *
 * <p>So the two screeners are judged by their ARTIFACT — see {@link #screenHasConsumedTheWatermark}.
 * Note the artifact question here is NOT #1366's: that canary runs the morning after and asks "did
 * output for the trade date ever land"; this one runs at 18:59 and asks "is any more screen work
 * going to happen tonight", whose answer is the schedulers' own skip condition. Keying on today's
 * calendar date would have left the measured case unfixed — on 2026-08-12 the day's bhavcopy did not
 * publish until the next morning (08-13 08:03 IST), so there was no output for 08-12 EITHER, and a
 * date-keyed check would have reported the same never-resolving PENDING.
 *
 * <p><b>⚠️ And that carve-out is only sound once the BHAVCOPY leg is terminal</b> (review Major A,
 * 2026-08-14) — see {@link #bhavcopyIsTerminal}. "The screen has consumed the watermark" means "no
 * more screen work tonight" only while the watermark can no longer MOVE tonight, and applying it
 * unconditionally made that false every day until ~18:45: at 10:00 both screens are current with
 * YESTERDAY's watermark, so both read DONE from IST midnight onward for jobs that had not run and
 * demonstrably would. The consequential case is the 18:59 one — with BHAVCOPY still PENDING the push
 * said {@code still pending: BHAVCOPY} while THREE legs were outstanding, which is LESS accurate,
 * in the moment it matters most, than the run-row-only check it replaced.
 *
 * <p><b>Never derives completion from the clock.</b> "Is source X done" is answered from ledger and
 * artifact state, never from wall-clock time — a change to when the evening jobs fire needs no
 * change here.
 *
 * <p><b>⚠️ But it does bound which ROWS are eligible to answer, and it must</b> (review Critical B,
 * 2026-08-17 — see {@link ExpectedLeg}). Judging "done" from state says nothing if the state being
 * read belongs to a different run: eight of the nine sources write a terminal {@code ingest_runs} row
 * on {@code ApplicationReadyEvent}, so a morning restart left SIX of nine reading DONE at 18:59 for
 * jobs that had not run. Each leg now declares the cron of its earliest legitimate trigger and rows
 * older than tonight's fire are dropped before classification. The distinction that keeps the rule
 * above intact: the clock still never decides that a job HAS finished — it only decides which day it
 * is, while each source's own SCHEDULE decides which rows are tonight's. A cron move still needs no
 * change here, because the boundary is read from the producer's own property.
 *
 * <p><b>A source counts as done only in {@link SourceState#DONE}.</b> {@link SourceState#STUCK} — a
 * {@code RUNNING} row aged past {@code artha.evening-chain.running-stale-minutes} — is OUTSTANDING,
 * exactly like {@link SourceState#PENDING}: it blocks {@link ChainReport#complete()} and is named in
 * the push. ⚠️ An earlier version of this class let STUCK count as "resolved" (on the reasoning that
 * nothing more is coming from a crashed job either way), which is precisely backwards for what this
 * report is FOR — an orphaned RUNNING row (a container recreate mid-job, no reaper: the MANAS_SCREEN
 * finding) would have produced "chain complete 9/9 — safe to shut down" while a job never actually
 * finished, which is the exact false-safe verdict the whole feature exists to prevent. Caught in
 * cross-vendor review before this shipped.
 *
 * <p><b>A finished chain is not automatically a CLEAN one.</b> {@link ChainReport#complete()} means
 * "nothing more will run", which is the shutdown question — it does NOT mean the evening went well,
 * because a {@code FAILURE} row is terminal and therefore {@link SourceState#DONE}. {@link #publish}
 * treats the two separately and a failed source can never produce a "complete" / "safe to shut down"
 * title (review Major 4: it used to, with the failure demoted to a suffix behind a title saying the
 * opposite — and the page renders that same shape into an {@code aria-live} region, so a screen
 * reader was announced the inverse of the truth).
 *
 * <p><b>The push fires at most once per IST day</b>, via a provisional-claim protocol identical in
 * shape to {@link IngestCoverageCanary}'s (a {@code canary_runs} row goes {@code CLAIMED} then
 * {@code DONE}; a claim older than {@link #CLAIM_LEASE} is stealable) — but simpler, because there is
 * only ONE door here (a single scheduled fire, no boot-catchup: if the stack is down nobody is
 * waiting on a push). The row is confirmed {@code DONE} only once {@link NtfyClient#trySend} reports
 * the delivery actually succeeded (or alerts are administratively off, in which case there is
 * nothing to retry toward), so a crash between claim and confirm leaves the day stealable rather
 * than permanently muted.
 *
 * <p><b>⚠️ There is NO retry, and that is a deliberate reversal.</b> This class shipped with {@link
 * IngestCoverageCanary}'s self-rescheduling reclaim chain copied across, and in this job every one
 * of its attempts was dead code in production: the check fires 18:59:00 against a HARD 19:00
 * shutdown, so a reclaim scheduled at {@code CLAIM_LEASE + RECLAIM_SLACK} landed at 19:04:05 — five
 * minutes after the machine is off. It passed its own IT only because the IT drove the {@code
 * Runnable} by hand on a mutable clock (review Major 2). Shrinking the lease to fit does not rescue
 * it either: one ntfy POST is bounded by {@code spring.http.client} 3 s connect + 60 s read, so a
 * single publish can legally consume the entire 60 s of runway, and no lease can both exceed one
 * publish (the condition a lease exists for) and fit inside the window. Anything scheduled sooner is
 * not a retry, it is a concurrent second POST. The realistic causes of a failed evening push — ntfy
 * unreachable, no network, DNS — do not self-heal in the seconds available either. So: a failed
 * 18:59 send is LOST for that evening, said plainly, logged at ERROR, with the claim left {@code
 * CLAIMED} so the row itself records that delivery was never confirmed.
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

  /**
   * One expected source's today-progress: its state plus the last-run detail for display.
   *
   * <p>{@code status} is normally the raw {@code ingest_runs} status ({@code RUNNING} / {@code
   * SUCCESS} / {@code FAILURE}), or null when nothing ran today. The one SYNTHETIC value is {@link
   * #STATUS_UP_TO_DATE}, which a screener carries when it wrote no row because it had nothing to do —
   * see {@link #screenHasConsumedTheWatermark}. It is deliberately not {@code SUCCESS}: no run
   * succeeded, and the display should say which of the two happened.
   */
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
   *
   * <p>⚠️ {@code complete} answers "will anything more run tonight", NOT "did tonight go well". A
   * {@code FAILURE} row is a terminal state and therefore DONE, so a chain can be complete AND
   * failed. Every consumer that renders a verdict must check for FAILURE separately — {@link
   * #publish} and the Data-Ops panel both do.
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
   * One expected evening-chain leg: the {@code ingest_runs} source it writes, plus the cron of the
   * EARLIEST trigger that can legitimately produce TONIGHT's row for it.
   *
   * <p><b>⚠️ This second field is the whole of review Critical B (2026-08-17), and without it the
   * report was structurally wrong for two-thirds of the chain.</b> {@link #report()} windows from IST
   * midnight and {@link #classify} called any non-{@code RUNNING} row in that window DONE at any age —
   * but EIGHT of the nine sources have an {@code ApplicationReadyEvent} boot path that writes a
   * terminal row the moment the process starts ({@code NseEodScheduler:54} for the three NSE pulls,
   * {@code BhavcopyStartupCatchup:49}, {@code DataQualityEodJob:86}, {@code EquityBreadthEodJob:63},
   * {@code MinerviniScheduler:58}, {@code ManasScheduler:54}). Measured twice on the live stack: four
   * boot rows stamped 02:39 IST on 2026-08-15 and again at 08:17 IST on 2026-08-17 (BHAVCOPY,
   * NSE_FII_DII, NSE_PARTICIPANT_OI, NSE_FII_DERIVATIVE), every one of them reading DONE sixteen and
   * ten hours respectively before the evening window — and a DONE bhavcopy then opened {@link
   * #bhavcopyIsTerminal}'s gate and promoted both screens too, so SIX of the nine read DONE for jobs
   * that had not run. That is the exact "chain complete — safe to shut down" the whole class exists
   * to prevent, produced by a stack that had merely been restarted that morning.
   *
   * <p><b>Why a per-source cron and not one coarse cut.</b> A blanket {@code today.atTime(16, 0)}
   * would work today and be wrong the first time a cron moves; the boundary belongs to each source's
   * own schedule, which is also what keeps {@code :83-85}'s "never derives completion from the clock"
   * true — the clock supplies only which calendar day it is, the SCHEDULE supplies the boundary.
   * Resolved through {@link Environment} against the SAME property key the job's own {@code
   * @Scheduled} reads, so compose's passthrough (e.g. {@code ARTHA_NSE_EOD_CRON}) moves both together
   * and a cron change needs no edit here. {@code cronDefault} mirrors the job's annotation default for
   * the case where nothing is set (mock, CI, a bare {@code java -jar}); the two copies are pinned
   * against each other by {@code eachLegMirrorsItsProducersScheduledDefault}.
   *
   * <p><b>Why the EARLIEST trigger rather than the source's own cron</b> — and this is also the answer
   * to "cron time, or cron time minus a tolerance?". The two failure directions are not symmetric: a
   * boundary set too LATE refuses a real run and costs a "still pending" push (annoying, safe), while
   * a boundary set too EARLY re-admits a boot row and restores the false "safe to shut down"
   * (the defect). A blind tolerance buys the second risk to insure against the first — and it is
   * weakest in exactly the scenario that matters most, a deploy at 18:40 whose boot rows would clear
   * an 18:40-ish cut. The only reason a row can legitimately precede its own cron is a NON-cron
   * trigger, and this chain has exactly one: both screeners also run from {@code
   * @EventListener(BhavcopyBackfillCompleted.class)} ({@code MinerviniScheduler:64}, {@code
   * ManasScheduler:60}), fired by the 18:45 bhavcopy backfill and therefore able to land before their
   * own 18:47/18:48 crons. So the screeners declare the BHAVCOPY cron — an exact boundary drawn from
   * the schedule that actually drives them, rather than a guessed slack.
   */
  record ExpectedLeg(String source, String cronProperty, String cronDefault) {}

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
  // javadoc for why this is its own list rather than IngestCoverageCanary.EXPECTED, and
  // ExpectedLeg for why each carries the cron of its EARLIEST legitimate trigger.
  static final List<ExpectedLeg> EXPECTED =
      List.of(
          // The three NSE pulls share one scheduler and therefore one cron (NseEodScheduler:60).
          new ExpectedLeg(
              IngestRunLedger.SOURCE_NSE_FII_DII, "artha.nse.eod-cron", "0 46 18 * * MON-FRI"),
          new ExpectedLeg(
              IngestRunLedger.SOURCE_NSE_PARTICIPANT_OI, "artha.nse.eod-cron", "0 46 18 * * MON-FRI"),
          new ExpectedLeg(
              IngestRunLedger.SOURCE_NSE_FII_DERIVATIVE, "artha.nse.eod-cron", "0 46 18 * * MON-FRI"),
          new ExpectedLeg(
              IngestRunLedger.SOURCE_BHAVCOPY, "artha.bhavcopy.eod-cron", "0 45 18 * * MON-FRI"),
          new ExpectedLeg(
              IngestRunLedger.SOURCE_MARKET_CONTEXT_DAY,
              "artha.context.eod-cron",
              "0 49 18 * * MON-FRI"),
          new ExpectedLeg(
              IngestRunLedger.SOURCE_DATA_QUALITY,
              "artha.data-quality.eod-cron",
              "0 50 18 * * MON-FRI"),
          // ⚠️ The screeners take the BHAVCOPY cron, not their own 18:47/18:48: their earliest
          // legitimate trigger is BhavcopyBackfillCompleted, not their fallback cron. See ExpectedLeg.
          new ExpectedLeg(
              IngestRunLedger.SOURCE_MINERVINI_SCREEN,
              "artha.bhavcopy.eod-cron",
              "0 45 18 * * MON-FRI"),
          new ExpectedLeg(
              IngestRunLedger.SOURCE_MANAS_SCREEN, "artha.bhavcopy.eod-cron", "0 45 18 * * MON-FRI"),
          new ExpectedLeg(
              IngestRunLedger.SOURCE_EQUITY_BREADTH,
              "artha.breadth.materialize-cron",
              "0 51 18 * * MON-FRI"),
          // ---- the tail: the strategy-signal insight sweeps + this service's own last job -------
          // ⚠️ Review Major C (2026-08-17). Five jobs run 18:54-18:58, INSIDE the window this check
          // closes at 18:59, and NONE was visible to it — the sell-decision sweep starts two minutes
          // before the check and the push said "chain complete" straight over it. The owner chose
          // extending the coverage over narrowing the claim to market-data.
          //
          // The strategy-signal rows arrive over HTTP and land in this service's own ingest_runs —
          // see EveningChainLegController for the four facts that decided that direction. They
          // classify through the SAME path as everything above, boundary included.
          //
          // ⚠️ TWO of the five are deliberately NOT here, and the rule is worth stating because it
          // is not laziness: AN UNCONDITIONAL EXPECTATION MAY ONLY NAME AN UNCONDITIONALLY-LOADED
          // PRODUCER. SwingBatchHeartbeat (18:54) is @ConditionalOnProperty("artha.heartbeat.url")
          // and GraduationPromotionScheduler (18:55) is
          // @ConditionalOnProperty("artha.graduation.promotion-enabled", havingValue="true") — when
          // either is disarmed the BEAN does not exist, so its @Scheduled never fires, nothing ever
          // reports, and a row here would be a leg stuck PENDING every evening forever. That is the
          // never-resolving alert this class already learned about from the screeners (see the
          // ingest_runs discussion above), arrived at from the other direction. Both ARE armed on
          // the live stack today (verified via docker inspect, 2026-08-17), which is exactly what
          // makes the trap easy to walk into. Closing them needs an ARMING signal this service can
          // read, and for the heartbeat that signal cannot be the property itself: the value is a
          // healthchecks.io ping URL, which is a credential and must not be mirrored into this
          // container's environment to be tested for blankness.
          new ExpectedLeg(
              IngestRunLedger.SOURCE_INSIGHT_STRATEGY_EVIDENCE,
              "artha.insights.strategy-evidence-cron",
              "0 56 18 * * MON-FRI"),
          new ExpectedLeg(
              IngestRunLedger.SOURCE_INSIGHT_SELL_DECISION,
              "artha.insights.sell-decision-cron",
              "0 57 18 * * MON-FRI"),
          // The last market-data job before shutdown; it wrote no ledger row at all until this
          // change (BhavcopyCloseCanary#sweep).
          new ExpectedLeg(
              IngestRunLedger.SOURCE_BHAVCOPY_CLOSE,
              "artha.bhavcopy-close.cron",
              "0 58 18 * * MON-FRI"));

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
   *
   * <p>⚠️ This constant is a MIRROR of the literal spelled out in {@link #check()}'s scheduling
   * annotation, never the source of it. Building that annotation by CONCATENATING this constant
   * into the placeholder compiles to the same folded string and passes every reflective check, but
   * {@code OperatingWindowTest} in strategy-signal-service walks the SOURCE TEXT of both services
   * to prove no scheduled job is stranded outside the owner's 08:00-19:00 machine window — and a
   * concatenation leaves it reading the cron as a bare placeholder prefix, truncated at the closing
   * quote with no default in it, which it correctly refuses as a schedule it cannot enumerate. That
   * guard's strictness is the point, so the literal lives in the annotation and {@code
   * defaultCheckCronMatchesTheScheduledAnnotation} keeps the two copies from drifting apart.
   */
  static final String DEFAULT_CHECK_CRON = "0 59 18 * * MON-FRI";

  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_FAILURE = "FAILURE";

  /**
   * Synthetic {@link SourceProgress#status()} for a screener that wrote no {@code ingest_runs} row
   * because its output was already current with the bhavcopy watermark — the dedup skip, which is a
   * legitimate "nothing left to do", not a missing run.
   */
  static final String STATUS_UP_TO_DATE = "UP_TO_DATE";

  /** {@code canary_runs.canary} key for this canary's per-IST-day push marker. */
  public static final String CANARY_KEY = "EVENING_CHAIN";

  private static final String STATE_CLAIMED = "CLAIMED";
  private static final String STATE_DONE = "DONE";
  private static final String SOURCE_TAG = "EVENING_CHAIN_CHECK";

  /**
   * How long a {@code CLAIMED} row suppresses another attempt before it is treated as one that died
   * mid-publish and becomes stealable. Sized, like {@link IngestCoverageCanary#CLAIM_LEASE}, to
   * exceed the longest a publish can legally take: one best-effort ntfy POST, bounded by {@code
   * spring.http.client}'s 3 s connect + 60 s read. ⚠️ If that read timeout is ever raised past this,
   * raise this with it.
   *
   * <p>Unlike the sibling's, this constant sizes only ONE thing. There is no reclaim here (see the
   * class javadoc), so it is not also a retry delay — it exists purely so that a claim left behind by
   * a crashed publish does not silence a later fire, should the check ever be configured to fire more
   * than once in an evening. Today it fires once.
   */
  static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

  /**
   * Default for {@code artha.evening-chain.running-stale-minutes}: how long a {@code RUNNING} row may
   * sit unfinished before it reads {@link SourceState#STUCK} rather than {@link SourceState#PENDING}.
   *
   * <p>⚠️ This is deliberately NOT {@code artha.ingest-canary.running-stale-minutes}, which this
   * class originally shared "so stuck can never mean something different here than on the
   * ingest-health page" (review Major 3). That knob defaults to 120 minutes, which is right for a T+1
   * morning board judging LAST evening's runs and is STRUCTURALLY UNREACHABLE here: the expected
   * writers are scheduled 18:45–18:58 and the check fires at 18:59, so the oldest RUNNING row this
   * canary can ever see is 14 minutes old and {@link #classify} could only ever return PENDING. One
   * shared knob for two windows an order of magnitude apart is not one knob, it is a dead branch —
   * the STUCK state and its tests existed while production could not reach them.
   *
   * <p>Ten minutes: measured, the whole evening chain runs 18:44→18:51 with each leg finishing inside
   * ~90 s (2026-08-13 {@code ingest_runs}), so a leg still RUNNING ten minutes on has outlived its
   * own window and will be killed by the shutdown either way. The failure direction is cheap on
   * purpose — PENDING and STUCK are BOTH outstanding and both block completion, so a
   * mis-classification changes the WORD in the push, never the verdict, and the word that helps in
   * the 60 s available is the one that says "this one crashed, it is not coming".
   */
  static final long DEFAULT_RUNNING_STALE_MINUTES = 10;

  private static final Logger log = LoggerFactory.getLogger(EveningChainCanary.class);

  private final JdbcTemplate jdbc;
  private final NtfyClient ntfy;
  private final MarketCalendar calendar;
  private final Clock clock;
  // Kept as a field (not just read once in the constructor) because each leg's expected-not-before
  // boundary is resolved from the SAME cron property its producer reads — see ExpectedLeg.
  private final Environment environment;
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
      @Value("${artha.evening-chain.enabled:true}") boolean enabled,
      @Value("${artha.evening-chain.alerts-enabled:true}") boolean alertsEnabled,
      // This canary's OWN aged-RUNNING threshold, not the shared ingest-canary one — see
      // DEFAULT_RUNNING_STALE_MINUTES for why sharing made STUCK unreachable in production.
      @Value("${artha.evening-chain.running-stale-minutes:10}") long runningStaleMinutes) {
    this.jdbc = jdbc;
    this.ntfy = ntfy;
    this.calendar = calendar;
    this.clock = clock;
    this.environment = environment;
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
      // ⚠️ LITERAL, never `"${...:" + DEFAULT_CHECK_CRON + "}"` — see that constant's javadoc.
      // strategy-signal's OperatingWindowTest reads this default out of the SOURCE TEXT, and a
      // concatenation reads to it as a job that can never fire inside the operating window.
      // ⚠️ `cron` and `zone` must stay on ONE line: market-data's CronPassthroughParityTest matches
      // the @Scheduled site PER LINE (activeCronSites) and asserts the zone on that same slice, so
      // wrapping zone onto the next line reads to it as an unzoned job. OperatingWindowTest is
      // indifferent (it searches the joined source), so only this file's formatting closes it.
      cron = "${artha.evening-chain.check-cron:0 59 18 * * MON-FRI}", zone = "Asia/Kolkata",
      scheduler = "monitorTaskScheduler")
  public void check() {
    if (!live || !enabled) {
      return;
    }
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    runOnce(today);
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
    for (ExpectedLeg leg : EXPECTED) {
      sources.add(
          classify(leg, bySource.get(leg.source()), generatedAt, expectedNotBefore(leg, today, dayEnd)));
    }
    // The screener carve-out reads the bhavcopy watermark, so it may only be applied once that
    // watermark is FINAL for the night — i.e. once BHAVCOPY itself is terminal (review Major A).
    if (bhavcopyIsTerminal(sources)) {
      sources.replaceAll(this::withScreenerArtifact);
    }
    // Only DONE counts as finished — STUCK is outstanding, exactly like PENDING (review Critical 1:
    // an orphaned RUNNING row must never read as "safe to shut down").
    int done = (int) sources.stream().filter(s -> s.state() == SourceState.DONE).count();
    boolean complete = done == sources.size();
    return new ChainReport(generatedAt, today, true, EXPECTED.size(), done, complete, List.copyOf(sources));
  }

  /**
   * Where one leg stands, judged ONLY on rows that could be tonight's.
   *
   * <p>{@code notBefore} is {@link #expectedNotBefore}'s answer for this leg — everything older is
   * dropped BEFORE classification, so a boot row is not merely reported differently, it is invisible
   * to every branch below (including the {@code RUNNING} one: a job still running from a boot hours
   * ago is not tonight's leg either, and calling it STUCK would name the wrong night). A leg with
   * nothing left is PENDING with a null status, which is exactly the shape {@link
   * #withScreenerArtifact} may still promote — deliberately, since "the screen has consumed a FINAL
   * watermark" is a statement about tonight regardless of what a boot row did this morning.
   */
  private SourceProgress classify(
      ExpectedLeg leg, List<RunRow> rows, Instant now, Instant notBefore) {
    List<RunRow> tonight =
        rows == null
            ? List.of()
            : rows.stream().filter(r -> !r.startedAt().isBefore(notBefore)).toList();
    if (tonight.isEmpty()) {
      return new SourceProgress(leg.source(), SourceState.PENDING, null, null, null);
    }
    RunRow latest = tonight.stream().max(Comparator.comparing(RunRow::startedAt)).orElseThrow();
    if (STATUS_RUNNING.equals(latest.status()) && latest.finishedAt() == null) {
      boolean aged = Duration.between(latest.startedAt(), now).compareTo(runningStale) > 0;
      return new SourceProgress(
          leg.source(),
          aged ? SourceState.STUCK : SourceState.PENDING,
          latest.status(),
          latest.startedAt(),
          null);
    }
    return new SourceProgress(
        leg.source(), SourceState.DONE, latest.status(), latest.startedAt(), latest.finishedAt());
  }

  /**
   * The earliest instant TODAY at which a row may count as {@code leg}'s tonight run: the next fire
   * of the leg's own trigger cron after IST midnight. See {@link ExpectedLeg} for why this exists,
   * why it is per-source, and why it is the exact cron time rather than the cron minus a tolerance.
   *
   * <p>Read live from {@link Environment} on the producer's own property key, so compose's
   * passthrough moves the boundary with the job. {@code cronDefault} covers the case where nothing is
   * set at all.
   *
   * <p>⚠️ Fail-SAFE, in the same direction as everything else here: a cron that cannot fire today at
   * all (a {@code MON-FRI} expression on a Saturday muhurat session) or one that will not parse
   * yields {@code dayEnd}, which no row inside the day window can reach — so the leg stays
   * outstanding rather than accepting whatever happens to be there. That cannot produce a
   * never-resolving PUSH, because {@link #check()}'s own cron is {@code MON-FRI} too and simply does
   * not fire on such a day; the page would show the honest answer, which on a muhurat Saturday is
   * that none of the weekday evening jobs ran and none will.
   */
  private Instant expectedNotBefore(ExpectedLeg leg, LocalDate today, Instant dayEnd) {
    String cron = environment.getProperty(leg.cronProperty(), leg.cronDefault());
    try {
      ZonedDateTime fire = CronExpression.parse(cron).next(today.atStartOfDay(Ist.ZONE));
      if (fire != null && fire.toLocalDate().equals(today)) {
        return fire.toInstant();
      }
      log.warn(
          "evening-chain: {}'s trigger cron ({}={}) does not fire on {} - leaving it outstanding",
          leg.source(), leg.cronProperty(), cron, today);
    } catch (IllegalArgumentException unparseable) {
      log.warn(
          "evening-chain: {}'s trigger cron ({}={}) will not parse ({}) - leaving it outstanding",
          leg.source(), leg.cronProperty(), cron, unparseable.getMessage());
    }
    return dayEnd;
  }

  /**
   * Whether tonight's BHAVCOPY leg has reached a terminal row, which is exactly when the watermark
   * the screener carve-out reads can no longer move tonight — the precondition that makes {@link
   * #withScreenerArtifact} sound (review Major A, 2026-08-14).
   *
   * <p>Terminal, not SUCCESS: a {@code FAILURE} row also means no more bhavcopy is coming tonight,
   * and the screeners' fallback crons (18:47/18:48, after the 18:45 backfill) still settle their own
   * state against the unmoved watermark before the 18:59 check. PENDING and STUCK both leave the
   * watermark live — a bhavcopy that has not finished may yet publish (NSE has published as late as
   * 19:31) — so the carve-out is withheld and the screeners stay outstanding, which is the fail-safe
   * direction: the cost is a "still pending" push, never a "safe to shut down" on a live chain.
   *
   * <p>Only the SCHEDULED {@code runLocked} publishes {@code BhavcopyBackfillCompleted} and thereby
   * drives the screens; {@code BhavcopyBackfillService#refetchDate} does not, so a manual re-fetch
   * after the leg closed cannot silently start screen work behind a DONE bhavcopy row. If it moved
   * the watermark, the screens simply read behind it and the carve-out returns false anyway.
   */
  private static boolean bhavcopyIsTerminal(List<SourceProgress> classified) {
    return classified.stream()
        .anyMatch(
            s ->
                IngestRunLedger.SOURCE_BHAVCOPY.equals(s.source())
                    && s.state() == SourceState.DONE);
  }

  /**
   * The screener carve-out (review Critical 1). A screener whose {@link #classify} verdict is "no
   * run row at all today" is re-asked of its ARTIFACT, and reads DONE when the screen has already
   * consumed everything the bhavcopy watermark offers — the schedulers' own dedup-skip condition, and
   * therefore exactly "no further screen work will happen tonight".
   *
   * <p>⚠️ Applied ONLY when {@link #bhavcopyIsTerminal} — the caller's gate, not this method's, and
   * the difference between "the screen is current" and "the screen is current and will stay that
   * way".
   *
   * <p>⚠️ It can only ever promote PENDING→DONE, never demote, and only from the NO-ROW shape
   * ({@code status == null}). Two deliberate consequences:
   *
   * <ul>
   *   <li>An aged {@code RUNNING} row still wins and stays {@link SourceState#STUCK}. A process that
   *       died after {@code repo.replaceAll} but before {@code ledger.succeed} leaves a current
   *       artifact AND a stranded RUNNING row; the screen output landing does not make the leg done
   *       (the plane-divergence probe runs between those two points), and a stranded row before a
   *       shutdown is precisely what the owner must be told.
   *   <li>A screen genuinely in flight right now (fresh RUNNING) is not waved through on a watermark
   *       an earlier same-evening run already satisfied.
   * </ul>
   */
  private SourceProgress withScreenerArtifact(SourceProgress progress) {
    if (progress.state() != SourceState.PENDING
        || progress.status() != null
        || ScreenOutputTables.tableFor(progress.source()) == null
        || !screenHasConsumedTheWatermark(progress.source())) {
      return progress;
    }
    return new SourceProgress(progress.source(), SourceState.DONE, STATUS_UP_TO_DATE, null, null);
  }

  /**
   * Whether {@code source}'s persisted screen output has caught up with the bhavcopy watermark it
   * screens against — {@code max(screen_date) == max(trade_date)}, the same two reads {@code
   * MinerviniScheduler}/{@code ManasScheduler} compare to decide whether to run at all.
   *
   * <p>⚠️ {@code equals}, deliberately, NOT {@code >=} (review minor m-2, 2026-08-14). The producers
   * skip iff {@code persisted.equals(watermark)} ({@code MinerviniScheduler:117}, {@code
   * ManasScheduler:80}), so a {@code screen_date} AHEAD of the watermark — reachable through {@code
   * runOnce(asOf)} behind {@code POST /run} with a forward {@code asOf} — is a state in which the
   * scheduler WOULD still run while a {@code >=} carve-out reported DONE permanently. Mirroring the
   * producers keeps this answering their question rather than a looser one of its own.
   *
   * <p>Fail-SAFE in the blocking direction: an unreadable watermark, a watermark with no screen
   * output behind it, or an empty equity table all return false, leaving the source outstanding. The
   * cost of a false PENDING is a "still pending" push; the cost of a false DONE is "safe to shut
   * down" on a chain that is not.
   *
   * <p>(An empty {@code nse_eod_bhavcopy} is not a silent hole: with no watermark at all the
   * schedulers do NOT dedup-skip — the skip needs a non-null persisted date — so they open a run row
   * and this branch is never the one answering.)
   */
  private boolean screenHasConsumedTheWatermark(String source) {
    try {
      LocalDate bhavcopy = jdbc.queryForObject(ScreenOutputTables.BHAVCOPY_WATERMARK_SQL, LocalDate.class);
      if (bhavcopy == null) {
        return false;
      }
      LocalDate screened =
          jdbc.queryForObject(
              "SELECT max(screen_date) FROM " + ScreenOutputTables.tableFor(source), LocalDate.class);
      return bhavcopy.equals(screened);
    } catch (RuntimeException unreadable) {
      log.warn(
          "evening-chain: could not read {}'s screen watermark ({}) - leaving it outstanding",
          source,
          unreadable.getMessage());
      return false;
    }
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
   * The evaluate → claim → publish protocol (mirrors {@link IngestCoverageCanary#runOnce}, minus the
   * boot-catchup door and the reclaim chain this canary cannot use — see the class javadoc). {@code
   * report()} runs first because it is side-effect-free, so a transient read failure returns before
   * anything is claimed. The claim gates the single side-effecting step (the push) and is provisional
   * until {@link NtfyClient#trySend} confirms delivery.
   *
   * <p>Every way this can end without a delivered push is logged at ERROR, because there is no
   * second attempt coming: 18:59 is the last thing that happens before the machine goes off. That
   * includes the {@code report()} failure above — a transient DB blip there used to WARN and return,
   * which read as routine when it is in fact the evening's only check not happening.
   */
  private void runOnce(LocalDate today) {
    ChainReport rep;
    try {
      rep = report();
    } catch (RuntimeException e) {
      log.error(
          "evening-chain: today's check could not be evaluated ({}) - there is no retry before the"
              + " 19:00 shutdown, so tonight's push is LOST",
          e.getMessage());
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
        log.warn(
            "evening-chain: {} is held by a live claim until {} - standing down",
            today,
            attempted.heldUntil());
      }
      return;
    }
    if (publish(rep)) {
      confirmPublished(today);
    } else {
      // The claim stays CLAIMED — a genuine send failure, not a decision to skip — so the row
      // records that delivery was never confirmed. Nothing retries it: see the class javadoc for why
      // no lease can both exceed one 63 s-bounded POST and fit inside 60 s of runway.
      log.error(
          "evening-chain: {}'s push did NOT deliver and there is no retry before the 19:00 shutdown"
              + " - tonight's message is LOST; the claim stays CLAIMED, unconfirmed",
          today);
    }
  }

  /**
   * Builds and sends today's one message. Returns whether the outcome is "confirmed" — either the
   * ntfy push actually succeeded, or alerts are administratively disabled (nothing to deliver, so
   * the claim should still resolve to DONE rather than recording a delivery that was never owed).
   *
   * <p>⚠️ THREE outcomes, not two (review Major 4). {@code outstanding} used to be computed from
   * PENDING/STUCK alone, so a source in {@code FAILURE} — terminal, therefore DONE — still produced
   * the title "evening chain complete" with the failure demoted to a body suffix. A phone shows the
   * title first, and the Data-Ops panel renders the same shape into an {@code aria-live} region, so
   * the one reader who gets ONLY the announcement was told the exact opposite of the truth. The
   * finished-with-failures case is genuinely distinct from both — nothing more will run (so it is not
   * "still pending") and it is not safe (so it is not "complete") — and it gets its own title.
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
    if (outstanding) {
      title = "ArthaYantra evening chain still pending";
      List<String> names = new ArrayList<>();
      pending.forEach(s -> names.add(s.source()));
      stuck.forEach(s -> names.add(s.source() + " (stuck)"));
      sb.append("still pending: ").append(String.join(", ", names));
    } else if (!failed.isEmpty()) {
      title = "ArthaYantra evening chain finished WITH FAILURES";
      sb.append("chain finished ")
          .append(rep.done())
          .append('/')
          .append(rep.total())
          .append(" — nothing more will run, but it is NOT clean");
    } else {
      title = "ArthaYantra evening chain complete";
      sb.append("chain complete ").append(rep.done()).append('/').append(rep.total());
    }
    if (!failed.isEmpty()) {
      sb.append(" — failed: ")
          .append(failed.stream().map(SourceProgress::source).collect(Collectors.joining(", ")));
    }
    String message = sb.toString();

    // ⚠️ A blank ntfy topic is a NO-OP, not a failure (review minor, 2026-08-17). NtfyClient#trySend
    // returns false both for "the POST failed" and for "no topic is configured, so nothing was ever
    // sent" — indistinguishable to the caller, so an unconfigured stack took the ERROR branch below
    // every single evening, left the claim CLAIMED and logged "tonight's message is LOST" about a
    // message that was never owed. Asking NtfyClient whether it is configured at all keeps trySend's
    // contract exactly as documented (it is the only production caller, but the semantics of "did the
    // POST succeed" are worth keeping honest) and puts this alongside !alertsEnabled, which is the
    // same situation reached by the other door.
    if (!alertsEnabled || !ntfy.isConfigured()) {
      log.info("evening chain (no ntfy topic or alerts disabled, not sent): {}", message);
      return true; // nothing to deliver, by design — confirm rather than reclaim toward nothing
    }
    boolean sent = ntfy.trySend(title, "default", message);
    if (sent) {
      log.info("evening chain: {}", message);
    } else {
      log.error("evening-chain push did NOT send (no retry before shutdown): {} — {}", title, message);
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
