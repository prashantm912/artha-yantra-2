package in.arthayantra.strategysignal.notifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Setup-level dedupe for scalp alerts (Z1). The generic {@link FloodControl} throttles per-strategy;
 * a scalper can legitimately re-arm the same option leg repeatedly within a window (the chart pattern
 * keeps re-printing), so this dedupes on the SETUP — strategy + underlying + option-side + strike —
 * within {@code window-seconds}. The first of a setup goes out; identical re-fires inside the window
 * are suppressed. In-memory (single-instance service), mirroring {@link FloodControl}.
 */
@Component
public class ScalpAlertDedupe {

  private final long windowSeconds;
  private final Clock clock;
  private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();

  /** Wires the dedupe window + clock. */
  public ScalpAlertDedupe(
      @Value("${artha.notifier.scalp-alerts.window-seconds:300}") long windowSeconds, Clock clock) {
    this.windowSeconds = windowSeconds;
    this.clock = clock;
  }

  /** True when this setup may fire (and records it); false when an identical setup is in cooldown. */
  public synchronized boolean admit(String setupKey) {
    Instant now = clock.instant();
    Instant last = lastSent.get(setupKey);
    if (last != null && Duration.between(last, now).getSeconds() < windowSeconds) {
      return false;
    }
    lastSent.put(setupKey, now);
    return true;
  }
}
