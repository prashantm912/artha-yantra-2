package in.arthayantra.strategysignal.notifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Flood control (Phase 41 / E-14): a per-strategy cooldown/dedup plus a global hourly cap. A chatty
 * strategy can neither flood the phone nor bury a critical ops alert; when the cap is exceeded the
 * service emits a single "N suppressed" summary push. In-memory (single-instance service).
 */
@Component
public class FloodControl {

  /** Per-signal admission outcome. */
  public enum Decision {
    ALLOW,
    COOLDOWN,
    CAPPED,
  }

  private final long cooldownSeconds;
  private final int hourlyCap;
  private final Clock clock;
  private final Map<UUID, Instant> lastSent = new ConcurrentHashMap<>();
  private Instant hourStart;
  private int hourlyCount;
  private int suppressed;
  private boolean capSummarySent;

  /** Wires the cooldown/cap config + clock. */
  public FloodControl(
      @Value("${artha.notifier.cooldown-seconds:60}") long cooldownSeconds,
      @Value("${artha.notifier.hourly-cap:30}") int hourlyCap,
      Clock clock) {
    this.cooldownSeconds = cooldownSeconds;
    this.hourlyCap = hourlyCap;
    this.clock = clock;
  }

  /** Admit (or suppress) one push for a strategy. */
  public synchronized Decision admit(UUID strategyId) {
    Instant now = clock.instant();
    if (hourStart == null || Duration.between(hourStart, now).toHours() >= 1) {
      hourStart = now;
      hourlyCount = 0;
      suppressed = 0;
      capSummarySent = false;
    }
    Instant last = lastSent.get(strategyId);
    if (last != null && Duration.between(last, now).getSeconds() < cooldownSeconds) {
      return Decision.COOLDOWN;
    }
    if (hourlyCount >= hourlyCap) {
      suppressed++;
      return Decision.CAPPED;
    }
    hourlyCount++;
    lastSent.put(strategyId, now);
    return Decision.ALLOW;
  }

  /** The suppressed count, returned ONCE per hour the first time the cap is hit (else 0). */
  public synchronized int capSummaryDue() {
    if (suppressed > 0 && !capSummarySent) {
      capSummarySent = true;
      return suppressed;
    }
    return 0;
  }
}
