package in.arthayantra.marketdata.corporateactions;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** B-17 uniform-ratio detector: planted split detected, noise rejected, boundary cases. */
class CorporateActionDetectorTest {

  private static CorporateActionDetector.AnchorEvidence anchor(
      String date, String cached, String kite) {
    return CorporateActionDetector.AnchorEvidence.of(
        LocalDate.parse(date), new BigDecimal(cached), new BigDecimal(kite));
  }

  @Test
  void plantedOneToFiveSplitIsDetectedWithItsUniformRatio() {
    // Kite re-serves pre-event history divided by 5 → cached/kite = 5.0 uniformly
    List<CorporateActionDetector.AnchorEvidence> anchors =
        List.of(
            anchor("2026-06-08", "100.00", "100.00"), // post-event anchor agrees
            anchor("2026-05-15", "500.00", "100.00"),
            anchor("2026-03-13", "450.00", "90.00"),
            anchor("2025-12-15", "400.00", "80.00"));

    Optional<CorporateActionDetector.Detection> detection =
        CorporateActionDetector.evaluate(anchors, 0.005, 0.01);

    assertThat(detection).isPresent();
    assertThat(detection.get().uniformRatio()).isEqualByComparingTo("5.00000000");
    assertThat(detection.get().anchorsDiverged()).isEqualTo(3);
    assertThat(detection.get().effectiveBoundary())
        .as("approximate ex-date = most recent diverged anchor")
        .isEqualTo(LocalDate.parse("2026-05-15"));
  }

  @Test
  void singleAnchorDivergenceNeverTriggers() {
    List<CorporateActionDetector.AnchorEvidence> anchors =
        List.of(
            anchor("2026-06-08", "100.00", "100.00"),
            anchor("2026-05-15", "130.00", "100.00")); // one bad anchor — noise

    assertThat(CorporateActionDetector.evaluate(anchors, 0.005, 0.01)).isEmpty();
  }

  @Test
  void nonUniformDivergenceIsNoiseNotBackAdjustment() {
    List<CorporateActionDetector.AnchorEvidence> anchors =
        List.of(
            anchor("2026-05-15", "180.00", "100.00"), // ratio 1.8
            anchor("2026-03-13", "260.00", "100.00")); // ratio 2.6 — not a uniform factor

    assertThat(CorporateActionDetector.evaluate(anchors, 0.005, 0.01)).isEmpty();
  }

  @Test
  void toleranceBoundaryIsRespected() {
    // 0.4% off on both anchors — inside the 0.5% tolerance, no divergence at all
    List<CorporateActionDetector.AnchorEvidence> anchors =
        List.of(
            anchor("2026-05-15", "100.40", "100.00"),
            anchor("2026-03-13", "100.40", "100.00"));

    assertThat(CorporateActionDetector.evaluate(anchors, 0.005, 0.01)).isEmpty();
  }
}
