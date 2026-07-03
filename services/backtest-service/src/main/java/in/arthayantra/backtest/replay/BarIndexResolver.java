package in.arthayantra.backtest.replay;

import in.arthayantra.strategyengine.series.EngineCandle;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signal-timestamp → 1m bar-index resolution for leg pairing (audit
 * replay-legs-silent-index0-fallback). An exact key hit behaves exactly as the old
 * {@code indexByTs} map — parity-critical, the goldens pin it. A MISS (a coarse-primary signal
 * whose bucket's FIRST 1m bar is absent — a routine 1m gap) used to silently resolve to bar 0 /
 * the last bar, opening the trade at the window start at that bar's price (a phantom P&L with no
 * error); it now resolves to the first bar AT/AFTER the timestamp with a WARN, and {@code -1}
 * (no such bar) lets the caller drop or force-close the leg explicitly. The ceiling map is keyed
 * by {@link Instant}, never the offset-bearing {@link OffsetDateTime} (the #214 cross-source
 * time-map trap).
 */
public final class BarIndexResolver {

  private static final Logger log = LoggerFactory.getLogger(BarIndexResolver.class);

  private final Map<String, Integer> exact = new HashMap<>();
  private final TreeMap<Instant, Integer> byInstant = new TreeMap<>();

  public BarIndexResolver(List<EngineCandle> oneMinute) {
    for (int i = 0; i < oneMinute.size(); i++) {
      exact.put(oneMinute.get(i).bucketStart().toString(), i);
      byInstant.put(oneMinute.get(i).bucketStart().toInstant(), i);
    }
  }

  /** Exact hit, else the first bar at/after {@code timestamp}; {@code -1} when none exists. */
  public int atOrAfter(String timestamp) {
    Integer hit = exact.get(timestamp);
    if (hit != null) {
      return hit;
    }
    Map.Entry<Instant, Integer> ceiling;
    try {
      ceiling = byInstant.ceilingEntry(OffsetDateTime.parse(timestamp).toInstant());
    } catch (java.time.format.DateTimeParseException unparseable) {
      log.warn("unparseable signal timestamp '{}' — no fill bar", timestamp);
      return -1;
    }
    if (ceiling == null) {
      log.warn("signal timestamp {} is past the last 1m bar — no fill bar", timestamp);
      return -1;
    }
    log.warn(
        "signal timestamp {} has no exact 1m bar (gap at the bucket's first minute) — resolved to"
            + " the next bar, index {}",
        timestamp, ceiling.getValue());
    return ceiling.getValue();
  }
}
