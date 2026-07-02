package in.arthayantra.marketdata.options;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketcalendar.MarketCalendar;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The chain snapshotter (Phase 15 / B-12), boundary-aligned since T2 (audit 2026-07-02): computes
 * the chain and persists EVERY row — raw quote fields unconditionally, IV/Greeks as the solver
 * allows (null + reason rows are first-class).
 * Publishes the fresh chain to the {@code options.chain.{underlying}.{expiry}} Redis key (TTL
 * 60 s) and the {@code options.chain} channel. Calendar-gated; manual trigger via the 202
 * endpoint.
 */
@Service
public class OptionsSnapshotService {

  private static final Logger log = LoggerFactory.getLogger(OptionsSnapshotService.class);

  private final OptionsChainService chainService;
  private final OptionsSnapshotRepository repository;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final List<String> snapshotUnderlyings;
  private final int expiryHorizonDays;
  private final Timer snapshotTimer;
  private final Counter snapshotRows;
  // previous-pass OI per leg — oi_change = oi − previous snapshot's oi (null on the first pass)
  private final Map<String, Long> previousOi = new java.util.concurrent.ConcurrentHashMap<>();
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "options-snapshot");
            t.setDaemon(true);
            return t;
          });

  /** Wires the snapshotter. */
  public OptionsSnapshotService(
      OptionsChainService chainService,
      OptionsSnapshotRepository repository,
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      MarketCalendar calendar,
      Clock clock,
      @Value("${artha.options.snapshot-underlyings:NIFTY 50}") List<String> snapshotUnderlyings,
      @Value("${artha.options.snapshot-expiry-horizon-days:90}") int expiryHorizonDays,
      MeterRegistry meterRegistry) {
    this.chainService = chainService;
    this.repository = repository;
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.calendar = calendar;
    this.clock = clock;
    this.snapshotUnderlyings = snapshotUnderlyings;
    this.expiryHorizonDays = expiryHorizonDays;
    this.snapshotTimer = meterRegistry.timer("ay_options_snapshot_duration_seconds");
    this.snapshotRows = meterRegistry.counter("ay_options_snapshot_rows_total");
  }

  /**
   * The capture cadence the cron below fires on. The gate shifts the market-hours check back by
   * this much: a fire at boundary B records the state accumulated over {@code (B - cadence, B]},
   * so it runs iff THAT window was in-session — the 09:15:00 fire is skipped (pre-open window) and
   * the 15:30:00 fire still runs (the EOD capture). MUST match the cron cadence.
   */
  private static final Duration CAPTURE_CADENCE = Duration.ofMinutes(2);

  /**
   * Boundary-aligned capture (audit 2026-07-02 §9.1, T2): cron on the IST minute grid, replacing
   * the boot-phase fixedDelay whose stamps drifted mid-bucket (the pass overruns the delay, so the
   * phase crept ~8 s per pass). 2-min cadence keeps today's ~50% Kite /quote duty (a full 6-index
   * pass is ~70 batched calls ≈ 70 s at the 1/s limit). Snapshots the full chain of EVERY expiry
   * within the horizon per underlying (B-12; the Phase 16 schedule registry references this).
   */
  @Scheduled(cron = "${artha.options.snapshot-cron:0 */2 * * * *}", zone = "Asia/Kolkata")
  public void scheduledSnapshot() {
    if (!isOpenSafe(clock.instant().minus(CAPTURE_CADENCE))) {
      return;
    }
    for (String underlying : snapshotUnderlyings) {
      for (LocalDate expiry : chainService.expiriesWithin(underlying, expiryHorizonDays)) {
        try {
          snapshotNow(underlying, expiry);
        } catch (Exception e) {
          log.warn(
              "scheduled options snapshot failed for {} {}: {}", underlying, expiry, e.getMessage());
        }
      }
    }
  }

  /** 30 s live chain broadcast, market hours (B-12) — publish only, no persistence. */
  @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
  public void scheduledBroadcast() {
    if (!isOpenSafe(clock.instant())) {
      return;
    }
    for (String underlying : snapshotUnderlyings) {
      try {
        publish(chainService.chain(underlying, null));
      } catch (Exception e) {
        log.warn("chain broadcast failed for {}: {}", underlying, e.getMessage());
      }
    }
  }

  /** 202-style manual trigger. */
  @SuppressWarnings("FutureReturnValueIgnored")
  public Map<String, String> triggerAsync(String underlying, LocalDate expiry) {
    String jobId = UUID.randomUUID().toString();
    executor.submit(
        () -> {
          try {
            snapshotNow(underlying, expiry);
            log.info("options snapshot {} done for {}", jobId, underlying);
          } catch (Exception e) {
            log.error("options snapshot {} failed for {}", jobId, underlying, e);
          }
        });
    return Map.of("jobId", jobId);
  }

  /** One synchronous snapshot pass; returns the persisted chain. */
  public OptionsChainService.Chain snapshotNow(String underlying, LocalDate expiry) {
    long started = System.nanoTime();
    // Stamp at ENTRY, floored to the minute grid (T2): the cron fires on boundaries, so the row's
    // ts IS the boundary it represents; the readers' end-of-window bucketing depends on it. Chains
    // later in a pass that crosses a minute stamp their own entry minute (still on-grid).
    OffsetDateTime ts = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
    OptionsChainService.Chain chain = chainService.chain(underlying, expiry);
    List<OptionsSnapshotRepository.SnapshotRow> rows = new ArrayList<>();
    for (OptionsChainService.StrikeRow strikeRow : chain.rows()) {
      addRow(rows, ts, chain, strikeRow.strike(), "CE", strikeRow.ce());
      addRow(rows, ts, chain, strikeRow.strike(), "PE", strikeRow.pe());
    }
    repository.insertAll(rows);
    snapshotRows.increment(rows.size());
    snapshotTimer.record(Duration.ofNanos(System.nanoTime() - started));
    publish(chain);
    return chain;
  }

  private void addRow(
      List<OptionsSnapshotRepository.SnapshotRow> rows,
      OffsetDateTime ts,
      OptionsChainService.Chain chain,
      java.math.BigDecimal strike,
      String optionType,
      OptionsChainService.Leg leg) {
    if (leg == null) {
      return;
    }
    String oiKey = chain.underlying() + "|" + chain.expiry() + "|" + strike + "|" + optionType;
    // keep the previous-pass map current on EVERY source so a later flag flip back to Kite is sane
    Long prev = leg.oi() == null ? null : previousOi.put(oiKey, leg.oi());
    Long oiChange;
    if (leg.prevOi() != null && leg.oi() != null) {
      // Wave U1 (Upstox source): prev_oi is the venue's own previous OI — use it directly
      oiChange = leg.oi() - leg.prevOi();
    } else {
      // default (Kite source): oi_change = oi − the previous snapshot's oi (null on the first pass)
      oiChange = leg.oi() == null || prev == null ? null : leg.oi() - prev;
    }
    rows.add(
        new OptionsSnapshotRepository.SnapshotRow(
            ts, chain.underlying(), chain.expiry(), strike, optionType, leg.tradingsymbol(),
            leg.ltp(), leg.bid(), leg.ask(), leg.volume(), leg.oi(), oiChange, chain.spot(),
            leg.iv(), leg.delta(), leg.gamma(), leg.theta(), leg.vega(), leg.rho(),
            leg.ivReason(), leg.priceSource(), chain.forward(), chain.riskFreeRate()));
  }

  private void publish(OptionsChainService.Chain chain) {
    try {
      String json = objectMapper.writeValueAsString(chain);
      String key = "options.chain." + chain.underlying() + "." + chain.expiry();
      redis.opsForValue().set(key, json, Duration.ofSeconds(60));
      redis.convertAndSend("options.chain", json);
    } catch (Exception e) {
      log.warn("options chain publish failed: {}", e.getMessage());
    }
  }

  private boolean isOpenSafe(java.time.Instant instant) {
    try {
      return calendar.isOpen(instant);
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }
}
