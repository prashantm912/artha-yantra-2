package in.arthayantra.marketdata.feed;

import in.arthayantra.marketdata.kite.RawTick;
import in.arthayantra.marketdata.kite.TickListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * The bounded ingress queue (A.7.1): capacity 10k, DROP-OLDEST with a counter — the latest price
 * always beats a stalled backlog. Feed callback threads only enqueue; the single-writer
 * normalizer drains.
 */
@Component
public class IngressQueue implements TickListener {

  static final int CAPACITY = 10_000;

  private final BlockingQueue<RawTick> queue;
  private final Counter ingested;
  private final Counter dropped;

  /** Production queue at the A.7.1 capacity. */
  @org.springframework.beans.factory.annotation.Autowired
  public IngressQueue(MeterRegistry meterRegistry) {
    this(meterRegistry, CAPACITY);
  }

  IngressQueue(MeterRegistry meterRegistry, int capacity) {
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.ingested = meterRegistry.counter("ay_ticks_ingested_total");
    this.dropped = meterRegistry.counter("ay_ticks_dropped_total");
  }

  @Override
  public void onTick(RawTick tick) {
    while (!queue.offer(tick)) {
      if (queue.poll() != null) {
        dropped.increment();
      }
    }
    ingested.increment();
  }

  /** Blocking poll for the normalizer loop. */
  public RawTick poll(Duration timeout) throws InterruptedException {
    return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Current depth (tests, status endpoints). */
  public int depth() {
    return queue.size();
  }
}
