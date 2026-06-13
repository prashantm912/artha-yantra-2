package in.arthayantra.strategyengine.indicators;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Shared exact-decimal arithmetic context (mirrors DecimalNum precision 32, HALF_UP). */
public final class EngineMath {

  /** Engine-wide MathContext — the vector generator mirrors exactly this. */
  public static final MathContext MC = new MathContext(32, RoundingMode.HALF_UP);

  /** One hundred. */
  public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private EngineMath() {}

  /** Boundary rounding to {@link EngineIndicator#SCALE}. */
  public static BigDecimal round(BigDecimal value) {
    return value.setScale(EngineIndicator.SCALE, RoundingMode.HALF_UP);
  }
}
