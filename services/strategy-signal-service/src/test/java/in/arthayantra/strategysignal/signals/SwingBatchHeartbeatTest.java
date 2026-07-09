package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * The swing-batch dead-man's-switch: pings the configured external monitor once per evening; the
 * monitor alerts when the ping is ABSENT (i.e. the stack was down at batch time). Verifies the arming
 * gate, the ping target, and the fail-soft contract (a ping error must never surface to the batch).
 */
class SwingBatchHeartbeatTest {

  /** Captures the ping target instead of hitting the network (send() is package-private). */
  private static final class Recording extends SwingBatchHeartbeat {
    private final boolean fail;
    private String pinged;
    private int calls;

    Recording(String url, boolean fail) {
      super(url);
      this.fail = fail;
    }

    @Override
    void send(String pingUrl) throws Exception {
      calls++;
      if (fail) {
        throw new java.io.IOException("simulated ping failure");
      }
      this.pinged = pingUrl;
    }
  }

  @Test
  void beatPingsTheConfiguredUrl() {
    Recording heartbeat = new Recording("https://hc-ping.com/abc-123", false);
    heartbeat.beat();
    assertThat(heartbeat.pinged).isEqualTo("https://hc-ping.com/abc-123");
    assertThat(heartbeat.calls).isEqualTo(1);
  }

  @Test
  void beatNoOpsOnBlankUrl() {
    Recording heartbeat = new Recording("  ", false);
    heartbeat.beat();
    assertThat(heartbeat.calls).isZero(); // no ping attempted when unarmed
  }

  @Test
  void beatSwallowsSendFailure() {
    Recording heartbeat = new Recording("https://hc-ping.com/abc-123", true);
    // A network/monitor failure must never propagate to the batch — the external monitor's own
    // missed-ping alert is the backstop.
    assertThatCode(heartbeat::beat).doesNotThrowAnyException();
    assertThat(heartbeat.calls).isEqualTo(1);
  }
}
