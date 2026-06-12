package in.arthayantra.marketdata.mockfeed;

import in.arthayantra.marketdata.kite.InstrumentDumpGateway;
import in.arthayantra.marketdata.kite.InstrumentDumpGateway.InstrumentRecord;
import in.arthayantra.marketdata.kite.MarketFeed;
import in.arthayantra.marketdata.kite.RawTick;
import in.arthayantra.marketdata.kite.TickListener;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The D13 mock {@link MarketFeed} (A.7a): ticks a fixed subset of the CD-14 fixture (indices
 * first) at {@code MOCK_TICKS_PER_SEC} per instrument with the CD-10 scenario/rate knobs. Feeds
 * the SAME ingress path the live Kite feed will use.
 */
@Component
@Profile("mock")
public class MockMarketFeed implements MarketFeed {

  private static final Logger log = LoggerFactory.getLogger(MockMarketFeed.class);

  private final InstrumentDumpGateway dumpGateway;
  private final double ticksPerSecond;
  private final int instrumentCount;
  private final long seed;
  private final String scenario;

  private volatile boolean running;
  private ScheduledExecutorService scheduler;

  public MockMarketFeed(
      InstrumentDumpGateway dumpGateway,
      @Value("${artha.mock.ticks-per-sec:1}") double ticksPerSecond,
      @Value("${artha.mock.instrument-count:10}") int instrumentCount,
      @Value("${artha.mock.seed:42}") long seed,
      @Value("${artha.mock.scenario:trend-up}") String scenario) {
    this.dumpGateway = dumpGateway;
    this.ticksPerSecond = ticksPerSecond;
    this.instrumentCount = instrumentCount;
    this.seed = seed;
    this.scenario = scenario;
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored") // fixed-rate ticking; errors logged in-task
  public synchronized void start(TickListener listener) {
    if (running) {
      return;
    }
    running = true;
    MockTickGenerator generator = new MockTickGenerator(seed, scenario);
    List<InstrumentRecord> subset =
        dumpGateway.fetchDump().stream()
            .filter(r -> "INDICES".equals(r.segment()) || "NSE".equals(r.segment()))
            .limit(instrumentCount)
            .toList();
    long periodMicros = (long) (1_000_000.0 / ticksPerSecond);
    scheduler = Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "mock-feed");
          thread.setDaemon(true);
          return thread;
        });
    scheduler.scheduleAtFixedRate(
        () -> {
          for (InstrumentRecord record : subset) {
            MockTickGenerator.Step step = generator.next(record.instrumentToken());
            listener.onTick(
                new RawTick(
                    record.instrumentToken(),
                    step.lastPrice(),
                    step.cumulativeDayVolume(),
                    Instant.now()));
          }
        },
        0,
        periodMicros,
        TimeUnit.MICROSECONDS);
    log.info(
        "mock feed started: {} instruments at {}/s each (scenario={}, seed={})",
        subset.size(),
        ticksPerSecond,
        scenario,
        seed);
  }

  @Override
  public synchronized void stop() {
    running = false;
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  @Override
  public boolean running() {
    return running;
  }
}
