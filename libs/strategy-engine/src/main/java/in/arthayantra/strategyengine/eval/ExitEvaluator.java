package in.arthayantra.strategyengine.eval;

import in.arthayantra.strategyengine.config.GateNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.indicators.EngineMath;
import in.arthayantra.strategyengine.indicators.IndicatorRegistry;
import in.arthayantra.strategyengine.series.EngineSeries;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bar-close exit evaluation (Phase 20). Precedence is FIXED and documented: stop_loss →
 * trailing_stop → take_profit → time_stop → signal_exit — protective stops always win a tie on
 * the same bar. Deterministic choices (identical in live and replay): ATR-based distances use
 * the ATR at ENTRY (never recomputed mid-trade), trailing peaks/troughs use bar highs/lows since
 * entry, r_multiple reads the initial risk off the first stop_loss rule.
 */
public final class ExitEvaluator {

  /** Position direction. */
  public enum Direction {
    LONG,
    SHORT
  }

  /** Open-position state the evaluator needs. */
  public record Position(Direction direction, BigDecimal entryPrice, int entryIndex) {}

  /** A triggered exit. */
  public record ExitDecision(String type, String reason) {}

  /** The entry-time protective levels (absolute prices); either may be {@code null}. */
  public record EntryLevels(BigDecimal stopLoss, BigDecimal takeProfit) {}

  private ExitEvaluator() {}

  /**
   * The protective levels a position would carry from entry: the FIRST stop_loss rule's level and
   * the FIRST take_profit rule's level, as absolute prices off {@code position.entryPrice()}
   * (direction-aware, same arithmetic as {@link #level}). Either is {@code null} when the strategy
   * declares no such rule or its distance is unresolvable. Read-only and deterministic — uses the
   * ATR/r-multiple-at-entry distances exactly like the exit evaluation, so a persisted level equals
   * the price the exit check would fire on. Pure reporting: it never affects emitted signals.
   */
  public static EntryLevels entryLevels(
      StrategyDefinition definition, IndicatorBank bank, Position position) {
    EngineSeries series = bank.primarySeries();
    return new EntryLevels(
        levelPrice(definition, series, position, "stop_loss", true),
        levelPrice(definition, series, position, "take_profit", false));
  }

  private static BigDecimal levelPrice(
      StrategyDefinition definition,
      EngineSeries series,
      Position position,
      String type,
      boolean isStop) {
    for (StrategyDefinition.ExitRuleSpec rule : definition.exitRules()) {
      if (!rule.type().equals(type)) {
        continue;
      }
      BigDecimal distance = levelDistance(definition, series, position, rule.params());
      if (distance == null) {
        return null;
      }
      boolean subtract = (position.direction() == Direction.LONG) == isStop;
      return subtract
          ? position.entryPrice().subtract(distance)
          : position.entryPrice().add(distance);
    }
    return null;
  }

