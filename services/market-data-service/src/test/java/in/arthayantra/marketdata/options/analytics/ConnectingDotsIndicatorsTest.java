package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit coverage for the Connecting Dots local indicators + the composite net→trend mapping. */
class ConnectingDotsIndicatorsTest {

  @Test
  void rsiWarmsUpNaNThenRisesOnAnUptrend() {
    double[] closes = new double[30];
    for (int i = 0; i < closes.length; i++) {
      closes[i] = 100 + i; // strictly rising → RSI → 100 (all gains)
    }
    double[] rsi = ConnectingDotsIndicators.rsi(closes, 14);
    for (int i = 0; i < 14; i++) {
      assertThat(rsi[i]).as("warmup bar %d is NaN", i).isNaN();
    }
    assertThat(rsi[14]).isFinite().isGreaterThan(99.0); // all-up → ~100
  }

  @Test
  void rsiFallsOnADowntrend() {
    double[] closes = new double[30];
    for (int i = 0; i < closes.length; i++) {
      closes[i] = 200 - i;
    }
    double[] rsi = ConnectingDotsIndicators.rsi(closes, 14);
    assertThat(rsi[20]).isLessThan(1.0); // all-down → ~0
  }

  @Test
  void vwapEqualsTypicalForConstantBars() {
    int n = 5;
    double[] h = new double[n];
    double[] l = new double[n];
    double[] c = new double[n];
    double[] v = new double[n];
    for (int i = 0; i < n; i++) {
      h[i] = 110;
      l[i] = 90;
      c[i] = 100;
      v[i] = 1000;
    }
    double[] vwap = ConnectingDotsIndicators.vwap(h, l, c, v); // typical = (110+90+100)/3 = 100
    assertThat(vwap[n - 1]).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void supertrendTurnsUpOnAStrongUptrend() {
    int n = 40;
    double[] h = new double[n];
    double[] l = new double[n];
    double[] c = new double[n];
    for (int i = 0; i < n; i++) {
      double base = 100 + i * 2.0;
      h[i] = base + 1;
      l[i] = base - 1;
      c[i] = base;
    }
    int[] dir = ConnectingDotsIndicators.supertrendDirection(h, l, c, 10, 3.0);
    assertThat(dir[n - 1]).as("strong uptrend → +1").isEqualTo(1);
  }

  @Test
  void compositeMapsNetVoteToTheFittedFiveState() {
    // 11 factors: 1=Bullish, 2=Bearish, 0=Neutral
    assertThat(ConnectingDotsService.composite(new int[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}))
        .as("net +11 → Ext.Bullish")
        .isEqualTo(1);
    assertThat(ConnectingDotsService.composite(new int[] {1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0}))
        .as("net +5 → Bullish")
        .isEqualTo(2);
    assertThat(ConnectingDotsService.composite(new int[] {1, 2, 1, 2, 0, 0, 0, 0, 0, 0, 0}))
        .as("net 0 → Bearish (the fitted asymmetry)")
        .isEqualTo(3);
    assertThat(ConnectingDotsService.composite(new int[] {2, 2, 2, 2, 2, 2, 0, 0, 0, 0, 0}))
        .as("net -6 → Ext.Bearish")
        .isEqualTo(4);
  }
}
