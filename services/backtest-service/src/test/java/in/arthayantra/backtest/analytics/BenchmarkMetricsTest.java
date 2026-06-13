package in.arthayantra.backtest.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.backtest.analytics.BenchmarkMetrics.Result;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * §D.16 benchmark-relative metrics on hand-computed return series — exact decimal strings (no
 * epsilon), plus the all-up / all-down capture edges and the degenerate-regression NULL path.
 */
class BenchmarkMetricsTest {

  @Test
  void handComputedAlphaBetaIrAndCapture() {
    // strat=[0.30,0.10] bench=[0.10,0.00] ppy=4; meanS=0.20 meanB=0.05.
    // beta = cov/varB = 0.005/0.0025 = 2; alpha = (0.20 - 2*0.05)*4 = 0.40.
    // active=[0.20,0.10] meanA=0.15 std=0.05; IR = 0.15/0.05*sqrt(4) = 6.
    // up partition = {0.10}: (0.30)/(0.10) = 3; down partition empty -> null.
    Result r =
        BenchmarkMetrics.compute(
            new double[] {0.30, 0.10},
            new double[] {0.10, 0.00},
            4.0,
            new BigDecimal("0.25"),
            new BigDecimal("0.10"));

    assertThat(r.alpha().toPlainString()).isEqualTo("0.400000");
    assertThat(r.beta().toPlainString()).isEqualTo("2.000000");
    assertThat(r.informationRatio().toPlainString()).isEqualTo("6.000000");
    assertThat(r.excessCagr().toPlainString()).isEqualTo("0.150000");
    assertThat(r.upCapture().toPlainString()).isEqualTo("3.000000");
    assertThat(r.downCapture()).isNull();
  }

  @Test
  void allDownWindowLeavesUpCaptureNull() {
    Result r =
        BenchmarkMetrics.compute(
            new double[] {-0.20, -0.05},
            new double[] {-0.10, -0.05},
            252.0,
            new BigDecimal("-0.30"),
            new BigDecimal("-0.10"));

    assertThat(r.upCapture()).isNull();
    assertThat(r.downCapture()).isNotNull();
    assertThat(r.excessCagr().toPlainString()).isEqualTo("-0.200000");
  }

  @Test
  void zeroBenchmarkVarianceLeavesAlphaBetaNullButIrComputable() {
    // bench constant -> varB=0 -> beta/alpha undefined; active still has dispersion -> IR defined.
    Result r =
        BenchmarkMetrics.compute(
            new double[] {0.10, 0.20},
            new double[] {0.05, 0.05},
            4.0,
            new BigDecimal("0.15"),
            new BigDecimal("0.05"));

    assertThat(r.beta()).isNull();
    assertThat(r.alpha()).isNull();
    assertThat(r.informationRatio().toPlainString()).isEqualTo("4.000000");
  }

  @Test
  void fewerThanTwoPointsYieldsOnlyExcessCagr() {
    Result r =
        BenchmarkMetrics.compute(
            new double[] {0.10},
            new double[] {0.05},
            252.0,
            new BigDecimal("0.10"),
            new BigDecimal("0.04"));

    assertThat(r.beta()).isNull();
    assertThat(r.informationRatio()).isNull();
    assertThat(r.excessCagr().toPlainString()).isEqualTo("0.060000");
  }
}