  /**
   * A9 [FP-5] intrabar exits: when {@code exit_intrabar} is on and the primary timeframe is
   * coarser than 1m, the LEVEL exits (stop_loss / trailing_stop / take_profit) are evaluated on
   * each CLOSED 1m bar — the same rule replay applies at the 1m floor. time_stop/signal_exit
   * stay primary-bar-close. ATR-based distances still read the ATR at the PRIMARY entry bar.
   */
  public static Optional<ExitDecision> evaluateIntrabarLevels(
      StrategyDefinition definition,
      EngineSeries primarySeries,
      int entryPrimaryIndex,
      EngineSeries oneMinute,
      Direction direction,
      BigDecimal entryPrice,
      int entryOneMinuteIndex,
      int oneMinuteIndex) {
    BigDecimal close = oneMinute.candle(oneMinuteIndex).close();
    Position primaryPosition = new Position(direction, entryPrice, entryPrimaryIndex);
    Position oneMinutePosition = new Position(direction, entryPrice, entryOneMinuteIndex);
    for (String type : new String[] {"stop_loss", "trailing_stop", "take_profit"}) {
      for (StrategyDefinition.ExitRuleSpec rule : definition.exitRules()) {
        if (!rule.type().equals(type)) {
          continue;
        }
        Optional<ExitDecision> decision =
            switch (type) {
              case "stop_loss" ->
                  levelOn(definition, primarySeries, primaryPosition, rule, close, true);
              case "take_profit" ->
                  levelOn(definition, primarySeries, primaryPosition, rule, close, false);
              case "trailing_stop" ->
                  trailingOn(
                      primarySeries, primaryPosition, oneMinute, oneMinutePosition,
                      oneMinuteIndex, rule, close);
              default -> Optional.empty();
            };
        if (decision.isPresent()) {
          return decision;
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<ExitDecision> levelOn(
      StrategyDefinition definition,
      EngineSeries atrSeries,
      Position atrPosition,
      StrategyDefinition.ExitRuleSpec rule,
      BigDecimal close,
      boolean isStop) {
    return level(definition, atrSeries, atrPosition, rule, close, isStop);
  }

  private static Optional<ExitDecision> trailingOn(
      EngineSeries atrSeries,
      Position atrPosition,
      EngineSeries priceSeries,
      Position pricePosition,
      int priceIndex,
      StrategyDefinition.ExitRuleSpec rule,
      BigDecimal close) {
    // peaks track the 1m series; ATR distances stay pinned to the primary entry bar
    Map<String, Object> params = rule.params();
    if ("atr_multiple".equals(String.valueOf(params.get("basis")))) {
      BigDecimal value = decimal(params.get("value"));
      BigDecimal atr = atrAtEntry(atrSeries, atrPosition, params);
      if (value == null || atr == null) {
        return Optional.empty();
      }
      BigDecimal peak = favorableExtreme(priceSeries, pricePosition, priceIndex);
      BigDecimal trailDistance = atr.multiply(value, EngineMath.MC);
      boolean isLong = pricePosition.direction() == Direction.LONG;
      boolean hit =
          isLong
              ? close.compareTo(peak.subtract(trailDistance)) <= 0
              : close.compareTo(peak.add(trailDistance)) >= 0;
      return hit
          ? Optional.of(new ExitDecision("trailing_stop", value + "x entry-ATR 1m trail off " + peak))
          : Optional.empty();
    }
    return trailing(priceSeries, pricePosition, priceIndex, rule, close);
  }

  /** Evaluates all exit rules at a bar; first match in precedence order wins. */
  public static Optional<ExitDecision> evaluate(
      StrategyDefinition definition,
      IndicatorBank bank,
      Position position,
      int primaryIndex) {
    EngineSeries series = bank.primarySeries();
    BigDecimal close = series.candle(primaryIndex).close();

    for (String type :
        new String[] {"stop_loss", "trailing_stop", "take_profit", "time_stop", "signal_exit"}) {
      for (StrategyDefinition.ExitRuleSpec rule : definition.exitRules()) {
        if (!rule.type().equals(type)) {
          continue;
        }
        Optional<ExitDecision> decision =
            switch (type) {
              case "stop_loss" -> level(definition, series, position, rule, close, true);
              case "take_profit" -> level(definition, series, position, rule, close, false);
              case "trailing_stop" -> trailing(series, position, primaryIndex, rule, close);
              case "time_stop" -> timeStop(series, position, primaryIndex, rule);
              case "signal_exit" -> signalExit(bank, primaryIndex, rule);
              default -> Optional.empty();
            };
        if (decision.isPresent()) {
          return decision;
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<ExitDecision> level(
      StrategyDefinition definition,
      EngineSeries series,
      Position position,
      StrategyDefinition.ExitRuleSpec rule,
      BigDecimal close,
      boolean isStop) {
    BigDecimal distance = levelDistance(definition, series, position, rule.params());
    if (distance == null) {
      return Optional.empty();
    }
    boolean hit;
    if (position.direction() == Direction.LONG) {
      hit =
          isStop
              ? close.compareTo(position.entryPrice().subtract(distance)) <= 0
              : close.compareTo(position.entryPrice().add(distance)) >= 0;
    } else {
      hit =
          isStop
              ? close.compareTo(position.entryPrice().add(distance)) >= 0
              : close.compareTo(position.entryPrice().subtract(distance)) <= 0;
    }
    String type = isStop ? "stop_loss" : "take_profit";
    return hit
        ? Optional.of(new ExitDecision(type, type + " " + params(rule) + " hit at " + close))
        : Optional.empty();
  }

  /** Absolute price distance for a level rule, per its basis. */
  private static BigDecimal levelDistance(
      StrategyDefinition definition,
      EngineSeries series,
      Position position,
      Map<String, Object> params) {
    String basis = String.valueOf(params.get("basis"));
    BigDecimal value = decimal(params.get("value"));
    if (value == null) {
      return null;
    }
    return switch (basis) {
      case "premium_pct" ->
          position.entryPrice().multiply(value, EngineMath.MC).divide(EngineMath.HUNDRED, EngineMath.MC);
      case "atr_multiple" -> {
        BigDecimal atr = atrAtEntry(series, position, params);
        yield atr == null ? null : atr.multiply(value, EngineMath.MC);
      }
      case "r_multiple" -> {
        BigDecimal initialRisk = initialRisk(definition, series, position);
        yield initialRisk == null ? null : initialRisk.multiply(value, EngineMath.MC);
      }
      case "index_points" -> value; // absolute index points — the distance IS the value (Nifty ~30-60, Sensex ~100-250)
      default -> null;
    };
  }

  private static Optional<ExitDecision> trailing(
      EngineSeries series,
      Position position,
      int index,
      StrategyDefinition.ExitRuleSpec rule,
      BigDecimal close) {
    Map<String, Object> params = rule.params();
    String basis = String.valueOf(params.get("basis"));
    boolean isLong = position.direction() == Direction.LONG;
    BigDecimal peak = favorableExtreme(series, position, index);

    if ("premium_pct".equals(basis)) {
      BigDecimal trailBy = decimal(params.containsKey("trail_by") ? params.get("trail_by") : params.get("value"));
      if (trailBy == null) {
        return Optional.empty();
      }
      BigDecimal activateAt = decimal(params.get("activate_at"));
      if (activateAt != null) {
        BigDecimal gainPct =
            (isLong
                    ? peak.subtract(position.entryPrice())
                    : position.entryPrice().subtract(peak))
                .divide(position.entryPrice(), EngineMath.MC)
                .multiply(EngineMath.HUNDRED, EngineMath.MC);
        if (gainPct.compareTo(activateAt) < 0) {
          return Optional.empty();
        }
      }
      BigDecimal trailDistance =
          peak.multiply(trailBy, EngineMath.MC).divide(EngineMath.HUNDRED, EngineMath.MC);
      boolean hit =
          isLong
              ? close.compareTo(peak.subtract(trailDistance)) <= 0
              : close.compareTo(peak.add(trailDistance)) >= 0;
      return hit
          ? Optional.of(new ExitDecision("trailing_stop", "trailed " + trailBy + "% off " + peak))
          : Optional.empty();
    }
    if ("atr_multiple".equals(basis)) {
      BigDecimal value = decimal(params.get("value"));
      BigDecimal atr = atrAtEntry(series, position, params);
      if (value == null || atr == null) {
        return Optional.empty();
      }
      BigDecimal trailDistance = atr.multiply(value, EngineMath.MC);
      boolean hit =
          isLong
              ? close.compareTo(peak.subtract(trailDistance)) <= 0
              : close.compareTo(peak.add(trailDistance)) >= 0;
      return hit
          ? Optional.of(
              new ExitDecision("trailing_stop", value + "x entry-ATR trail off " + peak))
          : Optional.empty();
    }
    if ("index_points".equals(basis)) {
      // fixed-offset trail: exit when price retraces `value` index points off the favourable peak.
      BigDecimal offset = decimal(params.get("value"));
      if (offset == null) {
        return Optional.empty();
      }
      boolean hit =
          isLong
              ? close.compareTo(peak.subtract(offset)) <= 0
              : close.compareTo(peak.add(offset)) >= 0;
      return hit
          ? Optional.of(new ExitDecision("trailing_stop", "trailed " + offset + "pts off " + peak))
          : Optional.empty();
    }
    return Optional.empty();
  }

  private static Optional<ExitDecision> timeStop(
      EngineSeries series, Position position, int index, StrategyDefinition.ExitRuleSpec rule) {
    Object maxBars = rule.params().get("max_bars");
    if (maxBars != null) {
      int held = index - position.entryIndex();
      return held >= ((Number) maxBars).intValue()
          ? Optional.of(new ExitDecision("time_stop", held + " bars held"))
          : Optional.empty();
    }
    Object maxDays = rule.params().get("max_holding_days");
    if (maxDays != null) {
      Set<LocalDate> sessions = new HashSet<>();
      for (int i = position.entryIndex() + 1; i <= index; i++) {
        sessions.add(EngineSeries.sessionDate(series.candle(i)));
      }
      sessions.remove(EngineSeries.sessionDate(series.candle(position.entryIndex())));
      return sessions.size() >= ((Number) maxDays).intValue()
          ? Optional.of(new ExitDecision("time_stop", sessions.size() + " trading days held"))
          : Optional.empty();
    }
    return Optional.empty();
  }

  private static Optional<ExitDecision> signalExit(
      IndicatorBank bank, int index, StrategyDefinition.ExitRuleSpec rule) {
    String text = String.valueOf(rule.params().get("rule"));
    GateNode node = StrategyCompiler.compileLeafText(text);
    ScoreBreakdown.GateResult result = GateEvaluator.evaluate(node, bank, index);
    if (!result.passed()) {
      return Optional.empty();
    }
    // Optional volume floor (S24 §3.3/§4.15): a VWAP/level break must come WITH volume to be real —
    // a no-volume "fake" break does NOT exit. Absent min_volume → unchanged (parity-safe-additive).
    Object minVolume = rule.params().get("min_volume");
    if (minVolume != null) {
      BigDecimal volume = bank.builtin("volume", index);
      if (volume == null || volume.compareTo(decimal(minVolume)) < 0) {
        return Optional.empty();
      }
    }
    return Optional.of(new ExitDecision("signal_exit", text));
  }

  private static BigDecimal favorableExtreme(EngineSeries series, Position position, int index) {
    boolean isLong = position.direction() == Direction.LONG;
    BigDecimal extreme = position.entryPrice();
    for (int i = position.entryIndex(); i <= index; i++) {
      BigDecimal candidate =
          isLong ? series.candle(i).high() : series.candle(i).low();
      if (isLong ? candidate.compareTo(extreme) > 0 : candidate.compareTo(extreme) < 0) {
        extreme = candidate;
      }
    }
    return extreme;
  }

  private static BigDecimal atrAtEntry(
      EngineSeries series, Position position, Map<String, Object> params) {
    Object period = params.get("atr_period");
    int atrPeriod = period == null ? 14 : ((Number) period).intValue();
    return IndicatorRegistry.create("ATR", series, null, Map.of("period", atrPeriod))
        .valueAt(position.entryIndex());
  }

  /** Initial risk per unit = the FIRST stop_loss rule's distance at entry; null without one. */
  private static BigDecimal initialRisk(
      StrategyDefinition definition, EngineSeries series, Position position) {
    for (StrategyDefinition.ExitRuleSpec rule : definition.exitRules()) {
      if ("stop_loss".equals(rule.type()) && !"r_multiple".equals(rule.params().get("basis"))) {
        return levelDistance(definition, series, position, rule.params());
      }
    }
    return null;
  }

  private static String params(StrategyDefinition.ExitRuleSpec rule) {
    return rule.params().get("basis") + "=" + rule.params().get("value");
  }

  private static BigDecimal decimal(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal bd) {
      return bd;
    }
    return new BigDecimal(value.toString());
  }
}
