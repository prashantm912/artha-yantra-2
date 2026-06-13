package in.arthayantra.strategyengine.fills;

import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * Selects the fill reference price by timing (A9 [FP-6]), one implementation for live and replay.
 * {@code NEXT_OPEN} → the next bar's open; {@code AT_CLOSE} → the signal bar's close. The A7 default
 * is {@code at_close} for {@code style: btst}, else {@code next_open}.
 */
public final class ReferencePriceSelector {

  private ReferencePriceSelector() {}

  /** Resolves the reference price for the given timing. */
  public static BigDecimal select(FillTiming timing, EngineCandle signalBar, EngineCandle nextBar) {
    return switch (timing) {
      case AT_CLOSE -> signalBar.close();
      case NEXT_OPEN -> {
        if (nextBar == null) {
          throw new IllegalStateException("next_open fill requires the next bar");
        }
        yield nextBar.open();
      }
    };
  }

  /** The A7 default timing: {@code at_close} for btst, else {@code next_open}; explicit wins. */
  public static FillTiming defaultFor(String style, String configuredTiming) {
    if (configuredTiming != null && !configuredTiming.isBlank()) {
      return FillTiming.valueOf(configuredTiming.trim().toUpperCase(Locale.ROOT));
    }
    return "btst".equalsIgnoreCase(style) ? FillTiming.AT_CLOSE : FillTiming.NEXT_OPEN;
  }
}
