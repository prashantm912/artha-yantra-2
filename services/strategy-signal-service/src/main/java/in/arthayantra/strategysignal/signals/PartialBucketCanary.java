package in.arthayantra.strategysignal.signals;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Live guard for the audit B1 / FID P0-1 done-check ("live 3m bar volume vs 1m-sum for the same
 * bucket"): the {@link LiveSeriesStore} completeness filter now excludes in-progress buckets so a
 * frozen first-minute partial can no longer accrue — this canary is the standing detector that the
 * regression stays fixed. For every warmed 3m series it re-derives the LAST COMPLETED 3m bar's
 * volume from the three 1m bars of the same bucket already held in the store's 1m series and
 * compares. A frozen 3m partial carries only its first minute's volume (~1/3 of the true bucket),
 * so a persistent volume shortfall is the fingerprint of the bug reappearing. Both series are in
 * memory — the comparison makes no REST call, no DB read and needs no new endpoint (only the
 * lot-size corroboration below reads the existing instrument-master endpoint, memoized per contract).
 *
 * <p>Purely observational: on an UNEXPLAINED mismatch it increments
 * {@code ay_signal_partial_bucket_mismatch_total}
 * and logs once per (series, bucket); it never blocks or mutates a series. It skips silently when the
 * three 1m bars are not all present (a coverage gap, not a freeze — {@code volume-tolerance} does not
 * apply to that case) or when the newest 3m bucket is still forming.
 *
 * <p>The comparison is structurally UNLIKE-vs-UNLIKE (T23, 2026-07-25): the 3m side is a REST-pulled
 * SQL rollup of DB 1m rows that the 10-minute recency window authoritatively REPLACES with
 * broker-official Kite bars at every boundary, while the store's 1m side stays live tick-agg and is
 * never revised. A tick printing in the sub-second gap between the last ~1 Hz snapshot before a 3m
 * edge and the edge itself is credited to the earlier bucket by the broker and the later one by
 * tick-agg, so consecutive buckets carry equal-and-opposite lot-multiple skews (measured ≤8 lots =
 * 520 on 35 of 37 events, 2026-07-24). A mismatch is treated as benign only when it clears BOTH
 * gates: at most the absolute tolerance — {@code max(volume-tolerance,
 * volume-tolerance-pct% of the expected 1m sum)}, default floor 650 = 10 NIFTY lots — AND at most
 * 10% of the expected 1m sum. The relative gate keeps a frozen partial on a genuinely THIN bucket
 * (e.g. 400 frozen of a true 1,000) firing even though its absolute shortfall is small. The frozen
 * first-minute signature (~2/3 of the bucket missing) fails both gates on any bar. Both knobs are
 * live-tunable: {@code artha.signals.partial-bucket-canary.volume-tolerance} (floor) +
 * {@code ...volume-tolerance-pct} (scaling).
 *
 * <p>⚠️ <b>{@code volume-tolerance-pct} DEFAULTS TO 0 — the scaling mechanism ships DORMANT, and
 * raising it is not safe</b> (G9/T23, 2026-07-29). The residue IS proportional (2.4–3.7% of a
 * thick opening bar) so scaling looks right, but the benign straddle arrives as an equal-and-opposite
 * PAIR across consecutive buckets whose members differ hugely as a FRACTION of their own bucket: the
 * 07-29 ±16,835 pair is 3.7% of the 460,005 opening bucket but 11.9% of the 141,245 next one. Any
 * pct>0 large enough to quiet the thick half leaves the thin half WARNing, converting a benign PAIRED
 * event into an <b>unpaired</b> one — precisely the signature the runbook (signal-analysis README
 * §3.17) teaches operators to read as a real defect, with its corroborating partner now suppressed.
 * At pct=0 the predicate is byte-identical to the pre-G9 fixed-absolute gate (pinned by test), so no
 * event changes state. Pair-aware suppression now EXISTS (below) and keys on the ± partner exactly
 * as that warning asked — which makes {@code pct} redundant rather than safe, so <b>leave it at
 * 0</b>: at pct=5 the 07-29 pair still splits, because the thick half's arm is
 * {@code max(650, 5% × 460,005) = 23,000} (16,835 ≤ it ⇒ benign, never held) while the thin half's
 * is {@code max(650, 5% × 141,245) = 7,062} (16,835 &gt; it ⇒ fires), so the pair rule never sees
 * two halves and the survivor WARNs ALONE. Arming pct on top of pair awareness manufactures the very
 * false-unpaired event both changes exist to eliminate. Pinned by test.
 *
 * <p><b>Pair awareness (G9/T23, 2026-08-01).</b> The gate above reads each bucket in isolation, but
 * the benign signature the runbook documents (signal-analysis README §3.17) is a PAIR property: the
 * straddling tick is not lost, merely credited one bucket late, so bucket N is short by X and bucket
 * N+1 is long by X. On 2026-07-29 all six WARNs were exact ± pairs on consecutive buckets, every
 * magnitude an exact lot multiple, zero unpaired events — provably benign by shape, and every one
 * still alarmed. So a non-benign bucket is now HELD for one bucket instead of alarming at once; when
 * the next bucket carries the equal-and-opposite partner the PAIR is suppressed together (one INFO
 * line + {@code ay_signal_partial_bucket_straddle_total}) and neither half is ever reported alone —
 * a half-suppressed pair would present as an unpaired event, which is exactly the shape §3.17
 * teaches operators to read as a real defect.
 *
 * <p>Corroboration is deliberately narrow — all four must hold, or the held half WARNs: the partner
 * is on the IMMEDIATELY following bucket; the two skews have OPPOSITE signs; both magnitudes are
 * exact multiples of the contract's lot size (from the instrument master, never a hardcoded 65 —
 * SENSEX differs and exchange revisions move both; a lot size not yet resolved keeps the rule
 * CLOSED, so the first non-benign bucket of a contract can never pair);
 * and the residue that does NOT cancel clears the same absolute arm the canary already treats as
 * noise per event AND stays under 10% of the larger half, so two unrelated events of merely similar
 * size cannot masquerade as a pair. That last bound is the whole safety argument: suppressing a pair
 * can hide at most {@code volume-tolerance} of unexplained volume, which is exactly what the
 * per-event gate already tolerates — the change adds no new blind spot.
 *
 * <p><b>Both</b> halves must be straddle-SHAPED — at most 25% of their OWN bucket. A larger skew is
 * structural, not the volume of a sub-second gap (the widest measured half is 11.9%), so the frozen
 * first-minute signature this canary exists for — ~2/3 of the bucket missing — keeps its pre-G9
 * latency exactly, always, whether it arrives as the FIRST bucket of a candidate pair or as the
 * partner. Capping only the held half was a hole (cross-vendor review): a frozen partner could be
 * cancelled by an earlier small opposite skew and suppressed, because the residue bound limits the
 * NET unexplained volume and says nothing about the GROSS corruption inside either bucket.
 *
 * <p>Deferral is preferred over emit-then-retract because this is a pager: a retraction only helps
 * an operator who reads it, and the counter cannot be un-incremented. The cost is real latency, and
 * the honest bounds (60 s sweep, 3 m bucket) measured against a bucket completing at T are: a
 * >25%-of-bucket skew — the frozen-partial signature — is never deferred and still WARNs by T+60 s,
 * unchanged; a SAME-SIGN sustained drift is released when the next bucket is classified, so its
 * first WARN lands by T+4 m instead of T+60 s, <b>one full bucket later</b>; and a partner that
 * never arrives (no successor bar, a coverage gap on it, or session end) is released on a wall-clock
 * deadline of partner-bucket completion plus one sweep, surfacing by T+5 m — <b>up to ~4 minutes
 * later than before</b>.
 *
 * <p>A restart inside that window would otherwise silence an observation the canary chose not to
 * report yet, so a hold is taken ONLY when it has been stored durably first ({@link HeldHalves});
 * failing that, the event WARNs immediately. On cold start the carried-over half is restored and
 * resolved by the ordinary machinery — paired if the newest bucket happens to be its successor,
 * WARNed otherwise — so it holds however long the restart lasted and however many buckets completed
 * meanwhile. Note that lot sizes start UNRESOLVED after a restart, so the first pair a fresh process
 * sees WARNs both halves rather than suppressing: two independently safe behaviours composing into
 * extra noise, never into silence.
 *
 * <p>Depends ONLY on {@link LiveSeriesStore} (never {@link SignalEngine}) and shares the engine's
 * on/off gate ({@code artha.signals.engine-enabled}, default on): the 3m series only exist when the
 * live engine warms them, so this is meaningless — and would just churn — when the engine is disabled
 * (e.g. the paper integration tests). Gating on the same property keeps it out of that context.
 */
