package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Calls the §8 SPAN margin appliance ({@code POST {margin.base}/api/v1/margin/size}) to replace the
 * flat margin-pct approximation for futures &amp; short options when {@code artha.margin.span-enabled=true}.
 *
 * <p><b>Advisory only.</b> An empty result (flag off, appliance unreachable, no {@code .spn} loaded,
 * or a leg that can't be resolved) signals the caller to fall back to the existing flat-pct path — a
 * paper order is never blocked by margin (consistent with the non-blocking buying-power design).
 */
public interface MarginServiceClient {

  /** SPAN margin required (the {@code marginRequired} of a single-leg basket sized to {@code qty}). */
  record MarginEstimate(BigDecimal marginRequired) {}

  /**
   * Capital usage (SPAN total) for one derivative leg, or {@link Optional#empty()} when SPAN sizing is
   * disabled/unavailable and the caller must use the flat-pct fallback.
   */
  Optional<MarginEstimate> marginFor(
      String exchange, String tradingsymbol, String side, BigDecimal price, long qty);
}
