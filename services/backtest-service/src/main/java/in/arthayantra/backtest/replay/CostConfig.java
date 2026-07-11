package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyengine.fills.FillSimulator.Brokerage;
import in.arthayantra.strategyengine.fills.FillSimulator.Fees;
import in.arthayantra.strategyengine.fills.FillSimulator.Slippage;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * The fill/cost configuration a replay applies via the shared {@code FillSimulator} — the {@code
 * costs} block resolved against an instrument class, tick and lot size. Defaults give a credit-free
 * equity proxy (5 bps slippage fallback, statutory fees from {@link FeeConstants}).
 *
 * <p>{@code slippageMultiplier} is the request-level cost-stress knob (EVO §3.2.5): the effective
 * slippage the {@code FillSimulator} computes is scaled by it at fill construction. It defaults to
 * {@code 1} (both factories), which is byte-identical to the pre-stress behaviour — an absent
 * {@code stressOverrides} request field never widens a fill. Only stressed re-runs (a fresh run id,
 * never a golden input) carry a multiplier &gt; 1.
 */
public record CostConfig(
    InstrumentClass instrumentClass,
    BigDecimal tickSize,
    long lotSize,
    Slippage slippage,
    Brokerage brokerage,
    Fees fees,
    BigDecimal slippageMultiplier) {

  /** A plain equity proxy with the per-class slippage fallback and default statutory fees. */
  public static CostConfig defaults() {
    return new CostConfig(
        InstrumentClass.EQUITY,
        new BigDecimal("0.05"),
        1,
        Slippage.NONE,
        new Brokerage(null, new BigDecimal("0.03")),
        Fees.DEFAULTS,
        BigDecimal.ONE);
  }

  /**
   * The OPTION analog of {@link #defaults()} for the premium-as-primary leg (Part 2): the per-class
   * option slippage fallback ({@code max(1 tick, half-spread)} → 1 tick at ₹0.05 with no quoted
   * spread), ₹20/lot flat brokerage, and the pinned statutory schedule (STT-on-sell, exchange txn,
   * GST, stamp-on-buy, SEBI). Mirrors the candle path's {@code CostConfig.defaults()} so premium-leg
   * fills stay paisa-parity with the shared {@code FillSimulator}.
   */
  public static CostConfig optionDefaults(long lotSize) {
    return new CostConfig(
        InstrumentClass.OPTION,
        new BigDecimal("0.05"),
        lotSize,
        Slippage.NONE,
        new Brokerage(new BigDecimal("20"), null),
        Fees.DEFAULTS,
        BigDecimal.ONE);
  }

  /**
   * The FUTURE analog of {@link #defaults()} for a candle-path futures signal series (P1-2 / audit
   * B4). Same 0.03%-per-side percentage brokerage as equity, but the {@code FillSimulator}'s FUTURE
   * branch applies the ₹20 flat cap (A9 [FP-7]) and the sell-side-only 0.02% futures STT / futures
   * exchange-txn / futures stamp legs from {@link FeeConstants} — where {@link #defaults()} would
   * have charged the both-sided 0.10% equity-delivery STT. Slippage falls back to the 1-tick futures
   * floor. Pre-P1-2 the candle path pinned {@link #defaults()} for EVERY run, so an index-future
   * strategy was costed as equity delivery — the exact mis-specification this factory removes.
   */
  public static CostConfig futureDefaults() {
    return new CostConfig(
        InstrumentClass.FUTURE,
        new BigDecimal("0.05"),
        1,
        Slippage.NONE,
        new Brokerage(null, new BigDecimal("0.03")),
        Fees.DEFAULTS,
        BigDecimal.ONE);
  }

  /**
   * The per-class default cost stack for the candle path (P1-2). EQUITY reproduces {@link #defaults()}
   * byte-for-byte (so an existing equity-class run is unchanged); FUTURE and OPTION select their own
   * statutory stacks. The candle path only ever resolves EQUITY / FUTURE (an options strategy routes
   * to the premium replay, which builds its own OPTION config); OPTION here is defensive completeness
   * for a request that forces the class via {@code costs.instrumentClass}.
   */
  public static CostConfig forClass(InstrumentClass instrumentClass) {
    return switch (instrumentClass) {
      case EQUITY -> defaults();
      case FUTURE -> futureDefaults();
      case OPTION -> optionDefaults(1);
    };
  }

  /**
   * Applies the request-level {@code costs} block (audit B4 / P1-2) on top of this derived per-class
   * config. An absent, non-object, or empty block returns {@code this} unchanged — so a run without a
   * {@code costs} block stays byte-identical to the auto-derived defaults (goldens hold). The grammar:
   *
   * <pre>
   * costs: {
   *   "instrumentClass": "EQUITY" | "OPTION" | "FUTURE",   // re-base the whole statutory stack
   *   "slippage":  { "ticks": N } | { "bps": X },          // override the per-class slippage fallback
   *   "brokerage": { "perLotInr": X, "pctPerSide": Y }     // override the brokerage model
   * }
   * </pre>
   *
   * <p>An explicit {@code instrumentClass} re-bases to that class's {@link #forClass} defaults FIRST
   * (so forcing OPTION yields ₹20/lot brokerage + the option slippage floor, not a relabelled equity
   * stack), then the finer {@code slippage}/{@code brokerage} overrides apply. The cost-stress
   * {@code slippageMultiplier} is preserved untouched — it is layered LAST at the call site
   * ({@link #withSlippageMultiplier}), keeping the stressOverrides "never widens a fill" doctrine
   * (#727) independent of this knob. A malformed value fails loud (a silently-ignored cost override
   * would manufacture wrong net numbers), never silently drops.
   */
  public CostConfig withOverrides(JsonNode costs) {
    if (costs == null || !costs.isObject() || costs.isEmpty()) {
      return this;
    }
    CostConfig base =
        costs.hasNonNull("instrumentClass")
            ? forClass(parseClass(costs.get("instrumentClass").asText()))
            : this;
    Slippage slippage = parseSlippage(costs.path("slippage"), base.slippage);
    Brokerage brokerage = parseBrokerage(costs.path("brokerage"), base.brokerage);
    return new CostConfig(
        base.instrumentClass,
        base.tickSize,
        base.lotSize,
        slippage,
        brokerage,
        base.fees,
        base.slippageMultiplier);
  }

  private static InstrumentClass parseClass(String value) {
    try {
      return InstrumentClass.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "costs.instrumentClass must be one of EQUITY / OPTION / FUTURE, got: " + value);
    }
  }

  private static Slippage parseSlippage(JsonNode node, Slippage fallback) {
    if (node == null || !node.isObject() || node.isEmpty()) {
      return fallback;
    }
    boolean hasTicks = node.hasNonNull("ticks");
    boolean hasBps = node.hasNonNull("bps");
    if (hasTicks && hasBps) {
      throw new IllegalArgumentException("costs.slippage: at most one of ticks / bps may be set");
    }
    if (hasTicks) {
      return Slippage.ticks(node.get("ticks").asInt());
    }
    if (hasBps) {
      return Slippage.bps(node.get("bps").decimalValue());
    }
    return fallback;
  }

  private static Brokerage parseBrokerage(JsonNode node, Brokerage fallback) {
    if (node == null || !node.isObject() || node.isEmpty()) {
      return fallback;
    }
    BigDecimal perLotInr =
        node.hasNonNull("perLotInr") ? node.get("perLotInr").decimalValue() : fallback.perLotInr();
    BigDecimal pctPerSide =
        node.hasNonNull("pctPerSide") ? node.get("pctPerSide").decimalValue() : fallback.pctPerSide();
    return new Brokerage(perLotInr, pctPerSide);
  }

  /**
   * A copy with the cost-stress {@code slippageMultiplier} applied (EVO §3.2.5). A {@code null} or
   * {@code 1} multiplier returns an unstressed config — the parity path stays byte-identical.
   */
  public CostConfig withSlippageMultiplier(BigDecimal multiplier) {
    BigDecimal m = multiplier == null ? BigDecimal.ONE : multiplier;
    return new CostConfig(instrumentClass, tickSize, lotSize, slippage, brokerage, fees, m);
  }
}
