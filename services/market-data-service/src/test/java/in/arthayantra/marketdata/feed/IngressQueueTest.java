package in.arthayantra.marketdata.feed;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.kite.RawTick;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** A.7.1 ingress contract: bounded, drop-oldest, counted. */
class IngressQueueTest {

  private static RawTick tick(long token) {
    return new RawTick(token, new BigDecimal("100.05"), token, Instant.EPOCH);
  }

  @Test
  void dropsOldestWhenFullAndCountsTheDrop() throws InterruptedException {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    IngressQueue queue = new IngressQueue(registry, 3);

    for (long token = 1; token <= 5; token++) {
      queue.onTick(tick(token));
    }

    assertThat(queue.depth()).isEqualTo(3);
    assertThat(registry.counter("ay_ticks_dropped_total").count()).isEqualTo(2.0);
    assertThat(registry.counter("ay_ticks_ingested_total").count()).isEqualTo(5.0);
    // oldest (1, 2) were dropped — latest price beats a stalled backlog
    assertThat(queue.poll(Duration.ofMillis(10)).instrumentToken()).isEqualTo(3);
    assertThat(queue.poll(Duration.ofMillis(10)).instrumentToken()).isEqualTo(4);
    assertThat(queue.poll(Duration.ofMillis(10)).instrumentToken()).isEqualTo(5);
  }
}
