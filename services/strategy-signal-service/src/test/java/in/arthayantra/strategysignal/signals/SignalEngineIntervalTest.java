package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Audit fix interval-duration-silent-default: an unknown primary must throw (matching the golden
 * runner), never silently mean "evaluate every 1m bar" — reload() refuses non-rollable primaries
 * up front, so the throw marks a programming error, not a config path.
 */
class SignalEngineIntervalTest {

  @Test
  void rollablePrimariesMapToTheirDurations() {
    assertThat(SignalEngine.intervalDuration("3m")).isEqualTo(Duration.ofMinutes(3));
    assertThat(SignalEngine.intervalDuration("5m")).isEqualTo(Duration.ofMinutes(5));
    assertThat(SignalEngine.intervalDuration("15m")).isEqualTo(Duration.ofMinutes(15));
    assertThat(SignalEngine.intervalDuration("1h")).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void anUnknownPrimaryThrowsInsteadOfSilentlyDegradingToOneMinute() {
    assertThatThrownBy(() -> SignalEngine.intervalDuration("1d"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1d");
  }
}
