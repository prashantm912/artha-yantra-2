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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bar-close exit evaluation (Phase 20). Precedence is FIXED and documented: stop_loss →
 * trailing_stop → take_profit → scaled_exit → square_off → time_stop → signal_exit — protective
 * stops always win a tie on the same bar. Deterministic choices (identical in live and replay):
 * ATR-based distances use the ATR at ENTRY (never recomputed mid-trade), trailing peaks/troughs use
 * bar highs/lows since entry, r_multiple reads the initial risk off the first stop_loss rule.
 *
 * <p>Manas Arora §3.5 exit doctrine (parity-safe additive, mirrors {@code ManasAroraSwingBacktest}):
 * an {@code atr_multiple} stop takes an optional {@code cap_pct} → distance = min(mult×ATR,
 * cap_pct%×entry) so a wide-ATR stop never exceeds ~10%; an {@code atr_multiple} trail takes an
 * optional {@code arm_pct} → the trail is inert until the FAVOURABLE extreme is up arm_pct, then
 * ratchets close − mult×ATR off the peak (monotone up); {@code square_off} exits on a too-fast move
 * (close ≥ close[fast_bars ago]×(1+fast_pct%)) OR a parabolic extension (close ≥
 * SMA(parabolic_ma)×(1+parabolic_dist_pct%)). All ATR/SMA reads are deterministic bar functions.
 */
public final class ExitEvaluator {

  /** Position direction. */
  public enum Direction {
    LONG,
    SHORT
  }

  /** Open-position state the evaluator needs. */
  public record Position(Direction direction, BigDecimal entryPrice, int entryIndex) {}

