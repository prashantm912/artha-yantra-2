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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
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
 * all. This list is the nine sources of the evening chain: the three {@code NseEodScheduler} pulls,
 * the bhavcopy backfill, the two swing screeners, and the three EOD analytics folds.
 * {@code MINERVINI_SCREEN}'s own {@code ingest_runs} boundary was moved (2026-08-11) to close only
 * AFTER {@code MinerviniScheduler}'s plane-divergence probe finishes, specifically so this class
 * never has to know that sub-step exists — when there IS a ledger row it already means "this leg is
 * fully done", not "the screen write happened".
 *
 * <p><b>⚠️ Seven of the nine are judged by their {@code ingest_runs} row; the two SCREENERS cannot
 * be, and an earlier version of this class judging them that way was a Critical.</b> That javadoc
 * used to claim the nine were "verified against each job's ledger call site", and they were not:
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
 * <p><b>Never derives completion from the clock.</b> "Is source X done" is answered from ledger and
 * artifact state, never from wall-clock time — a change to when the evening jobs fire needs no
 * change here.
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
   * morning board judging LAST evening's runs and is STRUCTURALLY UNREACHABLE here: the nine expected
   * writers are scheduled 18:45–18:51 and the check fires at 18:59, so the oldest RUNNING row this
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
    for (String source : EXPECTED) {
      sources.add(withScreenerArtifact(classify(source, bySource.get(source), generatedAt)));
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

  /**
   * The screener carve-out (review Critical 1). A screener whose {@link #classify} verdict is "no
   * run row at all today" is re-asked of its ARTIFACT, and reads DONE when the screen has already
   * consumed everything the bhavcopy watermark offers — the schedulers' own dedup-skip condition, and
   * therefore exactly "no further screen work will happen tonight".
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
   * screens against — {@code max(screen_date) >= max(trade_date)}, the same two reads {@code
   * MinerviniScheduler}/{@code ManasScheduler} compare to decide whether to run at all.
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
      return screened != null && !screened.isBefore(bhavcopy);
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

    if (!alertsEnabled) {
      log.info("evening chain (alerts disabled, not sent): {}", message);
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
