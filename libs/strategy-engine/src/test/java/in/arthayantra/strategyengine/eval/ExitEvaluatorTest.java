package in.arthayantra.strategyengine.eval;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Exit-rule semantics + the documented precedence (stops always beat softer exits). */
class ExitEvaluatorTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  /** Closes in cents, one per bar; highs/lows tightly wrapped. */
  private static EngineSeries series(long... closesCents) {
    List<EngineCandle> candles = new ArrayList<>();
    for (int i = 0; i < closesCents.length; i++) {
      BigDecimal close = BigDecimal.valueOf(closesCents[i], 2);
      candles.add(
          new EngineCandle(
              OffsetDateTime.of(2026, 2, 3, 9, 15, 0, 0, IST).plusMinutes(i),
              close,
              close.add(BigDecimal.valueOf(5, 2)),
              close.subtract(BigDecimal.valueOf(5, 2)),
              close,
              100));
    }
    return EngineSeries.of(new SeriesKey("NSE", "EXIT", "1m"), candles);
  }

  private static StrategyDefinition definitionWith(String exitRulesYaml) {
    String yaml =
        """
        schema: strategy-schema/v1
        id: exit-test
        name: "Exit Test"
        version: 1.0.0
        universe:
          mode: explicit
          instruments:
            - { exchange: NSE, tradingsymbol: EXIT }
        timeframes: { primary: 1m }
        indicators:
          - { name: EMA, alias: ema_fast, timeframe: 1m, params: { period: 3 }, weight: 1.0 }
          - { name: EMA, alias: ema_slow, timeframe: 1m, params: { period: 9 }, weight: 1.0 }
        entry_rules:
          direction: long
          gate:
            all:
              - "close > 0"
          scoring: { threshold: 0.5 }
        exit_rules:
        %s
        risk:
          position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
          max_positions: 1
          session: { style: intraday }
        """
            .formatted(exitRulesYaml);
    return StrategyCompiler.compile(StrategyDocuments.parse(yaml).config());
  }

  private static IndicatorBank bank(StrategyDefinition definition, EngineSeries series) {
    return IndicatorBank.build(
        definition,
        new StrategyDefinition.InstrumentRef("NSE", "EXIT"),
        key -> series);
  }

  @Test
  void premiumPctStopAndTargetTrigger() {
    StrategyDefinition def =
        definitionWith(
            """
              - { type: stop_loss,   params: { basis: premium_pct, value: 10 } }
              - { type: take_profit, params: { basis: premium_pct, value: 20 } }
            """);
    // entry at 100.00 (index 1); stop level 90, target 120
    EngineSeries s = series(10000, 10000, 9500, 8900, 12100);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1);
    IndicatorBank b = bank(def, s);

    assertThat(ExitEvaluator.evaluate(def, b, position, 2)).isEmpty(); // -5% only
    Optional<ExitEvaluator.ExitDecision> stop = ExitEvaluator.evaluate(def, b, position, 3);
    assertThat(stop).isPresent();
    assertThat(stop.get().type()).isEqualTo("stop_loss");

    // a bar through the target (no stop breach on the way)
    EngineSeries up = series(10000, 10000, 10500, 11000, 12100);
    Optional<ExitEvaluator.ExitDecision> target =
        ExitEvaluator.evaluate(def, bank(def, up), position, 4);
    assertThat(target).isPresent();
    assertThat(target.get().type()).isEqualTo("take_profit");
  }

  @Test
  void stopBeatsTargetOnTheSameBar() {
    StrategyDefinition def =
        definitionWith(
            """
              - { type: take_profit, params: { basis: premium_pct, value: 5 } }
              - { type: stop_loss,   params: { basis: premium_pct, value: 5 } }
            """);
    // absurd single-bar move below the stop: both levels formally satisfied on close checks
    // for opposite directions - precedence must pick stop_loss when its condition holds
    EngineSeries s = series(10000, 10000, 9000);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1);

    Optional<ExitEvaluator.ExitDecision> decision =
        ExitEvaluator.evaluate(def, bank(def, s), position, 2);

    assertThat(decision).isPresent();
    assertThat(decision.get().type()).isEqualTo("stop_loss");
  }

  @Test
  void trailingStopActivatesThenTrails() {
    StrategyDefinition def =
        definitionWith(
            """
              - { type: trailing_stop, params: { basis: premium_pct, activate_at: 10, trail_by: 5 } }
            """);
    // entry 100; run to 115.05 high (index 3: close 115.00 + 0.05), then drop to 109
    EngineSeries s = series(10000, 10000, 11000, 11500, 10900);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1);
    IndicatorBank b = bank(def, s);

    assertThat(ExitEvaluator.evaluate(def, b, position, 2))
        .as("activated (gain >= 10%) but close 110 above trail floor")
        .isEmpty();
    Optional<ExitEvaluator.ExitDecision> trail = ExitEvaluator.evaluate(def, b, position, 4);
    assertThat(trail).isPresent();
    assertThat(trail.get().type()).isEqualTo("trailing_stop");
  }

  @Test
  void trailingStopBeforeActivationStaysQuiet() {
    StrategyDefinition def =
        definitionWith(
            """
              - { type: trailing_stop, params: { basis: premium_pct, activate_at: 25, trail_by: 5 } }
            """);
    EngineSeries s = series(10000, 10000, 11000, 10300);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1);

    assertThat(ExitEvaluator.evaluate(def, bank(def, s), position, 3))
        .as("never activated - a 6.4% pullback off the 110 peak does not exit")
        .isEmpty();
  }

  @Test
  void timeStopCountsBarsAndTradingDays() {
    StrategyDefinition barsDef =
        definitionWith("  - { type: time_stop, params: { max_bars: 3 } }\n");
    EngineSeries s = series(10000, 10010, 10020, 10030, 10040, 10050);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1);
    IndicatorBank b = bank(barsDef, s);

    assertThat(ExitEvaluator.evaluate(barsDef, b, position, 3)).isEmpty(); // 2 bars held
    assertThat(ExitEvaluator.evaluate(barsDef, b, position, 4)).isPresent(); // 3 bars held

    // trading-day variant across two IST sessions
    StrategyDefinition daysDef =
        definitionWith("  - { type: time_stop, params: { max_holding_days: 1 } }\n");
    List<EngineCandle> candles = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      BigDecimal close = BigDecimal.valueOf(10000 + i, 2);
      candles.add(
          new EngineCandle(
              OffsetDateTime.of(2026, 2, 3, 15, 27 + i, 0, 0, IST),
              close, close, close, close, 10));
    }
    for (int i = 0; i < 3; i++) {
      BigDecimal close = BigDecimal.valueOf(10010 + i, 2);
      candles.add(
          new EngineCandle(
              OffsetDateTime.of(2026, 2, 4, 9, 15 + i, 0, 0, IST),
              close, close, close, close, 10));
    }
    EngineSeries twoDays = EngineSeries.of(new SeriesKey("NSE", "EXIT", "1m"), candles);
    ExitEvaluator.Position dayPosition =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 0);
    IndicatorBank db = bank(daysDef, twoDays);

    assertThat(ExitEvaluator.evaluate(daysDef, db, dayPosition, 2)).isEmpty(); // same session
    assertThat(ExitEvaluator.evaluate(daysDef, db, dayPosition, 3)).isPresent(); // next day
  }

  @Test
  void signalExitFiresOnItsRule() {
    StrategyDefinition def =
        definitionWith(
            """
              - { type: signal_exit, params: { rule: "close < 99" } }
            """);
    EngineSeries s = series(10000, 10000, 9950, 9890);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1);
    IndicatorBank b = bank(def, s);

    assertThat(ExitEvaluator.evaluate(def, b, position, 2)).isEmpty();
    Optional<ExitEvaluator.ExitDecision> exit = ExitEvaluator.evaluate(def, b, position, 3);
    assertThat(exit).isPresent();
    assertThat(exit.get().type()).isEqualTo("signal_exit");
  }

  @Test
  void signalExitHonoursMinVolumeFloor() {
    EngineSeries s = series(10000, 10000, 9950, 9890); // volume = 100 on every bar
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1);

    // volume 100 clears a floor of 50 -> the break exits (same as a plain signal_exit)
    StrategyDefinition passes =
        definitionWith(
            """
              - { type: signal_exit, params: { rule: "close < 99", min_volume: 50 } }
            """);
    Optional<ExitEvaluator.ExitDecision> exit =
        ExitEvaluator.evaluate(passes, bank(passes, s), position, 3);
    assertThat(exit).isPresent();
    assertThat(exit.get().type()).isEqualTo("signal_exit");

    // volume 100 below a floor of 200 -> a no-volume "fake" break does NOT exit
    StrategyDefinition suppressed =
        definitionWith(
            """
              - { type: signal_exit, params: { rule: "close < 99", min_volume: 200 } }
            """);
    assertThat(ExitEvaluator.evaluate(suppressed, bank(suppressed, s), position, 3))
        .as("a break below the volume floor is a fake and does not exit")
        .isEmpty();
  }

  @Test
  void indexPointsStopAndTargetAreAbsoluteDistances() {
    StrategyDefinition def =
        definitionWith(
            """
              - { type: stop_loss,   params: { basis: index_points, value: 30 } }
              - { type: take_profit, params: { basis: index_points, value: 60 } }
            """);
    // entry 100.00 (index 1): stop at 70 (-30 pts), target at 160 (+60 pts)
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1);

    // -25 pts: within the 30-pt stop, no exit
    assertThat(ExitEvaluator.evaluate(def, bank(def, series(10000, 10000, 7500)), position, 2))
        .isEmpty();
    // -35 pts: through the 30-pt stop
    Optional<ExitEvaluator.ExitDecision> stop =
        ExitEvaluator.evaluate(def, bank(def, series(10000, 10000, 6500)), position, 2);
    assertThat(stop).get().extracting(ExitEvaluator.ExitDecision::type).isEqualTo("stop_loss");
    // +65 pts: through the 60-pt target
    Optional<ExitEvaluator.ExitDecision> target =
        ExitEvaluator.evaluate(def, bank(def, series(10000, 10000, 16500)), position, 2);
    assertThat(target).get().extracting(ExitEvaluator.ExitDecision::type).isEqualTo("take_profit");

    // entryLevels reflect the absolute-point distances
    ExitEvaluator.EntryLevels levels =
        ExitEvaluator.entryLevels(def, bank(def, series(10000, 10000, 10000)), position);
    assertThat(levels.stopLoss()).isEqualByComparingTo("70.00");
    assertThat(levels.takeProfit()).isEqualByComparingTo("160.00");
  }

  @Test
  void indexPointsTrailingExitsOnAFixedPointRetraceOffThePeak() {
    StrategyDefinition def =
        definitionWith(
            """
              - { type: trailing_stop, params: { basis: index_points, value: 20 } }
            """);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1);
    // peak high reaches ~150.05 by index 3 (close 150 + 0.05 wrap); a drop to 135 is a 15.05-pt
    // retrace off the peak — within the 20-pt trail, no exit
    EngineSeries s = series(10000, 10000, 13000, 15000, 13500);
    IndicatorBank b = bank(def, s);
    assertThat(ExitEvaluator.evaluate(def, b, position, 4))
        .as("15-pt retrace inside the 20-pt trail")
        .isEmpty();
    // a deeper drop to 125 is a 25-pt retrace off the ~150 peak — through the trail
    EngineSeries deep = series(10000, 10000, 13000, 15000, 12500);
    Optional<ExitEvaluator.ExitDecision> trail =
        ExitEvaluator.evaluate(def, bank(def, deep), position, 4);
    assertThat(trail).get().extracting(ExitEvaluator.ExitDecision::type).isEqualTo("trailing_stop");
  }

  @Test
  void indicatorTrailingExitsWhenPriceCrossesBelowTheAliasLevel() {
    StrategyDefinition def =
        definitionWith(
            """
              - { type: trailing_stop, params: { basis: indicator, alias: vwap } }
            """);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1);
    // closes run up then plunge well below the running VWAP
    EngineSeries s = series(10000, 10000, 10500, 11000, 8000);
    IndicatorBank b = bank(def, s);

    assertThat(ExitEvaluator.evaluate(def, b, position, 3))
        .as("price 110 still above VWAP — no trail exit")
        .isEmpty();
    Optional<ExitEvaluator.ExitDecision> trail = ExitEvaluator.evaluate(def, b, position, 4);
    assertThat(trail).get().extracting(ExitEvaluator.ExitDecision::type).isEqualTo("trailing_stop");

    // an undeclared / unknown alias yields no level (never throws) -> no exit
    StrategyDefinition unknown =
        definitionWith(
            """
              - { type: trailing_stop, params: { basis: indicator, alias: psar } }
            """);
    assertThat(ExitEvaluator.evaluate(unknown, bank(unknown, s), position, 4))
        .as("undeclared alias -> null level -> no exit (guarded)")
        .isEmpty();
  }

  @Test
  void shortPositionsMirrorLevels() {
    StrategyDefinition def =
        definitionWith(
            """
              - { type: stop_loss,   params: { basis: premium_pct, value: 10 } }
              - { type: take_profit, params: { basis: premium_pct, value: 10 } }
            """);
    ExitEvaluator.Position shortPos =
        new ExitEvaluator.Position(ExitEvaluator.Direction.SHORT, new BigDecimal("100.00"), 1);

    // adverse move UP stops a short
    EngineSeries adverse = series(10000, 10000, 11100);
    Optional<ExitEvaluator.ExitDecision> stop =
        ExitEvaluator.evaluate(def, bank(def, adverse), shortPos, 2);
    assertThat(stop).isPresent();
    assertThat(stop.get().type()).isEqualTo("stop_loss");

    // favorable move DOWN takes profit
    EngineSeries favorable = series(10000, 10000, 8900);
    Optional<ExitEvaluator.ExitDecision> target =
        ExitEvaluator.evaluate(def, bank(def, favorable), shortPos, 2);
    assertThat(target).isPresent();
    assertThat(target.get().type()).isEqualTo("take_profit");
  }

  @Test
  void atrBasisUsesEntryAtr() {
    StrategyDefinition def =
        definitionWith(
            """
              - { type: stop_loss, params: { basis: atr_multiple, value: 2.0, atr_period: 5 } }
            """);
    // 25 steady bars warm ATR(5); entry at 20; tight 0.10 ranges -> ATR ~ 0.13
    long[] closes = new long[30];
    for (int i = 0; i < 30; i++) {
      closes[i] = 10000 + 8L * i;
    }
    closes[29] = 10000 + 8L * 20 - 200; // sharp drop ~2 below entry-ish level
    EngineSeries s = series(closes);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(
            ExitEvaluator.Direction.LONG, BigDecimal.valueOf(closes[20], 2), 20);
    IndicatorBank b = bank(def, s);

    assertThat(ExitEvaluator.evaluate(def, b, position, 24))
        .as("drift within 2x entry-ATR")
        .isEmpty();
    assertThat(ExitEvaluator.evaluate(def, b, position, 29))
        .as("2.00 drop blows through 2x entry-ATR")
        .isPresent();
  }

  /** Wide-range bars: close in cents, each bar's high/low span ±{@code halfRangeCents} → large ATR. */
  private static EngineSeries wideSeries(int halfRangeCents, long... closesCents) {
    List<EngineCandle> candles = new ArrayList<>();
    for (int i = 0; i < closesCents.length; i++) {
      BigDecimal close = BigDecimal.valueOf(closesCents[i], 2);
      candles.add(
          new EngineCandle(
              OffsetDateTime.of(2026, 2, 3, 9, 15, 0, 0, IST).plusMinutes(i),
              close,
              close.add(BigDecimal.valueOf(halfRangeCents, 2)),
              close.subtract(BigDecimal.valueOf(halfRangeCents, 2)),
              close,
              100));
    }
    return EngineSeries.of(new SeriesKey("NSE", "EXIT", "1m"), candles);
  }

  @Test
  void atrStopCapPctBoundsAWideAtrStopAtTenPercent() {
    // §3.5A: 2×ATR would imply a wide stop, so cap_pct=10 binds → stop distance = 10% of entry.
    StrategyDefinition capped =
        definitionWith(
            """
              - { type: stop_loss, params: { basis: atr_multiple, value: 2, atr_period: 5, cap_pct: 10 } }
            """);
    StrategyDefinition uncapped =
        definitionWith(
            """
              - { type: stop_loss, params: { basis: atr_multiple, value: 2, atr_period: 5 } }
            """);
    // 30 bars, each ±3.00 wide (TR ≈ 6.00 → ATR(5) ≈ 6.00), entry ~100 → 2×ATR ≈ 12 > the 10-pt cap.
    long[] closes = new long[30];
    for (int i = 0; i < 30; i++) {
      closes[i] = 10000; // flat closes, wide intrabar range → the whole TR is the ±3.00 span
    }
    EngineSeries s = wideSeries(300, closes);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 29);

    ExitEvaluator.EntryLevels cappedLevels =
        ExitEvaluator.entryLevels(capped, bank(capped, s), position);
    ExitEvaluator.EntryLevels uncappedLevels =
        ExitEvaluator.entryLevels(uncapped, bank(uncapped, s), position);
    assertThat(cappedLevels.stopLoss())
        .as("cap binds: stop distance = 10% of 100 → stop at 90.00")
        .isEqualByComparingTo("90.00");
    assertThat(uncappedLevels.stopLoss())
        .as("uncapped 2×ATR(~6) stop is wider (lower) than the capped 90")
        .isLessThan(new BigDecimal("90.00"));
  }

  @Test
  void atrStopCapPctIsInertWhenTwoAtrIsTighterThanTheCap() {
    // A cap_pct that is LOOSER than 2×ATR never binds → the stop equals the plain 2×ATR stop.
    StrategyDefinition capped =
        definitionWith(
            """
              - { type: stop_loss, params: { basis: atr_multiple, value: 2, atr_period: 5, cap_pct: 50 } }
            """);
    StrategyDefinition uncapped =
        definitionWith(
            """
              - { type: stop_loss, params: { basis: atr_multiple, value: 2, atr_period: 5 } }
            """);
    long[] closes = new long[30];
    for (int i = 0; i < 30; i++) {
      closes[i] = 10000;
    }
    EngineSeries s = wideSeries(300, closes); // 2×ATR ≈ 12 < 50% cap
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 29);
    assertThat(ExitEvaluator.entryLevels(capped, bank(capped, s), position).stopLoss())
        .as("loose cap is inert → equals the plain 2×ATR stop")
        .isEqualByComparingTo(
            ExitEvaluator.entryLevels(uncapped, bank(uncapped, s), position).stopLoss());
  }

  @Test
  void atrTrailArmPctStaysInertUntilArmedThenTrails() {
    // §3.5B: arm_pct=9 keeps the ATR trail inert until the position is up 9%, then it trails.
    StrategyDefinition def =
        definitionWith(
            """
              - { type: trailing_stop, params: { basis: atr_multiple, value: 2, atr_period: 5, arm_pct: 9 } }
            """);
    StrategyDefinition unarmed =
        definitionWith(
            """
              - { type: trailing_stop, params: { basis: atr_multiple, value: 2, atr_period: 5 } }
            """);
    // 30 tight warmup bars flat at 100 (ATR(5) ≈ 0.10), entry at index 29 = 100.00, then a small run.
    long[] warm = new long[35];
    for (int i = 0; i < 30; i++) {
      warm[i] = 10000;
    }
    warm[30] = 10400; // +4% peak — below the 9% arm
    warm[31] = 10300; // pull back: unarmed trail (peak 104 − 2×ATR~0.10 ≈ 103.8) would fire; armed stays inert
    warm[32] = 11000; // +10% peak — now armed
    warm[33] = 11500; // higher peak 115
    warm[34] = 11000; // drop of 5.00 off the 115 peak >> 2×ATR(~0.10) → armed trail fires
    EngineSeries s = series(warm);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 29);
    IndicatorBank b = bank(def, s);

    assertThat(ExitEvaluator.evaluate(def, b, position, 31))
        .as("peak only +4% (< 9% arm) → trail inert despite a pullback")
        .isEmpty();
    assertThat(ExitEvaluator.evaluate(unarmed, bank(unarmed, s), position, 31))
        .as("the same series WITHOUT arm_pct trails from entry → the pullback exits")
        .isPresent();
    Optional<ExitEvaluator.ExitDecision> armedExit = ExitEvaluator.evaluate(def, b, position, 34);
    assertThat(armedExit)
        .as("armed after +10%, the 5-pt drop off the 115 peak fires the ATR trail")
        .get()
        .extracting(ExitEvaluator.ExitDecision::type)
        .isEqualTo("trailing_stop");
  }

  @Test
  void squareOffFiresOnATooFastMove() {
    // §3.5C too-fast: close ≥ close[fast_bars ago] × (1 + fast_pct%).
    StrategyDefinition def =
        definitionWith(
            """
              - { type: square_off, params: { fast_pct: 35, fast_bars: 3 } }
            """);
    // index 3 = 140 vs index 0 = 100 → +40% ≥ +35% over 3 bars → fires. index 2 = 120 (< +35% over 3) no.
    EngineSeries s = series(10000, 10500, 12000, 14000);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 0);
    IndicatorBank b = bank(def, s);

    assertThat(ExitEvaluator.evaluate(def, b, position, 2))
        .as("+20% over 2 bars — no fast-move (needs 3-bar lookback anyway)")
        .isEmpty();
    Optional<ExitEvaluator.ExitDecision> exit = ExitEvaluator.evaluate(def, b, position, 3);
    assertThat(exit).get().extracting(ExitEvaluator.ExitDecision::type).isEqualTo("square_off");
    assertThat(exit.get().reason()).contains("too-fast");
  }

  @Test
  void squareOffFiresOnAParabolicExtension() {
    // §3.5C parabolic: close ≥ SMA(parabolic_ma) × (1 + parabolic_dist_pct%).
    StrategyDefinition def =
        definitionWith(
            """
              - { type: square_off, params: { parabolic_ma: 5, parabolic_dist_pct: 40 } }
            """);
    // 5 bars flat at 100, then a spike to 160. At the spike bar SMA(5)=(100·4+160)/5=112, trigger
    // 112×1.40=156.8, close 160 ≥ 156.8 → the parabolic extension fires. At the pre-spike bar SMA=100,
    // trigger 140, close 100 → not extended.
    EngineSeries s = series(10000, 10000, 10000, 10000, 10000, 16000);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 0);
    IndicatorBank b = bank(def, s);

    assertThat(ExitEvaluator.evaluate(def, b, position, 4))
        .as("price on the SMA — not extended")
        .isEmpty();
    Optional<ExitEvaluator.ExitDecision> exit = ExitEvaluator.evaluate(def, b, position, 5);
    assertThat(exit).get().extracting(ExitEvaluator.ExitDecision::type).isEqualTo("square_off");
    assertThat(exit.get().reason()).contains("parabolic");
  }

  @Test
  void squareOffIsInertWhenNeitherClauseIsConfigured() {
    // An empty-ish square_off (only one incomplete clause) never fires — additive safety.
    StrategyDefinition def =
        definitionWith("  - { type: square_off, params: { fast_bars: 3 } }\n");
    EngineSeries s = series(10000, 12000, 14000, 20000);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 0);
    assertThat(ExitEvaluator.evaluate(def, bank(def, s), position, 3))
        .as("fast_bars without fast_pct is an incomplete clause → inert")
        .isEmpty();
  }

  @Test
  void entryLevelsComputeProtectivePricesForLongAndShort() {
    StrategyDefinition def =
        definitionWith(
            """
              - { type: stop_loss,   params: { basis: premium_pct, value: 10 } }
              - { type: take_profit, params: { basis: premium_pct, value: 20 } }
            """);
    EngineSeries s = series(10000, 10000, 10000);
    IndicatorBank b = bank(def, s);

    ExitEvaluator.EntryLevels lng =
        ExitEvaluator.entryLevels(
            def, b, new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1));
    assertThat(lng.stopLoss()).isEqualByComparingTo("90.00"); // 100 - 10%
    assertThat(lng.takeProfit()).isEqualByComparingTo("120.00"); // 100 + 20%

    ExitEvaluator.EntryLevels sht =
        ExitEvaluator.entryLevels(
            def, b, new ExitEvaluator.Position(ExitEvaluator.Direction.SHORT, new BigDecimal("100.00"), 1));
    assertThat(sht.stopLoss()).isEqualByComparingTo("110.00"); // mirrored for a short
    assertThat(sht.takeProfit()).isEqualByComparingTo("80.00");
  }

  @Test
  void entryLevelsNullWhenStrategyDeclaresNoSuchRule() {
    StrategyDefinition def =
        definitionWith("  - { type: time_stop, params: { max_bars: 3 } }\n");
    EngineSeries s = series(10000, 10000, 10000);
    ExitEvaluator.EntryLevels levels =
        ExitEvaluator.entryLevels(
            def,
            bank(def, s),
            new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1));
    assertThat(levels.stopLoss()).isNull();
    assertThat(levels.takeProfit()).isNull();
  }

  @Test
  void trailStopReportsTheArmedAtrTrailAndAgreesWithEvaluate() {
    // audit M33: the read-only trailStop accessor mirrors the evaluated trailing_stop level exactly,
    // so the daily sell-decision reports the price the live exit fires on (not a proxy like sma20).
    StrategyDefinition def =
        definitionWith(
            "  - { type: trailing_stop, params: { basis: atr_multiple, value: 2, atr_period: 5,"
                + " arm_pct: 9 } }\n");
    // reuse the armed-trail fixture: 30 flat warmup bars then +4% / pull / +10% / +15% / drop.
    long[] warm = new long[35];
    for (int i = 0; i < 30; i++) {
      warm[i] = 10000;
    }
    warm[30] = 10400;
    warm[31] = 10300;
    warm[32] = 11000;
    warm[33] = 11500;
    warm[34] = 11000;
    EngineSeries s = series(warm);
    ExitEvaluator.Position pos =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 29);
    IndicatorBank b = bank(def, s);

    // before the 9% arm (peak only +4% at index 31): no trail level to report yet
    assertThat(ExitEvaluator.trailStop(def, b, pos, 31)).as("unarmed → no level").isEmpty();

    // armed after +10%: a level exists and is consistent with the evaluator at the boundary —
    // close above the level → the evaluator is quiet, close at/below → it fires trailing_stop.
    Optional<BigDecimal> lvl33 = ExitEvaluator.trailStop(def, b, pos, 33);
    assertThat(lvl33).isPresent();
    assertThat(s.candle(33).close()).isGreaterThan(lvl33.get());
    assertThat(ExitEvaluator.evaluate(def, b, pos, 33)).isEmpty();

    Optional<BigDecimal> lvl34 = ExitEvaluator.trailStop(def, b, pos, 34);
    assertThat(lvl34).isPresent();
    assertThat(s.candle(34).close()).isLessThanOrEqualTo(lvl34.get());
    assertThat(ExitEvaluator.evaluate(def, b, pos, 34))
        .get()
        .extracting(ExitEvaluator.ExitDecision::type)
        .isEqualTo("trailing_stop");
  }

  @Test
  void trailStopReportsTheIndicatorLevelForAnIndicatorBasisTrail() {
    // the Minervini-style indicator trail (its own sma50/vwap): trailStop returns the alias level the
    // evaluator crosses, so both swing families read an honest number off the one accessor.
    StrategyDefinition def =
        definitionWith("  - { type: trailing_stop, params: { basis: indicator, alias: vwap } }\n");
    EngineSeries s = series(10000, 10000, 10500, 11000, 8000);
    ExitEvaluator.Position pos =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1);
    IndicatorBank b = bank(def, s);

    Optional<BigDecimal> lvl = ExitEvaluator.trailStop(def, b, pos, 4);
    assertThat(lvl).isPresent();
    assertThat(s.candle(4).close()).isLessThan(lvl.get()); // 80 below the running vwap → exits here
    assertThat(ExitEvaluator.evaluate(def, b, pos, 4))
        .get()
        .extracting(ExitEvaluator.ExitDecision::type)
        .isEqualTo("trailing_stop");
  }

  @Test
  void trailStopIsEmptyWithoutATrailingStopRule() {
    StrategyDefinition def =
        definitionWith("  - { type: stop_loss, params: { basis: premium_pct, value: 10 } }\n");
    EngineSeries s = series(10000, 10000, 10000);
    assertThat(
            ExitEvaluator.trailStop(
                def,
                bank(def, s),
                new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("100.00"), 1),
                2))
        .isEmpty();
  }

  @Test
  void positionSizerRoundsLotsAndCapsKelly() {
    var inputs =
        new PositionSizer.Inputs(
            new BigDecimal("1000000"), new BigDecimal("250.00"), new BigDecimal("5.00"), 75);

    assertThat(
            PositionSizer.size(
                new StrategyDefinition.SizingSpec("fixed_quantity", Map.of("quantity", 160L)),
                inputs))
        .isEqualTo(150); // rounded DOWN to the 75 lot
    assertThat(
            PositionSizer.size(
                new StrategyDefinition.SizingSpec(
                    "percent_equity", Map.of("percent", new BigDecimal("10"))),
                inputs))
        .isEqualTo(375); // 100000/250 = 400 units -> 375
    assertThat(
            PositionSizer.size(
                new StrategyDefinition.SizingSpec(
                    "premium_budget", Map.of("budget_inr", new BigDecimal("40000"))),
                inputs))
        .isEqualTo(150); // 40000/(250*75) = 2 lots
    assertThat(
            PositionSizer.size(
                new StrategyDefinition.SizingSpec(
                    "atr_risk", Map.of("risk_pct_equity", new BigDecimal("1.0"))),
                inputs))
        .isEqualTo(1950); // 10000/5 = 2000 -> 1950
    assertThat(
            PositionSizer.size(
                new StrategyDefinition.SizingSpec(
                    "kelly_fraction", Map.of("fraction", new BigDecimal("0.9"))),
                inputs))
        .isEqualTo(975); // capped at 0.25: 250000/250 = 1000 -> 975
    assertThat(
            PositionSizer.size(
                new StrategyDefinition.SizingSpec(
                    "premium_budget", Map.of("budget_inr", new BigDecimal("10000"))),
                inputs))
        .as("below one lot sizes to zero")
        .isEqualTo(0);
  }
}