  /**
   * A triggered exit. {@code qtyFraction} is the fraction of the ORIGINAL position this leg closes
   * (1 = full close of the remaining); {@code tier} is the {@code scaled_exit} tier index that fired
   * (-1 = not a scaled tier). The 2-arg constructor is a full close, so every existing exit type is
   * unchanged and parity-safe.
   */
  public record ExitDecision(String type, String reason, BigDecimal qtyFraction, int tier) {
    /** A full-close decision (stop_loss / trailing_stop / take_profit / time_stop / signal_exit). */
    public ExitDecision(String type, String reason) {
      this(type, reason, BigDecimal.ONE, -1);
    }
  }

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
      boolean isLong = pricePosition.direction() == Direction.LONG;
      // Manas §3.5B arm_pct on the 1m intrabar path (mirrors the bar-close branch): inert until the
      // 1m favourable extreme is up arm_pct. Absent → armed from entry, byte-identical to before.
      BigDecimal armPct = decimal(params.get("arm_pct"));
      if (armPct != null) {
        BigDecimal gainPct =
            (isLong
                    ? peak.subtract(pricePosition.entryPrice())
                    : pricePosition.entryPrice().subtract(peak))
                .divide(pricePosition.entryPrice(), EngineMath.MC)
                .multiply(EngineMath.HUNDRED, EngineMath.MC);
        if (gainPct.compareTo(armPct) < 0) {
          return Optional.empty();
        }
      }
      BigDecimal trailDistance = atr.multiply(value, EngineMath.MC);
      boolean hit =
          isLong
              ? close.compareTo(peak.subtract(trailDistance)) <= 0
              : close.compareTo(peak.add(trailDistance)) >= 0;
      return hit
          ? Optional.of(new ExitDecision("trailing_stop", value + "x entry-ATR 1m trail off " + peak))
          : Optional.empty();
    }
    // null bank: the indicator-trail basis is bar-close-only and is skipped on the intrabar path.
    return trailing(null, priceSeries, pricePosition, priceIndex, rule, close);
  }

  /**
   * The LIVE {@code SignalEngine} entry point — first match in precedence order wins, {@code scaled_exit}
   * is NOT evaluated. The live path has no partial-position ledger yet (that is Phase 9), so a scaled
   * tier must never fire here (it would be mishandled as a full close of the whole position). A live
   * {@code scaled_exit} strategy simply doesn't scale out until Phase 9 wires the ledger — fail-safe,
   * never a wrong full close. The backtest/golden runner uses the 5-arg overload where tiers are active.
   */
  public static Optional<ExitDecision> evaluate(
      StrategyDefinition definition, IndicatorBank bank, Position position, int primaryIndex) {
    return evaluate(definition, bank, position, primaryIndex, Set.of(), false);
  }

  /**
   * The backtest/golden-runner entry point — first match in precedence order wins, {@code scaled_exit}
   * tiers ACTIVE. {@code firedTiers} are the tier indices already closed on this position, so a tier
   * never re-fires; a scaled tier returns a PARTIAL {@link ExitDecision} (its {@code qty_pct}) and the
   * position stays open for the remainder. Protective stops still win the precedence and close it all.
   */
  public static Optional<ExitDecision> evaluate(
      StrategyDefinition definition,
      IndicatorBank bank,
      Position position,
      int primaryIndex,
      Set<Integer> firedTiers) {
    return evaluate(definition, bank, position, primaryIndex, firedTiers, true);
  }

  private static Optional<ExitDecision> evaluate(
      StrategyDefinition definition,
      IndicatorBank bank,
      Position position,
      int primaryIndex,
      Set<Integer> firedTiers,
      boolean scaledEnabled) {
    EngineSeries series = bank.primarySeries();
    BigDecimal close = series.candle(primaryIndex).close();

    for (String type :
        new String[] {
          "stop_loss", "trailing_stop", "take_profit", "scaled_exit", "square_off", "time_stop",
          "signal_exit"
        }) {
      for (StrategyDefinition.ExitRuleSpec rule : definition.exitRules()) {
        if (!rule.type().equals(type)) {
          continue;
        }
        Optional<ExitDecision> decision =
            switch (type) {
              case "stop_loss" -> level(definition, series, position, rule, close, true);
              case "take_profit" -> level(definition, series, position, rule, close, false);
              case "trailing_stop" -> trailing(bank, series, position, primaryIndex, rule, close);
              case "scaled_exit" ->
                  scaledEnabled ? scaledExit(position, close, rule, firedTiers) : Optional.empty();
              case "square_off" -> squareOff(series, position, primaryIndex, rule, close);
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

  /**
   * Sell-into-strength (MV-7.4): the lowest not-yet-fired tier whose {@code profit_pct} the position
   * has reached returns a partial close of its {@code qty_pct} (fraction of the ORIGINAL position).
   * One tier per bar — a gap past two tiers fires the nearer this bar, the next on the following bar.
   */
  private static Optional<ExitDecision> scaledExit(
      Position position, BigDecimal close, StrategyDefinition.ExitRuleSpec rule, Set<Integer> firedTiers) {
    if (!(rule.params().get("tiers") instanceof List<?> tiers)) {
      return Optional.empty();
    }
    boolean isLong = position.direction() == Direction.LONG;
    for (int i = 0; i < tiers.size(); i++) {
      if (firedTiers.contains(i) || !(tiers.get(i) instanceof Map<?, ?> tier)) {
        continue;
      }
      BigDecimal profitPct = decimal(tier.get("profit_pct"));
      BigDecimal qtyPct = decimal(tier.get("qty_pct"));
      if (profitPct == null || qtyPct == null) {
        continue;
      }
      BigDecimal gainPct =
          (isLong ? close.subtract(position.entryPrice()) : position.entryPrice().subtract(close))
              .divide(position.entryPrice(), EngineMath.MC)
              .multiply(EngineMath.HUNDRED, EngineMath.MC);
      if (gainPct.compareTo(profitPct) >= 0) {
        return Optional.of(
            new ExitDecision(
                "scaled_exit", "tier " + i + " +" + profitPct + "% sell " + qtyPct, qtyPct, i));
      }
    }
    return Optional.empty();
  }

  /**
   * Manas §3.5C square-off (long-only doctrine): exit when the trend goes exhaustive — either a
   * TOO-FAST move (close ≥ close[{@code fast_bars} ago]×(1+{@code fast_pct}%)) OR a PARABOLIC
   * extension (close ≥ SMA({@code parabolic_ma})×(1+{@code parabolic_dist_pct}%)). Mirrors
   * {@code ManasAroraSwingBacktest.positionExit} (bar-close close/SMA reads, entry-independent), so
   * it is fully deterministic. The SMA warms {@code parabolic_ma} bars; before that the parabolic
   * clause is simply inert. Any param absent → that clause is skipped (both absent → never fires).
   */
  private static Optional<ExitDecision> squareOff(
      EngineSeries series,
      Position position,
      int primaryIndex,
      StrategyDefinition.ExitRuleSpec rule,
      BigDecimal close) {
    Map<String, Object> params = rule.params();
    BigDecimal fastPct = decimal(params.get("fast_pct"));
    Object fastBarsRaw = params.get("fast_bars");
    if (fastPct != null && fastBarsRaw != null) {
      int fastBars = ((Number) fastBarsRaw).intValue();
      if (primaryIndex - fastBars >= 0) {
        BigDecimal past = series.candle(primaryIndex - fastBars).close();
        BigDecimal trigger =
            past.multiply(
                BigDecimal.ONE.add(fastPct.divide(EngineMath.HUNDRED, EngineMath.MC)), EngineMath.MC);
        if (close.compareTo(trigger) >= 0) {
          return Optional.of(
              new ExitDecision("square_off", "too-fast +" + fastPct + "% in " + fastBars + " bars"));
        }
      }
    }
    Object parabolicMaRaw = params.get("parabolic_ma");
    BigDecimal parabolicDistPct = decimal(params.get("parabolic_dist_pct"));
    if (parabolicMaRaw != null && parabolicDistPct != null) {
      int parabolicMa = ((Number) parabolicMaRaw).intValue();
      BigDecimal sma =
          IndicatorRegistry.create("SMA", series, null, Map.of("period", parabolicMa))
              .valueAt(primaryIndex);
      if (sma != null && sma.signum() > 0) {
        BigDecimal trigger =
            sma.multiply(
                BigDecimal.ONE.add(parabolicDistPct.divide(EngineMath.HUNDRED, EngineMath.MC)),
                EngineMath.MC);
        if (close.compareTo(trigger) >= 0) {
          return Optional.of(
              new ExitDecision(
                  "square_off", "parabolic +" + parabolicDistPct + "% over sma" + parabolicMa));
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
      case "premium_pct", "percent" ->
          // percent-of-entry-price distance: premium_pct on an option leg, percent on cash equity
          // (Minervini MV-7.2 initial stop, e.g. 8% below entry). Same formula, clearer name.
          position.entryPrice().multiply(value, EngineMath.MC).divide(EngineMath.HUNDRED, EngineMath.MC);
      case "atr_multiple" -> {
        BigDecimal atr = atrAtEntry(series, position, params);
        if (atr == null) {
          yield null;
        }
        BigDecimal atrDistance = atr.multiply(value, EngineMath.MC);
        // Manas §3.5A: an optional cap_pct bounds the stop DISTANCE at cap_pct% of entry — the
        // tighter of mult×ATR and the cap wins, so a wide-ATR stop never exceeds ~10%. Absent →
        // unchanged (parity-safe-additive). Applies to stop/take_profit; harmless on take_profit.
        BigDecimal capPct = decimal(params.get("cap_pct"));
        if (capPct == null) {
          yield atrDistance;
        }
        BigDecimal cap =
            position.entryPrice().multiply(capPct, EngineMath.MC).divide(EngineMath.HUNDRED, EngineMath.MC);
        yield atrDistance.min(cap);
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
      IndicatorBank bank,
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
      // Manas §3.5B: an optional arm_pct keeps the ATR trail INERT until the favourable extreme is
      // up arm_pct off entry — before then the initial stop holds. Absent → armed from entry, so
      // every existing atr_multiple trail is byte-identical. Deterministic: the peak is a bar-high
      // function, the ATR is the entry-ATR, so once armed the stop = peak − mult×ATR ratchets up.
      BigDecimal armPct = decimal(params.get("arm_pct"));
      if (armPct != null) {
        BigDecimal gainPct =
            (isLong ? peak.subtract(position.entryPrice()) : position.entryPrice().subtract(peak))
                .divide(position.entryPrice(), EngineMath.MC)
                .multiply(EngineMath.HUNDRED, EngineMath.MC);
        if (gainPct.compareTo(armPct) < 0) {
          return Optional.empty();
        }
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
    if ("indicator".equals(basis)) {
      // trail to a named level (psar / vwap / supertrend-line): exit when price crosses it. Needs
      // the bank, so it is bar-close-ONLY — the intrabar path passes a null bank (level → skip).
      BigDecimal level = indicatorLevel(bank, String.valueOf(params.get("alias")), index);
      if (level == null) {
        return Optional.empty();
      }
      boolean hit = isLong ? close.compareTo(level) <= 0 : close.compareTo(level) >= 0;
      return hit
          ? Optional.of(
              new ExitDecision("trailing_stop", "trailed to " + params.get("alias") + " " + level))
          : Optional.empty();
    }
    return Optional.empty();
  }

  /**
   * Resolves a trailing {@code indicator} alias to a price level: a built-in operand
   * ({@code close/volume/vwap}) via {@link IndicatorBank#builtin}, else a declared indicator alias via
   * {@link IndicatorBank#valueAt} (guarded by {@code has} so an undeclared alias yields null, never the
   * "alias not in the bank" throw). Null when the bank is absent (the intrabar path) or the alias is
   * unknown/warming.
   */
  private static BigDecimal indicatorLevel(IndicatorBank bank, String alias, int index) {
    if (bank == null) {
      return null;
    }
    if (BarValues.isBuiltin(alias)) {
      return bank.builtin(alias, index);
    }
    return bank.has(alias) ? bank.valueAt(alias, index) : null;
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
