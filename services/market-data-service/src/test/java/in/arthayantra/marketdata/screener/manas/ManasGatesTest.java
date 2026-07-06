package in.arthayantra.marketdata.screener.manas;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The Manas Arora selection-filter gates (§4.1 six-criteria + §1.2 universe + §4.3 liquidity + §4.4
 * float). Synthetic numbers, no IO. A clear-pass name clears all six selection gates + the
 * within-high universe gate + both liquidity checks; a clear-fail name (cheap penny, below its
 * 200-SMA, dead volume, near its 52-week low) fails them.
 */
class ManasGatesTest {

  // Config-default thresholds (mirror the @Value defaults).
  private static final BigDecimal MIN_PRICE = new BigDecimal("30");
  private static final BigDecimal PCT_ABOVE_LOW = new BigDecimal("100"); // >= 100% above 52w low
  private static final BigDecimal WITHIN_HIGH = new BigDecimal("25"); // within 25% of 52w high
  private static final BigDecimal MIN_AVG_VOLUME = new BigDecimal("5000");
  private static final BigDecimal LIQUIDITY_FLOOR = new BigDecimal("2500"); // 100 qty * 25 multiple
  private static final BigDecimal MAX_FF_MCAP_CR = new BigDecimal("5000");
  private static final BigDecimal MAX_FF_PCT = new BigDecimal("35");

  @Test
  void clearPassClearsAllSixGatesPlusUniverseAndLiquidity() {
    // A leadership low-cap: close 220, above a rising 200-SMA (150) with 50-SMA (180) > 200-SMA,
    // >100% above its 52w low (90 -> 220 is +144%), 8% below its 52w high (240, within 25%), and it
    // just made a new 52w high (recentHigh 240 == high52w). Volume 200k (liquid).
    BigDecimal close = bd(220);
    BigDecimal sma50 = bd(180);
    BigDecimal sma200 = bd(150);
    BigDecimal sma200Ago = bd(140); // 200-SMA rising
    BigDecimal high52w = bd(240);
    BigDecimal low52w = bd(90);
    BigDecimal recentHigh = bd(240); // new 52w high within the lookback

    boolean[] g =
        ManasGates.gates(
            close, sma50, sma200, sma200Ago, high52w, low52w, recentHigh, MIN_PRICE, PCT_ABOVE_LOW);

    assertThat(g).containsExactly(true, true, true, true, true, true);
    assertThat(ManasGates.passed(g)).isEqualTo(6);
    assertThat(ManasGates.withinHigh(close, high52w, WITHIN_HIGH)).isTrue();
    assertThat(ManasGates.aboveSma50(close, sma50)).isTrue(); // preferably-above-50 soft flag
    assertThat(ManasGates.liquidVolume(bd(200_000), MIN_AVG_VOLUME)).isTrue();
    assertThat(ManasGates.liquidDepth(bd(200_000), LIQUIDITY_FLOOR)).isTrue();
    // Low-cap gate: a small free-float (800 cr, 22% free float) passes; and an UNKNOWN cap also passes.
    assertThat(ManasGates.lowCap(bd(800), bd(22), MAX_FF_MCAP_CR, MAX_FF_PCT)).isTrue();
    assertThat(ManasGates.lowCap(null, null, MAX_FF_MCAP_CR, MAX_FF_PCT)).isTrue();
  }

  @Test
  void clearFailFailsEveryGate() {
    // A sick penny stock: close 12 (< 30), below a FALLING 200-SMA (20 -> was 25), 50-SMA (15) below
    // the 200-SMA, only +20% off its 52w low (10 -> 12), and 60% below its 52w high (30). No new
    // high (recentHigh 18 < high52w 30). Dead volume (1,200 shares), near-illiquid.
    BigDecimal close = bd(12);
    BigDecimal sma50 = bd(15);
    BigDecimal sma200 = bd(20);
    BigDecimal sma200Ago = bd(25); // 200-SMA FALLING
    BigDecimal high52w = bd(30);
    BigDecimal low52w = bd(10);
    BigDecimal recentHigh = bd(18); // no new 52w high

    boolean[] g =
        ManasGates.gates(
            close, sma50, sma200, sma200Ago, high52w, low52w, recentHigh, MIN_PRICE, PCT_ABOVE_LOW);

    assertThat(g).containsExactly(false, false, false, false, false, false);
    assertThat(ManasGates.passed(g)).isZero();
    assertThat(ManasGates.withinHigh(close, high52w, WITHIN_HIGH)).isFalse(); // 60% below the high
    assertThat(ManasGates.aboveSma50(close, sma50)).isFalse();
    assertThat(ManasGates.liquidVolume(bd(1_200), MIN_AVG_VOLUME)).isFalse(); // dead volume veto
    assertThat(ManasGates.liquidDepth(bd(1_200), LIQUIDITY_FLOOR)).isFalse();
  }

  @Test
  void lowCapGateRejectsKnownLargeCapButKeepsUnknown() {
    // A known large-cap (12,000 cr free-float, 60% free float) is dropped when the gate is applied;
    // an unknown cap is kept (never a silent drop when the fundamentals feed is absent).
    assertThat(ManasGates.lowCap(bd(12_000), bd(60), MAX_FF_MCAP_CR, MAX_FF_PCT)).isFalse();
    assertThat(ManasGates.lowCap(bd(4_000), bd(60), MAX_FF_MCAP_CR, MAX_FF_PCT))
        .isFalse(); // small cap but too-high free-float %
    assertThat(ManasGates.lowCap(bd(4_000), bd(28), MAX_FF_MCAP_CR, MAX_FF_PCT))
        .isTrue(); // genuine low-cap
    assertThat(ManasGates.lowCap(null, null, MAX_FF_MCAP_CR, MAX_FF_PCT)).isTrue(); // unknown kept
  }

  @Test
  void weightedRsBlendsTrailingReturnsAndTreatsNullLagsAsZero() {
    // All four lookbacks doubled (ret = 1.0 each): 0.4·1 + 0.2·1 + 0.2·1 + 0.2·1 = 1.0.
    assertThat(ManasGates.weightedRs(bd(200), bd(100), bd(100), bd(100), bd(100)))
        .isEqualByComparingTo("1.0");
    // A null/edge lag contributes 0 (ManasGates.ret returns 0): only the 3-month leg counts → 0.4·1.0.
    assertThat(ManasGates.weightedRs(bd(200), bd(100), null, null, null)).isEqualByComparingTo("0.4");
    // A stronger-momentum name ranks above a weaker one (the cross-sectional order the RS-rank needs).
    BigDecimal strong = ManasGates.weightedRs(bd(300), bd(100), bd(120), bd(140), bd(150));
    BigDecimal weak = ManasGates.weightedRs(bd(110), bd(100), bd(105), bd(108), bd(109));
    assertThat(strong).isGreaterThan(weak);
  }

  private static BigDecimal bd(long v) {
    return BigDecimal.valueOf(v);
  }
}