@Component
@ConditionalOnProperty(
    value = "artha.signals.engine-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PartialBucketCanary {

  private static final Logger log = LoggerFactory.getLogger(PartialBucketCanary.class);
  private static final String THREE_MINUTE = "3m";
  private static final String ONE_MINUTE = "1m";
  private static final Duration BUCKET = Duration.ofMinutes(3);
  // Deferral cap: a skew above this share of its own bucket cannot be a sub-second boundary
  // straddle (widest measured half: 11.9%), so it is reported at once instead of being held.
  private static final long STRADDLE_MAX_PERCENT = 25;
  // Slack past the partner bucket's own completion (heldBucketStart + 2×BUCKET) before a held half
  // is released as unpaired. One sweep period: the partner is observable on the first sweep after
  // that instant, and check() runs BEFORE the release in the same sweep, so this only has to cover
  // a single late 3m REST refresh.
  private static final Duration PAIR_GRACE = Duration.ofSeconds(60);
  // Hard bound on any single held-half store call. Generous for a loopback Redis, small enough that
  // even three stalled calls cannot consume a 60 s sweep interval.
  private static final Duration REDIS_TIMEOUT = Duration.ofSeconds(2);

  /**
   * A contract's lot size from the instrument master — the only source of truth. 65 is merely
   * today's NIFTY lot; SENSEX differs and exchange revisions move both, so nothing here hardcodes
   * one.
   *
   * <p><b>Implementations MUST return immediately and MUST NOT block.</b> This is called from the
   * sweep, which runs on the single-threaded {@code monitorTaskScheduler} whose contract
   * ({@code MonitorSchedulingConfig}) is that no detector makes a blocking external call there — one
   * slow lookup would delay every sibling watchdog on that pool. {@code null} therefore means "not
   * known YET", not "the master said no", and it keeps the straddle rule CLOSED either way: a pair
   * we cannot prove is lot-quantised still WARNs.
   */
  @FunctionalInterface
  public interface LotSizes {
    /** The already-known lot size, or {@code null} when it is not resolved yet. Never blocks. */
    Long cachedLotSize(String exchange, String tradingsymbol);
  }

  /** One classified, non-benign bucket held for one bucket awaiting its straddle partner. */
  public record Held(Instant bucketStart, long actual, long expected, long skew) {}

  /**
   * Durable home for the at-most-one deferred half per series.
   *
   * <p>A hold is an OBSERVATION that has not been reported yet, and a restart inside the deferral
   * window would silence it. Re-deriving it afterwards is impossible, not merely awkward: the 3m
   * side is {@code rangeRolledFromOneMinute} over the very same {@code candles} 1m rows the 1m
   * series is re-warmed from ({@code CandleQueryService:103-105}), so once the process dies the
   * diverging side — live, never-revised tick-agg held only in memory — is gone and any
   * re-classification of that bucket compares DB against DB and is benign by construction. Carrying
   * the observation is therefore the ONLY mechanism that preserves it; no depth of look-back can.
   *
   * <p>Hence the invariant this type exists to enforce: <b>a half is deferred only if it was stored
   * durably first</b>. When {@link #save} reports failure — Redis down, or no store configured at
   * all — the canary does not defer; it WARNs immediately, exactly as it did before G9. Either the
   * hold survives a restart or it was never a hold.
   */
  public interface HeldHalves {
    /** Persists the half; {@code false} means it is NOT durable, so the caller must WARN now. */
    boolean save(SeriesKey key, Held half);

    /**
     * EVERY carried half, keyed by {@link SeriesKey#canonical()} — the durable index, read
     * independently of which series happen to be warmed right now.
     *
     * <p>Two distinctions this return type has to keep. An <b>empty</b> {@code Optional} means the
     * store could not be read: UNKNOWN, never "absent", because collapsing the two would let one
     * Redis blip look like "there was nothing to restore" and swallow an unreported observation. An
     * <b>empty map</b> means the store was read and genuinely carries nothing.
     */
    Optional<Map<String, Held>> carried();

    /** Drops a carried half by canonical id (it has been paired, released, or reported). */
    void clear(String seriesId);
  }

  private final LiveSeriesStore store;
  private final Clock clock;
  private final Counter mismatches;
  private final Counter straddles;
  private final long volumeTolerance;
  private final double volumeTolerancePct;
  private final LotSizes lotSizes;
  private final HeldHalves heldHalves;
  // The last completed 3m bucket already CLASSIFIED (benign / held / warned) per series. Every
  // bucket is classified exactly once, which is also what keeps a WARN to once per (series, bucket).
  private final Map<SeriesKey, Instant> classified = new ConcurrentHashMap<>();
  // At most one deferred half per series, awaiting corroboration from the next bucket.
  private final Map<SeriesKey, Held> pending = new ConcurrentHashMap<>();
  // Series whose carried-over half has already been restored this process (once each, at cold start).
  private final Set<SeriesKey> restored = ConcurrentHashMap.newKeySet();

  /**
   * Wires the shared series store, the clock, the meters, the (tunable) absolute-arm basis — a
   * fixed floor plus a percent-of-expected-sum scale (0 = the pre-G9 fixed-absolute gate) — and the
   * instrument-master lot-size lookup the straddle rule needs.
   */
  @Autowired
  public PartialBucketCanary(
      LiveSeriesStore store,
      Clock clock,
      MeterRegistry meterRegistry,
      RestClient.Builder restClientBuilder,
      ObjectProvider<StringRedisTemplate> redis,
      @Value("${artha.marketdata.base-url}") String marketDataBaseUrl,
      @Value("${artha.signals.partial-bucket-canary.volume-tolerance:650}") long volumeTolerance,
      @Value("${artha.signals.partial-bucket-canary.volume-tolerance-pct:0.0}")
          double volumeTolerancePct) {
    this(
        store,
        clock,
        meterRegistry,
        volumeTolerance,
        volumeTolerancePct,
        restLotSizes(restClientBuilder.baseUrl(marketDataBaseUrl).build(), LOT_PREFETCH),
        // No Redis bean (e.g. a slice test) ⇒ no durable store ⇒ the canary simply never defers.
        redis.getIfAvailable() == null ? NO_DURABLE_STORE : redisHeldHalves(redis.getObject(), HELD_IO));
  }

  /** Test seam: the same canary against explicit lot-size and held-half sources, with no I/O. */
  public PartialBucketCanary(
      LiveSeriesStore store,
      Clock clock,
      MeterRegistry meterRegistry,
      long volumeTolerance,
      double volumeTolerancePct,
      LotSizes lotSizes,
      HeldHalves heldHalves) {
    this.store = store;
    this.clock = clock;
    this.mismatches = meterRegistry.counter("ay_signal_partial_bucket_mismatch_total");
    this.straddles = meterRegistry.counter("ay_signal_partial_bucket_straddle_total");
    this.volumeTolerance = volumeTolerance;
    this.volumeTolerancePct = volumeTolerancePct;
    this.lotSizes = lotSizes;
    this.heldHalves = heldHalves;
  }

  /** Sweeps every warmed 3m series, comparing its last completed bar to the 1m sum. */
  @Scheduled(fixedDelay = 60_000, initialDelay = 90_000, scheduler = "partialBucketTaskScheduler")
  public void sweep() {
    Instant now = clock.instant();
    Set<SeriesKey> threeMinKeys = new LinkedHashSet<>();
    for (SeriesKey key : store.keys()) {
      if (THREE_MINUTE.equals(key.interval())) {
        threeMinKeys.add(key);
      }
    }
    try {
      recoverCarriedHalves(threeMinKeys, now);
    } catch (RuntimeException e) {
      log.warn("partial-bucket canary could not recover carried halves: {}", e.toString());
    }
    for (SeriesKey key : threeMinKeys) {
      try {
        // Order is load-bearing: classify first so a partner arriving on THIS sweep corroborates
        // the held half before the deadline check would release it as unpaired.
        check(key, now);
        releaseIfUnpaired(key, now);
      } catch (RuntimeException e) {
        // Contained PER KEY: this canary is the first cross-thread reader of EngineSeries (an
        // unsynchronized ArrayList mutated on the eval thread), so a torn read on one series must
        // skip only that series, never abort the whole sweep cycle.
        log.warn("partial-bucket canary check failed for {}: {}", key.canonical(), e.toString());
      }
    }
  }

  /**
   * Reads the durable index INDEPENDENTLY of which series are warmed right now, and resolves every
   * entry it finds.
   *
   * <p>Indexing recovery by the live series set is the trap this avoids: "is this alarm still
   * reachable" and "is this contract still trading" are unrelated questions, and a restart is
   * precisely when the live set changes. A hold stranded on a rolled-over contract would otherwise
   * never be loaded, never resolved and never reported — defeating the persistence entirely.
   *
   * <p>An entry whose series IS warmed is handed to the ordinary machinery (it can still pair).
   * An entry with no live series is reported here once its own release deadline has passed — the
   * deadline matters because on a cold boot the engine warms series lazily, so "not in the live set
   * yet" must not be mistaken for "gone".
   */
  private void recoverCarriedHalves(Set<SeriesKey> threeMinKeys, Instant now) {
    Optional<Map<String, Held>> read = heldHalves.carried();
    if (read.isEmpty()) {
      return; // UNKNOWN, not empty — retry next sweep; deferral stays refused meanwhile
    }
    Map<String, Held> carried = read.get();
    Set<String> liveIds = new HashSet<>();
    for (SeriesKey key : threeMinKeys) {
      String id = key.canonical();
      liveIds.add(id);
      if (restored.add(key)) {
        Held half = carried.get(id);
        if (half != null) {
          pending.put(key, half);
        }
      }
    }
    for (Map.Entry<String, Held> entry : carried.entrySet()) {
      if (liveIds.contains(entry.getKey()) || now.isBefore(releaseDeadline(entry.getValue()))) {
        continue;
      }
      // Report FIRST, then delete: a crash in between may duplicate a WARN, never erase one.
      warn(entry.getKey(), entry.getValue());
      heldHalves.clear(entry.getKey());
    }
  }

  private static Instant releaseDeadline(Held half) {
    return half.bucketStart().plus(BUCKET.multipliedBy(2)).plus(PAIR_GRACE);
  }

  private void check(SeriesKey threeMinKey, Instant now) {
    EngineSeries threeMin = store.series(threeMinKey);
    if (threeMin == null || threeMin.size() == 0) {
      return;
    }
    classifyBucket(threeMinKey, threeMin, threeMin.size() - 1, now);
  }

  private void classifyBucket(
      SeriesKey threeMinKey, EngineSeries threeMin, int bucketIndex, Instant now) {
    EngineCandle last = threeMin.candle(bucketIndex);
    Instant bucketStart = last.bucketStart().toInstant();
    if (bucketStart.plus(BUCKET).isAfter(now)) {
      return; // the newest 3m bar is still forming — nothing complete to check yet
    }
    if (bucketStart.equals(classified.get(threeMinKey))) {
      return; // already classified this exact (series, bucket)
    }
    EngineSeries oneMin =
        store.series(new SeriesKey(threeMinKey.exchange(), threeMinKey.tradingsymbol(), ONE_MINUTE));
    if (oneMin == null) {
      return;
    }
    long expected = 0;
    for (int minute = 0; minute < 3; minute++) {
      Instant minuteStart = bucketStart.plus(Duration.ofMinutes(minute));
      int index = oneMin.indexAtOrBefore(minuteStart);
      if (index < 0 || !oneMin.candle(index).bucketStart().toInstant().equals(minuteStart)) {
        // 1m coverage incomplete for this bucket — skip silently (not a freeze), and deliberately
        // do NOT mark it classified: coverage can still fill in. Any half held on the PREVIOUS
        // bucket is released by its own wall-clock deadline, never stranded here.
        return;
      }
      expected += oneMin.candle(index).volume();
    }
    long actual = last.volume();
    long skew = actual - expected;
    long diff = Math.abs(skew);
    // benign needs BOTH gates: ≤ the absolute arm (a fixed floor, optionally scaled by a percent
    // of the expected sum — DORMANT at the shipped pct=0, where this is exactly the pre-G9 fixed
    // gate; the class javadoc explains why pct>0 manufactures false-unpaired events) AND ≤ 10% of
    // the expected sum. The relative gate keeps a frozen partial on a thin bucket firing (its
    // absolute shortfall is small but its relative one is ~2/3); a zero-volume expected sum
    // tolerates only an exact match.
    long absoluteTolerance =
        Math.max(volumeTolerance, (long) (expected * volumeTolerancePct / 100.0));
    boolean benign = diff <= absoluteTolerance && diff * 10L <= expected;
    // WRITE-AHEAD ORDERING, everywhere below: acknowledge the observation (counter + log) and only
    // THEN delete the durable record. A crash between the two may duplicate a report; the reverse
    // order would erase one, which is exactly the loss this persistence exists to prevent.
    Held half = pending.remove(threeMinKey);
    if (half != null) {
      if (!benign && corroborates(threeMinKey, half, bucketStart, expected, skew)) {
        straddles.increment();
        log.info(
            "partial-bucket straddle: {} buckets {} and {} carry an equal-and-opposite lot-multiple "
                + "skew ({} / {}, residue {}) — the documented boundary straddle (README §3.17). "
                + "The PAIR is suppressed; neither half is ever reported alone",
            threeMinKey.canonical(),
            half.bucketStart(),
            bucketStart,
            half.skew(),
            skew,
            Math.abs(half.skew() + skew));
        heldHalves.clear(threeMinKey.canonical());
        classified.put(threeMinKey, bucketStart);
        return; // both halves explained — this bucket is consumed and cannot pair again
      }
      warn(threeMinKey.canonical(), half); // no corroboration: the held half is unexplained
      heldHalves.clear(threeMinKey.canonical());
    }
    if (benign) {
      classified.put(threeMinKey, bucketStart);
      return;
    }
    Held candidate = new Held(bucketStart, actual, expected, skew);
    // Defer ONLY a plausible straddle half — lot-quantised (§3.17) and a small enough share of its
    // own bucket to be a sub-second boundary effect — AND only when the hold is durable, so a
    // restart can never silence an observation we chose not to report. Anything else WARNs now,
    // exactly as the pre-pair canary did.
    if (isLotMultiple(threeMinKey, diff)
        && straddleShaped(expected, diff)
        && restored.contains(threeMinKey) // never overwrite a carried half we could not read
        && heldHalves.save(threeMinKey, candidate)) {
      pending.put(threeMinKey, candidate);
      classified.put(threeMinKey, bucketStart);
      return;
    }
    warn(threeMinKey.canonical(), candidate);
    // Marked classified only AFTER the bucket has been acted on. Marking it earlier meant a throw
    // in between (the per-key catch in sweep swallows it) retired the bucket unexamined — the same
    // lose-information ordering as the durable delete, found by auditing for a third instance.
    classified.put(threeMinKey, bucketStart);
  }

  private boolean corroborates(
      SeriesKey key, Held half, Instant bucketStart, long expected, long skew) {
    if (!bucketStart.equals(half.bucketStart().plus(BUCKET))) {
      return false; // a straddle spills into the IMMEDIATELY following bucket and no further
    }
    if (Long.signum(skew) != -Long.signum(half.skew())) {
      return false; // same direction ⇒ a sustained drift, not volume landing one bucket late
    }
    // BOTH halves must be straddle-shaped against their OWN bucket. Capping only the half being
    // HELD was a hole (cross-vendor review, 2026-08-01): an incoming partner that is itself a
    // frozen first-minute bar — 2/3 of ITS bucket missing — could be cancelled out by an earlier
    // small opposite skew and suppressed. The residue bound limits the NET unexplained volume; it
    // says nothing about the GROSS corruption inside either bucket, and gross corruption is
    // precisely what this canary exists to detect.
    if (!straddleShaped(half.expected(), Math.abs(half.skew()))
        || !straddleShaped(expected, Math.abs(skew))) {
      return false;
    }
    if (!isLotMultiple(key, Math.abs(skew)) || !isLotMultiple(key, Math.abs(half.skew()))) {
      return false;
    }
    long residue = Math.abs(half.skew() + skew);
    // The part of the pair that does NOT cancel must itself be noise: it clears the same absolute
    // arm the canary already treats as benign per event AND stays under 10% of the larger half, so
    // two unrelated events of merely similar size can never masquerade as a pair.
    return residue <= volumeTolerance
        && residue * 10L <= Math.max(Math.abs(skew), Math.abs(half.skew()));
  }

  /** True when {@code magnitude} is at most {@link #STRADDLE_MAX_PERCENT} of its own bucket. */
  private static boolean straddleShaped(long expected, long magnitude) {
    return magnitude * 100L <= expected * STRADDLE_MAX_PERCENT;
  }

  private boolean isLotMultiple(SeriesKey key, long magnitude) {
    Long lot = lotSizes.cachedLotSize(key.exchange(), key.tradingsymbol());
    // lot 1, or not resolved YET, makes the test vacuous — treated as "cannot prove", fail CLOSED.
    return lot != null && lot > 1 && magnitude % lot == 0;
  }

  private void releaseIfUnpaired(SeriesKey key, Instant now) {
    Held half = pending.get(key);
    if (half == null) {
      return;
    }
    if (now.isBefore(releaseDeadline(half))) {
      return; // the partner bucket has not finished (plus a sweep of slack) — keep waiting
    }
    pending.remove(key);
    warn(key.canonical(), half); // report FIRST …
    heldHalves.clear(key.canonical()); // … then drop the durable record
  }

  private void warn(String seriesId, Held half) {
    mismatches.increment();
    log.warn(
        "partial-bucket canary: {} last completed 3m bar volume {} != Σ(3×1m) {} for bucket {} "
            + "(shortfall {}) — UNPAIRED: no equal-and-opposite lot-multiple partner on the next "
            + "bucket, and a frozen first-minute partial (audit B1 / FID P0-1) looks exactly like this",
        seriesId,
        half.actual(),
        half.expected(),
        half.bucketStart(),
        half.expected() - half.actual());
  }

  private static ThreadFactory daemon(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true); // never keeps the JVM alive; no shutdown hook needed
      return thread;
    };
  }

  // Lot-size prefetch: fire-and-forget, and naturally bounded because `inFlight` allows at most one
  // outstanding request per contract.
  private static final ExecutorService LOT_PREFETCH =
      Executors.newSingleThreadExecutor(daemon("partial-bucket-lot-prefetch"));

  // Held-half store I/O. Its own thread (a stalled Redis must not queue behind a slow HTTP prefetch
  // or vice versa) and a BOUNDED queue: under a sustained Redis hang the waiter gives up after
  // REDIS_TIMEOUT, and without a bound the abandoned tasks would pile up and then all execute in a
  // burst on recovery — a stale `save` landing after its `clear` would resurrect an observation
  // that has already been reported. Rejection is just another failure, i.e. "do not defer".
  private static final ExecutorService HELD_IO =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(8),
          daemon("partial-bucket-held-io"),
          new ThreadPoolExecutor.AbortPolicy());

  private static LotSizes restLotSizes(RestClient restClient, ExecutorService prefetch) {
    // NON-BLOCKING by construction: the sweep reads the cache and never waits. A miss returns null
    // (⇒ the caller fails CLOSED and WARNs) and fires the lookup on a dedicated daemon thread, so
    // the sweep thread is never held by an HTTP call — `spring.http.client.read-timeout` is 30s
    // here, and a 30s stall on a scheduler thread is exactly what the detector fence forbids.
    // Consequence, deliberate: the FIRST non-benign bucket of a contract can never be paired,
    // because its lot size is not known yet. That costs at most one extra WARN per contract per
    // process and is the safe direction.
    // Same endpoint ShadowCostModel already reads (Modulith: signals cannot import the paper slice).
    Map<String, Long> lots = new ConcurrentHashMap<>();
    Set<String> inFlight = ConcurrentHashMap.newKeySet();
    return (exchange, tradingsymbol) -> {
      String cacheKey = exchange + ":" + tradingsymbol;
      Long known = lots.get(cacheKey);
      if (known != null) {
        return known;
      }
      // One outstanding request per contract; a failure clears the marker so it retries next sweep
      // (a permanently dead endpoint costs one request per contract per minute, never a stall).
      if (inFlight.add(cacheKey)) {
        try {
          prefetch.execute(
              () -> {
              try {
                InstrumentDto dto =
                    restClient
                        .get()
                        .uri("/api/v1/instruments/{exchange}/{tradingsymbol}", exchange, tradingsymbol)
                        .retrieve()
                        .body(InstrumentDto.class);
                if (dto != null && dto.lotSize() != null && dto.lotSize() > 0) {
                  lots.put(cacheKey, dto.lotSize());
                }
              } catch (RuntimeException e) {
                log.warn(
                    "partial-bucket canary lot-size lookup failed for {} — straddle pairing stays "
                        + "closed (events WARN unpaired): {}",
                    cacheKey,
                    e.getMessage());
              } finally {
                inFlight.remove(cacheKey);
              }
            });
        } catch (RejectedExecutionException rejected) {
          inFlight.remove(cacheKey); // let the next sweep try again
        }
      }
      return null;
    };
  }

  /** The one instrument-master field the straddle rule needs. */
  private record InstrumentDto(Long lotSize) {}

  /** No durable store — {@code save} always refuses, so the canary never defers. */
  private static final HeldHalves NO_DURABLE_STORE =
      new HeldHalves() {
        @Override
        public boolean save(SeriesKey key, Held half) {
          return false;
        }

        @Override
        public Optional<Map<String, Held>> carried() {
          return Optional.of(Map.of()); // read SUCCEEDED; there is genuinely nothing to carry
        }

        @Override
        public void clear(String seriesId) {
          // nothing was ever stored
        }
      };

  private static HeldHalves redisHeldHalves(StringRedisTemplate redis, ExecutorService io) {
    // Redis, not Postgres: no migration, AOF + a named volume, and it outlives a strategy-signal
    // container restart — the case that loses a hold.
    //
    // ONE HASH, not a key per series. That makes the durable index a single small read (`HGETALL`
    // over at most a handful of fields), so recovery can enumerate holds INDEPENDENTLY of which
    // series happen to be warmed — without that, a hold stranded on a rolled-over contract is never
    // loaded, never resolved and never reported. It also keeps save/clear to one atomic field op.
    //
    // ⚠️ NO TTL, deliberately. The deployed instance runs `maxmemory-policy volatile-lru`
    // (deploy/docker-compose.yml), under which ONLY keys carrying an expiry are eviction
    // candidates. An unreported observation must not be one: giving it a TTL would let memory
    // pressure — or simply the clock — silently discard a mismatch nobody has seen yet. Fields are
    // deleted the moment the observation is REPORTED, so lifetime is bounded by resolution.
    //
    // Every call is time-bounded on `io` and never runs on the sweep thread for longer than that
    // bound: no Redis command timeout is configured service-wide, and setting one would change
    // behaviour for every other Redis user in this service.
    return new HeldHalves() {
      private static final String HASH = "ay:partial-bucket-canary:held";

      @Override
      public boolean save(SeriesKey key, Held half) {
        String value =
            half.bucketStart().getEpochSecond()
                + "|" + half.actual() + "|" + half.expected() + "|" + half.skew();
        // Not durable ⇒ the caller WARNs now instead of deferring. Never silently swallowed.
        return bounded(
            key.canonical(),
            "persist the held half",
            () -> {
              redis.opsForHash().put(HASH, key.canonical(), value);
              return Boolean.TRUE;
            },
            Boolean.FALSE);
      }

      @Override
      public Optional<Map<String, Held>> carried() {
        return bounded(
            HASH,
            "read the durable held-half index",
            () -> {
              Map<Object, Object> raw = redis.opsForHash().entries(HASH);
              Map<String, Held> halves = new LinkedHashMap<>();
              for (Map.Entry<Object, Object> entry : raw.entrySet()) {
                Held half = parse(String.valueOf(entry.getValue()));
                if (half != null) {
                  halves.put(String.valueOf(entry.getKey()), half);
                }
              }
              return Optional.of(halves);
            },
            Optional.empty());
      }

      @Override
      public void clear(String seriesId) {
        bounded(
            seriesId,
            "clear the held half",
            () -> {
              redis.opsForHash().delete(HASH, seriesId);
              return Boolean.TRUE;
            },
            Boolean.FALSE);
      }

      private Held parse(String raw) {
        String[] parts = raw.split("\\|");
        if (parts.length != 4) {
          return null; // an unreadable VALUE, which is not an unreadable STORE
        }
        try {
          return new Held(
              Instant.ofEpochSecond(Long.parseLong(parts[0])),
              Long.parseLong(parts[1]),
              Long.parseLong(parts[2]),
              Long.parseLong(parts[3]));
        } catch (NumberFormatException e) {
          return null;
        }
      }

      /** Runs one Redis call off the sweep thread with a hard bound; never throws. */
      private <T> T bounded(String seriesId, String what, Callable<T> call, T onFailure) {
        Future<T> future;
        try {
          future = io.submit(call);
        } catch (RejectedExecutionException rejected) {
          // The store thread is backed up (a stalled Redis) — that IS a failure, report it as one.
          log.warn(
              "partial-bucket canary could not {} for {} — store I/O is saturated: {}",
              what, seriesId, rejected.toString());
          return onFailure;
        }
        try {
          return future.get(REDIS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          future.cancel(true);
          Thread.currentThread().interrupt();
          return onFailure;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
          // Cancel so an abandoned write cannot land later and resurrect a resolved observation.
          future.cancel(true);
          log.warn(
              "partial-bucket canary could not {} for {} — treating it as UNRESOLVED (the canary "
                  + "reports rather than defers until the store answers): {}",
              what,
              seriesId,
              e.toString());
          return onFailure;
        }
      }
    };
  }
}
