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
 * The 5-min snapshotter (Phase 15 / B-12): computes the chain and persists EVERY row — raw quote
 * fields unconditionally, IV/Greeks as the solver allows (null + reason rows are first-class).
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
      MeterRegistry meterRegistry) {
    this.chainService = chainService;
    this.repository = repository;
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.calendar = calendar;
    this.clock = clock;
    this.snapshotUnderlyings = snapshotUnderlyings;
    this.snapshotTimer = meterRegistry.timer("ay_options_snapshot_duration_seconds");
    this.snapshotRows = meterRegistry.counter("ay_options_snapshot_rows_total");
  }

  /** 5-min cadence, market hours only (B-12; the Phase 16 schedule registry references this). */
  @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
  public void scheduledSnapshot() {
    if (!isOpenSafe()) {
      return;
    }
    for (String underlying : snapshotUnderlyings) {
      try {
        snapshotNow(underlying, null);
      } catch (Exception e) {
        log.warn("scheduled options snapshot failed for {}: {}", underlying, e.getMessage());
      }
    }
  }

  /** 30 s live chain broadcast, market hours (B-12) — publish only, no persistence. */
  @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
  public void scheduledBroadcast() {
    if (!isOpenSafe()) {
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
    OptionsChainService.Chain chain = chainService.chain(underlying, expiry);
    // one ts for the whole pass — the PK groups the snapshot
    OffsetDateTime ts = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
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
    Long prev = leg.oi() == null ? null : previousOi.put(oiKey, leg.oi());
    Long oiChange = leg.oi() == null || prev == null ? null : leg.oi() - prev;
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

  private boolean isOpenSafe() {
    try {
      return calendar.isOpen(clock.instant());
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }
}
