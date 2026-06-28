package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import java.math.BigDecimal;

/**
 * Siva #1 Two-Candle Theory pattern detector (master plan §12, doc §3.1). The chart-only YAML gate
 * grammar cannot express a multi-bar candle pattern (no {@code open}/colour operand, no prior-bar
 * lookback), so the namesake trigger lives here as a typed gate consulted in {@link
 * ScalperConfluenceGate} for strategies that declare it.
 *
 * <p>The setup (§3.1 L360-376): the entry deploys on the <b>3rd candle</b> (the just-closed bar at
 * {@code entryIndex}); the two candles before it must be (a) the same colour as the side — two GREEN
 * for CE, two RED for PE — (b) each independently above the per-instrument volume floor (NIFTY 125k /
 * other indices 50k), and (c) the 2nd candle must be <b>strong</b> — its combined shadow under twice
 * its body (a wick ≥ 2× body is rejection from the level, momentum likely dies; §3.1 L361). The 1st
 * candle's low (CE) / high (PE) is the strategy's primary stop-loss anchor (§3.1 L388).
 *
 * <p>Pure + deterministic over fixed closed bars, so a replay recomputes it byte-identically (§12.9).
 */
public final class TwoCandleGate {

  private TwoCandleGate() {}

  /** Whether the 2-candle formation is present and, if so, the 1st-candle stop anchor. */
  public record Formation(boolean present, BigDecimal stopLevel) {}

  private static final Formation ABSENT = new Formation(false, null);
  private static final BigDecimal TWO = BigDecimal.valueOf(2);

  /**
   * Detect the formation at the entry (3rd) bar.
   *
   * @param future the index-future series the scalper evaluates on
   * @param entryIndex the just-closed deploy (3rd) bar index
   * @param side CE (wants two green candles) or PE (two red)
   * @param underlying the underlying name (drives the volume floor)
   */
  public static Formation detect(
      EngineSeries future, int entryIndex, OptionType side, String underlying) {
    return detect(future, entryIndex, side, underlying, false);
  }

  /**
   * E6 #10 (tag {@code two-candle-substitution}) overload: when {@code allowVolumeSubstitution} is set,
   * a 2nd candle that misses the volume floor is still accepted IF the deploy (3rd) bar itself clears
   * the floor on the side's colour (the doc's "if the 2nd is light, the breakout candle carries it").
   * The colour + 2nd-candle-strength + 1st-candle-floor checks are unchanged, so {@code false} (the
   * default) is byte-identical to the strict detector.
   */
  public static Formation detect(
      EngineSeries future,
      int entryIndex,
      OptionType side,
      String underlying,
      boolean allowVolumeSubstitution) {
    if (future == null || entryIndex < 2) {
      return ABSENT;
    }
    EngineCandle first = future.candle(entryIndex - 2); // the 1st candle (SL anchor)
    EngineCandle second = future.candle(entryIndex - 1); // the 2nd candle (strength key)
    boolean ce = side == OptionType.CE;
    if (!sameColour(first, ce) || !sameColour(second, ce)) {
      return ABSENT;
    }
    if (!floorMet(underlying, first)) {
      return ABSENT;
    }
    if (!floorMet(underlying, second)) {
      // strict path rejects a light 2nd candle; the substitution path admits it only when the deploy
      // (3rd) bar clears the floor on the side colour.
      EngineCandle deploy = future.candle(entryIndex);
      boolean substituted =
          allowVolumeSubstitution && sameColour(deploy, ce) && floorMet(underlying, deploy);
      if (!substituted) {
        return ABSENT;
      }
    }
    if (!strongBody(second)) {
      return ABSENT;
    }
    return new Formation(true, ce ? first.low() : first.high());
  }

  /** CE wants a green candle (close &gt; open); PE wants red (close &lt; open). */
  private static boolean sameColour(EngineCandle c, boolean green) {
    int cmp = c.close().compareTo(c.open());
    return green ? cmp > 0 : cmp < 0;
  }

  private static boolean floorMet(String underlying, EngineCandle c) {
    return ScalperGates.volume(underlying, BigDecimal.valueOf(c.volume())).pass();
  }

  /** Strong = combined shadow under 2× the body; a doji (zero body) or a 2×-body wick is rejected. */
  private static boolean strongBody(EngineCandle c) {
    BigDecimal body = c.close().subtract(c.open()).abs();
    if (body.signum() == 0) {
      return false;
    }
    BigDecimal shadow = c.high().subtract(c.low()).subtract(body);
    return shadow.compareTo(body.multiply(TWO)) < 0;
  }
}
