package in.arthayantra.marketdata.corporateactions;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.candles.CandleQueryService;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The 16:30 IST corporate-action anchor-close integrity job (B-17 / amendment A8): per active
 * equity, ONE ranged 1d fetch through the rate-limited gateway, ~8 sparse anchor closes diffed
 * against the cache; uniform-ratio divergence on ≥ 2 anchors ⇒ DETECTED → full rate-limited
 * re-backfill into STAGING → verify → swap → cagg refresh (swapped-in rows carry fresh
 * {@code fetched_at}, so the Stage-D dataHash flags pre-event runs not-like-for-like) → RESOLVED.
 * Single-anchor noise only counts {@code ay_corporate_action_anchor_noise_total}. Kite-diff is the
 * SOLE detection input.
 *
 * <p>The re-backfill runs BEFORE any deletion (V057, see {@link #rebuildBaseByStagedSwap}) — it used
 * to run after a full purge, so a failed fetch left the symbol gutted with nothing that would ever
 * retry it.
 *
 * <p>Kill switch (A14, 2026-07-10): {@code artha.corporate-actions.enabled=false} un-registers this
 * bean entirely (default-armed via {@code matchIfMissing}) — the remediation OOM-crashed live
 * Postgres 3× and the operator needs a one-line .env disarm without a code change.
 *
 * <p>Failure is TWO classes, not one (task_6903cd5e / V051), because a failed run leaves the symbol
 * strictly worse off than it started and nothing else ever re-fires for it: a failure at the cagg
 * refresh has a committed base ⇒ {@code REFRESH_FAILED}, resumed refresh-only by the checkpoint
 * until {@code artha.corporate-actions.max-refresh-attempts} is spent and it becomes the terminal
 * {@code REFRESH_ABANDONED}; a failure BEFORE the base commits has no rebuilt base ⇒ plain
 * {@code FAILED}, terminal and never resumed. Which class a run belongs to is a RECORDED fact
 * ({@code status} read back from the row), never inferred from in-process state.
 */
@Service
@ConditionalOnProperty(
    name = "artha.corporate-actions.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CorporateActionJob {

  private static final Logger log = LoggerFactory.getLogger(CorporateActionJob.class);
  private static final List<Period> ANCHOR_OFFSETS =
      List.of(
          Period.ofDays(7), Period.ofMonths(1), Period.ofMonths(3), Period.ofMonths(6),
          Period.ofYears(1), Period.ofYears(2), Period.ofYears(3), Period.ofYears(5));

  /** The Redis integrity key surfaced on GET /api/v1/system/status (B-13). */
  public static final String INTEGRITY_KEY = "marketdata:integrity:corporate-actions";

  /**
   * The statuses that RECORD a committed base rebuild — the only ones a refresh-only resume may
   * follow (V051). {@code BASE_REBUILT} = the run died hard mid-refresh; {@code REFRESH_FAILED} =
   * it threw mid-refresh. Everything else either has no rebuilt base ({@code FAILED},
   * {@code REBACKFILL_RUNNING}) or wants no more work ({@code RESOLVED},
   * {@code REFRESH_ABANDONED}), and resuming refresh-only over an incomplete base would materialise
   * aggregates over an incomplete base — strictly worse than leaving it alone.
   */
  private static final Set<String> BASE_COMMITTED = Set.of("BASE_REBUILT", "REFRESH_FAILED");

  /**
   * States nothing may move a row OUT of. A worker that started before the sweep judged its event
   * must not un-terminal it on the way out (task_6903cd5e).
   */
  private static final Set<String> TERMINAL = Set.of("RESOLVED", "FAILED", "REFRESH_ABANDONED");

  private final InstrumentRepository instruments;
  private final CandleRepository candles;
  private final CandleQueryService queryService;
  private final HistoricalCandleGateway gateway;
  private final CorporateActionRepository events;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final NtfyClient ntfy;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final double tolerance;
  private final double uniformityEpsilon;
  private final int rebackfillDays1m;
  private final int rebackfillDays1d;
  private final List<String> symbolOverride;
  private final int maxRefreshAttempts;
  private final int rebuildRetryCooldownDays;
  private final Counter anchorNoise;
  private final Counter unadjustedTail;

  /**
   * Events with an attempt QUEUED OR RUNNING. The executor serialises EXECUTION but does nothing
   * about the state machine around it: without this fence the next sweep re-reads a snapshot of a
   * row whose attempt is still in flight, and can queue a duplicate attempt (blowing past the
   * bound) or abandon an event that is about to report success — after which the finishing worker
   * overwrites the terminal row. The claim is taken BEFORE enqueueing and released when the task
   * ends, so a claimed event is invisible to {@link #resumeOrAbandon} until its attempt is done and
   * its counter committed (task_6903cd5e).
   */
  private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "corporate-action-rebuild");
            t.setDaemon(true);
            return t;
          });

  /** Wires the job; tolerance/epsilon/depth are config (B-17). */
  public CorporateActionJob(
      InstrumentRepository instruments,
      CandleRepository candles,
      CandleQueryService queryService,
      HistoricalCandleGateway gateway,
      CorporateActionRepository events,
      MarketCalendar calendar,
      Clock clock,
      NtfyClient ntfy,
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      @Value("${artha.corporate-actions.tolerance:0.005}") double tolerance,
      @Value("${artha.corporate-actions.uniformity-epsilon:0.01}") double uniformityEpsilon,
      // defaults exceed Kite's serving depth (~2015 for 1m) so the staged re-backfill spans the
      // whole re-fetchable history — anything deeper cannot be re-fetched anyway, and since V057
      // the swap deletes only the span it actually staged, such bars are now LEFT ALONE rather
      // than purged with no replacement
      @Value("${artha.corporate-actions.rebackfill-days-1m:4400}") int rebackfillDays1m,
      @Value("${artha.corporate-actions.rebackfill-days-1d:7300}") int rebackfillDays1d,
      @Value("${artha.corporate-actions.symbols:}") List<String> symbolOverride,
      // how many cagg-refresh attempts ONE event gets before it is abandoned to an operator; the
      // sweep is daily, so this is also the backoff — 3 spends three calendar days before paging
      @Value("${artha.corporate-actions.max-refresh-attempts:3}") int maxRefreshAttempts,
      // how long a FAILED rebuild waits before the sweep will re-detect the symbol. Bounds the Kite
      // page cost of a symbol that can never pass staged verification (V057) without ever sealing
      // it out of the sweep the way the pre-V057 purge did.
      @Value("${artha.corporate-actions.rebuild-retry-cooldown-days:7}") int rebuildRetryCooldownDays,
      MeterRegistry meterRegistry) {
    this.instruments = instruments;
    this.candles = candles;
    this.queryService = queryService;
    this.gateway = gateway;
    this.events = events;
    this.calendar = calendar;
    this.clock = clock;
    this.ntfy = ntfy;
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.tolerance = tolerance;
    this.uniformityEpsilon = uniformityEpsilon;
    this.rebackfillDays1m = rebackfillDays1m;
    this.rebackfillDays1d = rebackfillDays1d;
    this.symbolOverride = symbolOverride;
    this.maxRefreshAttempts = maxRefreshAttempts;
    this.rebuildRetryCooldownDays = rebuildRetryCooldownDays;
    this.anchorNoise = meterRegistry.counter("ay_corporate_action_anchor_noise_total");
    // bars left at PRE-event prices below the staged span — a silent discontinuity, so it needs
    // a standing signal rather than only a log line
    this.unadjustedTail = meterRegistry.counter("ay_corporate_action_unadjusted_tail_bars_total");
  }

  /** 16:30 IST daily — six-field cron, IST zone, calendar-gated (B-12). */
  @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Kolkata")
  public void scheduledSweep() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    if (!isTradingDaySafe(today)) {
      return;
    }
    sweepNow();
  }

  /** One synchronous detection sweep; remediation runs async per detection. */
  public List<UUID> sweepNow() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    List<Instrument> scope = sweepScope();
    List<UUID> detections = new ArrayList<>();
    List<String> detectedSymbols = new ArrayList<>();
    for (Instrument equity : scope) {
      try {
        sweepSymbol(equity, today)
            .ifPresent(
                id -> {
                  detections.add(id);
                  detectedSymbols.add(equity.tradingsymbol());
                });
      } catch (Exception e) {
        log.warn("corporate-action sweep failed for {}: {}", equity.tradingsymbol(), e.getMessage());
      }
    }
    publishIntegrity(detectedSymbols);
    return detections;
  }

  private List<Instrument> sweepScope() {
    List<Instrument> all = instruments.activeEquities();
    if (symbolOverride.isEmpty() || (symbolOverride.size() == 1 && symbolOverride.get(0).isBlank())) {
      return all;
    }
    return all.stream().filter(i -> symbolOverride.contains(i.tradingsymbol())).toList();
  }

  private java.util.Optional<UUID> sweepSymbol(Instrument equity, LocalDate today) throws Exception {
    InstrumentKey key = new InstrumentKey(equity.exchange(), equity.tradingsymbol());
    // A14 resume: a symbol whose MOST-RECENT event RECORDS a committed base rebuild got its base
    // re-fetched but never finished the chunked cagg refresh — resume the refresh only, never
    // redo the ~12-year staged rebuild. Detection would NOT re-fire this (post-rebuild cache == Kite ⇒ no
    // divergence, so no fresh DETECTED row + no double-remediation), so this checkpoint scan is the
    // SOLE resume trigger. Reuses the existing event row.
    //
    // task_6903cd5e (2026-07-31): that reasoning covers a refresh that ERRORED exactly as much as
    // one that crashed, yet only BASE_REBUILT was checked — so the job recovered from a crash but
    // not from an error, and 13 symbols sat live with a rebuilt base and ~1% of a control symbol's
    // cagg rows, forever. REFRESH_FAILED (V051) is the recorded form of that class and resumes here.
    java.util.Optional<CorporateActionRepository.EventRow> latest =
        events.latestEvent(equity.exchange(), equity.tradingsymbol());
    if (latest.isPresent() && BASE_COMMITTED.contains(latest.get().status())) {
      resumeOrAbandon(latest.get(), equity, today, key);
      return java.util.Optional.empty(); // a resume, not a fresh detection
    }
    // The rebuild path's retry BOUND (V057). Before this PR a failed rebuild gutted the symbol, and
    // the hasNonBhavcopyDaily pre-filter below then sealed it out of the sweep forever — which
    // accidentally bounded the cost of a symbol that could not be rebuilt. Leaving the cache intact
    // deliberately breaks that seal, so the divergence stays visible and the next sweep re-detects:
    // the natural retry this PR exists to restore. Unbounded, that same property is a nightly
    // ~196-page Kite re-fetch (1d + 1m at 60-day paging) against the SHARED rate limiter, plus a
    // nightly urgent page, for any symbol whose rebuild can never pass verification.
    //
    // So a recent FAILED rebuild cools off rather than retrying every night. A cooldown, not a
    // strike count: a count that reaches its bound re-seals the symbol permanently, which is the
    // failure mode this PR is closing, whereas a cooldown bounds the COST without ever making the
    // state unrecoverable. It is deliberately NOT applied to any other status — a resumable
    // refresh keeps its own separate, count-based bound (maxRefreshAttempts → REFRESH_ABANDONED).
    // detected_at is NOT NULL (V006_2), so there is no null branch to guard. The IST conversion IS
    // needed: `today` is an IST date and detectedAt is a UTC-offset instant, so a bare toLocalDate()
    // compares a UTC date against an IST one — agreeing for the 16:30-IST cron (11:00 UTC) but
    // silently granting an 8-day cooldown to a manual sweepNow() after 18:30 IST.
    if (latest.isPresent()
        && "FAILED".equals(latest.get().status())
        && latest
            .get()
            .detectedAt()
            .atZoneSameInstant(Ist.ZONE)
            .toLocalDate()
            .isAfter(today.minusDays(rebuildRetryCooldownDays))) {
      log.debug(
          "corporate-action rebuild for {} failed within the {}-day cooldown — not retrying tonight",
          key.canonical(),
          rebuildRetryCooldownDays);
      return java.util.Optional.empty();
    }
    // Skip BHAVCOPY-only equities (Phase C): the bulk universe is split/bonus-adjusted on read by
    // EquitySplitBonusAdjuster, not by this staged Kite-refetch path. Without this gate, projecting
    // bhavcopy 1d candles for the whole ~22k equity universe would fire one Kite fetch per symbol
    // on every sweep.
    if (!candles.hasNonBhavcopyDaily(equity.exchange(), equity.tradingsymbol())) {
      return java.util.Optional.empty();
    }
    // anchors snapped back to trading days; only those with a cached close participate
    Map<LocalDate, BigDecimal> cachedCloses = new HashMap<>();
    for (Period offset : ANCHOR_OFFSETS) {
      LocalDate anchor = snapToTradingDay(today.minus(offset));
      OffsetDateTime bucket = anchor.atStartOfDay().atOffset(Ist.OFFSET);
      BigDecimal cached = candles.closeAt(equity.exchange(), equity.tradingsymbol(), "1d", bucket);
      // closeAt is <=-semantics: only trust an exact-bucket hit for anchor comparison
      List<in.arthayantra.marketdata.candles.Candle> exact =
          candles.range(equity.exchange(), equity.tradingsymbol(), "1d", bucket, bucket.plusDays(1));
      if (cached != null && !exact.isEmpty()) {
        cachedCloses.put(anchor, exact.get(0).close());
      }
    }
    if (cachedCloses.size() < 2) {
      return java.util.Optional.empty(); // nothing comparable cached
    }
    LocalDate oldest = cachedCloses.keySet().stream().min(LocalDate::compareTo).orElseThrow();
    // ONE ranged 1d fetch through the rate-limited gateway — diff only, never upserted
    List<HistoricalCandleGateway.Candle> kiteBars =
        gateway.fetch(
            key,
            "1d",
            oldest.atStartOfDay().atOffset(Ist.OFFSET).toInstant(),
            today.atStartOfDay().atOffset(Ist.OFFSET).toInstant());
    Map<LocalDate, BigDecimal> kiteCloses = new HashMap<>();
    for (HistoricalCandleGateway.Candle bar : kiteBars) {
      kiteCloses.put(bar.bucketStart().toInstant().atZone(Ist.ZONE).toLocalDate(), bar.close());
    }

    List<CorporateActionDetector.AnchorEvidence> evidence = new ArrayList<>();
    for (Map.Entry<LocalDate, BigDecimal> entry : cachedCloses.entrySet()) {
      BigDecimal kite = kiteCloses.get(entry.getKey());
      if (kite != null) {
        evidence.add(
            CorporateActionDetector.AnchorEvidence.of(entry.getKey(), entry.getValue(), kite));
      }
    }
    long divergedCount =
        evidence.stream()
            .filter(a -> Math.abs(a.ratio().doubleValue() - 1.0) > tolerance)
            .count();
    var detection = CorporateActionDetector.evaluate(evidence, tolerance, uniformityEpsilon);
    if (detection.isEmpty()) {
      if (divergedCount == 1) {
        anchorNoise.increment();
        log.info("single-anchor divergence on {} — noise, never remediated", key.canonical());
      }
      return java.util.Optional.empty();
    }

    CorporateActionDetector.Detection d = detection.get();
    UUID id =
        events.insertDetected(
            equity.exchange(),
            equity.tradingsymbol(),
            d.effectiveBoundary(),
            d.uniformRatio(),
            d.anchorsChecked(),
            d.anchorsDiverged(),
            objectMapper.writeValueAsString(d.diverged()));
    ntfy.send(
        "Corporate action detected",
        "default",
        key.canonical() + " ratio " + d.uniformRatio() + " on " + d.anchorsDiverged() + " anchors");
    submitRemediation(id, equity, today);
    return java.util.Optional.of(id);
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private void submitRemediation(UUID id, Instrument equity, LocalDate today) {
    if (!inFlight.add(id)) {
      return; // already queued or running — never two attempts against one event
    }
    executor.submit(
        () -> {
          List<String> swappedIntervals = new ArrayList<>();
          try {
            events.updateStatus(id, "REBACKFILL_RUNNING");
            OffsetDateTime now = OffsetDateTime.now(clock);
            rebuildBaseByStagedSwap(equity, today, now, swappedIntervals);
            events.updateStatus(id, "BASE_REBUILT");
            refreshRebuiltAggregates(id, today, now);
            recordResolved(
                id,
                "BASE_REBUILT",
                equity,
                " cache rebuilt; swapped intervals: " + String.join(", ", swappedIntervals));
          } catch (Exception e) {
            log.error("corporate-action rebuild failed for {}", equity.tradingsymbol(), e);
            recordFailure(id, equity, e, swappedIntervals);
          } finally {
            inFlight.remove(id);
          }
        });
  }

  /**
   * FETCH → VERIFY → SWAP: the base rebuild, ordered so that a failed re-fetch leaves the existing
   * series INTACT.
   *
   * <p>It used to be purge → re-fetch, and it had to be, because the re-fetch went through the
   * GAP-AWARE {@code prefetch}/{@code ensureCoverage}: that only fetches buckets the cache misses,
   * so against an intact series it fetches nothing — the purge was what manufactured the gaps. The
   * cost of that bargain was that every failure mode of a multi-hour, ~12-year, rate-limited Kite
   * fetch (rate limit, auth, network, OOM) landed AFTER the destruction, and nothing ever re-fired:
   * a purged symbol fails the sweep's own {@code hasNonBhavcopyDaily} pre-filter forever once the
   * daily bhavcopy job refills its 1d bars, so the sweep skipped its own victims before reaching
   * detection. 45 symbols were measured in that state on 2026-08-04, each holding only BHAVCOPY 1d
   * bars and zero 1m base rows.
   *
   * <p>{@code stageFullRange} ignores present coverage instead, so no purge is needed to force the
   * re-fetch, and the whole fetch lands in the V057 staging buffer. Only once
   * {@link #verifyStagedRebuild} proves the staged series covers what it is about to overwrite does
   * {@code swapStaged} delete and refill — a delete scoped to exactly the staged span, so every
   * bucket removed provably has a replacement row waiting.
   *
   * <p>⚠️ Staging is cleared on the way IN as well as OUT, unconditionally, and round 4 deliberately
   * KEPT it that way after trying the alternative. Review F4 asked for the staged rows to survive a
   * partial swap as a resumable checkpoint; that was implemented and then REVERTED, because
   * {@link #verifyStagedRebuild} validates COVERAGE ONLY — bar counts and span bounds, never values
   * — and the existence probe it resumed on carried no fetch time and no event identity. A retry
   * fires after {@code COOLDOWN_DAYS}, so a checkpoint staged before a LATER corporate action would
   * pass verification on coverage and be swapped in at pre-event prices: silently wrong data on the
   * exact plane this class exists to protect. Less destructive is not safer. A from-scratch rebuild
   * is wasteful and correct; real resumability needs provenance columns on the staging table plus a
   * freshness check, which is a separate change. The partial state is made VISIBLE instead — see the
   * urgent failure alert naming the intervals that actually committed.
   *
   * <p>The 1m stage still defers the cagg refresh to the caller (it writes no aggregates at all
   * now), so the {@code BASE_REBUILT} checkpoint still lands after the base commits and BEFORE the
   * chunked refresh — a hard crash mid-refresh RESUMES on the next sweep instead of redoing ~12
   * years for nothing (A14, 2026-07-10 3× live-Postgres OOM).
   */
  private void rebuildBaseByStagedSwap(
      Instrument equity, LocalDate today, OffsetDateTime now, List<String> swappedIntervals) {
    String exchange = equity.exchange();
    String symbol = equity.tradingsymbol();
    try {
      candles.clearStaging(exchange, symbol);
      queryService.stageFullRange(
          exchange, symbol, "1d",
          today.minusDays(rebackfillDays1d).atStartOfDay().atOffset(Ist.OFFSET), now);
      queryService.stageFullRange(
          exchange, symbol, "1m",
          today.minusDays(rebackfillDays1m).atStartOfDay().atOffset(Ist.OFFSET), now);
      // BOTH intervals are verified BEFORE EITHER is swapped: verifying and swapping per-interval
      // would let a good 1d swap land and a bad 1m refusal abort, leaving the symbol half-adjusted
      // — a series that looks complete and is silently wrong, which is worse than the gutting this
      // change exists to prevent.
      verifyStagedRebuild(equity, "1d");
      verifyStagedRebuild(equity, "1m");
      // ⚠️ 1m SWAPS FIRST AND 1d LAST, AND THE ORDER IS LOAD-BEARING — do not "tidy" it back into
      // the 1d-then-1m order the staging above uses.
      //
      // The two interval swaps are separate operations, but each DELETE+INSERT window is one
      // transaction. Making the full ~1.1M-row interval replacement one transaction is exactly the
      // memory profile that OOM'd this instance 3× — see PURGE_WINDOW_MONTHS. So an ORDINARY
      // catchable DB error on the second interval — decompression cap, lock or statement timeout,
      // disk — commits the first interval and retains the verified staging checkpoint for retry.
      //
      // Detection reads ONLY the 1d series (the anchor diff above). So if 1d went first and 1m
      // failed, the cache's 1d would now EQUAL Kite: the next sweep would find no divergence, never
      // re-detect, and the symbol would sit forever on adjusted 1d + unadjusted 1m + stale caggs —
      // the self-sealing class this whole PR exists to close, rebuilt in a new place.
      //
      // Swapping the detection input LAST inverts that: the same failure leaves 1d unadjusted, so
      // the divergence stays VISIBLE and the rebuild can redo both legs idempotently (a re-stage +
      // re-verify + re-swap over an already-adjusted 1m is a no-op in value terms).
      //
      // ⚠️ "Visible" is NOT "recovered on the next sweep", and an earlier draft of this comment
      // claimed the stronger thing. A failure here records FAILED with detected_at = today, which
      // the rebuild cooldown gate above then holds off for rebuildRetryCooldownDays — so recovery
      // is that many days later, not the next night, with the symbol on adjusted 1m + unadjusted 1d
      // + stale caggs throughout. It is PAGED urgently the whole time (recordFailure alerts on the
      // REBACKFILL_RUNNING -> FAILED transition), and it is still strictly better than the pre-V057
      // permanent seal, but it is not next-night recovery. Distinguishing "failed before any swap"
      // (a livelock candidate the cooldown is FOR) from "failed after a partial swap" (a transient
      // DB error the cooldown should skip) needs a recorded status that does not exist yet, so it
      // is a follow-up, not something to infer at read time. Pinned by
      // aPartialSwapFailureIsHeldByTheCooldownNotRetriedNextSweep.
      // Each swap commits per six-month WINDOW, so a failure partway through an interval leaves
      // durable windows behind. PartialSwapException is how that progress reaches the caller — the
      // interval never gets added to swappedIntervals on that path, so without it a half-replaced
      // series is indistinguishable from one where nothing landed.
      try {
        candles.swapStaged(exchange, symbol, "1m");
        swappedIntervals.add("1m");
        reportUnadjustedTail(equity, "1m");
        candles.swapStaged(exchange, symbol, "1d");
        swappedIntervals.add("1d");
        reportUnadjustedTail(equity, "1d");
      } catch (CandleRepository.PartialSwapException partial) {
        swappedIntervals.add(partial.interval() + PARTIAL_SUFFIX);
        throw partial;
      }
    } finally {
      candles.clearStaging(exchange, symbol);
    }
  }

  /**
   * Refuses a staged series that is not a safe replacement, BEFORE anything is deleted. Throwing
   * here lands the event in {@code FAILED} exactly as any other pre-base-commit failure does — and
   * because the cache is now untouched, it still diverges from Kite, so the NEXT sweep re-detects
   * the symbol and tries again. That natural retry is the property the old order destroyed.
   *
   * <p>Three checks, no tolerances — a swap that accepts a truncated fetch is the original defect
   * with extra steps:
   *
   * <ul>
   *   <li>NON-EMPTY. Guards a fetch that returned nothing without throwing; swapping it in would be
   *       a purge wearing a rebuild's clothes.
   *   <li>NO UNREPLACED BUCKET inside the staged span. This is the invariant that makes the swap
   *       safe at all: every live bucket the delete will remove has a staged row to replace it.
   *       Catches an interior hole and a mid-range truncation.
   *   <li>REACHES AT LEAST AS FAR FORWARD as the live series. Its real trigger is a fetch that came
   *       back SHORT WITHOUT THROWING — a fetch that dies throws, and never reaches verification at
   *       all. {@code GapDetector} pages oldest-first, so such a fetch is short at the RECENT end,
   *       where the staged span simply stops early and the previous check, which only judges INSIDE
   *       that span, cannot see it.
   * </ul>
   *
   * <p>A fourth measurement is taken but does NOT refuse: bars OLDER than the staged span. The swap
   * leaves them untouched, so after a corporate action they keep pre-event prices while everything
   * above is adjusted — a silent discontinuity no other signal can reach (the deepest anchor is 5
   * years; check 2 is span-scoped by construction). Refusing on it would permanently strand the 49
   * live symbols that hold such bars, so it is alerted rather than blocked, and the operator's
   * remedy is to widen {@code rebackfill-days-1d} until the count reaches zero.
   */
  private void verifyStagedRebuild(Instrument equity, String interval) {
    CandleRepository.StagedCoverage staged =
        candles.stagedCoverage(equity.exchange(), equity.tradingsymbol(), interval);
    String key = equity.exchange() + ":" + equity.tradingsymbol() + " " + interval;
    if (staged.stagedBars() == 0) {
      throw new IllegalStateException("staged rebuild for " + key + " fetched no bars");
    }
    if (staged.cachedBarsNotStaged() > 0) {
      throw new IllegalStateException(
          "staged rebuild for "
              + key
              + " would delete "
              + staged.cachedBarsNotStaged()
              + " cached bar(s) it cannot replace");
    }
    if (staged.cachedTo() != null && staged.stagedTo().isBefore(staged.cachedTo())) {
      throw new IllegalStateException(
          "staged rebuild for "
              + key
              + " stops at "
              + staged.stagedTo()
              + ", short of the cached series at "
              + staged.cachedTo());
    }
    log.info(
        "staged rebuild for {} verified: {} bar(s) covering [{} .. {}]",
        key, staged.stagedBars(), staged.stagedFrom(), staged.stagedTo());
  }

  /** Reports the post-swap tail only after this interval's replacement has committed. */
  private void reportUnadjustedTail(Instrument equity, String interval) {
    CandleRepository.StagedCoverage postSwap =
        candles.stagedCoverage(equity.exchange(), equity.tradingsymbol(), interval);
    String key = equity.exchange() + ":" + equity.tradingsymbol() + " " + interval;
    if (postSwap.cachedBarsBelowSpan() > 0) {
      unadjustedTail.increment(postSwap.cachedBarsBelowSpan());
      log.warn(
          "swapped rebuild for {} leaves {} cached bar(s) older than {} UNADJUSTED — the series"
              + " carries a price discontinuity at that boundary; widen"
              + " artha.corporate-actions.rebackfill-days-"
              + interval
              + " to cover them",
          key,
          postSwap.cachedBarsBelowSpan(),
          postSwap.stagedFrom());
    }
  }

  /**
   * Resume path (A14): the base was already re-fetched (committed) in a prior remediation that
   * crashed or errored mid-refresh — redo the CHUNKED cagg refresh ONLY, then RESOLVED. Skips the
   * staged re-backfill and the swap entirely. Status is LEFT as it was during the refresh (not downgraded to
   * REBACKFILL_RUNNING) so a hard crash here re-resumes on the next sweep instead of stranding a
   * REBACKFILL_RUNNING row; a soft (catchable) failure re-records the same resumable class, which
   * the next sweep picks up again until the attempt bound is spent.
   *
   * <p>{@code entryStatus} is the status the gate resumed FROM, and success compare-and-sets against
   * it — a refresh can outlive the sweep that queued it, and the row it started on may have been
   * judged terminal since.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  private void submitRefreshOnly(UUID id, String entryStatus, Instrument equity, LocalDate today) {
    if (!inFlight.add(id)) {
      return; // already queued or running — never two attempts against one event
    }
    executor.submit(
        () -> {
          try {
            refreshRebuiltAggregates(id, today, OffsetDateTime.now(clock));
            recordResolved(id, entryStatus, equity, " cagg refresh resumed");
          } catch (Exception e) {
            log.error("corporate-action refresh resume failed for {}", equity.tradingsymbol(), e);
            recordFailure(id, equity, e, null);
          } finally {
            inFlight.remove(id);
          }
        });
  }

  /**
   * The success write, compare-and-set against the status the attempt started on. A losing CAS means
   * a concurrent sweep already judged this event (the only realistic writer is an abandon), so the
   * terminal row stands and NO "rebuilt" alert goes out — claiming success for a run whose verdict
   * was overruled is exactly the lie an operator would act on.
   */
  private void recordResolved(UUID id, String expected, Instrument equity, String detail) {
    if (!events.updateStatusIf(id, expected, "RESOLVED")) {
      log.warn(
          "corporate-action refresh for {} finished but the event left {} — not marking RESOLVED",
          equity.tradingsymbol(),
          expected);
      return;
    }
    ntfy.send(
        "Corporate action rebuilt",
        "default",
        equity.exchange() + ":" + equity.tradingsymbol() + detail);
  }

  /**
   * The checkpoint gate (task_6903cd5e): a base-committed event gets another refresh-only attempt,
   * or — once its recorded attempts reach the bound — is abandoned to an operator ONCE. Abandoning
   * is done HERE rather than in the failure handler on purpose: a run that dies without unwinding
   * never reaches a catch block, so deciding at the gate is the only place that bounds a hard-crash
   * loop too, and the sweep is alive to alert. REFRESH_ABANDONED is outside {@link #BASE_COMMITTED},
   * so the row is never re-entered and the urgent alert fires exactly once.
   *
   * <p>An event with an attempt still IN FLIGHT is left strictly alone. Judging it would be judging
   * a stale snapshot: its counter is already incremented (the attempt records itself before it
   * runs), so the bound reads as spent while the work that might still succeed is running — and
   * abandoning it hands the finishing worker a terminal row to overwrite.
   */
  private void resumeOrAbandon(
      CorporateActionRepository.EventRow row,
      Instrument equity,
      LocalDate today,
      InstrumentKey key) {
    if (inFlight.contains(row.id())) {
      log.info("corporate-action refresh for {} still in flight — sweep leaves it", key.canonical());
      return;
    }
    if (row.refreshAttempts() >= maxRefreshAttempts) {
      // CAS from the status we READ: between that read and here the attempt could have finished
      // and RESOLVED the row, and an abandon must never overwrite a completed rebuild
      if (!events.updateStatusIf(row.id(), row.status(), "REFRESH_ABANDONED")) {
        log.info("corporate-action event for {} moved before abandon — leaving it", key.canonical());
        return;
      }
      log.error(
          "corporate-action cagg refresh abandoned for {} after {} attempts",
          key.canonical(),
          row.refreshAttempts());
      ntfy.send(
          "Corporate action refresh ABANDONED",
          "urgent",
          key.canonical()
              + " — cagg refresh failed "
              + row.refreshAttempts()
              + " attempts; the base is rebuilt but its aggregates stay STALE until an operator"
              + " re-arms the event");
      return;
    }
    submitRefreshOnly(row.id(), row.status(), equity, today);
  }

  /**
   * Records a failure against the phase the ROW actually reached, never against what the in-flight
   * lambda believes — {@code status} is one overwritten column, so "did this run commit its base?"
   * is only answerable by reading it back. Base committed ⇒ REFRESH_FAILED, which the next sweep
   * resumes (redoing a cagg refresh cannot corrupt anything). Base NOT committed ⇒ plain FAILED,
   * which the checkpoint deliberately does NOT resume: since V057 the run failed at or before the
   * staged swap, so the base is either untouched or half-swapped — never the completed rebuild a
   * refresh-only retry assumes, and materialising aggregates over it would bake in that state.
   *
   * <p>Alerts fire on the state CHANGE only. With retries armed, an alert per attempt would page the
   * operator every night for the same symbol; the transitions that matter are the FIRST failure and
   * reaching a terminal state, and {@link #resumeOrAbandon} owns the latter.
   *
   * <p>A row already TERMINAL is left untouched: a concurrent sweep judged this event while the
   * attempt was running, and downgrading {@code REFRESH_ABANDONED} to {@code FAILED} on the way out
   * would both destroy that verdict and re-classify the event into the wrong failure class.
   */
  /** Marks an interval whose swap left durable windows behind without finishing. */
  private static final String PARTIAL_SUFFIX = "(partial)";

  /**
   * Both intervals swapped with NO partial marker — the staged base is fully in place.
   *
   * <p>Deliberately requires the absence of {@link #PARTIAL_SUFFIX}: a half-replaced interval means
   * the base is NOT committed, and treating it as such would route a genuinely broken series to a
   * resume path that only redoes aggregates.
   */
  private static boolean bothIntervalsSwappedCleanly(List<String> swappedIntervals) {
    return swappedIntervals != null
        && swappedIntervals.size() == 2
        && swappedIntervals.stream().noneMatch(i -> i.endsWith(PARTIAL_SUFFIX));
  }

  private void recordFailure(
      UUID id, Instrument equity, Exception e, List<String> swappedIntervals) {
    String recorded = events.statusOf(id).orElse("");
    if (TERMINAL.contains(recorded)) {
      log.warn(
          "corporate-action attempt for {} failed but the event is already {} — leaving it",
          equity.tradingsymbol(),
          recorded);
      return;
    }
    // ⚠️ The base can be committed WITHOUT the checkpoint having been written (cross-vendor review
    // 2026-08-10, round 3). Both swaps land, then reportUnadjustedTail or clearStaging throws —
    // before recordResolved persists BASE_REBUILT. Judged on `recorded` alone that is an ordinary
    // FAILED, and this one is WORSE than the cooldown: both live intervals now MATCH Kite, so
    // detection finds no divergence and never re-fires. The base is correct and the cagg refresh is
    // stranded FOREVER — the 13-symbols-with-1%-of-their-caggs failure this class already carries a
    // resume path for. Two CLEAN entries mean the base IS committed, so route to REFRESH_FAILED,
    // which BASE_COMMITTED covers and the checkpoint scan resumes.
    boolean baseCommitted =
        BASE_COMMITTED.contains(recorded) || bothIntervalsSwappedCleanly(swappedIntervals);
    // ⚠️ A PARTIAL swap is not an ordinary failure and must NOT be recorded as one (V056; found by
    // cross-vendor review 2026-08-10). The cooldown skips any symbol whose latest event is FAILED
    // within rebuild-retry-cooldown-days — so recording a committed-1m/failed-1d run as FAILED
    // suppressed the very recovery the 1m-first swap order exists to guarantee, and left every
    // consumer reading ADJUSTED 1m against UNADJUSTED 1d for that symbol for the whole cooldown.
    // PARTIAL_SWAP is outside the cooldown's match set, so the next sweep re-detects (1d is still
    // unadjusted, so detection DOES re-fire) and re-stages from scratch. Fresh restage, not reuse:
    // retained staging is unsafe here because verifyStagedRebuild validates coverage only.
    // Partial = ANY durable progress that is not a clean full swap of BOTH intervals. Note the
    // size<2 test alone is NOT enough: 1m fully swapped plus 1d failing PARTWAY yields two entries
    // and is still a half-replaced series, which is why the suffix is checked too.
    boolean partialSwap =
        swappedIntervals != null
            && !swappedIntervals.isEmpty()
            && (swappedIntervals.size() < 2
                || swappedIntervals.stream().anyMatch(i -> i.endsWith(PARTIAL_SUFFIX)));
    String next = baseCommitted ? "REFRESH_FAILED" : partialSwap ? "PARTIAL_SWAP" : "FAILED";
    if (!events.updateStatusIf(id, recorded, next)) {
      log.warn(
          "corporate-action event for {} moved during a failing attempt — not writing {}",
          equity.tradingsymbol(),
          next);
      return;
    }
    if (next.equals(recorded)) {
      return; // same state as the previous attempt — nothing changed to report
    }
    String progress =
        swappedIntervals == null
            ? ""
            : swappedIntervals.isEmpty()
                ? " — no candle intervals swapped"
                : swappedIntervals.size() == 2
                    ? " — candle intervals swapped: " + String.join(", ", swappedIntervals)
                    : " — partial rebuild; swapped interval(s): "
                        + String.join(", ", swappedIntervals);
    // The title names the status ACTUALLY recorded. It used to hardcode "FAILED", which after V056
    // would have paged FAILED for a row written as PARTIAL_SWAP — an operator reading the page would
    // conclude the cooldown now holds the symbol for a week when in fact it retries tonight.
    ntfy.send(
        baseCommitted ? "Corporate action refresh failed" : "Corporate action rebuild " + next,
        baseCommitted ? "default" : "urgent",
        equity.exchange()
            + ":"
            + equity.tradingsymbol()
            + (baseCommitted ? " — base rebuilt, cagg refresh retrying: " : " — ")
            + progress
            + " — "
            + e.getMessage());
  }

  /**
   * The chunked cagg refresh over the rebuilt 1m window (CandleRepository slices it ≤92-day).
   *
   * <p>Two independent properties meet here, from #1151 and task_6903cd5e. It calls the REBUILD
   * variant because this span reaches back years into COMPRESSED cagg chunks and so needs the raised
   * per-DML decompression cap that every other refresh caller deliberately does without. And the
   * attempt is RECORDED BEFORE it runs, not after it fails, so the retry bound still holds when a run
   * dies without unwinding — the OOM kill this checkpoint exists for never reaches a catch block,
   * which is exactly why counting failures instead of attempts would leave crash loops unbounded.
   */
  private void refreshRebuiltAggregates(UUID id, LocalDate today, OffsetDateTime now) {
    events.incrementRefreshAttempts(id);
    candles.refreshDerivedAggregatesForRebuild(
        today.minusDays(rebackfillDays1m).atStartOfDay().atOffset(Ist.OFFSET), now);
  }

  private LocalDate snapToTradingDay(LocalDate date) {
    LocalDate candidate = date;
    for (int i = 0; i < 10 && !isTradingDaySafe(candidate); i++) {
      candidate = candidate.minusDays(1);
    }
    return candidate;
  }

  private void publishIntegrity(List<String> detected) {
    try {
      redis
          .opsForValue()
          .set(
              INTEGRITY_KEY,
              objectMapper.writeValueAsString(
                  Map.of("lastRun", OffsetDateTime.now(clock).toString(), "detected", detected)));
    } catch (Exception e) {
      log.warn("integrity key publish failed: {}", e.getMessage());
    }
  }

  private boolean isTradingDaySafe(LocalDate day) {
    try {
      return calendar.isTradingDay(day);
    } catch (IllegalArgumentException uncoveredYear) {
      java.time.DayOfWeek dow = day.getDayOfWeek();
      return dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY;
    }
  }
}
