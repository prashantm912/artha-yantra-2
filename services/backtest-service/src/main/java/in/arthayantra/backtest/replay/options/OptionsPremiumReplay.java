package in.arthayantra.backtest.replay.options;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.backtest.client.MarketDataClient;
import in.arthayantra.backtest.replay.CostConfig;
import in.arthayantra.backtest.replay.EquityCurveDownsampler;
import in.arthayantra.backtest.replay.EquityPoint;
import in.arthayantra.backtest.replay.ReplayResult;
import in.arthayantra.backtest.replay.Trade;
import in.arthayantra.backtest.replay.options.OptionContractSelector.ExpiryMode;
import in.arthayantra.backtest.replay.options.OptionContractSelector.OptionContract;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.fills.FillSimulator;
import in.arthayantra.strategyengine.fills.FillSimulator.Fill;
import in.arthayantra.strategyengine.fills.FillSimulator.FillRequest;
import in.arthayantra.strategyengine.fills.LtpSlippageV1;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategyengine.fills.TouchBasis;
import in.arthayantra.strategyengine.golden.GoldenSignalsJson.SignalEvent;
import in.arthayantra.strategyengine.golden.TickwiseGoldenRunner;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
  private final MarketDataClient marketData; // null in the golden/unit path (gate never runs there)
  /** Shared fill model — the SAME one the candle path + paper ledger use (paisa-parity, §D.11). */
  private final FillSimulator fills = new LtpSlippageV1();

  @org.springframework.beans.factory.annotation.Autowired
  public OptionsPremiumReplay(
      OptionContractSelector selector,
      CandlePremiumReader premiumReader,
      MarketDataClient marketData) {
    this.selector = selector;
    this.premiumReader = premiumReader;
    this.marketData = marketData;
  }

  /** Test convenience: no OI-confluence gate (the goldens drive {@code replayLegs} directly). */
  OptionsPremiumReplay(OptionContractSelector selector, CandlePremiumReader premiumReader) {
    this(selector, premiumReader, null);
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
      List<EngineCandle> strikeReferenceOneMinute,
      Map<SeriesKey, List<EngineCandle>> contextCandles,
      BigDecimal initialEquity) {
    // NEW: backtest.relax_session (opt-in, default false) disables the intraday clock rail so an
    // armed scalper fires its signal-driven entries across the FULL session for functional evaluation
    // (the live confluence gates are firewalled out of replay anyway). It does NOT relax the signal
    // gate or indicator warmup — those would manufacture fake trades — so a sparse backtest stays a
    // signal/data-fidelity artifact (judge on live). Off ⇒ premium goldens are byte-identical.
    boolean relaxSession = config.path("backtest").path("relax_session").asBoolean(false);
    List<SignalEvent> signals =
        new TickwiseGoldenRunner(definition, underlyingExchange, underlyingTradingsymbol)
            .run(underlyingOneMinute, contextCandles, null, relaxSession);
    List<PairedLeg> legs = pairLegs(signals, underlyingOneMinute);
    OiGate gate = parseOiGate(config);
    // 2b-E2: the option legs + the OI-confluence index resolve from {@code universe.underlying} (the
    // options-execution ROOT), NOT from the signal-series symbol — which may be a decoupled {@code
    // signal_underlying} (e.g. NIFTY-FUT-CONT) while the legs trade SENSEX. When no signal_underlying
    // is set, {@code underlyingTradingsymbol} == the underlying, so this is byte-identical to before
    // and the premium goldens hold.
    if ((gate.enabled() || gate.iv()) && marketData != null) {
      legs = filterCounterTrend(legs, underlyingOneMinute, oiGateIndex(config, gate), gate);
    }
    return replayLegs(
        signals,
        underlyingOneMinute,
        legs,
        optionRoot(config),
        universeSpec(config),
        exitRules(config),
        budgetInr(config),
        minPremiumInr(config),
        maxLots(config),
        premiumBand(config),
        initialEquity,
        strikeReferenceOneMinute);
  }

  /** Pairs the signal stream into directed legs (entry→exit bar indices), mirroring the candle path. */
  static List<PairedLeg> pairLegs(List<SignalEvent> signals, List<EngineCandle> underlying) {
    in.arthayantra.backtest.replay.BarIndexResolver resolver =
        new in.arthayantra.backtest.replay.BarIndexResolver(underlying);
    int lastBar = Math.max(underlying.size() - 1, 0);
    List<PairedLeg> legs = new ArrayList<>();
    SignalEvent open = null;
    for (SignalEvent ev : signals) {
      if ("EXIT".equals(ev.direction())) {
        if (open != null) {
          // unresolvable entry ⇒ drop the leg loudly (never a fabricated bar-0 trade);
          // unresolvable exit ⇒ force-close at the last bar (audit replay-legs-silent-index0-fallback)
          int entryIndex = resolver.atOrAfter(open.timestamp());
          if (entryIndex >= 0) {
            int exitIndex = resolver.atOrAfter(ev.timestamp());
            legs.add(
                new PairedLeg(
                    "SHORT".equals(open.direction()),
                    entryIndex,
                    exitIndex >= 0 ? exitIndex : lastBar));
          }
          open = null;
        }
      } else if (open == null) {
        open = ev;
      }
    }
    if (open != null) {
      int entryIndex = resolver.atOrAfter(open.timestamp());
      if (entryIndex >= 0) {
        legs.add(new PairedLeg("SHORT".equals(open.direction()), entryIndex, lastBar));
      }
    }
    return legs;
  }

  /**
   * Opt-in OI/IV-confluence entry gate (both dimensions off by default → the existing premium goldens
   * are untouched). {@code enabled} = the OI-trend filter; {@code iv} = the IV-confluence filter (#4).
   */
  record OiGate(boolean enabled, String interval, int intervalMin, String index, boolean iv) {}

  /**
   * Reads {@code backtest.oi_confluence_gate:{enabled,iv,interval,index}} (a backtest-only knob, not the
   * strategy schema). {@code index} is an OPTIONAL override of the OI-confluence index (2b-E2): blank →
   * gate on the options-execution root, set → gate on a different index (e.g. a NIFTY-signal / SENSEX-
   * option strategy gating on {@code NIFTY 50} OI). {@code iv} (#4) arms the sibling IV-confluence filter.
   */
  static OiGate parseOiGate(JsonNode config) {
    JsonNode g = config.path("backtest").path("oi_confluence_gate");
    boolean enabled = g.path("enabled").asBoolean(false);
    boolean iv = g.path("iv").asBoolean(false);
    String interval = g.path("interval").asText("5m");
    String index = g.path("index").asText("");
    String digits = interval.replaceAll("[^0-9]", "");
    return new OiGate(enabled, interval, digits.isEmpty() ? 5 : Integer.parseInt(digits), index, iv);
  }

  /**
   * The options-execution root registry symbol (2b-E2): {@code universe.underlying} stripped to its root
   * (e.g. {@code NIFTY 50} → {@code NIFTY}), DECOUPLED from the signal series. Identical to
   * {@code registryUnderlying(signal symbol)} whenever no {@code signal_underlying} override is set.
   */
  static String optionRoot(JsonNode config) {
    return registryUnderlying(optionRootDisplay(config));
  }

  /** The options-execution root display name — {@code universe.underlying.tradingsymbol} (e.g. {@code NIFTY 50}). */
  static String optionRootDisplay(JsonNode config) {
    return config.path("universe").path("underlying").path("tradingsymbol").asText();
  }

  /**
   * The index for the OI-confluence gate: the explicit {@code oi_confluence_gate.index} override when
   * set, else the options-execution root display — so a decoupled NIFTY-signal / SENSEX-option strategy
   * gates on SENSEX OI by default, or on {@code NIFTY 50} OI if it opts in via the override.
   */
  static String oiGateIndex(JsonNode config, OiGate gate) {
    return gate.index().isBlank() ? optionRootDisplay(config) : gate.index();
  }

  /**
   * Drops legs whose ENTRY fires against the historical OI/IV confluence — the attribution-surface
   * finding that counter-confluence entries lose, turned into a deterministic, opt-in backtest filter:
   *
   * <ul>
   *   <li>{@code oi_confluence_gate.enabled} (the OI dimension): drops long/CE into a Bearish or
   *       Ext.Bearish Connecting-Dots {@code trend}, short/PE into a Bullish or Ext.Bullish one.
   *   <li>{@code oi_confluence_gate.iv} (the IV dimension, #4): drops long/CE entered when the
   *       active-strike IV factor reads Bearish (richer puts), short/PE when it reads Bullish — the
   *       backtest analogue of the live IV-confluence gate. NEUTRAL on derived history (CLAUDE.md), so
   *       this is a FORWARD-paper discriminator (judge OI/IV-led strategies on live, not the backtest).
   * </ul>
   *
   * <p>Both read the SAME per-session Connecting-Dots fetch (one call/session, deterministic: same
   * stored data → same kept legs). A bucket with no OI data (the {@code row} missing) does NOT filter —
   * the leg passes through. Each dimension is independent: a strategy may arm the IV filter alone.
   */
  List<PairedLeg> filterCounterTrend(
      List<PairedLeg> legs, List<EngineCandle> underlying, String indexName, OiGate gate) {
    java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
    Map<java.time.LocalDate, Map<String, in.arthayantra.backtest.client.MarketDataClient.CdRow>> bySession =
        new HashMap<>();
    List<PairedLeg> kept = new ArrayList<>();
    for (PairedLeg leg : legs) {
      java.time.OffsetDateTime entry = underlying.get(leg.entryIndex()).bucketStart();
      java.time.LocalDate session = entry.atZoneSameInstant(ist).toLocalDate();
      Map<String, in.arthayantra.backtest.client.MarketDataClient.CdRow> matrix =
          bySession.computeIfAbsent(
              session,
              s -> {
                Map<String, in.arthayantra.backtest.client.MarketDataClient.CdRow> m = new HashMap<>();
                for (in.arthayantra.backtest.client.MarketDataClient.CdRow r :
                    marketData.connectingDots(indexName, s, gate.interval()).rowsOrEmpty()) {
                  m.put(r.timeInterval(), r);
                }
                return m;
              });
      in.arthayantra.backtest.client.MarketDataClient.CdRow row =
          matrix.get(
              in.arthayantra.backtest.analytics.OiAttributionService.bucketLabel(
                  entry, gate.intervalMin()));
      boolean drop = false;
      if (gate.enabled() && row != null) {
        int trend = row.trend();
        drop =
            (!leg.shortSide() && (trend == 3 || trend == 4)) // long CE into bearish OI
                || (leg.shortSide() && (trend == 1 || trend == 2)); // short PE into bullish OI
      }
      if (!drop && gate.iv() && row != null) {
        int ivFactor = row.activeStrikeIv(); // 0 Neutral / 1 Bullish / 2 Bearish
        drop =
            (!leg.shortSide() && ivFactor == 2) // long CE into a bearish IV read
                || (leg.shortSide() && ivFactor == 1); // short PE into a bullish IV read
      }
      if (!drop) {
        kept.add(leg);
      }
    }
    return kept;
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

  /**
   * Minimum entry premium to trade ({@code risk.position_sizing.params.min_premium_inr}, default ₹1).
   * A near-worthless premium makes {@code lots = budget/premium} explode, so the per-lot cost stack
   * dwarfs the budget — guard against it. Default ₹1 so existing strategies (which set no floor) are
   * protected; raise it per-strategy for a tighter liquidity bar.
   */
  static BigDecimal minPremiumInr(JsonNode config) {
    JsonNode n = config.path("risk").path("position_sizing").path("params").path("min_premium_inr");
    return n.isNumber() ? n.decimalValue() : new BigDecimal("1.0");
  }

  /** Optional hard cap on lots ({@code risk.position_sizing.params.max_lots}); 0 ⇒ unlimited. */
  static int maxLots(JsonNode config) {
    return config.path("risk").path("position_sizing").path("params").path("max_lots").asInt(0);
  }

  /**
   * Backtest premium-band strike selection ({@code backtest.strike_premium_band}; backtest-only, not in
   * {@code strategy-schema/v1}, read leniently like {@code min_premium_inr}). When ENABLED with both
   * {@code lo} + {@code hi}, the replay picks the ATM/ITM strike whose entry premium lands in the band
   * (nearest the midpoint) — matching the live {@code StrikePicker} — instead of the single
   * nearest-strike-to-spot. ABSENT or half-filled (a one-sided bound is unsafe) ⇒ {@link
   * PremiumBand#DISABLED} → the legacy nearest-strike path (the existing golden holds, byte-identical).
   */
  record PremiumBand(
      boolean enabled,
      BigDecimal lo,
      BigDecimal hi,
      int window,
      BigDecimal strikeStep,
      boolean fallbackNearest) {
    static final PremiumBand DISABLED = new PremiumBand(false, null, null, 0, null, false);
  }

  static PremiumBand premiumBand(JsonNode config) {
    JsonNode b = config.path("backtest").path("strike_premium_band");
    if (b.isMissingNode() || !b.path("enabled").asBoolean(false)) {
      return PremiumBand.DISABLED;
    }
    BigDecimal lo = dec(b, "lo");
    BigDecimal hi = dec(b, "hi");
    if (lo == null || hi == null) {
      return PremiumBand.DISABLED; // never select on a one-sided bound
    }
    return new PremiumBand(
        true, lo, hi, b.path("window").asInt(strikeWindow(config)), dec(b, "strike_step"),
        b.path("fallback_nearest").asBoolean(true));
  }

  /** ATM±window from {@code universe.options.strikes.width} (default 3) — the width the YAML declares. */
  static int strikeWindow(JsonNode config) {
    return config.path("universe").path("options").path("strikes").path("width").asInt(3);
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
   * Replays the underlying-derived legs into option trades + a PER-BAR mark-to-market equity curve:
   * {@code cash} holds settled fills (entry outflow + exit inflow, both slippage+cost inclusive via the
   * shared {@link FillSimulator}); an open long is marked each bar at its observed premium, so {@code
   * equity(bar) = cash + Σ open-position marks} — the same MTM shape the candle-path {@code
   * ReplayEngine} produces. Signals are paired upstream and passed through. Deterministic — a premium
   * golden pins it.
   */
  public ReplayResult replayLegs(
      List<in.arthayantra.strategyengine.golden.GoldenSignalsJson.SignalEvent> signals,
      List<EngineCandle> underlying,
      List<PairedLeg> legs,
      String underlyingSymbol,
      UniverseSpec spec,
      PremiumExitEvaluator.Rules rules,
      long budgetInr,
      BigDecimal minPremiumInr,
      int maxLots,
      BigDecimal initialEquity,
      List<EngineCandle> strikeReferenceOneMinute) {
    return replayLegs(
        signals, underlying, legs, underlyingSymbol, spec, rules, budgetInr, minPremiumInr, maxLots,
        PremiumBand.DISABLED, initialEquity, strikeReferenceOneMinute);
  }

  /** As above but with the backtest premium-band selection; the no-band form delegates DISABLED. */
  public ReplayResult replayLegs(
      List<in.arthayantra.strategyengine.golden.GoldenSignalsJson.SignalEvent> signals,
      List<EngineCandle> underlying,
      List<PairedLeg> legs,
      String underlyingSymbol,
      UniverseSpec spec,
      PremiumExitEvaluator.Rules rules,
      long budgetInr,
      BigDecimal minPremiumInr,
      int maxLots,
      PremiumBand band,
      BigDecimal initialEquity,
      List<EngineCandle> strikeReferenceOneMinute) {
    // 2b-E2b: anchor the ATM strike on the strike-reference series' price at the entry instant (e.g.
    // SENSEX-fut for a SENSEX-options strategy), not the signal bar's close. When the strike reference
    // IS the signal series, the lookup returns the entry bar's own close → byte-identical to before.
    NavigableMap<java.time.Instant, BigDecimal> strikeRef = strikeReferenceByInstant(strikeReferenceOneMinute);
    List<Trade> trades = new ArrayList<>();
    List<LegResult> priced = new ArrayList<>();
    int seq = 0;
    for (PairedLeg leg : legs) {
      Optional<LegResult> r =
          priceLeg(
              seq + 1, underlying, underlyingSymbol, leg, spec, rules, budgetInr, minPremiumInr,
              maxLots, band, strikeRef);
      if (r.isEmpty()) {
        continue;
      }
      seq++;
      priced.add(r.get());
      trades.add(r.get().trade());
    }

    List<EquityPoint> equity = new ArrayList<>(underlying.size());
    List<EquityPoint> drawdown = new ArrayList<>(underlying.size());
    BigDecimal cash = initialEquity;
    BigDecimal peak = initialEquity;
    BigDecimal finalEquity = initialEquity;
    for (int b = 0; b < underlying.size(); b++) {
      for (LegResult lr : priced) {
        if (lr.entryIndex() == b) {
          cash = cash.add(lr.entryCash());
        }
        if (lr.exitIndex() == b) {
          cash = cash.add(lr.exitCash());
        }
      }
      BigDecimal mark = BigDecimal.ZERO;
      for (LegResult lr : priced) {
        if (b >= lr.entryIndex() && b < lr.exitIndex()) {
          mark = mark.add(lr.marks().get(b - lr.entryIndex()).multiply(BigDecimal.valueOf(lr.qty())));
        }
      }
      BigDecimal running = cash.add(mark);
      OffsetDateTime ts = underlying.get(b).bucketStart();
      finalEquity = running.setScale(2, RoundingMode.HALF_UP);
      equity.add(new EquityPoint(ts, finalEquity));
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
        finalEquity,
        underlying.size(),
        barsInPosition);
  }

  /** A priced leg: the {@link Trade} plus the per-bar marking inputs the MTM equity loop needs. */
  private record LegResult(
      Trade trade,
      int entryIndex,
      int exitIndex,
      long qty,
      List<BigDecimal> marks,
      BigDecimal entryCash,
      BigDecimal exitCash) {}

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
    // Single-leg test seam: permissive (no floor / no cap) so the fixture trades exactly as specified.
    // The strike reference is the signal series itself → the strike is anchored on the entry bar's close.
    return priceLeg(
            seq, underlying, underlyingSymbol, leg, spec, rules, budgetInr, BigDecimal.ZERO, 0,
            PremiumBand.DISABLED, strikeReferenceByInstant(underlying))
        .map(LegResult::trade);
  }

  /** Strike-reference price lookup keyed by INSTANT (cross-source-safe — the #214 offset-key lesson). */
  private static NavigableMap<java.time.Instant, BigDecimal> strikeReferenceByInstant(
      List<EngineCandle> strikeReferenceOneMinute) {
    java.util.TreeMap<java.time.Instant, BigDecimal> map = new java.util.TreeMap<>();
    for (EngineCandle c : strikeReferenceOneMinute) {
      map.put(c.bucketStart().toInstant(), c.close());
    }
    return map;
  }

  /**
   * The strike-anchor spot at the entry bar: the strike-reference close at/just-before the entry instant
   * (carry-forward), falling back to the signal bar's own close when the reference has no covering bar.
   * When the reference IS the signal series, this is exactly the entry bar's close → parity-preserving.
   */
  private static BigDecimal strikeSpotAt(
      NavigableMap<java.time.Instant, BigDecimal> strikeRef, EngineCandle entryBar) {
    var e = strikeRef.floorEntry(entryBar.bucketStart().toInstant());
    return e != null ? e.getValue() : entryBar.close();
  }

  /** A band candidate's premium at the entry instant (a one-bar read window) — null when uncovered. */
  private BigDecimal premiumAtEntry(OptionContract contract, EngineCandle entryBar) {
    OffsetDateTime to = entryBar.bucketStart().plusMinutes(1);
    NavigableMap<OffsetDateTime, BigDecimal> series =
        premiumReader.premiumSeries(contract, entryBar.bucketStart(), to);
    return CandlePremiumReader.premiumAt(series, entryBar.bucketStart());
  }

  /**
   * Prices one leg into a {@link LegResult} (the {@link Trade} + the per-bar marking inputs), or empty
   * when there's no tradeable contract for the bias/expiry or the budget can't afford a lot. Entry and
   * exit fills run through the shared {@link FillSimulator} (slippage + the full statutory cost stack),
   * so P&L is cost-inclusive and paisa-parity with the candle path + paper ledger. A resolved contract
   * with NO backfilled premium in the window throws 422 {@code DATA_GAP} (fail-run, not a silent skip).
   */
  private Optional<LegResult> priceLeg(
      int seq,
      List<EngineCandle> underlying,
      String underlyingSymbol,
      PairedLeg leg,
      UniverseSpec spec,
      PremiumExitEvaluator.Rules rules,
      long budgetInr,
      BigDecimal minPremiumInr,
      int maxLots,
      PremiumBand band,
      NavigableMap<java.time.Instant, BigDecimal> strikeRef) {
    EngineCandle entryBar = underlying.get(leg.entryIndex());
    // 2b-E2b: anchor the ATM strike on the strike-reference price (e.g. SENSEX-fut for SENSEX options),
    // not the signal bar's close. Reference == signal series → identical (the entry bar's own close).
    BigDecimal strikeSpot = strikeSpotAt(strikeRef, entryBar);
    boolean longBias = !leg.shortSide();
    LocalDate onOrAfter = entryBar.bucketStart().toLocalDate();
    // E7 band-aware selection (backtest-only, opt-in): pick the ATM/ITM strike whose entry premium lands
    // in the band, nearest the midpoint (the live StrikePicker shape). DISABLED ⇒ the legacy
    // nearest-strike-to-spot path, byte-identical to before — the existing premium golden holds.
    Optional<OptionContract> resolved =
        band.enabled()
            ? selector
                .selectInBand(
                    underlyingSymbol, strikeSpot, onOrAfter, spec.expiryMode(), spec.expiryOffset(),
                    longBias, spec.optionTypes(), band.window(), band.lo(), band.hi(),
                    band.strikeStep(), c -> premiumAtEntry(c, entryBar))
                .or(
                    () ->
                        band.fallbackNearest()
                            ? selector.select(
                                underlyingSymbol, strikeSpot, onOrAfter, spec.expiryMode(),
                                spec.expiryOffset(), longBias, spec.optionTypes())
                            : Optional.empty())
            : selector.select(
                underlyingSymbol, strikeSpot, onOrAfter, spec.expiryMode(), spec.expiryOffset(),
                longBias, spec.optionTypes());
    if (resolved.isEmpty()) {
      return Optional.empty(); // no tradeable contract for the bias/expiry — a legit skip
    }
    OptionContract contract = resolved.get();

    EngineCandle exitBarSignal = underlying.get(leg.exitIndex());
    OffsetDateTime to = exitBarSignal.bucketStart().plusMinutes(1);
    NavigableMap<OffsetDateTime, BigDecimal> series =
        premiumReader.premiumSeries(contract, entryBar.bucketStart(), to);

    // align the premium to each underlying bar from entry to the signal-exit (carry-forward)
    List<BigDecimal> premiums = new ArrayList<>(leg.exitIndex() - leg.entryIndex() + 1);
    for (int j = leg.entryIndex(); j <= leg.exitIndex(); j++) {
      premiums.add(CandlePremiumReader.premiumAt(series, underlying.get(j).bucketStart()));
    }
    BigDecimal entryPremium = premiums.get(0);
    if (series.isEmpty() || entryPremium == null || entryPremium.signum() <= 0) {
      // FAIL-RUN (owner decision): a resolved contract with no backfilled premium over the run window
      // is a data gap, not a silent skip. Mirror the §D.6 422 DATA_GAP pre-flight pattern.
      throw new ApiException(
          422,
          ErrorCodes.DATA_GAP,
          "no backfilled premium coverage for " + contract.exchange() + ":" + contract.tradingsymbol(),
          Map.of(
              "exchange", contract.exchange(),
              "tradingsymbol", contract.tradingsymbol(),
              "from", entryBar.bucketStart().toString(),
              "to", to.toString(),
              "presentBars", series.size()));
    }

    // Minimum-premium floor: a near-worthless deep-OTM/expiry-day option (e.g. ₹0.10) makes
    // lots = budget/premium EXPLODE (thousands of lots), so the per-lot cost stack dwarfs the budget
    // and one trade can lose multiples of the premium paid — physically impossible for a long option.
    // Below the floor is a legit SKIP (illiquid/degenerate), not a data gap. Default ₹1 (min_premium_inr).
    if (entryPremium.compareTo(minPremiumInr) < 0) {
      return Optional.empty();
    }

    long lot = contract.lotSize();
    long lots =
        entryPremium.multiply(BigDecimal.valueOf(lot)).signum() == 0
            ? 0
            : BigDecimal.valueOf(budgetInr)
                .divide(entryPremium.multiply(BigDecimal.valueOf(lot)), 0, RoundingMode.DOWN)
                .longValue();
    // Optional hard cap on lots (0 = unlimited) — a second bound on degenerate sizing (max_lots).
    if (maxLots > 0 && lots > maxLots) {
      lots = maxLots;
    }
    long qty = lots * lot;
    if (qty <= 0) {
      return Optional.empty(); // budget can't afford one lot at this premium
    }

    int signalExitOffset = leg.exitIndex() - leg.entryIndex();
    PremiumExitEvaluator.Exit exit =
        PremiumExitEvaluator.evaluate(entryPremium, premiums, rules, signalExitOffset);
    int exitIndex = leg.entryIndex() + exit.barOffset();
    EngineCandle exitBar = underlying.get(exitIndex);

    // Cost-inclusive fills via the shared FillSimulator: BUY at entry (cash out), SELL at exit (cash
    // in); net P&L = the two signed netValues summed (slippage + brokerage + statutory legs included).
    CostConfig cost = CostConfig.optionDefaults(lot);
    Fill entryFill = fill(cost, Side.BUY, qty, entryPremium);
    Fill exitFill = fill(cost, Side.SELL, qty, exit.premium());
    BigDecimal pnl =
        entryFill.netValue().add(exitFill.netValue()).setScale(2, RoundingMode.HALF_UP);
    BigDecimal notional = entryPremium.multiply(BigDecimal.valueOf(qty));
    BigDecimal pnlPct =
        notional.signum() == 0
            ? BigDecimal.ZERO
            : pnl.multiply(HUNDRED).divide(notional, 6, RoundingMode.HALF_UP);

    Trade trade =
        new Trade(
            seq,
            Side.BUY,
            qty,
            entryBar.bucketStart(),
            entryFill.fillPrice(),
            exitBar.bucketStart(),
            exitFill.fillPrice(),
            pnl,
            pnlPct,
            exit.reason(),
            exit.barOffset(),
            TouchBasis.CLOSE_EVAL,
            null,
            contract.exchange(),
            contract.tradingsymbol(),
            level(entryPremium, rules.stopLossPct(), false),
            level(entryPremium, rules.takeProfitPct(), true));

    List<BigDecimal> marks = new ArrayList<>(premiums.subList(0, exit.barOffset() + 1));
    return Optional.of(
        new LegResult(
            trade, leg.entryIndex(), exitIndex, qty, marks,
            entryFill.netValue(), exitFill.netValue()));
  }

  /** One premium-leg fill through the shared model (no quoted spread → the option 1-tick fallback). */
  private Fill fill(CostConfig cost, Side side, long qty, BigDecimal referencePrice) {
    return fills.simulate(
        new FillRequest(
            side,
            qty,
            cost.lotSize(),
            referencePrice,
            cost.instrumentClass(),
            cost.tickSize(),
            null,
            cost.slippage(),
            cost.brokerage(),
            cost.fees()));
  }

  private static BigDecimal level(BigDecimal entry, BigDecimal pct, boolean up) {
    if (pct == null) {
      return null;
    }
    BigDecimal frac = pct.movePointLeft(2);
    return entry.multiply(up ? BigDecimal.ONE.add(frac) : BigDecimal.ONE.subtract(frac));
  }
}
