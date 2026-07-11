package in.arthayantra.strategysignal.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests of the priority model (INT design §3.2): the weighted-component × trust-cap math,
 * band assignment, clamps, and the BLOCKED / neutral-fallback rules. No Spring context.
 */
class PriorityModelTest {

  private final PriorityModel model = new PriorityModel(InsightProperties.Priority.defaults());

  /** A scalper signal with the two cross-module inputs (track, risk) at their neutral priors. */
  private SignalPriorityInputs scalper(DataTrust trust, long ageSeconds) {
    return new SignalPriorityInputs(
        42L, "scalper", "NFO", "NIFTY25200CE", "CE", new BigDecimal("25200"),
        new BigDecimal("0.81"), new BigDecimal("0.72"), // composite / threshold → edge 0.9
        new BigDecimal("0.75"), null, null, // scalper confluence 0.75
        null, null, null, false, // track: closedTrades null → neutral 0.5
        null, null, false, null, // risk: heat null → neutral 0.5
        ageSeconds, null, trust, List.of(), "2026-07-12T09:47:12+05:30");
  }

  @Test
  void weightsBandsAndClampsFollowTheSpecFormula() {
    // edge 0.9 (27) + context 0.75 (15) + track 0.5 (10) + risk 0.5 (7.5) + freshness 1.0 (15) = 74.5
    PriorityModel.Result r = model.evaluate(scalper(DataTrust.OK, 0));
    assertThat(r.blocked()).isFalse();
    assertThat(r.score()).isEqualByComparingTo("74.50");
    assertThat(r.band()).isEqualTo("B");
    assertThat(r.trustCap()).isEqualByComparingTo("1.0");
    assertThat(r.detail().components()).extracting(PriorityDetail.Component::key)
        .containsExactly("edge", "context", "track", "risk", "freshness");
  }

  @Test
  void degradedTrustMechanicallyCapsTheScore() {
    PriorityModel.Result r = model.evaluate(scalper(DataTrust.DEGRADED, 0));
    // 74.50 × 0.79 = 58.855 → 58.86 (C band): DEGRADED cannot reach A even from a strong raw score.
    assertThat(r.score()).isEqualByComparingTo("58.86");
    assertThat(r.band()).isEqualTo("C");
    assertThat(r.trustCap()).isEqualByComparingTo("0.79");
  }

  @Test
  void blockedTrustYieldsNoScore() {
    PriorityModel.Result r = model.evaluate(scalper(DataTrust.BLOCKED, 0));
    assertThat(r.blocked()).isTrue();
    assertThat(r.score()).isNull();
    assertThat(r.detail()).isNull();
  }

  @Test
  void edgeMarginClampsAtBandTop() {
    // composite − threshold = 0.30, band 0.10 → raw 3.0, clamped to c=1.0 (edge points 30).
    SignalPriorityInputs in =
        new SignalPriorityInputs(
            1L, "scalper", "NFO", "X", "CE", null, new BigDecimal("1.00"), new BigDecimal("0.70"),
            new BigDecimal("0.50"), null, null, null, null, null, false, null, null, false, null,
            0L, null, DataTrust.OK, List.of(), "asof");
    PriorityDetail.Component edge = model.evaluate(in).detail().components().get(0);
    assertThat(edge.c()).isEqualByComparingTo("1.0000");
    assertThat(edge.points()).isEqualByComparingTo("30.00");
  }

  @Test
  void zeroAdvisedLotsZeroesRiskComponent() {
    SignalPriorityInputs in =
        new SignalPriorityInputs(
            1L, "scalper", "NFO", "X", "CE", null, new BigDecimal("0.80"), new BigDecimal("0.72"),
            new BigDecimal("0.60"), null, null, null, null, null, false,
            new BigDecimal("40"), new BigDecimal("100"), false, 0, // advisedLots 0 → risk 0
            0L, null, DataTrust.OK, List.of(), "asof");
    PriorityDetail.Component risk = model.evaluate(in).detail().components().get(3);
    assertThat(risk.c()).isEqualByComparingTo("0.0000");
  }

  @Test
  void swingRsFallsBackToNeutralWithNote() {
    SignalPriorityInputs in =
        new SignalPriorityInputs(
            7L, "swing", "NSE", "SBCL", "BUY", null, new BigDecimal("0.80"), new BigDecimal("0.72"),
            null, null, "RS unavailable on API", null, null, null, false, null, null, false, null,
            0L, null, DataTrust.OK, List.of(), "asof");
    PriorityDetail.Component context = model.evaluate(in).detail().components().get(1);
    assertThat(context.c()).isEqualByComparingTo("0.5000");
    assertThat(context.evidence().get(0).value()).contains("RS unavailable");
  }
}
