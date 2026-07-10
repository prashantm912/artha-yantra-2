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
import java.util.UUID;
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
 * against the cache; uniform-ratio divergence on ≥ 2 anchors ⇒ DETECTED → purge → full
 * rate-limited re-backfill → cagg refresh (rewritten rows carry fresh {@code fetched_at}, so the
 * Stage-D dataHash flags pre-event runs not-like-for-like) → RESOLVED. Single-anchor noise only
 * counts {@code ay_corporate_action_anchor_noise_total}. Kite-diff is the SOLE detection input.
 *
 * <p>Kill switch (A14, 2026-07-10): {@code artha.corporate-actions.enabled=false} un-registers this
 * bean entirely (default-armed via {@code matchIfMissing}) — the remediation OOM-crashed live
 * Postgres 3× and the operator needs a one-line .env disarm without a code change.
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
  private final Counter anchorNoise;
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
      // defaults exceed Kite's serving depth (~2015 for 1m) so the FULL purge is always
      // matched by a full re-backfill — anything deeper cannot be re-fetched anyway
      @Value("${artha.corporate-actions.rebackfill-days-1m:4400}") int rebackfillDays1m,
      @Value("${artha.corporate-actions.rebackfill-days-1d:7300}") int rebackfillDays1d,
      @Value("${artha.corporate-actions.symbols:}") List<String> symbolOverride,
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
    this.anchorNoise = meterRegistry.counter("ay_corporate_action_anchor_noise_total");
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
    // A14 resume: a symbol whose MOST-RECENT event is BASE_REBUILT crashed AFTER its base was
    // re-fetched (committed) but BEFORE the chunked cagg refresh finished — resume the refresh only,
    // never re-purge ~12 years. Detection would NOT re-fire this (post-rebuild the cache == Kite ⇒
    // no divergence, so no fresh DETECTED row + no double-remediation), so this checkpoint scan is
    // the SOLE resume trigger. Reuses the existing event row.
    java.util.Optional<CorporateActionRepository.EventRow> latest =
        events.latestEvent(equity.exchange(), equity.tradingsymbol());
    if (latest.isPresent() && "BASE_REBUILT".equals(latest.get().status())) {
      submitRefreshOnly(latest.get().id(), equity, today);
      return java.util.Optional.empty(); // a resume, not a fresh detection
    }
    // Skip BHAVCOPY-only equities (Phase C): the bulk universe is split/bonus-adjusted on read by
    // EquitySplitBonusAdjuster, not by this purge+Kite-refetch path. Without this gate, projecting
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
    executor.submit(
        () -> {
          try {
            events.updateStatus(id, "REBACKFILL_RUNNING");
            // purge (compressed-safe WINDOWED delete) → full re-backfill THROUGH the rate-limited
            // gateway (prefetch = ensureCoverage = limiter path); rewritten rows carry fresh
            // fetched_at (the dataHash bump). The 1m prefetch DEFERS its cagg refresh
            // (refreshAggregates=false) so we can checkpoint BASE_REBUILT once the base commits but
            // BEFORE the (chunked) refresh — a hard crash mid-refresh then RESUMES on the next sweep
            // instead of re-purging ~12 years for nothing (A14, 2026-07-10 3× live-Postgres OOM).
            candles.purgeSymbol(equity.exchange(), equity.tradingsymbol());
            OffsetDateTime now = OffsetDateTime.now(clock);
            queryService.prefetch(
                equity.exchange(), equity.tradingsymbol(), "1d",
                today.minusDays(rebackfillDays1d).atStartOfDay().atOffset(Ist.OFFSET), now);
            queryService.prefetch(
                equity.exchange(), equity.tradingsymbol(), "1m",
                today.minusDays(rebackfillDays1m).atStartOfDay().atOffset(Ist.OFFSET), now, false);
            events.updateStatus(id, "BASE_REBUILT");
            refreshRebuiltAggregates(today, now);
            events.updateStatus(id, "RESOLVED");
            ntfy.send(
                "Corporate action rebuilt",
                "default",
                equity.exchange() + ":" + equity.tradingsymbol() + " cache rebuilt");
          } catch (Exception e) {
            log.error("corporate-action rebuild failed for {}", equity.tradingsymbol(), e);
            events.updateStatus(id, "FAILED");
            ntfy.send(
                "Corporate action rebuild FAILED",
                "urgent",
                equity.exchange() + ":" + equity.tradingsymbol() + " — " + e.getMessage());
          }
        });
  }

  /**
   * Resume path (A14): the base was already re-fetched (committed) in a prior remediation that
   * crashed mid-refresh — redo the CHUNKED cagg refresh ONLY, then RESOLVED. Skips purge + prefetch
   * entirely. Status is LEFT at BASE_REBUILT during the refresh (not downgraded to
   * REBACKFILL_RUNNING) so a hard crash here re-resumes on the next sweep instead of stranding a
   * REBACKFILL_RUNNING row; a soft (catchable) failure is a real error → FAILED + urgent alert.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  private void submitRefreshOnly(UUID id, Instrument equity, LocalDate today) {
    executor.submit(
        () -> {
          try {
            refreshRebuiltAggregates(today, OffsetDateTime.now(clock));
            events.updateStatus(id, "RESOLVED");
            ntfy.send(
                "Corporate action rebuilt",
                "default",
                equity.exchange() + ":" + equity.tradingsymbol() + " cagg refresh resumed");
          } catch (Exception e) {
            log.error("corporate-action refresh resume failed for {}", equity.tradingsymbol(), e);
            events.updateStatus(id, "FAILED");
            ntfy.send(
                "Corporate action rebuild FAILED",
                "urgent",
                equity.exchange() + ":" + equity.tradingsymbol() + " — resume: " + e.getMessage());
          }
        });
  }

  /** The chunked cagg refresh over the rebuilt 1m window (CandleRepository slices it ≤92-day). */
  private void refreshRebuiltAggregates(LocalDate today, OffsetDateTime now) {
    candles.refreshDerivedAggregates(
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
