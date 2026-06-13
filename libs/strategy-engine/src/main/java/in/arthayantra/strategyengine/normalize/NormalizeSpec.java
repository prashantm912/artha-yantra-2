package in.arthayantra.strategyengine.normalize;

import java.math.BigDecimal;
import java.util.List;

/**
 * A parsed {@code normalize{}} block (§C-2.3). {@code linear} carries from/to; {@code step}
 * carries ordered bands ({@code upto} null on the final catch-all); {@code direction} and
 * {@code rsi_momentum} carry nothing.
 */
public record NormalizeSpec(Type type, BigDecimal from, BigDecimal to, List<Band> bands) {

  /** Built-in normalizer types. */
  public enum Type {
    LINEAR,
    STEP,
    DIRECTION,
    RSI_MOMENTUM
  }

  /** One step band: applies while value &lt;= upto; null upto = catch-all. */
  public record Band(BigDecimal upto, BigDecimal score) {}

  /** Linear factory. */
  public static NormalizeSpec linear(BigDecimal from, BigDecimal to) {
    return new NormalizeSpec(Type.LINEAR, from, to, null);
  }

  /** Step factory. */
  public static NormalizeSpec step(List<Band> bands) {
    return new NormalizeSpec(Type.STEP, null, null, List.copyOf(bands));
  }

  /** Direction factory. */
  public static NormalizeSpec direction() {
    return new NormalizeSpec(Type.DIRECTION, null, null, null);
  }

  /** RSI-momentum factory. */
  public static NormalizeSpec rsiMomentum() {
    return new NormalizeSpec(Type.RSI_MOMENTUM, null, null, null);
  }
}
