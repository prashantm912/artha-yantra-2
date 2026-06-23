package in.arthayantra.backtest.replay.options;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.backtest.replay.EquityCurveDownsampler;
import in.arthayantra.backtest.replay.EquityPoint;
import in.arthayantra.backtest.replay.ReplayResult;
import in.arthayantra.backtest.replay.Trade;
import in.arthayantra.backtest.replay.options.OptionContractSelector.ExpiryMode;
import in.arthayantra.backtest.replay.options.OptionContractSelector.OptionContract;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategyengine.fills.TouchBasis;
import in.arthayantra.strategyengine.golden.GoldenSignalsJson.SignalEvent;
import in.arthayantra.strategyengine.golden.TickwiseGoldenRunner;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Premium-as-primary replay (Part 2, the engine-swap assembly). Signals are generated on the UNDERLYING
 * (unchanged, parity-preserving) and paired into directed legs upstream; this turns each leg into a
 * trade on the OPTION it would actually buy — long bias buys the ATM CE, short the ATM PE — filled and
 * P&L'd on the option's OWN premium series ({@link PremiumSource#CANDLE_1M}), with the {@code
 * premium_pct} exits ({@link PremiumExitEvaluator}) replacing the signal-only exit.
 *
 * <p>A SEPARATE path from {@code ReplayEngine.replay} (the candle-close path) — so the existing
 * Phase-30 parity goldens stay byte-identical; this path gets its own golden. Per leg: resolve the
 * contract from the spot at entry ({@link OptionContractSelector}), read its premium series ({@link
 * CandlePremiumReader}), align it to the underlying bars (carry-forward), then evaluate the exit.
 */
@Component
public class OptionsPremiumReplay {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final OptionContractSelector selector;
  private final CandlePremiumReader premiumReader;

  public OptionsPremiumReplay(OptionContractSelector selector, CandlePremiumReader premiumReader) {
    this.selector = selector;
    this.premiumReader = premiumReader;
  }

  /**
   * Premium-as-primary replay for an options strategy: signals are generated on the UNDERLYING
   * (unchanged, parity-preserving), paired, and each leg is replayed as an option trade. The §D.6
   * options spec + premium_pct exits + premium budget are parsed from the config. The candle-close
   * {@code ReplayEngine.replay} path is left untouched (its goldens hold).
   */
  public ReplayResult replay(
      StrategyDefinition definition,
      JsonNode config,
      String underlyingExchange,
      String underlyingTradingsymbol,
      List<EngineCandle> underlyingOneMinute,
      Map<SeriesKey, List<EngineCandle>> contextCandles,
      BigDecimal initialEquity) {
    List<SignalEvent> signals =
        new TickwiseGoldenRunner(definition, underlyingExchange, underlyingTradingsymbol)
            .run(underlyingOneMinute, contextCandles, null);
    List<PairedLeg> legs = pairLegs(signals, underlyingOneMinute);
    return replayLegs(
        signals,
        underlyingOneMinute,
        legs,
        registryUnderlying(underlyingTradingsymbol),
        universeSpec(config),
        exitRules(config),
        budgetInr(config),
        initialEquity);
  }

  /** Pairs the signal stream into directed legs (entry→exit bar indices), mirroring the candle path. */
  static List<PairedLeg> pairLegs(List<SignalEvent> signals, List<EngineCandle> underlying) {
    Map<String, Integer> idx = new HashMap<>();
    for (int i = 0; i < underlying.size(); i++) {
      idx.put(underlying.get(i).bucketStart().toString(), i);
    }
    int lastBar = Math.max(underlying.size() - 1, 0);
    List<PairedLeg> legs = new ArrayList<>();
    SignalEvent open = null;
    for (SignalEvent ev : signals) {
      if ("EXIT".equals(ev.direction())) {
        if (open != null) {
          legs.add(
              new PairedLeg(
                  "SHORT".equals(open.direction()),
                  idx.getOrDefault(open.timestamp(), 0),
                  idx.getOrDefault(ev.timestamp(), lastBar)));
          open = null;
        }
      } else if (open == null) {
        open = ev;
      }
    }
    if (open != null) {
      legs.add(
          new PairedLeg("SHORT".equals(open.direction()), idx.getOrDefault(open.timestamp(), 0), lastBar));
    }
    return legs;
  }

  /** The registry underlying symbol (e.g. {@code NIFTY 50} → {@code NIFTY}). */
  static String registryUnderlying(String tradingsymbol) {
    int sp = tradingsymbol.indexOf(' ');
    return sp < 0 ? tradingsymbol : tradingsymbol.substring(0, sp);
  }

  /** Parses {@code universe.options} → the expiry rule + allowed sides (the traded strike is the ATM). */
  static UniverseSpec universeSpec(JsonNode config) {
    JsonNode opts = config.path("universe").path("options");
    JsonNode expiry = opts.path("expiry");
    ExpiryMode mode;
    int offset = 0;
    if (expiry.isTextual()) {
      mode =
          "nearest_monthly".equals(expiry.asText())
              ? ExpiryMode.NEAREST_MONTHLY
              : ExpiryMode.NEAREST_WEEKLY;
    } else {
      mode = ExpiryMode.OFFSET;
      offset = expiry.path("offset").asInt(0);
    }
    Set<String> types = new HashSet<>();
    for (JsonNode t : opts.path("option_types")) {
      types.add(t.asText());
    }
    if (types.isEmpty()) {
      types = Set.of("CE", "PE");
    }
    return new UniverseSpec(mode, offset, types);
  }

  /** Parses {@code exit_rules} → the premium-pct thresholds (signal_exit stays on the signal stream). */
  static PremiumExitEvaluator.Rules exitRules(JsonNode config) {
    BigDecimal sl = null;
    BigDecimal tp = null;
    BigDecimal trailAt = null;
    BigDecimal trailBy = null;
    Integer timeBars = null;
    for (JsonNode rule : config.path("exit_rules")) {
      JsonNode p = rule.path("params");
      switch (rule.path("type").asText()) {
        case "stop_loss" -> {
          if (isPremiumPct(p)) {
            sl = dec(p, "value");
          }
        }
        case "take_profit" -> {
          if (isPremiumPct(p)) {
            tp = dec(p, "value");
          }
        }
        case "trailing_stop" -> {
          trailAt = dec(p, "activate_at");
          trailBy = dec(p, "trail_by");
        }
        case "time_stop" -> {
          if (p.has("max_bars")) {
            timeBars = p.path("max_bars").asInt();
          }
        }
        default -> {
          /* signal_exit etc. — handled by the signal stream */
        }
      }
    }
    return new PremiumExitEvaluator.Rules(sl, tp, trailAt, trailBy, timeBars);
  }

  /** The premium budget per trade ({@code risk.position_sizing.params.budget_inr}); 0 ⇒ no trades. */
  static long budgetInr(JsonNode config) {
    return config.path("risk").path("position_sizing").path("params").path("budget_inr").asLong(0);
  }

  private static boolean isPremiumPct(JsonNode params) {
    return "premium_pct".equals(params.path("basis").asText());
  }

  private static BigDecimal dec(JsonNode params, String field) {
    JsonNode n = params.path(field);
    return n.isMissingNode() || n.isNull() ? null : new BigDecimal(n.asText());
  }

  /** An underlying-derived directed leg: the entry/exit bar indices on the underlying 1m series. */
  public record PairedLeg(boolean shortSide, int entryIndex, int exitIndex) {}

  /** The resolved §D.6 {@code options_of_underlying} selection knobs (strike is always the ATM leg). */
  public record UniverseSpec(ExpiryMode expiryMode, int expiryOffset, Set<String> optionTypes) {}

  /**
   * Replays the underlying-derived legs into option trades + an equity curve. cash + realized P&L steps
   * at each trade's exit bar (v1 realized-step equity; per-bar mark-to-market of an open premium is a
   * follow-up). Signals are paired upstream and passed through onto the result. Deterministic — a
   * premium golden pins it.
   */
  public ReplayResult replayLegs(
      List<in.arthayantra.strategyengine.golden.GoldenSignalsJson.SignalEvent> signals,
      List<EngineCandle> underlying,
      List<PairedLeg> legs,
      String underlyingSymbol,
      UniverseSpec spec,
      PremiumExitEvaluator.Rules rules,
      long budgetInr,
      BigDecimal initialEquity) {
    List<Trade> trades = new ArrayList<>();
    Map<Integer, BigDecimal> pnlByExitBar = new HashMap<>();
    int seq = 0;
    for (PairedLeg leg : legs) {
      Optional<Trade> t =
          tradeForLeg(seq + 1, underlying, underlyingSymbol, leg, spec, rules, budgetInr);
      if (t.isEmpty()) {
        continue;
      }
      seq++;
      Trade trade = t.get();
      trades.add(trade);
      pnlByExitBar.merge(leg.entryIndex() + trade.barsHeld(), trade.pnl(), BigDecimal::add);
    }

    List<EquityPoint> equity = new ArrayList<>(underlying.size());
    List<EquityPoint> drawdown = new ArrayList<>(underlying.size());
    BigDecimal running = initialEquity;
    BigDecimal peak = initialEquity;
    for (int b = 0; b < underlying.size(); b++) {
      BigDecimal applied = pnlByExitBar.get(b);
      if (applied != null) {
        running = running.add(applied);
      }
      OffsetDateTime ts = underlying.get(b).bucketStart();
      equity.add(new EquityPoint(ts, running.setScale(2, RoundingMode.HALF_UP)));
      if (running.compareTo(peak) > 0) {
        peak = running;
      }
      BigDecimal dd =
          peak.signum() == 0
              ? BigDecimal.ZERO
              : peak.subtract(running).multiply(HUNDRED).divide(peak, 6, RoundingMode.HALF_UP);
      drawdown.add(new EquityPoint(ts, dd));
    }
    long barsInPosition = trades.stream().mapToLong(Trade::barsHeld).sum();
    return new ReplayResult(
        signals,
        trades,
        EquityCurveDownsampler.downsample(equity, 500),
        EquityCurveDownsampler.downsample(drawdown, 500),
        initialEquity,
        running.setScale(2, RoundingMode.HALF_UP),
        underlying.size(),
        barsInPosition);
  }

  /**
   * The premium trade for one leg, or empty when there's no tradeable contract (bias side not allowed,
   * no expiry/strike listed, the option's premium series doesn't cover the entry, or the budget can't
   * afford one lot). Long-premium only: a BUY at entry, sold to close at the premium exit.
   *
   * @param seq the 1-based trade sequence number
   * @param underlying the underlying 1m bars (the signal/spot series)
   * @param underlyingSymbol the registry underlying (e.g. {@code NIFTY})
   * @param leg the directed leg (entry/exit indices into {@code underlying})
   * @param spec the expiry rule + allowed option types
   * @param rules the premium-pct exit thresholds
   * @param budgetInr the premium budget per trade (lots = floor(budget / (premium × lot)))
   */
  public Optional<Trade> tradeForLeg(
      int seq,
      List<EngineCandle> underlying,
      String underlyingSymbol,
      PairedLeg leg,
      UniverseSpec spec,
      PremiumExitEvaluator.Rules rules,
      long budgetInr) {
    EngineCandle entryBar = underlying.get(leg.entryIndex());
    boolean longBias = !leg.shortSide();
    Optional<OptionContract> resolved =
        selector.select(
            underlyingSymbol,
            entryBar.close(),
            entryBar.bucketStart().toLocalDate(),
            spec.expiryMode(),
            spec.expiryOffset(),
            longBias,
            spec.optionTypes());
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    OptionContract contract = resolved.get();

    EngineCandle exitBarSignal = underlying.get(leg.exitIndex());
    NavigableMap<OffsetDateTime, BigDecimal> series =
        premiumReader.premiumSeries(
            contract, entryBar.bucketStart(), exitBarSignal.bucketStart().plusMinutes(1));

    // align the premium to each underlying bar from entry to the signal-exit (carry-forward)
    List<BigDecimal> premiums = new ArrayList<>(leg.exitIndex() - leg.entryIndex() + 1);
    for (int j = leg.entryIndex(); j <= leg.exitIndex(); j++) {
      premiums.add(CandlePremiumReader.premiumAt(series, underlying.get(j).bucketStart()));
    }
    BigDecimal entryPremium = premiums.get(0);
    if (entryPremium == null || entryPremium.signum() <= 0) {
      return Optional.empty(); // no premium has traded at entry — can't price the leg
    }

    long lot = contract.lotSize();
    long lots = entryPremium.multiply(BigDecimal.valueOf(lot)).signum() == 0
        ? 0
        : BigDecimal.valueOf(budgetInr)
            .divide(entryPremium.multiply(BigDecimal.valueOf(lot)), 0, RoundingMode.DOWN)
            .longValue();
    long qty = lots * lot;
    if (qty <= 0) {
      return Optional.empty(); // budget can't afford one lot at this premium
    }

    int signalExitOffset = leg.exitIndex() - leg.entryIndex();
    PremiumExitEvaluator.Exit exit =
        PremiumExitEvaluator.evaluate(entryPremium, premiums, rules, signalExitOffset);
    EngineCandle exitBar = underlying.get(leg.entryIndex() + exit.barOffset());

    BigDecimal pnl =
        exit.premium().subtract(entryPremium).multiply(BigDecimal.valueOf(qty))
            .setScale(2, RoundingMode.HALF_UP);
    BigDecimal notional = entryPremium.multiply(BigDecimal.valueOf(qty));
    BigDecimal pnlPct =
        notional.signum() == 0
            ? BigDecimal.ZERO
            : pnl.multiply(HUNDRED).divide(notional, 6, RoundingMode.HALF_UP);

    return Optional.of(
        new Trade(
            seq,
            Side.BUY,
            qty,
            entryBar.bucketStart(),
            entryPremium,
            exitBar.bucketStart(),
            exit.premium(),
            pnl,
            pnlPct,
            exit.reason(),
            exit.barOffset(),
            TouchBasis.CLOSE_EVAL,
            null,
            contract.exchange(),
            contract.tradingsymbol(),
            level(entryPremium, rules.stopLossPct(), false),
            level(entryPremium, rules.takeProfitPct(), true)));
  }

  private static BigDecimal level(BigDecimal entry, BigDecimal pct, boolean up) {
    if (pct == null) {
      return null;
    }
    BigDecimal frac = pct.movePointLeft(2);
    return entry.multiply(up ? BigDecimal.ONE.add(frac) : BigDecimal.ONE.subtract(frac));
  }
}
