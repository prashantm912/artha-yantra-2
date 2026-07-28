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
        // `min_premium_inr` — the premium below which this is not a trade worth sizing. A
        // near-worthless deep-OTM/expiry-day option (₹0.10) makes lots = budget/premium EXPLODE, so
        // the per-lot cost stack dwarfs the budget and one trade can lose multiples of the premium
        // paid — physically impossible for a long option. OptionsPremiumReplay has skipped those
        // entries since it was written (`minPremiumInr`, ~line 844); LIVE had no floor at all, and
        // the schema forbade anyone from declaring one, so three layers disagreed about a param that
        // no valid strategy could set. Same divergence class that hid the G7 defect for three weeks.
        //
        // ABSENT IS A NO-OP — deliberately NOT the replay's ₹1 default. Adopting that default here
        // would silently change sizing for every existing config and move the goldens; a floor only
        // binds when a strategy asks for one. That asymmetry is real and is called out in the PR
        // rather than papered over: a config that sets nothing still differs between live and replay.
        BigDecimal minPremium = optionalDecimalParam(spec.params(), "min_premium_inr");
        if (minPremium != null && inputs.price() != null && inputs.price().compareTo(minPremium) < 0) {
          yield 0;
        }
        BigDecimal perLotCost = inputs.price().multiply(BigDecimal.valueOf(lot), EngineMath.MC);
        long lots =
            perLotCost.signum() <= 0
                ? 0
                : budget.divide(perLotCost, 0, RoundingMode.FLOOR).longValueExact();
        // `max_lots` bounds the COUNT, because a fixed budget buys the most lots exactly when the
        // premium is cheapest — i.e. the far-expiry-day, thinnest, most gamma-exposed contract of
        // the month. Measured: at a ₹20,000 budget a NIFTY leg averages 2.7 lots but reaches 36
        // (2,340 units) on a ₹549/lot expiry-day close.
        //
        // The backtest replay has honoured this same param, with these same semantics (0/absent ⇒
        // unlimited), since it was written — `OptionsPremiumReplay.maxLots`. LIVE ignored it, so the
        // two sized differently for any config that set it. That is the divergence class that hid
        // the G7 defect for three weeks, so it is closed here rather than left to the next reader.
        long maxLots = maxLots(spec);
        if (maxLots > 0 && lots > maxLots) {
          lots = maxLots;
        }
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

  /**
   * The declared lot cap, or 0 for "unlimited" — the same semantic {@code OptionsPremiumReplay.maxLots}
   * has always used, so live and replay read one config key one way.
   *
   * <p>Public because the cap must ALSO bind on the hero-zero profit-funded path, which overrides
   * this sizer's answer entirely. Enforcing it in two places off one reader is what keeps them from
   * drifting; a second private copy is exactly how the live-vs-replay gap opened in the first place.
   */
  public static long maxLots(StrategyDefinition.SizingSpec spec) {
    return optionalLongParam(spec.params(), "max_lots");
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

  /**
   * An optional numeric param; absent ⇒ 0. Deliberately lenient about the boxed type — YAML and JSON
   * round-trips hand us Integer, Long or BigDecimal for the same literal depending on the path, so
   * reading through {@link Number} keeps a config's meaning independent of how it was parsed.
   */
  /** An optional decimal param; absent ⇒ null (the caller treats that as "no bound declared"). */
  private static BigDecimal optionalDecimalParam(Map<String, Object> params, String name) {
    Object value = params.get(name);
    if (value == null) {
      return null;
    }
    return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
  }

  private static long optionalLongParam(Map<String, Object> params, String name) {
    Object value = params.get(name);
    if (value == null) {
      return 0;
    }
    return value instanceof Number n ? n.longValue() : new BigDecimal(value.toString()).longValue();
  }
}
