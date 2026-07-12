package in.arthayantra.marketdata.options;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OI-outlier plausibility test (app-platform audit 2026-07-10 §8 V6). A pure, side-effect-free judge
 * over the two values every captured snapshot already carries — {@code oi} and {@code oi_change} (the
 * change vs the prior bucket) — so it needs no prior-bucket lookup: {@code prior = oi - oi_change}.
 *
 * <p>A row is an outlier iff either:
 *
 * <ul>
 *   <li>{@code oi < 0} — impossible open interest (a corrupt print), OR
 *   <li>the prior bucket had a meaningful OI ({@code prior >= minPriorOi}, so illiquid / brand-new
 *       strikes are never judged) AND the single-bucket change exceeds {@code jumpFactor}× that prior
 *       ({@code |oi_change| > jumpFactor * prior}).
 * </ul>
 *
 * <p>Deliberately conservative (a high default {@code jumpFactor}): quarantining a real row HIDES it
 * from the folds, so the guard only ever fires on gross bad prints, never marginal moves. Tunable via
 * {@code artha.options.oi-outlier.*}; {@code enabled=false} disables it entirely (nothing ever flagged).
 */
@Component
public class OiOutlierDetector {

  private final boolean enabled;
  private final double jumpFactor;
  private final long minPriorOi;

  public OiOutlierDetector(
      @Value("${artha.options.oi-outlier.enabled:true}") boolean enabled,
      @Value("${artha.options.oi-outlier.jump-factor:20.0}") double jumpFactor,
      @Value("${artha.options.oi-outlier.min-prior-oi:5000}") long minPriorOi) {
    this.enabled = enabled;
    this.jumpFactor = jumpFactor;
    this.minPriorOi = minPriorOi;
  }

  /** Whether this (oi, oiChange) pair is an implausible print that folds should skip. */
  public boolean isOutlier(Long oi, Long oiChange) {
    if (!enabled) {
      return false;
    }
    if (oi != null && oi < 0) {
      return true;
    }
    if (oi == null || oiChange == null) {
      return false;
    }
    long prior = oi - oiChange;
    if (prior < minPriorOi) {
      return false;
    }
    return Math.abs((double) oiChange) > jumpFactor * prior;
  }
}
