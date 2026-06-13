package in.arthayantra.strategyengine.eval;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.indicators.EngineMath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Position sizing v1 (§C-2.11): fixed_quantity, percent_equity, premium_budget, atr_risk,
 * kelly_fraction (HARD-CAPPED at 0.25 — re-capped here defensively even though the schema
 * enforces it). Quantities are LOT-ROUNDED DOWN, never fractional; below one lot sizes to zero
 * (the engine emits no trade rather than a fantasy fill).
 */
public final class PositionSizer {

  /** Inputs the methods draw from; unused fields may be null for a given method. */
  public record Inputs(
      BigDecimal equity, BigDecimal price, BigDecimal stopDistance, long lotSize) {}

  private static final BigDecimal KELLY_CAP = new BigDecimal("0.25");

  private PositionSizer() {}

  /** Computes the lot-rounded quantity (units, multiple of lotSize; 0 when unaffordable). */
  public static long size(StrategyDefinition.SizingSpec spec, Inputs inputs) {
    long lot = Math.max(1, inputs.lotSize());
    return switch (spec.method()) {
      case "fixed_quantity" -> lotRound(longParam(spec.params(), "quantity"), lot);
      case "percent_equity" -> {
        BigDecimal percent = decimalParam(spec.params(), "percent");
        BigDecimal budget =
            inputs.equity().multiply(percent, EngineMath.MC).divide(EngineMath.HUNDRED, EngineMath.MC);
        yield lotRound(unitsFor(budget, inputs.price()), lot);
      }
      case "premium_budget" -> {
        BigDecimal budget = decimalParam(spec.params(), "budget_inr");
        BigDecimal perLotCost = inputs.price().multiply(BigDecimal.valueOf(lot), EngineMath.MC);
        long lots =
            perLotCost.signum() <= 0
                ? 0
                : budget.divide(perLotCost, 0, RoundingMode.FLOOR).longValueExact();
        yield lots * lot;
      }
      case "atr_risk" -> {
        BigDecimal riskPct = decimalParam(spec.params(), "risk_pct_equity");
        if (inputs.stopDistance() == null || inputs.stopDistance().signum() <= 0) {
          yield 0;
        }
        BigDecimal riskBudget =
            inputs.equity().multiply(riskPct, EngineMath.MC).divide(EngineMath.HUNDRED, EngineMath.MC);
        yield lotRound(
            riskBudget.divide(inputs.stopDistance(), 0, RoundingMode.FLOOR).longValueExact(), lot);
      }
      case "kelly_fraction" -> {
        BigDecimal fraction = decimalParam(spec.params(), "fraction").min(KELLY_CAP);
        BigDecimal budget = inputs.equity().multiply(fraction, EngineMath.MC);
        yield lotRound(unitsFor(budget, inputs.price()), lot);
      }
      default -> throw new IllegalArgumentException("unknown sizing method " + spec.method());
    };
  }

  private static long unitsFor(BigDecimal budget, BigDecimal price) {
    if (price == null || price.signum() <= 0) {
      return 0;
    }
    return budget.divide(price, 0, RoundingMode.FLOOR).longValueExact();
  }

  private static long lotRound(long units, long lot) {
    return units - (units % lot);
  }

  private static BigDecimal decimalParam(Map<String, Object> params, String name) {
    Object value = params.get(name);
    if (value == null) {
      throw new IllegalArgumentException("sizing param '" + name + "' is required");
    }
    return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
  }

  private static long longParam(Map<String, Object> params, String name) {
    Object value = params.get(name);
    if (value == null) {
      throw new IllegalArgumentException("sizing param '" + name + "' is required");
    }
    return ((Number) value).longValue();
  }
}
