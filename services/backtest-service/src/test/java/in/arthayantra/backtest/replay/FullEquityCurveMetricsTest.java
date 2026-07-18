package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.golden.GoldenCandleCsv;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AY-SL-01: risk metrics (Sharpe/Sortino/maxDD, every path/risk metric) must be computed on the FULL
 * mark-to-market equity curve, not the display-downsampled one. Before the fix {@code ReplayEngine}
 * stored {@code downsample(equityCurve, 500)} on the result and {@code BacktestRunner}/{@code
 * FoldEvaluator} fed THAT strided curve to {@link MetricsCalculator} — inflating Sharpe/Sortino
 * ≈√stride and understating maxDrawdown on a dense (&gt;500-point) run. The fix carries the full curve
 * on the in-memory result and downsamples only at the persistence boundary ({@code RunRepository}).
 */
class FullEquityCurveMetricsTest {

  private static final BigDecimal INITIAL = new BigDecimal("100000");
  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-05T09:15:00+05:30");

  private final MetricsCalculator calc = new MetricsCalculator(new ObjectMapper());
  private final ReplayEngine engine = new ReplayEngine(new ObjectMapper());

  @Test
  void fullCurveCapturesADrawdownAndCadenceThatDownsamplingCorrupts() {
    // A dense 1000-point curve that drifts gently UP except for a single deep 1-bar trough at an ODD
    // index. downsample(.,500) uses stride 2 and keeps indices {0,2,4,...,998} plus the last (999) —
    // so it SKIPS the odd-index trough entirely. The full curve captures the real ~60% drawdown; the
    // strided curve reads a near-monotonic rise (maxDD ≈ 0). This is exactly the AY-SL-01 corruption.
    List<EquityPoint> full = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      BigDecimal equity = i == 501 ? new BigDecimal("40000") : new BigDecimal(100000 + i);
      full.add(new EquityPoint(T0.plusMinutes(i), equity));
    }
    List<EquityPoint> down = EquityCurveDownsampler.downsample(full, 500);
    // sanity: the downsampler genuinely strips the trough this test relies on.
    assertThat(down).doesNotContain(full.get(501));

    Metrics onFull = calc.compute(List.of(), full, INITIAL, full.get(999).equity(), "1m", "1m", 1000L, 0L);
    Metrics onDown =
        calc.compute(
            List.of(), down, INITIAL, down.get(down.size() - 1).equity(), "1m", "1m", 1000L, 0L);

