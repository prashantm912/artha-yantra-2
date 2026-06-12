package in.arthayantra.marketdata.corporateactions;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The B-17 uniform-ratio test, pure and clock-free: divergence beyond tolerance on ≥ 2 anchors
 * whose cached÷Kite ratios agree (relative stddev below epsilon) ⇒ corporate action — what
 * distinguishes back-adjustment (all pre-event anchors off by the same factor) from noise.
 * Single-anchor divergence NEVER triggers.
 */
public final class CorporateActionDetector {

  /** One anchor comparison. */
  public record AnchorEvidence(
      LocalDate date, BigDecimal cachedClose, BigDecimal kiteClose, BigDecimal ratio) {

    /** Builds evidence with the ratio computed. */
    public static AnchorEvidence of(LocalDate date, BigDecimal cached, BigDecimal kite) {
      BigDecimal ratio =
          kite.signum() == 0 ? BigDecimal.ZERO : cached.divide(kite, MathContext.DECIMAL64);
      return new AnchorEvidence(date, cached, kite, ratio);
    }
  }

  /** A confirmed detection. */
  public record Detection(
      BigDecimal uniformRatio,
      LocalDate effectiveBoundary,
      int anchorsChecked,
      int anchorsDiverged,
      List<AnchorEvidence> diverged) {}

  private CorporateActionDetector() {}

  /**
   * Evaluates one symbol's anchors. {@code tolerance} is relative (default 0.005); {@code
   * epsilon} bounds the relative stddev of diverged ratios (uniformity).
   */
  public static Optional<Detection> evaluate(
      List<AnchorEvidence> anchors, double tolerance, double epsilon) {
    List<AnchorEvidence> diverged =
        anchors.stream()
            .filter(a -> a.kiteClose() != null && a.kiteClose().signum() > 0)
            .filter(a -> Math.abs(a.ratio().doubleValue() - 1.0) > tolerance)
            .toList();
    if (diverged.size() < 2) {
      return Optional.empty(); // single-anchor noise never remediates (B-17)
    }
    double mean =
        diverged.stream().mapToDouble(a -> a.ratio().doubleValue()).average().orElse(0);
    double variance =
        diverged.stream()
            .mapToDouble(a -> Math.pow(a.ratio().doubleValue() - mean, 2))
            .average()
            .orElse(0);
    double relativeStddev = mean == 0 ? Double.MAX_VALUE : Math.sqrt(variance) / Math.abs(mean);
    if (relativeStddev >= epsilon) {
      return Optional.empty(); // diverged but NOT uniform — random noise, not back-adjustment
    }
    // the approximate ex-date: the most recent anchor still showing divergence
    LocalDate boundary =
        diverged.stream().map(AnchorEvidence::date).max(LocalDate::compareTo).orElse(null);
    return Optional.of(
        new Detection(
            BigDecimal.valueOf(mean).setScale(8, RoundingMode.HALF_UP),
            boundary,
            anchors.size(),
            diverged.size(),
            diverged));
  }
}
