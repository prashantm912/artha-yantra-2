package in.arthayantra.gateway.ws;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code GATEWAY_WS_FLUSH_HZ} knob math (A.7.2): 20 Hz default → 50 ms, the 10 Hz floor →
 * 100 ms, and degenerate values clamp instead of dividing by zero or spinning. (The env→property
 * mapping itself lives in application.yml: {@code artha.ws.flush-hz: ${GATEWAY_WS_FLUSH_HZ:20}}.)
 */
class StompFlushConfigTest {

  private static long intervalFor(int flushHz) {
    return new StompWebSocketHandler(null, new SimpleMeterRegistry(), flushHz).flushIntervalMs();
  }

  @Test
  void flushHzMapsToFlushInterval() {
    assertThat(intervalFor(20)).isEqualTo(50); // default — ≤ 50 ms added wait
    assertThat(intervalFor(10)).isEqualTo(100); // slowest setting that passes the 150 ms gate
  }

  @Test
  void degenerateValuesClampSafely() {
    assertThat(intervalFor(0)).isEqualTo(1000); // never divide by zero
    assertThat(intervalFor(-5)).isEqualTo(1000);
    assertThat(intervalFor(1000)).isEqualTo(50); // 50 ms floor — never busy-spin
  }
}
