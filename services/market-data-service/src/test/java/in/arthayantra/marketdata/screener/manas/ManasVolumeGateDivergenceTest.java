package in.arthayantra.marketdata.screener.manas;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.screener.manas.ManasAroraSwingBacktest.BtTrade;
import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpDetector;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M8 (#128 batch scoping): "Manas live volume gate 20-day vs backtest 50-day — different trade
 * populations." Confirmed still true — the live and backtest paths enforce two DIFFERENT volume
 * concepts entirely: live's {@code ManasGates.liquidVolume} is the doctrine's §4.3 ABSOLUTE
 * liquidity veto (20-day average volume must clear a fixed floor,
 * {@code MomentumTradingManasArora_Consolidated_Strategy.md:234-236}), while the backtest's own
 * {@code volumeRatio(volume, 50)} vs {@code VOL_MIN} is a BACKTEST-ONLY expanding-volume-on-breakout
 * RATIO filter (today's volume vs its own 50-day trailing average) with UNVERIFIED DOCTRINE
 * PROVENANCE — {@code ManasAroraSwingBacktest.java} previously labelled it "§4.7", which is WRONG:
 * the doctrine's own §4.7 is "EOD Workflow & Timeframes" ({@code :259}), and its only volume rule
 * (§4.3) has no breakout-expansion ratio at all. Corrected here (cross-vendor review, 2026-08-02) so
 * this test never repeats that confident-but-wrong citation. {@code
 * ManasAroraSwingBacktest.selectionGates} never calls {@code ManasGates.liquidVolume} at all.
 *
 * <p>This is a CHARACTERIZATION, not a fix — like M6/M9, reconciling the two gates is a
 * live-population / backtest-methodology change (HOLD-tier, the same class as M36/M37) and is
 * explicitly out of this slice.
 *
 * <p>Two kinds of evidence: {@link #aThinNameWithATodaySpikePassesTheBacktestRatioGateButFailsTheLiveAbsoluteFloor}
 * and its sibling drive the LEAF formulas ({@link ManasGates#liquidVolume},
 * {@link ManasAroraSwingBacktest#volumeRatio}) directly for a precise, numeric before/after — this
 * matches every real call site verified by reading {@code entryFires}'s own comparison
 * ({@code if (volRatio50[i] <= volMin) return false;}, i.e. pass iff {@code ratio > VOL_MIN}, exactly
 * what these tests compute. {@link #theRealEntryPipelineTakesTheBreakoutOnAVolumeSpikeButSkipsItWithoutOne}
 * and {@link #theRealEntryPipelineTakesAThinNameThatWouldFailLiveButClearsTheRatioGate} go one step
 * further and drive the divergence through the PRODUCTION entry point itself
 * ({@link ManasAroraSwingBacktest#simulate}), not a leaf formula in isolation — proving the
 * backtest-only filter is a real, live wire in the entry funnel, not merely a comparison this test
 * happens to reproduce.
 */
class ManasVolumeGateDivergenceTest {

  private static final BigDecimal MIN_AVG_VOLUME = new BigDecimal("5000"); // live default (§4.3)
  private static final int LOOKBACK = 50; // the backtest-only filter's window (no doctrine citation)

  @Test
  void aThinNameWithATodaySpikePassesTheBacktestRatioGateButFailsTheLiveAbsoluteFloor() {
    // 50 quiet days at 2,500 shares/day (a small/illiquid name), then a one-day spike to 4,200 —
    // ~1.68x its own 50-day average, comfortably above VOL_MIN (1.2) so the backtest-only
    // expanding-volume filter passes it. But the live 20-day AVERAGE volume (dominated by the same
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
    assertThat(backtestPasses).as("the backtest-only expanding-volume filter").isTrue();
    assertThat(avgVolume20).as("live 20-day average volume").isEqualByComparingTo("2585");
    assertThat(livePasses).as("live's §4.3 absolute liquidity floor").isFalse();
  }

  @Test
  void aLiquidSteadyNameWithNoExpansionPassesTheLiveFloorButFailsTheBacktestRatioGate() {
    // 51 days flat at 50,000 shares/day — comfortably liquid by the live 20-day absolute floor, but
    // NO expansion at all (ratio == 1.0), so the backtest-only filter (which needs > VOL_MIN = 1.2)
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
    assertThat(backtestPasses).as("the backtest-only expanding-volume filter").isFalse();
    assertThat(avgVolume20).as("live 20-day average volume").isEqualByComparingTo("50000");
    assertThat(livePasses).as("live's §4.3 absolute liquidity floor").isTrue();
  }

  @Test
  void theRealEntryPipelineTakesTheBreakoutOnAVolumeSpikeButSkipsItWithoutOne() {
    // The SAME proven Stage-2 uptrend -> consolidation -> fresh-high-breakout shape
    // ManasAroraSwingBacktestTest.breakoutSeries() uses (256 uptrend + 12 consolidation + breakout
    // tail on 3x volume) — driven through the REAL production entry point
    // (ManasAroraSwingBacktest.simulate), not the leaf volumeRatio formula in isolation.
    ManasAroraSwingBacktest sim = new ManasAroraSwingBacktest();
    VcpDetector vcp = new VcpDetector(2.5, 2, 6, 0.2, 0.9, 0.5, 0.5, 100, 40, 60, 0, 65);
    ConsolidationBreakout breakout = new ConsolidationBreakout(2.5, 10, 40, 25);
    LocalDate entryFrom = LocalDate.of(2020, 1, 1).plusDays(264);

    List<BtTrade> withSpike =
        sim.simulate("TESTCO", breakoutPanel(1_000, 3_000), vcp, breakout, entryFrom);
    assertThat(withSpike.stream().filter(t -> t.setup().equals("breakout")))
        .as("ratio ~3.0 clears VOL_MIN in the REAL pipeline — same shape the existing smoke test"
            + " (ManasAroraSwingBacktestTest.breakoutTakesATradeAndExitsOnTheRollover) proves fires")
        .isNotEmpty();

    List<BtTrade> noSpike =
        sim.simulate("TESTCO", breakoutPanel(1_000, 1_000), vcp, breakout, entryFrom);
    assertThat(noSpike.stream().filter(t -> t.setup().equals("breakout")))
        .as("identical geometry, but NO volume expansion (ratio ~1.0) — the real pipeline takes"
            + " NO breakout trade at all, proving the backtest-only filter genuinely blocks entries"
            + " here, not just in a standalone formula call")
        .isEmpty();
  }

  @Test
  void theRealEntryPipelineTakesAThinNameThatWouldFailLiveButClearsTheRatioGate() {
    // Same shape, scaled down to a name FAR below live's 5,000-share absolute floor (avg 50/day,
    // spike to 150 -> same 3x ratio). The backtest's ratio math cares only about relative expansion,
    // so this thin name clears the REAL entry pipeline exactly as readily as a liquid one — while
    // live's ManasGates.liquidVolume would reject it outright. This is the M8 divergence proven
    // through the actual production call site end-to-end, not a hypothetical formula comparison.
    ManasAroraSwingBacktest sim = new ManasAroraSwingBacktest();
    VcpDetector vcp = new VcpDetector(2.5, 2, 6, 0.2, 0.9, 0.5, 0.5, 100, 40, 60, 0, 65);
    ConsolidationBreakout breakout = new ConsolidationBreakout(2.5, 10, 40, 25);
    LocalDate entryFrom = LocalDate.of(2020, 1, 1).plusDays(264);
    List<DailyBar> thinPanel = breakoutPanel(50, 150);

    List<BtTrade> trades = sim.simulate("TESTCO", thinPanel, vcp, breakout, entryFrom);

    assertThat(trades.stream().filter(t -> t.setup().equals("breakout")))
        .as("the real backtest entry pipeline takes this trade despite volume far below the live"
            + " absolute floor")
        .isNotEmpty();
    double[] volumes = thinPanel.stream().mapToDouble(DailyBar::volume).toArray();
    BigDecimal avgVolume20 = trailingAverage(volumes, volumes.length - 1, 20);
    assertThat(ManasGates.liquidVolume(avgVolume20, MIN_AVG_VOLUME))
        .as("but live's own §4.3 absolute floor would reject this same name (avg ~%s)".formatted(avgVolume20))
        .isFalse();
  }

  /**
   * The proven {@code ManasAroraSwingBacktestTest.breakoutSeries()} shape (256-session uptrend from
   * 80 to 185, a 12-session consolidation, then a fresh-high breakout tail to 195/197/190/178/170),
   * with the base volume and the breakout-bar volume both parameterised so the SAME geometry can be
   * driven at different absolute liquidity levels while holding the expansion ratio fixed.
   */
  private static List<DailyBar> breakoutPanel(long baseVolume, long breakoutBarVolume) {
    List<DailyBar> bars = new ArrayList<>();
    for (int i = 0; i < 256; i++) {
      bars.add(bar(i, 80.0 + 105.0 * i / 255.0, baseVolume));
    }
    double[] base = {182, 179, 183, 180, 182, 178, 183, 181, 182, 179, 183, 180};
    for (int i = 0; i < base.length; i++) {
      bars.add(bar(256 + i, base[i], baseVolume));
    }
    double[] tail = {195, 197, 190, 178, 170};
    long[] vol = {breakoutBarVolume, baseVolume, baseVolume, baseVolume, baseVolume};
    for (int i = 0; i < tail.length; i++) {
      bars.add(bar(268 + i, tail[i], vol[i]));
    }
    return bars;
  }

  private static DailyBar bar(int day, double price, long volume) {
    LocalDate d = LocalDate.of(2020, 1, 1).plusDays(day);
    return new DailyBar(d, price, price, price, price, volume);
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
