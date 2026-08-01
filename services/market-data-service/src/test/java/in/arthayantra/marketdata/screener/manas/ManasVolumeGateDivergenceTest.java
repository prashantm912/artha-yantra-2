package in.arthayantra.marketdata.screener.manas;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * M8 (#128 batch scoping): "Manas live volume gate 20-day vs backtest 50-day — different trade
 * populations." Confirmed still true — the live and backtest paths are not merely different
 * WINDOWS of the same doctrine rule, they enforce two DIFFERENT §4 concepts entirely: live's {@code
 * ManasGates.liquidVolume} is the §4.3 ABSOLUTE liquidity veto (20-day average volume must clear a
 * fixed floor), while the backtest's own {@code volumeRatio(volume, 50)} vs {@code VOL_MIN} is the
 * §4.7 expanding-volume-on-breakout RATIO gate (today's volume vs its own 50-day trailing average).
 * {@code ManasAroraSwingBacktest.selectionGates} never calls {@code ManasGates.liquidVolume} at all.
 *
 * <p>This is a CHARACTERIZATION, not a fix — like M6/M9, reconciling the two gates is a
 * live-population / backtest-methodology change (HOLD-tier, the same class as M36/M37) and is
 * explicitly out of this slice. Both scenarios below drive the ACTUAL production formulas
 * ({@link ManasGates#liquidVolume} and {@link ManasAroraSwingBacktest#volumeRatio}) on the same
 * synthetic volume series and show the divergence is not hypothetical: it flips in BOTH directions.
 */
class ManasVolumeGateDivergenceTest {

  private static final BigDecimal MIN_AVG_VOLUME = new BigDecimal("5000"); // live default (§4.3)
  private static final int LOOKBACK = 50; // backtest's §4.7 window

  @Test
  void aThinNameWithATodaySpikePassesTheBacktestRatioGateButFailsTheLiveAbsoluteFloor() {
    // 50 quiet days at 2,500 shares/day (a small/illiquid name), then a one-day spike to 4,200 —
    // ~1.68x its own 50-day average, comfortably above VOL_MIN (1.2) so the backtest's §4.7
    // expanding-volume check passes it. But the live 20-day AVERAGE volume (dominated by the same
    // 49 quiet days plus one spike) is still only ~2,585 shares — well under the live §4.3 floor.
    double[] volume = new double[LOOKBACK + 1];
    for (int i = 0; i < LOOKBACK; i++) {
      volume[i] = 2_500;
    }
    volume[LOOKBACK] = 4_200; // today's spike

    double[] ratio = ManasAroraSwingBacktest.volumeRatio(volume, LOOKBACK);
    boolean backtestPasses = ratio[LOOKBACK] > ManasAroraSwingBacktest.VOL_MIN;

    BigDecimal avgVolume20 = trailingAverage(volume, LOOKBACK, 20);
    boolean livePasses = ManasGates.liquidVolume(avgVolume20, MIN_AVG_VOLUME);

    assertThat(ratio[LOOKBACK]).as("today's spike ratio").isCloseTo(1.68, org.assertj.core.data.Offset.offset(0.01));
    assertThat(backtestPasses).as("backtest's §4.7 expanding-volume gate").isTrue();
    assertThat(avgVolume20).as("live 20-day average volume").isEqualByComparingTo("2585");
    assertThat(livePasses).as("live's §4.3 absolute liquidity floor").isFalse();
  }

  @Test
  void aLiquidSteadyNameWithNoExpansionPassesTheLiveFloorButFailsTheBacktestRatioGate() {
    // 51 days flat at 50,000 shares/day — comfortably liquid by the live 20-day absolute floor, but
    // NO expansion at all (ratio == 1.0), so the backtest's §4.7 gate (which needs > VOL_MIN = 1.2)
    // rejects the exact same day as a non-entry.
    double[] volume = new double[LOOKBACK + 1];
    for (int i = 0; i <= LOOKBACK; i++) {
      volume[i] = 50_000;
    }

    double[] ratio = ManasAroraSwingBacktest.volumeRatio(volume, LOOKBACK);
    boolean backtestPasses = ratio[LOOKBACK] > ManasAroraSwingBacktest.VOL_MIN;

    BigDecimal avgVolume20 = trailingAverage(volume, LOOKBACK, 20);
    boolean livePasses = ManasGates.liquidVolume(avgVolume20, MIN_AVG_VOLUME);

    assertThat(ratio[LOOKBACK]).as("steady-volume ratio").isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(backtestPasses).as("backtest's §4.7 expanding-volume gate").isFalse();
    assertThat(avgVolume20).as("live 20-day average volume").isEqualByComparingTo("50000");
    assertThat(livePasses).as("live's §4.3 absolute liquidity floor").isTrue();
  }

  /** The trailing {@code window}-day average ending at (and including) index {@code i}. */
  private static BigDecimal trailingAverage(double[] v, int i, int window) {
    double sum = 0;
    for (int j = i - window + 1; j <= i; j++) {
      sum += v[j];
    }
    return BigDecimal.valueOf(sum / window);
  }
}