    // The FULL curve captures the ~60% drawdown; the downsampled curve misses it entirely.
    assertThat(onFull.maxDrawdown()).isGreaterThan(new BigDecimal("59"));
    assertThat(onDown.maxDrawdown()).isLessThan(new BigDecimal("1"));
    assertThat(onFull.maxDrawdown()).isGreaterThan(onDown.maxDrawdown());
    // Sharpe/Sortino also differ — the strided return series omits the trough's swings (wrong cadence).
    assertThat(onFull.sharpe()).isNotEqualByComparingTo(onDown.sharpe());
    assertThat(onFull.sortino()).isNotEqualByComparingTo(onDown.sortino());
  }

  @Test
  void replayCarriesTheFullEquityCurveNotADownsampledOne() throws IOException {
    // A real >500-bar replay (the frozen ema-crossover golden fixture is 5 days × 375 1m bars).
    Fixture fx = loadEmaCrossover();
    assertThat(fx.primary().size()).isGreaterThan(500); // guards the fixture is dense enough to matter

    ReplayResult result =
        engine.replay(
            fx.definition(), "NSE", "NIFTY 50", fx.primary(), Map.of(),
            new BigDecimal("1000000"), CostConfig.defaults(), true);

    // One mark-to-market point per primary bar — the FULL curve (pre-fix this was capped at ≤501).
    assertThat(result.equityCurve()).hasSize(fx.primary().size());
    assertThat(result.drawdownCurve()).hasSize(fx.primary().size());
    // The persistence boundary still bounds the STORED curve to ≤501 points (storage shape unchanged).
    assertThat(EquityCurveDownsampler.downsample(result.equityCurve(), 500))
        .hasSizeLessThanOrEqualTo(501);
  }

  @Test
  void sharpeSortinoAnnualizeAtTheCurveCadenceNotThePrimaryTimeframe() {
    // AY-SL-01 fix round 2: the replay equity curve is 1m-spaced REGARDLESS of primary timeframe
    // (the primary rolls up signals only), so a 3m-primary run's ratio metrics must annualize at the
    // curve's 1m cadence — annualizing the same 1m-spaced returns at 3m periodsPerYear understates
    // Sharpe/Sortino ≈√3 (1h ≈√60). tradeFrequency, by contrast, legitimately keys off the PRIMARY
    // timeframe (#785). This pins the split: wrong wiring in either direction fails.
    List<EquityPoint> curve = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      // 1m-spaced zigzag with genuine down-moves so sharpe/sortino are non-degenerate (non-zero).
      curve.add(new EquityPoint(T0.plusMinutes(i), new BigDecimal(100000 + i * 5 + (i % 3) * 40)));
    }
    BigDecimal fin = curve.get(999).equity();
    List<Trade> trades =
        List.of(closedTrade(1, "100", "110", "10"), closedTrade(1, "100", "110", "10"));

    Metrics m3mPrimary = calc.compute(trades, curve, INITIAL, fin, "3m", "1m", 1000L, 100L);
    Metrics m1mPrimary = calc.compute(trades, curve, INITIAL, fin, "1m", "1m", 1000L, 100L);
    Metrics wrongCadence = calc.compute(trades, curve, INITIAL, fin, "3m", "3m", 1000L, 100L);

    // sanity: the fixture discriminates (3m-annualized values genuinely differ from 1m-annualized).
    assertThat(wrongCadence.sharpe()).isNotEqualByComparingTo(m1mPrimary.sharpe());
    // Sharpe/Sortino follow the CURVE cadence: a 3m-primary run over the same 1m-spaced curve equals
    // the 1m-primary run — if compute wrongly annualized at the primary timeframe, m3mPrimary would
    // instead equal wrongCadence and this fails.
    assertThat(m3mPrimary.sharpe()).isEqualByComparingTo(m1mPrimary.sharpe());
    assertThat(m3mPrimary.sortino()).isEqualByComparingTo(m1mPrimary.sortino());
    assertThat(m3mPrimary.sharpe()).isNotEqualByComparingTo(wrongCadence.sharpe());
    // tradeFrequency still keys off the PRIMARY timeframe (sessions divisor 125 for 3m vs 375 for
    // 1m) — the cadence split must not leak into the session-denominated metrics (#785).
    assertThat(m3mPrimary.full().get("tradeFrequency").asText())
        .isEqualTo(wrongCadence.full().get("tradeFrequency").asText())
        .isNotEqualTo(m1mPrimary.full().get("tradeFrequency").asText());
    // maxDrawdown is cadence-independent.
    assertThat(m3mPrimary.maxDrawdown()).isEqualByComparingTo(wrongCadence.maxDrawdown());
  }

  @Test
  void shortRunBelow500PointsIsByteIdenticalMetricsBeforeAndAfter() {
    // A ≤500-point curve: the persistence downsampler is a NO-OP (returns the same list), so a short
    // run's metrics are byte-identical to the pre-fix behaviour (regression guard).
    List<EquityPoint> curve = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      curve.add(new EquityPoint(T0.plusMinutes(i), new BigDecimal(100000 + (i % 7) * 13 - 40)));
    }
    List<EquityPoint> stored = EquityCurveDownsampler.downsample(curve, 500);
    assertThat(stored).isSameAs(curve); // no-op below the target

    Metrics onFull =
        calc.compute(List.of(), curve, INITIAL, curve.get(299).equity(), "1m", "1m", 300L, 0L);
    Metrics onStored =
        calc.compute(List.of(), stored, INITIAL, curve.get(299).equity(), "1m", "1m", 300L, 0L);

    assertThat(onStored.sharpe()).isEqualByComparingTo(onFull.sharpe());
    assertThat(onStored.sortino()).isEqualByComparingTo(onFull.sortino());
    assertThat(onStored.maxDrawdown()).isEqualByComparingTo(onFull.maxDrawdown());
  }

  private static Trade closedTrade(long qty, String entry, String exit, String pnl) {
    return new Trade(
        0, null, qty, T0, new BigDecimal(entry), T0.plusMinutes(30), new BigDecimal(exit),
        new BigDecimal(pnl), null, null, 0, null, null, null, null, null, null);
  }

  private record Fixture(StrategyDefinition definition, List<EngineCandle> primary) {}

  /** Loads the frozen ema-crossover golden fixture: its indicators are on the primary (no context). */
  private static Fixture loadEmaCrossover() throws IOException {
    Path root = goldenRoot();
    String yaml =
        Files.readString(
            root.resolve("strategies/ema-crossover.yaml"), StandardCharsets.UTF_8);
    StrategyDefinition definition =
        StrategyCompiler.compile(StrategyDocuments.parse(yaml).config());
    List<EngineCandle> primary = new ArrayList<>();
    for (int day = 1; day <= 5; day++) {
      primary.addAll(readCandles(root.resolve("candles/NSE_NIFTY50_1m_day" + day + ".csv")));
    }
    return new Fixture(definition, primary);
  }

  private static List<EngineCandle> readCandles(Path file) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return GoldenCandleCsv.parse(reader);
    }
  }

  private static Path goldenRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve("libs/strategy-engine/src/test/resources/golden");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("golden root not found");
  }
}
