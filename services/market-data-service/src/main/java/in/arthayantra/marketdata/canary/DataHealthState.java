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
  // T20: ticks observed since the key's last closed bar — the divergence check's density guard.
  // A REAL bar stall accumulates ticks (~1 Hz tape → hundreds); a thin far-month tape accrues a
  // handful between its rare bar closes, which is exactly the false-positive shape that paged 5
  // sessions running (FINNIFTY26SEPFUT, 35 bars/day).
  private final Map<String, java.util.concurrent.atomic.AtomicLong> ticksSinceBar =
      new ConcurrentHashMap<>();

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
    ticksSinceBar
        .computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong())
        .incrementAndGet();
  }

  /** Called by the bar sink after a closed 1m bar is persisted + published. */
  public void recordBar(String exchange, String tradingsymbol) {
    String key = exchange + ":" + tradingsymbol;
    if (key.equals(drillSuppressKey)) {
      return; // the drill key's tick counter keeps accruing, so the drill still fires end-to-end
    }
    lastBarAt.put(key, clock.instant());
    java.util.concurrent.atomic.AtomicLong n = ticksSinceBar.get(key);
    if (n != null) {
      n.set(0);
    }
  }

  long ticksSinceBar(String key) {
    java.util.concurrent.atomic.AtomicLong n = ticksSinceBar.get(key);
    return n == null ? 0 : n.get();
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
