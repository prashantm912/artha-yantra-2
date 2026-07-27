package in.arthayantra.marketdata.candles;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.canary.DataHealthState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The production {@link CandleBuilder.BarSink}: idempotent hypertable upsert + closed-bar publish
 * on {@code candles.1m.{exchange}.{tradingsymbol}} (D9 verbatim — closed bars are bus traffic,
 * higher intervals come from aggregates on read). Records {@code ay_candle_builder_lag_seconds}
 * (bar-end → persist).
 */
@Component
public class BarWriter implements CandleBuilder.BarSink {

  private static final Logger log = LoggerFactory.getLogger(BarWriter.class);

  private final CandleRepository repository;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Timer builderLag;
  private final DataHealthState healthState;
  private final MarketCalendar calendar;
  private final Counter gateRejected;
  private final Set<LocalDate> calendarFallbackDates = ConcurrentHashMap.newKeySet();

  /** Wires persistence + publish. */
  public BarWriter(
      CandleRepository repository,
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      Clock clock,
      MeterRegistry meterRegistry,
      DataHealthState healthState,
      MarketCalendar calendar) {
    this.repository = repository;
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.builderLag = meterRegistry.timer("ay_candle_builder_lag_seconds");
    this.healthState = healthState;
    this.calendar = calendar;
    this.gateRejected = meterRegistry.counter("ay_candle_gate_rejected_total");
  }

  @Override
  public void onClosedBar(Candle bar) {
    if (!shouldWrite(bar)) {
      return;
    }
    try {
      repository.upsert(bar);
      String channel = "candles.1m." + bar.exchange() + "." + bar.tradingsymbol();
      redis.convertAndSend(channel, objectMapper.writeValueAsString(bar));
      healthState.recordBar(bar.exchange(), bar.tradingsymbol());
      OffsetDateTime barEnd = bar.bucket().plusMinutes(1);
      long lagMs = Duration.between(barEnd.toInstant(), clock.instant()).toMillis();
      builderLag.record(Math.max(0, lagMs), TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      log.error("closed-bar write failed for {}:{}", bar.exchange(), bar.tradingsymbol(), e);
    }
  }

  /**
   * Rejects only live tick-aggregation bars that are BOTH on a non-trading date AND carry zero
   * traded volume. Historical and mock writes use different sinks/sources and must retain their
   * existing behavior. An uncovered calendar year fails open so a stale holiday resource cannot
   * silently stop the hot write path.
   *
   * <p><b>The zero-volume conjunct is load-bearing, not belt-and-braces</b> (cross-vendor review,
   * Critical 1). {@code isTradingDay} hard-returns false for ANY Saturday/Sunday, and the bundled
   * holiday CSV's own header states it does NOT model off-pattern sessions — the 2026-11-08 Diwali
   * Muhurat session falls on a SUNDAY, and a Union-Budget Saturday runs a full 09:15–15:30 session.
   * On a date-only gate those real, volume-bearing bars would be dropped PERMANENTLY: {@code
   * TradingBuckets.minuteBuckets} only enumerates buckets on trading days, so {@code GapDetector}
   * never marks the day missing and the cache-first re-fetch never backfills it. Every weekend
   * TICK_AGG date in the entire capture history has {@code sum(volume) = 0}, so this conjunct loses
   * no phantom coverage while being structurally incapable of dropping a bar that actually traded.
   */
  private boolean shouldWrite(Candle bar) {
    if (!"TICK_AGG".equals(bar.source())) {
      return true;
    }
    // The WHOLE body is guarded, including the date derivation: an unchecked throw escaping here
    // would abort CandleBuilder.flush's forEach for every remaining symbol AND leave the
    // accumulator's bucket un-nulled, so the same bar re-throws forever and the flush sweep dies
    // permanently for all instruments (cross-vendor review, Minor 4). `date` stays null-able so the
    // catch never re-derives it — handling a throw must not throw.
    LocalDate date = null;
    try {
      date = bar.bucket().atZoneSameInstant(MarketCalendar.IST).toLocalDate();
      if (!calendar.isTradingDay(date) && bar.volume() == 0) {
        // Counted, not just logged: this line is DEBUG and root is INFO in production, so without a
        // counter an over-blocking gate would be invisible until the engine starved days later
        // (cross-vendor review, Major 2). A nonzero count inside 09:15–15:30 IST is alertable.
        gateRejected.increment();
        log.debug(
            "ignoring zero-volume tick-agg bar on non-trading date {} for {}:{}",
            date,
            bar.exchange(),
            bar.tradingsymbol());
        return false;
      }
    } catch (RuntimeException e) {
      if (date == null || calendarFallbackDates.add(date)) {
        log.warn(
            "trading-day check unavailable for {} ({}); allowing tick-agg bars",
            date,
            e.getMessage());
      }
    }
    return true;
  }
}
