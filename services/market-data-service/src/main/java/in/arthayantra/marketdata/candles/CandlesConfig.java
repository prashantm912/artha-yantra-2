package in.arthayantra.marketdata.candles;

import in.arthayantra.marketcalendar.MarketCalendar;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Wires the Phase-10 builder into the feed pipeline + the hypertable size gauge. */
@Configuration
public class CandlesConfig {

  /** Shared NSE calendar instance. */
  @Bean
  public MarketCalendar marketCalendar() {
    return MarketCalendar.nse();
  }

  /** IST session bucketer. */
  @Bean
  public SessionBucketer sessionBucketer(MarketCalendar calendar) {
    return new SessionBucketer(calendar);
  }

  /** The 1m builder, registered as a NormalizedTickListener on the feed pipeline. */
  @Bean
  public CandleBuilder candleBuilder(
      MarketCalendar calendar, BarWriter writer, Clock clock, Environment environment) {
    boolean live = environment.matchesProfiles("live");
    String source = live ? "TICK_AGG" : "MOCK";
    // Live clamps pre/post-market prints into the session open/15:29 close bucket (B-7). The mock
    // feed ticks 24/7, so that same clamp would freeze the 1m feed at 15:29 every evening — no new
    // closed bars roll and the live signal engine starves — so mock uses a 24/7 unclamped bucketer.
    // (The shared sessionBucketer() bean stays clamped for MarketSurfaceController.)
    SessionBucketer bucketer = new SessionBucketer(calendar, live);
    return new CandleBuilder(bucketer, writer, clock, source);
  }

  /** Calendar-driven expected-bucket enumerator (Phase 11 gap math + mock history). */
  @Bean
  public TradingBuckets tradingBuckets(MarketCalendar calendar) {
    return new TradingBuckets(calendar);
  }

  /** Gap math for the cache-first read path. */
  @Bean
  public GapDetector gapDetector(TradingBuckets tradingBuckets) {
    return new GapDetector(tradingBuckets);
  }

  /** Sweeps bar closes past the 5 s grace + samples the hypertable size gauge. */
  @Component
  public static class CandleHousekeeping {
    private static final Logger log = LoggerFactory.getLogger(CandleHousekeeping.class);

    private final CandleBuilder builder;
    private final CandleRepository repository;
    private final AtomicLong hypertableBytes = new AtomicLong();
    private final AtomicLong databaseBytes = new AtomicLong();

    /** Registers the size gauges. */
    public CandleHousekeeping(
        CandleBuilder builder, CandleRepository repository, MeterRegistry meterRegistry) {
      this.builder = builder;
      this.repository = repository;
      // ay_hypertable_bytes is candles-ONLY; ay_database_size_bytes is the whole-DB total the
      // 50 GB retention review trigger actually measures (audit AYDB-03 — candles is ~half the DB).
      meterRegistry.gauge("ay_hypertable_bytes", hypertableBytes);
      meterRegistry.gauge("ay_database_size_bytes", databaseBytes);
    }

    /** 1 s sweep: close bars past minute+grace. */
    @Scheduled(fixedDelay = 1_000)
    public void flushBars() {
      builder.flush();
    }

    /** 15-min storage-size sample (B-7 disk-budget visibility, live from Phase 10). */
    @Scheduled(fixedDelay = 900_000, initialDelay = 15_000)
    public void sampleStorageSizes() {
      try {
        hypertableBytes.set(repository.hypertableBytes());
        databaseBytes.set(repository.databaseSizeBytes());
      } catch (Exception e) {
        log.warn("storage size sample failed: {}", e.getMessage());
      }
    }
  }
}
