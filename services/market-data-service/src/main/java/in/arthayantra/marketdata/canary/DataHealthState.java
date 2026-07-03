package in.arthayantra.marketdata.canary;

import in.arthayantra.marketdata.feed.NormalizedTick;
import in.arthayantra.marketdata.feed.NormalizedTickListener;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory data-plane liveness state for the {@link DataHealthCanary} (roadmap F4): per-instrument
 * last-tick arrival and last-closed-bar wall times. Registered on the feed pipeline as a
 * {@link NormalizedTickListener} (tick side); {@code BarWriter} calls {@link #recordBar} after each
 * successful persist+publish (bar side). Wall-clock stamps, never the tick's own exchange
 * timestamp — a mis-stamped frame is exactly what the canary must not trust (the 2026-07-03
 * CandleBuilder poison).
 */
@Component
public class DataHealthState implements NormalizedTickListener {

  private final Clock clock;
  private final String drillSuppressKey;
  private final Map<String, Instant> lastTickAt = new ConcurrentHashMap<>();
  private final Map<String, Instant> firstTickAt = new ConcurrentHashMap<>();
  private final Map<String, Instant> lastBarAt = new ConcurrentHashMap<>();

  /**
   * {@code drill-suppress-key} is the fault-drill hook: bars for that one instrument key (e.g.
   * {@code NSE:NIFTY 50}) are not recorded, simulating a dead 1m publisher end-to-end through the
   * canary + alert path without touching the real pipeline. Blank (the default) in normal runs.
   */
  public DataHealthState(
      Clock clock, @Value("${artha.canary.drill-suppress-key:}") String drillSuppressKey) {
    this.clock = clock;
    this.drillSuppressKey = drillSuppressKey;
  }

  @Override
  public void onNormalizedTick(NormalizedTick tick) {
    String key = tick.exchange() + ":" + tick.tradingsymbol();
    Instant now = clock.instant();
    lastTickAt.put(key, now);
    firstTickAt.putIfAbsent(key, now);
  }

  /** Called by the bar sink after a closed 1m bar is persisted + published. */
  public void recordBar(String exchange, String tradingsymbol) {
    String key = exchange + ":" + tradingsymbol;
    if (key.equals(drillSuppressKey)) {
      return;
    }
    lastBarAt.put(key, clock.instant());
  }

  Map<String, Instant> lastTicks() {
    return Map.copyOf(lastTickAt);
  }

  Instant firstTick(String key) {
    return firstTickAt.get(key);
  }

  Instant lastBar(String key) {
    return lastBarAt.get(key);
  }
}
