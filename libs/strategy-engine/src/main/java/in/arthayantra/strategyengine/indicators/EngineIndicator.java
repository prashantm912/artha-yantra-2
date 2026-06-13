package in.arthayantra.strategyengine.indicators;

import java.math.BigDecimal;

/**
 * One evaluable indicator instance. Values are exact decimals rounded to {@link #SCALE} at this
 * boundary (stable string forms for breakdowns and vectors); {@code null} while the indicator is
 * warming up or its inputs are unavailable (missing OI, no prior session …) — a null value can
 * never reach a composite (the engine refuses to score it, it never silently becomes zero).
 */
public interface EngineIndicator {

  /** Output scale at the engine boundary. */
  int SCALE = 8;

  /** Value at a bar index, or null when not computable yet. */
  BigDecimal valueAt(int index);

  /** Bars before which {@link #valueAt} returns null (warm-up window). */
  int unstableBars();
}
