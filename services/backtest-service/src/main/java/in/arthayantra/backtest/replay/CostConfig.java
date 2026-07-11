package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyengine.fills.FillSimulator.Brokerage;
import in.arthayantra.strategyengine.fills.FillSimulator.Fees;
import in.arthayantra.strategyengine.fills.FillSimulator.Slippage;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

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
   * statutory stacks. The candle path only ever resolves EQUITY / FUTURE for real strategies (an
   * options strategy routes to the premium replay, which builds its own per-leg OPTION config with the
   * REAL lot size); the OPTION branch is reachable only by auto-detecting an explicit NFO/BFO
   * option-series universe instrument — {@code optionDefaults(1)} then prices per-lot brokerage at lot
   * 1 (₹20 per unit), a disclosed approximation since the candle path has no contract catalog to
   * resolve the true lot size. {@code costs.instrumentClass} REFUSES {@code OPTION}
   * ({@link #withOverrides}) so that corner cannot be forced by request.
   */
  public static CostConfig forClass(InstrumentClass instrumentClass) {
    return switch (instrumentClass) {
      case EQUITY -> defaults();
      case FUTURE -> futureDefaults();
      case OPTION -> optionDefaults(1);
    };
  }

  private static final Set<String> COSTS_KEYS = Set.of("instrumentClass", "slippage", "brokerage");
  private static final Set<String> SLIPPAGE_KEYS = Set.of("ticks", "bps");
  private static final Set<String> BROKERAGE_KEYS = Set.of("perLotInr", "pctPerSide");

  /**
   * Applies the request-level {@code costs} block (audit B4 / P1-2) on top of this derived per-class
   * config. An absent ({@code null}/missing/JSON-null) or empty ({@code {}}) block returns
   * {@code this} unchanged — so a run without a {@code costs} block stays byte-identical to the
   * auto-derived defaults (goldens hold). The grammar (STRICT — see below):
   *
   * <pre>
   * costs: {
   *   "instrumentClass": "EQUITY" | "FUTURE",              // re-base the whole statutory stack
   *   "slippage":  { "ticks": N } | { "bps": X },          // override the per-class slippage fallback
   *   "brokerage": { "perLotInr": X, "pctPerSide": Y }     // override the brokerage model
   * }
   * </pre>
   *
   * <p><b>Parsing is fail-loud, never fail-soft</b> (money-lens review of #B4): every violation
   * throws {@link IllegalArgumentException} — unknown keys anywhere in the block, a non-object
   * {@code costs}/{@code slippage}/{@code brokerage}, a quoted-string or non-integral {@code ticks},
   * a quoted-string {@code bps}/{@code perLotInr}/{@code pctPerSide}, or a negative number. The
   * failure this precludes is silent zeroing: {@code "bps": "8"} read leniently would become an
   * EXPLICIT {@code Slippage.bps(0)} that bypasses the per-class fallback — zero slippage that a
   * cost-stress multiplier then scales to zero, feeding false robustness into the deflated-Sharpe /
   * plateau gates. {@code instrumentClass} accepts EQUITY or FUTURE only: OPTION is refused because
   * the candle path cannot resolve a real option lot size (options strategies route to the premium
   * replay, which prices its own OPTION stack).
   *
   * <p>An explicit {@code instrumentClass} re-bases to that class's {@link #forClass} defaults FIRST,
   * then the finer {@code slippage}/{@code brokerage} overrides apply. The outer record's cost-stress
   * {@code slippageMultiplier} is preserved through the re-base and re-applied on the result — kept
   * independent of this knob so the stressOverrides "never widens a fill" doctrine (#727) holds.
   */
  public CostConfig withOverrides(JsonNode costs) {
    if (costs == null || costs.isMissingNode() || costs.isNull()) {
      return this;
    }
    if (!costs.isObject()) {
      throw new IllegalArgumentException("costs must be a JSON object; got: " + costs.getNodeType());
    }
    if (costs.isEmpty()) {
      return this;
    }
    requireKnownKeys(costs, COSTS_KEYS, "costs");
    CostConfig base =
        costs.hasNonNull("instrumentClass") ? forClass(parseClass(costs.get("instrumentClass"))) : this;
    Slippage slippage = parseSlippage(costs.path("slippage"), base.slippage);
    Brokerage brokerage = parseBrokerage(costs.path("brokerage"), base.brokerage);
    return new CostConfig(
        base.instrumentClass,
        base.tickSize,
        base.lotSize,
        slippage,
        brokerage,
        base.fees,
        // the OUTER record's multiplier — a re-base via forClass() resets it to 1, which would
        // silently drop an already-applied stress (review finding 5)
        slippageMultiplier);
  }

  private static void requireKnownKeys(JsonNode node, Set<String> allowed, String where) {
    node.fieldNames()
        .forEachRemaining(
            key -> {
              if (!allowed.contains(key)) {
                throw new IllegalArgumentException(
                    where + " has unknown key '" + key + "'; allowed: " + allowed);
              }
            });
  }

  private static InstrumentClass parseClass(JsonNode node) {
    if (!node.isTextual()) {
      throw new IllegalArgumentException(
          "costs.instrumentClass must be a JSON string (EQUITY or FUTURE); got: " + node);
    }
    InstrumentClass cls;
    try {
      cls = InstrumentClass.valueOf(node.asText().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "costs.instrumentClass must be EQUITY or FUTURE; got: " + node.asText());
    }
    if (cls == InstrumentClass.OPTION) {
      throw new IllegalArgumentException(
          "costs.instrumentClass OPTION is not supported on the candle path (no lot size to price"
              + " per-lot brokerage against) — an options strategy routes to the premium replay"
              + " (universe.mode: options_of_underlying), which prices its own OPTION stack");
    }
    return cls;
  }

  private static Slippage parseSlippage(JsonNode node, Slippage fallback) {
    if (node.isMissingNode() || node.isNull()) {
      return fallback;
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException(
          "costs.slippage must be a JSON object {ticks} or {bps}; got: " + node);
    }
    requireKnownKeys(node, SLIPPAGE_KEYS, "costs.slippage");
    boolean hasTicks = node.hasNonNull("ticks");
    boolean hasBps = node.hasNonNull("bps");
    if (hasTicks && hasBps) {
      throw new IllegalArgumentException("costs.slippage: at most one of ticks / bps may be set");
    }
    if (hasTicks) {
      JsonNode ticks = node.get("ticks");
      if (!ticks.isIntegralNumber()) {
        throw new IllegalArgumentException(
            "costs.slippage.ticks must be an integral JSON number; got: " + ticks);
      }
      int value = ticks.asInt();
      if (value < 0) {
        throw new IllegalArgumentException("costs.slippage.ticks must be >= 0; got: " + value);
      }
      return Slippage.ticks(value);
    }
    if (hasBps) {
      return Slippage.bps(requireNonNegativeNumber(node.get("bps"), "costs.slippage.bps"));
    }
    return fallback;
  }

  private static Brokerage parseBrokerage(JsonNode node, Brokerage fallback) {
    if (node.isMissingNode() || node.isNull()) {
      return fallback;
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException(
          "costs.brokerage must be a JSON object {perLotInr, pctPerSide}; got: " + node);
    }
    requireKnownKeys(node, BROKERAGE_KEYS, "costs.brokerage");
    BigDecimal perLotInr =
        node.hasNonNull("perLotInr")
            ? requireNonNegativeNumber(node.get("perLotInr"), "costs.brokerage.perLotInr")
            : fallback.perLotInr();
    BigDecimal pctPerSide =
        node.hasNonNull("pctPerSide")
            ? requireNonNegativeNumber(node.get("pctPerSide"), "costs.brokerage.pctPerSide")
            : fallback.pctPerSide();
    return new Brokerage(perLotInr, pctPerSide);
  }

  private static BigDecimal requireNonNegativeNumber(JsonNode node, String where) {
    if (!node.isNumber()) {
      throw new IllegalArgumentException(
          where + " must be a JSON number (not a quoted string); got: " + node);
    }
    BigDecimal value = node.decimalValue();
    if (value.signum() < 0) {
      throw new IllegalArgumentException(where + " must be >= 0; got: " + value.toPlainString());
    }
    return value;
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
