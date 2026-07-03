package in.arthayantra.marketdata.candles;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.canary.DataHealthState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
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

  /** Wires persistence + publish. */
  public BarWriter(
      CandleRepository repository,
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      Clock clock,
      MeterRegistry meterRegistry,
      DataHealthState healthState) {
    this.repository = repository;
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.builderLag = meterRegistry.timer("ay_candle_builder_lag_seconds");
    this.healthState = healthState;
  }

  @Override
  public void onClosedBar(Candle bar) {
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
}
