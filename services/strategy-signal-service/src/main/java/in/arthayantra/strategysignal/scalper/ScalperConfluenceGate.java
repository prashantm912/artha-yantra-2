package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategyengine.eval.BarValues;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer.Confluence;
import in.arthayantra.strategysignal.scalper.MarketOiClient.ChainSnapshot;
import in.arthayantra.strategysignal.scalper.ScalperConfig.StructuralStop;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The §12.3 confluence seam — consulted after the chart {@code EntryEvaluator} passes and before
 * {@code emitEntry}, for scalper strategies only (Model A). It assembles the per-bar {@link
 * ScalperGateContext} (chart dots from the engine {@code IndicatorBank} on the index FUTURE + the
 * OI/macro half from {@link MarketOiClient}), scores Connect-the-Dots, and — when the side's
 * confluence holds — picks the option to trade ({@link StrikePicker}). An empty result BLOCKS the
 * entry (confluence failed, the chain was unavailable, or no strike met the delta/premium band).
 *
 * <p>Wired into {@code SignalEngine} as an {@code Optional<ScalperConfluenceGate>}, mirroring the
 * {@code EmissionGuard} seam. LIVE-only: the OI/macro/chain reads are current snapshots and never run
 * on the deterministic replay path — the picked option + confluence are persisted at entry (the V009
 * side-channel) so a replay reads them back rather than re-calling market-data.
 */
@Component
public class ScalperConfluenceGate {

  /** The scalper indicator-alias convention — a scalper YAML declares these on the index future. */
  static final String VWMA = "vwma20";

  static final String PSAR = "psar";
  static final String RSI = "rsi14";
  // E5 §3.2/§4.2 higher-TF RSI aliases the 5m/daily caps read (declared on the opted-in YAMLs only).
  static final String RSI_5M = "rsi5m";
  static final String RSI_DAILY = "rsi_daily";
  static final String SUPERTREND = "supertrend";
  // E6 §3.10/§4.14.6 15m SuperTrend confirmation alias (declared on the opted-in YAMLs only).
  static final String SUPERTREND_15M = "supertrend15m";
  // optional 60-minute bias confirmation (e.g. SUPERTREND@60m); absent ⇒ unknown ⇒ never blocks.
  static final String BIAS_60M = "bias60m";

  private final MarketOiClient client;
  private final ScalperOiProps oiProps;

  /** Wires the market-data OI/chain client + the Tier-1 OI-analytics thresholds. */
  public ScalperConfluenceGate(MarketOiClient client, ScalperOiProps oiProps) {
    this.client = client;
    this.oiProps = oiProps;
  }

  /** One tradeable option leg the seam picked (the V009 side-channel carrier, §3.11 two-leg). */
  public record Leg(OptionType optionType, StrikePicker.Pick pick) {}

  /**
   * The chosen option(s), the side, the confluence that justified it, and the entry-time structural
   * stop-loss on the index future ({@code null} when the strategy sizes off structure/VWAP only).
   *
   * <p>A directional decision carries ONE leg: {@code side} is CE/PE and {@code legs} = [that leg].
   * A #11 NEUTRAL straddle carries TWO legs (ATM CE + ATM PE, both BUY): {@code side} is {@code null}
   * (no direction) and {@code legs} = [CE, PE]. {@code pick()} returns the PRIMARY leg (the CE for a
   * straddle) — the frozen {@code tradeable_*} columns + the scalp event read it; the full leg list
   * rides the {@code scalper_detail} JSON side-channel.
   */
  public record Decision(
      OptionType side,
      List<Leg> legs,
      Confluence confluence,
      LocalDate expiry,
      BigDecimal structuralStop,
      OpenHighLow.Tier ohTier,
      // E8 §3.2: the live call-put OI imbalance % at entry, surfaced for the probability-graded
      // suggested-qty OI-gap factor (a thin gap trims size). Null for the neutral straddle path / when
      // the chain imbalance is unavailable. Advisory only ([S]) — NOT part of the frozen breakdown.
      BigDecimal oiImbalancePct,
      // E8 §2.8: the live India VIX level at entry, surfaced for the volatility-based sizing factor (an
      // elevated VIX — 15+ sellers / 17+ shorts — trims size). Null for the neutral straddle path / when
      // the VIX feed is unavailable (off-hours / derived history). Advisory only ([S]).
      BigDecimal vixLevel) {

    /** The directional/primary leg (CE for a straddle) — never empty: every decision has ≥1 leg. */
    public StrikePicker.Pick pick() {
      return legs.get(0).pick();
    }

    /** True for the #11 neutral straddle (two BUY legs, no directional side). */
    public boolean neutral() {
      return side == null;
    }
  }

  /**
   * One evaluated rail: what it tested, whether it passed, by what margin, and its declared
   * missing-data {@link FailPolicy} (resolved from {@link RailPolicies} at record time — an
   * unregistered rail name throws, so a new rail cannot ship without declaring its polarity).
   */
  public record RailCheck(
      String rail, boolean pass, BigDecimal operand, BigDecimal threshold, BigDecimal margin,
      String reason, FailPolicy policy) {}

  /**
   * The live-only "why this entry was blocked" record persisted to {@code strategy.signal_rejections}.
   * Carries the FIRST failing rail (name + operand vs threshold + signed margin), every rail evaluated
   * up to it, the full Connect-the-Dots confluence (dot-by-dot) when the composite was reached, and the
   * raw OI/macro/chart context. Never built on the deterministic replay (the gate is live-only).
   */
  public record RejectionDiagnostic(
      String blockingRail,
      OptionType side,
      BigDecimal operand,
      BigDecimal threshold,
      BigDecimal margin,
      String reason,
      BigDecimal compositeScore,
      BigDecimal compositeThreshold,
      List<RailCheck> checks,
      Confluence confluence,
      ScalperGateContext context,
      // The would-be trade the block vetoed (signal-analysis §7.1) — the option root, its expiry,
      // the leg the StrikePicker WOULD have picked (all-eval runs the pick even on a block) and the
      // gate's structural stop. Null when the block happened before that stage (time-window /
      // chain-unavailable / straddle path) — those rows are not shadow-tradeable.
      String underlying,
      LocalDate expiry,
      StrikePicker.Pick pick,
      BigDecimal structuralStop,
      // G17/T14: the comparison direction of the BLOCKING rail, declared by the gate function that
      // compared it. The persist seam judges `margin` against this, so the self-contradiction check
      // is derived from the operator rather than a name table that drifts from the wiring.
      RailMarginSign marginSign) {

    /** Pre-G17 form: {@code marginSign} defaults to UNSIGNED (asserts nothing). */
    public RejectionDiagnostic(
        String blockingRail,
        OptionType side,
        BigDecimal operand,
        BigDecimal threshold,
        BigDecimal margin,
        String reason,
        BigDecimal compositeScore,
        BigDecimal compositeThreshold,
        List<RailCheck> checks,
        Confluence confluence,
        ScalperGateContext context,
        String underlying,
        LocalDate expiry,
        StrikePicker.Pick pick,
        BigDecimal structuralStop) {
      this(
          blockingRail, side, operand, threshold, margin, reason, compositeScore, compositeThreshold,
          checks, confluence, context, underlying, expiry, pick, structuralStop,
          RailMarginSign.UNSIGNED);
    }
  }

  /**
   * INT §13 row 19 / FID P1-8: the fired-side counterpart of {@link RejectionDiagnostic}. When the gate
   * PASSES, this carries the SAME per-rail condition matrix ({@code checks} — every rail's operand vs
   * threshold + margin), the Connect-the-Dots confluence, and the raw OI/macro/chart context that the
   * entry cleared — everything the block path records MINUS the blocking-rail summary (nothing blocked).
   * Built from the SAME {@link Diag} state the {@link Decision} came from (never a re-evaluation), so it
   * is a deterministic function of the bar. Persisted to {@code signals.fired_diagnostic} (mirroring
   * {@code signal_rejections.diagnostic}'s shape) so the §4.2 Stage-2 fired-vs-rejected contrast joins
   * cleanly. LIVE-only — the golden replay never reaches the gate ⇒ never built on backtest → parity-safe.
   */
  public record FiredDiagnostic(
      OptionType side,
      BigDecimal compositeScore,
      BigDecimal compositeThreshold,
      List<RailCheck> checks,
      Confluence confluence,
      ScalperGateContext context,
      String underlying,
      LocalDate expiry,
      StrikePicker.Pick pick,
      BigDecimal structuralStop) {}

  /**
   * The gate verdict: a firing {@link Decision} (with its fired-side {@link FiredDiagnostic}) OR a
   * {@link RejectionDiagnostic} explaining the block. Exactly one of {@code fired}/{@code rejection} is
   * present. Replaces the old bare {@code Optional<Decision>} so the live engine can persist WHY an entry
   * was rejected — and, symmetrically, the full condition matrix behind a FIRED entry (§13 row 19).
   */
  public record Result(
      Optional<Decision> decision, RejectionDiagnostic rejection, FiredDiagnostic fired) {

    /** True when the gate blocked the entry (a rejection diagnostic is present). */
    public boolean blocked() {
      return decision.isEmpty();
    }
  }

  /**
   * Mutable per-evaluation collector: accumulates the rail checks and the resolved state (side, context,
   * confluence) so a block turns into a {@link RejectionDiagnostic} carrying the FIRST failing rail plus
   * everything evaluated before it. One instance per {@code evaluate} call — never shared.
   */
  private static final class Diag {
    private final List<RailCheck> checks = new ArrayList<>();
    private OptionType side;
    private ScalperGateContext context;
    private Confluence confluence;
    private BigDecimal confluenceThreshold;
    private RailCheck firstFailure;
    // G17/T14: the comparison direction the FIRST failing rail declared, captured alongside it so
    // the persist seam can judge the summary margin against the operator that produced it. Kept
    // here rather than on RailCheck so the diagnostic JSON + its 3 test builders stay untouched.
    private RailMarginSign firstFailureSign = RailMarginSign.UNSIGNED;
    private String underlying;
    private LocalDate expiry;
    private StrikePicker.Pick pick;
    private BigDecimal structuralStop;

    /** True once any recorded rail has failed — the entry is blocked (checked once, at the end). */
    boolean anyFailed() {
      return firstFailure != null;
    }

    private RailCheck record(RailCheck rc, RailMarginSign sign) {
      checks.add(rc);
      if (!rc.pass() && firstFailure == null) {
        firstFailure = rc; // the FIRST failing rail becomes the summary blockingRail
        firstFailureSign = sign; // ...and its operator direction rides with it (G17)
      }
      return rc;
    }

    /** Records a {@link GateOutcome} rail; returns true when it FAILED. */
    boolean fails(String rail, GateOutcome o, BigDecimal threshold) {
      BigDecimal operand = o.operand();
      BigDecimal margin =
          operand != null && threshold != null ? operand.subtract(threshold) : null;
      return !record(
              new RailCheck(
                  rail, o.pass(), operand, threshold, margin, o.reason(), RailPolicies.of(rail)),
              o.marginSign()) // DERIVED: the sign the gate function itself declared
          .pass();
    }

    /** Records a boolean/verdict rail (no scalar operand); returns true when it FAILED. */
    boolean failsBool(String rail, boolean pass, String reason) {
      return !record(
              new RailCheck(rail, pass, null, null, null, reason, RailPolicies.of(rail)),
              RailMarginSign.UNSIGNED) // no scalar operand/threshold -> margin is always null
          .pass();
    }

    /** Records the confluence-composite rail (operand=aggregate vs threshold); true when INVALID. */
    boolean failsScore(String rail, boolean valid, BigDecimal aggregate, BigDecimal threshold, String reason) {
      return !record(
              new RailCheck(
                  rail, valid, aggregate, threshold,
                  compositeMargin(valid, aggregate, threshold), reason, RailPolicies.of(rail)),
              // aggregate >= threshold is a floor; compositeMargin() already records NULL for a
              // decisive-leg block (B5/#985), so a NON-null blocked margin is the scalar shortfall.
              RailMarginSign.NEGATIVE_WHEN_BLOCKED)
          .pass();
    }

    /**
     * Builds the diagnostic. The summary scalars (blockingRail/operand/threshold/margin/reason) come from
     * the FIRST failing rail (the one the old short-circuit would have reported); {@code checks} carries
     * EVERY evaluated rail — pass and fail — so the DB holds the complete condition matrix.
     */
    Result block() {
      RailCheck b = firstFailure;
      return new Result(
          Optional.empty(),
          new RejectionDiagnostic(
              b.rail(), side, b.operand(), b.threshold(), b.margin(), b.reason(),
              confluence == null ? null : confluence.aggregate(), confluenceThreshold,
              List.copyOf(checks), confluence, context, underlying, expiry, pick, structuralStop,
              firstFailureSign),
          null);
    }

    /**
     * Builds a firing {@link Result} carrying {@code decision} AND its fired-side {@link FiredDiagnostic}
     * — the full condition matrix every recorded rail cleared, plus the confluence + context. Uses the
     * SAME accumulated state {@link #block()} would have serialized (§13 row 19), so the fired diagnostic
     * is a deterministic function of the bar (no re-evaluation, no re-fetch).
     */
    Result fire(Decision decision) {
      return new Result(
          Optional.of(decision),
          null,
          new FiredDiagnostic(
              side,
              confluence == null ? null : confluence.aggregate(),
              confluenceThreshold,
              List.copyOf(checks),
              confluence,
              context,
              underlying,
              expiry,
              pick,
              structuralStop));
    }
  }

  /**
   * Confluence-gate one passing chart entry. Empty BLOCKS the signal.
   *
   * @param cfg the strategy's scalper knobs (underlying, strike band, threshold)
   * @param bank the engine indicator bank for the index future at this bar
   * @param future the index-future series (raw OHLCV for the §3.1 candle pattern + structural stop)
   * @param index the just-closed primary bar index
   * @param barInstant the bar instant (deterministic; drives the StrikePicker expiry clock)
   * @param istTime the bar's IST wall-clock (the time-window dot)
   * @param eodDate the EOD-read date for breadth/FII (see {@link MarketOiClient})
   */
  public Optional<Decision> evaluate(
      ScalperConfig cfg,
      BarValues bank,
      EngineSeries future,
      int index,
      Instant barInstant,
      LocalTime istTime,
      LocalDate eodDate) {
    // Bare-decision ORACLE read (E9 D4 confluence-flip EXIT, SignalEngine.confluenceFlipExit): does NOT
    // enforce option_types — the flip oracle must see the TRUE market side, including the one the strategy
    // will not ENTER, so a held single-side position can detect a confluence flip against it.
    return evaluateInternal(
            cfg, bank, future, index, barInstant, istTime, eodDate, null, false, false)
        .decision();
  }

  /**
   * As {@link #evaluate}, but returns the full {@link Result} — the firing {@link Decision} OR the
   * {@link RejectionDiagnostic} explaining WHY the entry was blocked. The live engine calls this so it
   * can persist the rejection; the deterministic replay does not (the gate is live-only).
   */
  public Result evaluateWithDiagnostic(
      ScalperConfig cfg,
      BarValues bank,
      EngineSeries future,
      int index,
      Instant barInstant,
      LocalTime istTime,
      LocalDate eodDate) {
    // The ENTRY evaluation the live engine persists — ENFORCES the declared option-side constraint.
    return evaluateInternal(
        cfg, bank, future, index, barInstant, istTime, eodDate, null, false, true);
  }

  /**
   * E11 §3.8 BTST/STBT carry ENTRY — the {@link Result} form (see the 7-arg {@link
   * #evaluateWithDiagnostic}). {@code forcedSide} pins the CE/PE side the caller already resolved — the
   * overnight carry's side is the day-close LOCATION (toward the day HIGH ⇒ CE/BTST, toward the LOW ⇒
   * PE/STBT, §3.8's BTST-vs-STBT split), NOT the intraday VWAP-decisive read; pass {@code null} to keep
   * the default VWAP derivation. {@code carryClock=true} skips the §0B intraday time window (the 15:20
   * pre-close clock IS the carry's window — the intraday cap would otherwise block every carry). Every
   * other rail (volume / RSI / confluence / OI / macro / strike pick) runs UNCHANGED against the pinned
   * side, so the carry is validated by the SAME confluence the intraday scalps use — fail-closed, NEUTRAL
   * on derived history (so a carry reads ~0 backtest trades). Entry path: ENFORCES the declared
   * option-side constraint (see {@link #evaluateInternal}).
   *
   * <p>There is deliberately NO bare-decision 9-arg {@code evaluate(...)} counterpart: the live BTST/STBT
   * carry OPENS a position, so it must run through this enforcing form. The only bare, non-enforcing read
   * is the 7-arg {@link #evaluate} flip-exit oracle ({@code SignalEngine.confluenceFlipExit}).
   */
  public Result evaluateWithDiagnostic(
      ScalperConfig cfg,
      BarValues bank,
      EngineSeries future,
      int index,
      Instant barInstant,
      LocalTime istTime,
      LocalDate eodDate,
      OptionType forcedSide,
      boolean carryClock) {
    return evaluateInternal(
        cfg, bank, future, index, barInstant, istTime, eodDate, forcedSide, carryClock, true);
  }

  /**
   * The shared evaluation core. {@code enforceOptionSide} is the LIVE-vs-BACKTEST parity guard scoped to
   * ENTRY: when true (every {@code evaluateWithDiagnostic} entry path — the only path the live engine opens
   * a position from), a non-empty {@code option_types} that excludes the VWAP-derived side BLOCKS. The bare
   * {@code evaluate()} decision reads pass FALSE — the E9 D4 confluence-flip EXIT oracle
   * ({@code SignalEngine.confluenceFlipExit}) must report the TRUE market side, INCLUDING the side the
   * strategy will not ENTER, so a held single-side position can detect a confluence flip against it.
   */
  private Result evaluateInternal(
      ScalperConfig cfg,
      BarValues bank,
      EngineSeries future,
      int index,
      Instant barInstant,
      LocalTime istTime,
      LocalDate eodDate,
      OptionType forcedSide,
      boolean carryClock,
      boolean enforceOptionSide) {
    Diag diag = new Diag();
    // §0B hard pre-flight (§12.1): the time window — the one the YAML session cannot express (the
    // 11:00–13:00 midday block). Blocked early, before the chain fetch, to skip the HTTP fan-out.
    // #9 (section 3.9) Morning Trade: the opening-tick path uses its own opening window instead (the
    // ~09:16-09:30 opening tick the general "after 09:45" rule must NOT block — owner-confirmed).
    // E11 §3.8: the carry clock (carryClock) SKIPS this window — the 15:20 pre-close evaluation IS the
    // carry's window, and the intraday cap would block it.
    if (!carryClock) {
      GateOutcome timeOutcome =
          cfg.openingTick()
              ? ScalperGates.timeWindow(istTime, ScalperConfig.OPENING_FROM, ScalperConfig.OPENING_TO)
              : cfg.has("s24-trade-window")
                  // W4 (S24 Shared-S2): the explicit 09:45-14:30 window — no 11:00-13:00 midday block, cap 14:30.
                  ? ScalperGates.timeWindow(istTime, ScalperGates.NO_TRADE_BEFORE, ScalperGates.S24_WINDOW_TO)
                  // §5 part 2: the §0B rail honours per-strategy scalper.params (bounds + midday toggle);
                  // unset ⇒ the Siva 09:45/15:30/midday-on defaults, byte-identical.
                  : ScalperGates.timeWindow(
                      istTime,
                      cfg.params().noTradeBefore(),
                      cfg.params().noFreshEntryAfter(),
                      cfg.params().middayBlock());
      if (diag.fails("time-window", timeOutcome, null)) {
        return diag.block();
      }
    }
    // E8 §3.5 time-of-day-preference (tag time-of-day-preference): on top of the §0B hard window, an
    // opt-in strategy only INITIATES inside the high-probability 10:00-13:30 band ("best 10:00-11:30,
    // ease off after ~13:30"), rendered as a hard skip. Default-OFF — no shipped YAML carries the tag,
    // so every config stays byte-identical. Fails fast before the chain fetch.
    if (cfg.has("time-of-day-preference")
        && diag.fails(
            "time-of-day-preference",
            ScalperGates.timeOfDayPreference(istTime, ScalperConfig.PREFER_FROM, ScalperConfig.PREFER_TO),
            null)) {
      return diag.block();
    }
    Optional<ChainSnapshot> chainOpt = client.chain(cfg.underlying());
    if (chainOpt.isEmpty()) {
      diag.failsBool("chain-unavailable", false, "options chain unavailable");
      return diag.block();
    }
    ChainSnapshot chain = chainOpt.get();
    // Shadow-book capture (signal-analysis §7.1): the option root + its live expiry ride the
    // diagnostic so a blocked-but-composite-passing entry can be opened as a virtual position.
    diag.underlying = cfg.underlying();
    diag.expiry = chain.expiry();
    Chart chart = chart(bank, index);
    // signal-analysis rollup 2026-07-06 §7#1 (tag relative-volume-floor, default-OFF): the fixed §0B
    // floor (NIFTY 125k) sits above the physical 3m volume range on the live tick-agg series (max ~95k on
    // 2026-07-06), so the §0B volume rail never passes live and vetoes every scalper — but the same veto
    // SAVES money on chop, so the fix is a RELATIVE floor, not a lower fixed number. When armed, resolve
    // the floor to k × the median of the prior-N bar volumes (the SAME series chart.volume() reads →
    // scale-invariant: it self-calibrates whatever the bar's volume basis). Unarmed ⇒ the resolved fixed
    // floor, so the rail stays byte-identical for every shipped strategy. Computed ONCE, used by both the
    // straddle branch and the directional §0B volume check below.
    BigDecimal absVolFloor = ScalperGates.volumeFloorFor(cfg.signalIndex(), cfg.params().volumeFloor());
    BigDecimal windowVolFloor =
        cfg.has("relative-volume-floor")
            ? ScalperGates.relativeVolumeFloor(
                priorVolumes(bank, index, oiProps.relativeVolumeWindow().intValue()),
                oiProps.relativeVolumeMultiplier(),
                oiProps.relativeVolumeMinBars().intValue(),
                absVolFloor)
            : absVolFloor;
    // G10 (tag time-of-day-volume-floor, default-OFF): the in-session window above is SYSTEMATICALLY
    // biased against the open. Measured over 21 sessions, its opening-10 median runs mean 3.52x /
    // worst 8.42x the session median and over-estimates on 19 of 21 — because the opening surge is a
    // LEVEL SHIFT, not an outlier (07-29's first ten 3m bars had a MINIMUM of 32,760 against a session
    // median of 15,015), and a median cannot reject a level. Consequence: bars clear the floor 11.9%
    // of the time in the first 90 minutes vs 30.9% for the rest of the day.
    //
    // The fix is to compare a bar against the SAME TIME OF DAY on prior sessions, so a busy 09:18 is
    // judged against other 09:18s. Bake-off on the same 21 sessions (open-pass / rest-pass):
    //   status quo 11.9/30.9 (ratio 0.39)  ·  prior-session SEED 57.8/29.5 (1.96)  ·  profile 27.5/29.2 (0.94)
    // ⚠️ The flat prior-session seed is NOT the fix — it REVERSES the bias instead of removing it,
    // making the open the LOOSEST part of the day. Only a per-time-of-day baseline tracks the shape.
    //
    // Depth is deliberately shallow: the live 3m series warms 4 CALENDAR days (LiveSeriesStore
    // .warmupDays has no 3m case, so it takes default 4), which is 2-3 sessions. That is not a
    // limitation — 2 sessions measured the MOST uniform of all depths tried (0.94 vs 0.85 at 3 and
    // 0.79 at 5), a shallow profile tracking the current regime more closely.
    //
    // Fail-safe by construction: too few prior sessions carry this offset and relativeVolumeFloor
    // falls through to `windowVolFloor`, i.e. exactly today's behaviour. Reuses the SAME median x
    // multiplier math as the window floor — only the SAMPLE differs.
    BigDecimal effVolFloor =
        cfg.has("time-of-day-volume-floor")
            ? ScalperGates.relativeVolumeFloor(
                timeOfDayVolumes(
                    bank, future, index, oiProps.timeOfDayProfileSessions().intValue()),
                oiProps.relativeVolumeMultiplier(),
                MIN_TIME_OF_DAY_SESSIONS,
                windowVolFloor)
            : windowVolFloor;
    // #11 (section 3.11) Straddle: a direction-NEUTRAL volatility position trading BOTH legs of the
    // SAME ATM strike (delta≈0.5 each). It must NOT take the CE/PE directional split below — there is no
    // single side — so it branches here on the side-agnostic §0B rails (time window already passed +
    // the volume floor). §3.11 gives no chart-RSI rule for a straddle, and the combined-premium-vs-VWAP
    // entry trigger + the low-IV gate are LIVE market-data series the deterministic seam cannot recompute
    // (deferred to live management); v1 emits the two-leg draft once an ATM pair exists. Short straddle
    // (SELL legs) is SPAN-deferred — StraddleLegPicker only ever returns BUY legs.
    if (cfg.requireStraddle()) {
      // All-eval: record every straddle condition (no short-circuit) so the DB holds the full matrix.
      diag.fails(
          "volume-floor",
          ScalperGates.volume(cfg.signalIndex(), chart.volume(), effVolFloor),
          effVolFloor);
      // E4 §3.A.4 low-iv-straddle: a LONG straddle wants LOW IV (cheap both legs); skip when either
      // side's 6-strike IV avg is rich. Armed via the tag only (else the neutral path pays no extra
      // fetch). Pass cfg.underlying() so the index/expiry are a matched pair (the macro IV pair is the
      // option-root's own; a null avg never blocks). LIVE-only — the neutral path has no golden.
      if (cfg.has("low-iv-straddle")) {
        boolean tooRich =
            StraddleIvGate.tooRichForLong(
                client.macro(cfg.underlying(), eodDate, chain.expiry()), oiProps);
        diag.failsBool(
            "low-iv-straddle",
            !tooRich,
            tooRich ? "straddle IV too rich for a long" : "IV ok for long straddle");
      }
      var pair =
          StraddleLegPicker.pick(
              chain.candidates(), chain.spot(), chain.basis(), barInstant, chain.expiry(),
              cfg.strikeParams().rate());
      if (pair.isEmpty()) {
        diag.failsBool("strike-pick", false, "no ATM straddle pair in band");
      }
      if (diag.anyFailed()) {
        return diag.block();
      }
      var s = pair.get();
      // #11 straddle is direction-neutral — no Open=High tier / no graded sizing
      return diag.fire(
          new Decision(
              null,
              List.of(new Leg(OptionType.CE, s.call()), new Leg(OptionType.PE, s.put())),
              neutralConfluence(),
              chain.expiry(),
              null,
              null,
              null,
              null));
    }
    // §0B VWAP-decisive: CE above VWAP, PE below — the side the rest of the confluence must confirm.
    // E11 §3.8: a BTST carry pins the side from the day-close LOCATION (forcedSide), overriding the
    // intraday VWAP read.
    OptionType side =
        forcedSide != null
            ? forcedSide
            : chart.close() != null && chart.vwap() != null && chart.close().compareTo(chart.vwap()) >= 0
                ? OptionType.CE
                : OptionType.PE;
    diag.side = side;
    // LIVE-vs-BACKTEST parity (option-side-constraint): the strategy's declared universe.options.option_types
    // constrains which side it may trade. The backtest ENFORCES it (OptionContractSelector:94-96 skips a bias
    // whose side isn't listed); the live gate derived `side` from VWAP alone and never enforced it — so a
    // PE-only slug could open a CE (and a CE-only a PE) on a bar where the chart pre-gate (vwma20) and this
    // seam (vwap) disagree. Fail-closed, mirrored exactly: a non-empty option_types that EXCLUDES the derived
    // side BLOCKS — a wrong-side bar is categorically un-tradeable for this strategy. Empty ⇒ unconstrained
    // (the side-agnostic straddle already returned above; a both-sides directional strategy is inert). Blocked
    // HERE, before the OI/macro context fan-out, so a wrong-side bar skips the HTTP round-trips (the same §0B
    // "block early to skip the fan-out" pattern the time window uses). Scoped to ENTRY (enforceOptionSide):
    // the bare evaluate() decision reads — the E9 D4 confluence-flip EXIT oracle — must NOT enforce, or a
    // single-side held position could never see the opposite side the market flipped to (a protective exit
    // silently removed). Only evaluateWithDiagnostic (the entry path) enforces.
    if (enforceOptionSide && !cfg.optionTypes().isEmpty()) {
      boolean sideAllowed = cfg.optionTypes().contains(side.name());
      if (diag.failsBool(
          "option-side-constraint",
          sideAllowed,
          sideAllowed
              ? "derived side " + side + " within declared option_types"
              : "derived side "
                  + side
                  + " not in declared option_types "
                  + new java.util.TreeSet<>(cfg.optionTypes()))) {
        return diag.block();
      }
    }
    // E8 §2.2 r8 / §3.4 vwap-distance (tag vwap-distance): an entry too FAR from VWAP is an extended
    // chase, not a pullback — a HARD skip (the Siva "wait for a pullback near VWAP, don't chase the
    // move" discipline). Chart-only (|close-vwap|/close), side-agnostic; a null operand degrades to
    // pass. Default-OFF — no shipped YAML carries the tag, so every config stays byte-identical. The
    // 0.4% band is a placeholder default tuned BEFORE any strategy is armed onto it.
    if (cfg.has("vwap-distance")) {
      diag.fails(
          "vwap-distance",
          ScalperGates.vwapDistance(
              chart.close(), chart.vwap(),
              cfg.params().vwapDistanceMinFrac(), cfg.params().vwapDistanceMaxFrac()),
          cfg.params().vwapDistanceMaxFrac());
    }
    // W4 (tag gap-size-side-gate, S24 #9): a large gap-down suppresses the PE (put-buy) side
    // ("300-400 gap-down no-put"). Default-OFF; reads the same session gap GapState detects (pure).
    if (cfg.has("gap-size-side-gate")) {
      diag.fails(
          "gap-size-side-gate",
          ScalperGates.gapSizeSide(GapState.detect(future, index), side, cfg.params().gapSuppressPts()),
          cfg.params().gapSuppressPts());
    }
    // §0B hard "no trade" rails: volume floor + the RSI gate (both are blocks, not the soft dots the
    // scorer also weighs — a strong-everything-else signal must still respect them). #2 (open-high-low)
    // relaxes RSI to the source's ">50" floor; a strategy with a YAML scalper.params.rsi_band block uses
    // its OWN configurable bounds (rsiBandCustom, E5 §3.5 — takes precedence over the fixed tag); a
    // strategy carrying the W3 rsi-s24-bands tag uses the ratified 50-75 / 40-50 / 40-25 band
    // (rsiS24Band); all are per-strategy overrides — the shared rsiBand (60-80 / 20-40) is unchanged for
    // every other strategy, so the goldens never move.
    diag.fails(
        "volume-floor",
        ScalperGates.volume(cfg.signalIndex(), chart.volume(), effVolFloor),
        effVolFloor);
    ScalperParams.RsiBand band = cfg.params().rsiBand();
    GateOutcome rsiOutcome =
        cfg.requireOpenHighLow()
            ? ScalperGates.rsiAbove(chart.rsi14(), oiProps.openHighRsiFloor())
            : band != null
                ? ScalperGates.rsiBandCustom(
                    chart.rsi14(), side, band.ceLo(), band.ceHi(), band.peLo(), band.peHi())
                : cfg.requireRsiS24Bands()
                    ? ScalperGates.rsiS24Band(chart.rsi14(), side)
                    : ScalperGates.rsiBand(chart.rsi14(), side);
    diag.fails("rsi-band", rsiOutcome, null);
    // E5 §3.2/§4.2 higher-TF RSI caps: on top of the 3m rail, the 5m must not be overbought (CE) and the
    // daily must agree (CE < 75 / PE > 25). Armed via rsi-5m-cap / rsi-daily-cap; the opted-in YAML
    // declares rsi5m@5m / rsiDaily@1d (warmed by §3.2a). Fail-closed on a null higher-TF read.
    if (cfg.has("rsi-5m-cap")) {
      diag.fails(
          "rsi-5m-cap",
          ScalperGates.rsiHigherTfCap(
              chart.rsi5m(), side, oiProps.rsi5mCeCap(), oiProps.rsi5mPeFloor()),
          null);
    }
    if (cfg.has("rsi-daily-cap")) {
      diag.fails(
          "rsi-daily-cap",
          ScalperGates.rsiHigherTfCap(
              chart.rsiDaily(), side, oiProps.rsiDailyCeCap(), oiProps.rsiDailyPeFloor()),
          null);
    }
    // E6 §3.10/§4.14.6 supertrend-15m: the 15m SuperTrend trend must agree with the side (a momentum
    // entry confirmed by the higher-TF trend). Read the 15m ST direction off the bank only when declared
    // (warmed by §3.2a); an unknown/unwarmed direction (0) fail-OPENs. Armed via the tag, default-OFF.
    if (cfg.has("supertrend-15m")) {
      BigDecimal st15 = bank.has(SUPERTREND_15M) ? bank.valueAt(SUPERTREND_15M, index) : null;
      int dir15m = st15 == null ? 0 : st15.signum();
      diag.fails("supertrend-15m", ScalperGates.supertrend15mAlign(dir15m, side), null);
    }
    // E6 §4.14.6 psar-durability: a PSAR too CLOSE to price = a short-lived (whipsaw) trend — require the
    // |close-PSAR| gap to clear the durability floor. Reads the existing chart close/psar; fail-OPEN on a
    // null PSAR. Armed via the tag, default-OFF.
    if (cfg.has("psar-durability")) {
      diag.fails(
          "psar-durability",
          ScalperGates.psarDurable(chart.close(), chart.psar()),
          ScalperGates.PSAR_DISTANCE_MIN_PCT);
    }
    // E5 §3.6 rsi-cooloff: after a HOT prior bar (RSI overbought/oversold) the entry waits for a cooled
    // pullback candle (the cross-bar half of overbought-defer). Reads the existing 3m rsi14 (prior bar)
    // + the deploy bar colour; a null future degrades to pass (no candle to judge). Armed via the tag.
    if (cfg.has("rsi-cooloff") && future != null) {
      EngineCandle deploy = future.candle(index);
      boolean pullback =
          side == OptionType.CE
              ? deploy.close().compareTo(deploy.open()) < 0 // a CE waits for a RED pullback candle
              : deploy.close().compareTo(deploy.open()) > 0; // a PE waits for a GREEN pullback candle
      diag.fails(
          "rsi-cooloff",
          ScalperGates.rsiCoolOff(bank.previousValueAt(RSI, index), chart.rsi14(), pullback, side),
          null);
    }
    // E5 §3.7 post-vertical RSI-recovery (RATIFICATION-PACK row 51): after a vertical FALL crashed the RSI
    // oversold, a reversal long waits until it has recovered back toward 40. Scans the prior `lookback`
    // bars' 3m RSI for the oversold trough, then defers to the recovery gate (inert when no recent
    // trough). Armed via the rsi-recovery tag (default-OFF), the trend-change family. The window read uses
    // the always-declared rsi14 alias; PE is inert (the mirror is a short-variant concern, plan §2.5).
    if (cfg.has("rsi-recovery")) {
      int lookback = oiProps.rsiRecoveryLookback().intValue();
      BigDecimal trough = null;
      for (int k = 1; k <= lookback && index - k >= 0; k++) {
        BigDecimal r = bank.valueAt(RSI, index - k);
        if (r != null && (trough == null || r.compareTo(trough) < 0)) {
          trough = r;
        }
      }
      diag.fails(
          "rsi-recovery",
          ScalperGates.rsiRecovery(
              trough, chart.rsi14(), side, oiProps.rsiOversoldTrough(), oiProps.rsiRecoveryLevel()),
          oiProps.rsiRecoveryLevel());
    }
    // E6 §3.3 pct-price-move (Market-Movers): the index must have moved >= the floor% in the side's
    // direction since the SESSION OPEN — a "mover" scalp needs a real move, not chop. Reads the deploy
    // close + the session-open bar off the future series; a null future degrades to pass. Armed via the tag.
    if (cfg.has("pct-price-move") && future != null && index >= 0) {
      BigDecimal sessionOpen = future.candle(future.sessionStart(index)).open();
      diag.fails(
          "pct-price-move",
          ScalperGates.pctPriceMove(chart.close(), sessionOpen, oiProps.pctPriceMoveFloor(), side),
          oiProps.pctPriceMoveFloor());
    }
    // E6 §3.x rising-volume: a real move comes WITH expanding participation — the deploy bar's volume must
    // exceed the prior bar's. Only consulted when a prior bar exists (index >= 1), so a session-edge bar is
    // never blocked. Armed via the tag, default-OFF.
    if (cfg.has("rising-volume") && future != null && index >= 1) {
      diag.fails(
          "rising-volume",
          ScalperGates.risingVolume(future.candle(index).volume(), future.candle(index - 1).volume()),
          null);
    }
    // E11 §3.9 Morning-Trade OPENING FORMATION (tag morning-opening-formation, doc L540): do NOT enter on
    // the gap alone — the 2nd session candle must break+hold the 1st candle's high (CE)/low (PE). Morning
    // family only (opening-tick). Blocks the opening bar itself (formation not yet formed). Live-only seam.
    if (cfg.openingTick() && cfg.has("morning-opening-formation") && future != null && index >= 0) {
      int s = future.sessionStart(index);
      if (index < s + 1) {
        // only the opening bar so far — wait for the 2nd candle, never the gap alone
        diag.failsBool(
            "morning-opening-formation", false, "opening bar only — 2nd candle not yet formed");
        return diag.block();
      }
      EngineCandle c1 = future.candle(s);
      EngineCandle c2 = future.candle(s + 1);
      diag.fails(
          "morning-opening-formation",
          ScalperGates.openingFormation(c1.high(), c1.low(), c2.high(), c2.low(), c2.close(), side),
          null);
    }
    // E11 §3.9 Morning-Trade EOD PRECONDITION (tag morning-eod-precondition, doc L534): the opening view
    // needs a CONVINCING prior-session close in the side direction (close in the side's quartile of the
    // prior session's range). Reads the warmed prior session off the future series (no new feed). Morning
    // family only. A null/absent prior session degrades to pass. Live-only seam.
    if (cfg.openingTick() && cfg.has("morning-eod-precondition")) {
      PriorExtremes pe = priorSessionExtremes(future, index);
      if (pe != null) {
        diag.fails(
            "morning-eod-precondition",
            ScalperGates.eodConvincingClose(
                pe.high(), pe.low(), pe.close(), side, ScalperConfig.MORNING_NEAR_EXTREME_FRAC),
            null);
      }
    }
    // E3 volume-pump (tag volume-pump, §4.15.3): the deploy candle must be a floor-clearing pump closing
    // in the side's direction (dark-green/dark-red attribution). Reads the bar OHLCV off the future
    // series already in scope (no Chart extension). Default-OFF; a null future degrades to pass.
    if (cfg.has("volume-pump") && future != null && index >= 0) {
      EngineCandle bar = future.candle(index);
      diag.fails(
          "volume-pump",
          ScalperGates.volumePump(
              bar.close(), bar.open(), BigDecimal.valueOf(bar.volume()), cfg.signalIndex(), side),
          null);
    }
    // W4 PARAM #5 (tag indicator-distance-veto): a chart-only overextension veto — block when price has
    // run far from the vwap/vwma/psar cluster (mean-reversion risk). Default-OFF; a null/absent cluster
    // degrades to pass inside the gate, so it never blocks on missing data.
    if (cfg.has("indicator-distance-veto")) {
      diag.fails(
          "indicator-distance-veto",
          ScalperGates.indicatorDistance(chart, cfg.params().indicatorDistanceMaxPct()),
          cfg.params().indicatorDistanceMaxPct());
    }
    // W4 PARAM #10 (tag divergence-vol-gate): the S24 Day-21 counter-trend confirm — a heavyweight ~125k
    // bar regardless of the index floor. Default-OFF; pairs with trend-change but is independent.
    if (cfg.has("divergence-vol-gate")) {
      diag.fails("divergence-vol-gate", ScalperGates.divergenceVolume(chart.volume()), null);
    }
    // W4 (tag overbought-defer, S24 §3.1): stand aside while the tape is exhaustion-overbought (CE) /
    // oversold (PE). Default-OFF; a null RSI degrades to pass inside the gate.
    if (cfg.has("overbought-defer")) {
      diag.fails("overbought-defer", ScalperGates.overboughtDefer(chart.rsi14(), side), null);
    }
    // §3.12 trendline-break (tag trendline-break): require a confirmed break of the 2-pivot session
    // trendline in the side's direction (CE breaks a falling resistance, PE a rising support) — the
    // §3.12 chart-pattern reversal trigger, distinct from the single-pivot MarketStructure break. Built
    // default-OFF (armed on no shipped strategy; the owner arms it per-strategy, like atr-stop, because a
    // mechanical 2-pivot line is a refinement whose home is a discretionary owner choice). Fail-closed:
    // too few pivots / no break / a null future → block.
    if (cfg.has("trendline-break")) {
      boolean broke = Trendline.detect(future, index, side).broke();
      diag.failsBool(
          "trendline-break", broke,
          broke ? "trendline broke in side direction" : "no confirmed 2-pivot trendline break");
    }
    // §3.1 Two-Candle: when the strategy declares it, the multi-bar formation is a HARD entry gate
    // (the chart-only YAML grammar cannot express it). The 1st-candle extreme becomes the stop.
    BigDecimal structuralStop = structuralStop(cfg, future, index, side);
    if (cfg.requireTwoCandle()) {
      diag.failsBool(
          "two-candle", structuralStop != null,
          structuralStop != null ? "two-candle formation present" : "two-candle formation not present");
    }
    // #4 (section 3.4) Gap-Theory: when the strategy declares it, a still-open significant gap BLOCKS
    // the entry until it fills; once filled the with-trend entry passes and the pre-gap extreme
    // becomes the stop. No significant gap => the gate is INERT and leaves the entry to the confluence.
    if (cfg.requireGapFill()) {
      GapTheoryGate.Verdict gap = GapTheoryGate.evaluate(future, index, side, cfg.signalIndex());
      diag.failsBool(
          "gap-fill", gap.pass(),
          gap.pass() ? "gap filled / no significant gap" : "significant gap still open");
      if (gap.pass()) {
        structuralStop = gap.stopLevel();
      }
    }
    // E8 §3.9 Morning Trade: before the intraday VWAP settles (~10:30 IST) it is unreliable, so the
    // morning structural stop is the PRIOR-day VWAP (the first support on a fall, doc L539/L541) — used
    // only when it sits on the protective side of the entry; otherwise the default structural stop
    // stands. The doc cites the STRIKE's prior-day VWAP; the strike isn't selected until later, so this
    // uses the INDEX-future's prior-session VWAP (the same index-price domain every scalper structural
    // stop lives in — the stop is touched by the index future). Live-only seam (parity-safe by
    // firewall); a fresh boot with no prior session degrades to the default stop.
    if (cfg.has("prior-day-vwap-stop")
        && cfg.openingTick()
        && future != null
        && index >= 0
        && istTime.isBefore(ScalperConfig.VWAP_ACTIONABLE_FROM)) {
      BigDecimal vwapStop =
          ScalperGates.priorDayVwapStop(priorSessionVwap(future, index), future.candle(index).close(), side);
      if (vwapStop != null) {
        structuralStop = vwapStop;
      }
    }
    // E8 §2.2 ATR stop (tag atr-stop): a volatility-scaled structural stop = entry ∓ 2×ATR(14) on the
    // index future (the same index-price domain as every scalper structural stop). Reuses the engine's
    // Wilder-ATR indicator on the warmed future series; null ATR (too few bars) leaves the default stop.
    // Owner-directed MECHANISM, shipped default-OFF (armed on NO strategy): §2.2 ATR is a probability-
    // graded SIZING lever, not a doc stop gate, and every directional family already specifies its own
    // structural stop (or deliberately has none) — so no family is a faithful auto-home. The owner arms
    // this per-strategy after deciding which family's default stop it should replace.
    if (cfg.has("atr-stop") && future != null && index >= 0) {
      BigDecimal atrStop =
          ScalperGates.atrStop(
              future.candle(index).close(), atrAtEntry(future, index), ScalperConfig.ATR_STOP_MULT, side);
      if (atrStop != null) {
        structuralStop = atrStop;
      }
    }
    // The live bar's IST date drives the S24 monthly-expiry OI suppression (distinct from eodDate,
    // the prior completed session used for breadth/FII).
    LocalDate tradeDate = barInstant.atZone(Ist.ZONE).toLocalDate();
    // 2c: the OI confluence reads the configured oi-index (the niftyoi/sensexoi A/B), which may differ
    // from the option-execution root. When it does, fetch THAT index's chain for its expiry (the OI
    // read is expiry-specific); if unavailable, fall back to the option-root expiry so the OI factors
    // degrade to NEUTRAL rather than block. Same index ⇒ reuse the chain already fetched (parity-identical).
    LocalDate oiExpiry =
        cfg.oiIndex().equals(cfg.underlying())
            ? chain.expiry()
            : client.chain(cfg.oiIndex()).map(ChainSnapshot::expiry).orElse(chain.expiry());
    ScalperGateContext ctx =
        client.context(cfg.oiIndex(), cfg.signalIndex(), istTime, eodDate, oiExpiry, tradeDate, chart);
    diag.context = ctx;
    if (ctx == null) {
      // The OI/macro context (and thus every OI/composite condition) can't be evaluated — a hard
      // precondition, like the chain fetch. In production context() is never null; this guards a
      // fetch failure from NPE-ing the all-eval sweep. Rails already recorded above stand.
      diag.failsBool("context-unavailable", false, "OI/macro context unavailable");
      return diag.block();
    }
    // #5 (T2.1): the oi-cross-filter strategies HARD-require a >=50% call-put dOI imbalance before
    // the confluence is even consulted. Fail-closed like the volume/RSI rails; a null imbalance
    // (data unavailable / flat-OI caveat) DEGRADES to pass inside the gate, so it never blocks then.
    if (cfg.requireCallPutDeltaFilter()) {
      diag.fails(
          "call-put-delta-filter",
          ScalperGates.callPutDeltaFilter(ctx.oi(), oiProps.crossFilterPct()),
          oiProps.crossFilterPct());
    }
    // E2 M1 (tag oi-cross-required, Trending-OI #5 defining trigger): a COMPLETED fresh PE-over-CE /
    // CE-over-PE cross favouring the side is a HARD precondition — stricter than the soft trending_cross
    // dot (gapWidening alone does not satisfy it). Fail-closed; degrades to NEUTRAL on derived history.
    if (cfg.has("oi-cross-required")) {
      diag.fails("oi-cross-required", ScalperGates.oiCrossRequired(ctx.oi(), side), null);
    }
    // E2 M2 (tag oi-slope-agree, Trending-OI #5): the active-strike sentiment LEVEL and SLOPE must both
    // favour the side (a hard conjunction of the two soft sentiment dots). Fail-closed on null.
    if (cfg.has("oi-slope-agree")) {
      diag.fails("oi-slope-agree", ScalperGates.oiSlopeAgree(ctx.oi(), side), null);
    }
    // E2 M3 (tag oi-divergence-magnitude, Trending-OI #5): the OI lines must diverge by a real magnitude
    // (>=20% of total OI) with a corroborating price impulse (>=50%). Fail-closed; NEUTRAL on history.
    if (cfg.has("oi-divergence-magnitude")) {
      diag.fails(
          "oi-divergence-magnitude",
          ScalperGates.oiDivergenceMagnitude(
              ctx.oi(), cfg.params().oiDivergenceMinPct(), cfg.params().priceImpulseMinPct(), side),
          cfg.params().oiDivergenceMinPct());
    }
    // E2 M4 (tag flat-oi-stand-aside, the doc's flat-OI trap): a null/flat call-put imbalance STANDS
    // ASIDE (block) — the deliberate inverse of #5's fail-open (keep the two tags mutually exclusive).
    if (cfg.has("flat-oi-stand-aside")) {
      diag.fails("flat-oi-stand-aside", ScalperGates.flatOiStandAside(ctx.oi()), null);
    }
    // E2 M6 (tag max-oi-sr-gate): the entry must not trade INTO the dominant standing-OI wall on its
    // side (max-CE-OI strike = overhead resistance, max-PE-OI strike = support). Walls come from the
    // chain's per-strike OI ladder already in hand (no new fetch); fail-open on a missing ladder/spot.
    if (cfg.has("max-oi-sr-gate")) {
      diag.fails(
          "max-oi-sr-gate",
          ScalperGates.oiWallClear(
              maxOiStrike(chain.strikeOi(), true), maxOiStrike(chain.strikeOi(), false),
              chain.spot(), side),
          null);
    }
    // E2 M7 (tag oi-interval-and-60m-trend, Trending-OI #5 §3.5 "5-15m interval + a 60m broader-trend
    // read"): the 60-minute OI build must AGREE with the side — a slower confirmation above the 5m
    // cross/slope. A FOCUSED 2nd /options/trending?interval=60m read, fetched ONLY when armed (cfg.has
    // short-circuits first, so unarmed scalpers pay no extra OI fetch). Fail-OPEN on an unknown (0) trend.
    if (cfg.has("oi-interval-and-60m-trend")) {
      diag.fails(
          "oi-interval-and-60m-trend",
          ScalperGates.oi60mAgree(client.trend60mDir(cfg.oiIndex(), oiExpiry, tradeDate), side),
          null);
    }
    // FU2 — soft-dots-to-hard-gates: each of these confluence reads is ALSO a scored soft dot; arming the
    // tag makes it a STRICT requirement (the scorer/den is unchanged → parity-safe). Every operand is
    // already in hand. The natural home is the #10 "Connect-the-Dots" strategy, whose identity IS
    // requiring the whole confluence to align. Each is default-OFF.
    // - indicator-alignment: VWAP+VWMA+PSAR+ST all on the side (fail-closed on a missing/opposed leg).
    if (cfg.has("indicator-alignment-gate")) {
      diag.fails("indicator-alignment-gate", ScalperGates.indicatorAlignment(chart, side), null);
    }
    // - futures-oi: the futures OI quadrant must support the side (CE wants LB/SC; fail-closed on NEUTRAL).
    if (cfg.has("futures-oi-gate")) {
      diag.fails("futures-oi-gate", ScalperGates.oiQuadrant(ctx.oi(), side), null);
    }
    // - breadth: advances/declines > 32 for the side (fail-closed on a 0/0 / unavailable read).
    if (cfg.has("breadth-gate")) {
      diag.fails("breadth-gate", ScalperGates.breadth(ctx.macro(), side), BigDecimal.valueOf(32));
    }
    // - basis: the futures basis must agree (premium→CE, discount→PE); fail-OPEN on a null basis.
    if (cfg.has("basis-gate")) {
      diag.fails("basis-gate", ScalperGates.futuresBasis(ctx.oi(), side), null);
    }
    // E3 P1 — directional-VIX HARD gate: VIX direction must confirm the side (falling→CE, rising→PE);
    // fail-OPEN on unknown direction (an off-hours/history 422 leaves vixRising null → inert). Default-OFF
    // (armed on no strategy — the soft vix dot already encodes the directional read).
    if (cfg.has("directional-vix-gate")) {
      diag.fails("directional-vix-gate", ScalperGates.vix(ctx.macro(), side), null);
    }
    // E4 (tag iv-buyer-cap, IV>40 -> sellers' market): block a long-premium BUY when the traded side's
    // 6-strike IV is too rich (> 0.40 fraction). Fail-OPEN on a null side IV. A standalone risk veto.
    if (cfg.has("iv-buyer-cap")) {
      diag.fails(
          "iv-buyer-cap",
          ScalperGates.ivBuyerCap(ctx.macro(), side, cfg.params().ivBuyerCap()),
          cfg.params().ivBuyerCap());
    }
    // E3 fii-bias (tag fii-bias, §4.6): the FII L/S flow must not oppose the side (long% >= 50 for CE).
    // Reads the existing Macro.fiiLongPct (no new feed); fail-open on a null/neutral read.
    //
    // Scoped to ENTRY (enforceOptionSide) for the SAME reason as option-side-constraint above: the bare
    // evaluate() read is the confluence-flip EXIT oracle, and a rail that can fail on the OPPOSITE side
    // would stop a held position ever seeing the flip against it — silently removing a protective exit.
    // That is not hypothetical here: FII index-future long share sits near 8-16%, so a CE evaluation
    // fails this rail on essentially every bar, and a held PE could never flip out. Harmless while
    // Macro.fiiLongPct was null (fail-open), which is exactly why fixing the EOD read exposed it.
    if (enforceOptionSide && cfg.has("fii-bias")) {
      diag.fails("fii-bias", ScalperGates.fiiBias(ctx.macro(), side), ScalperGates.FII_NEUTRAL_PCT);
    }
    // E3 §3.3 fii-dii-gate (tag fii-dii-gate): the RICHER FII participant bias — the futures change-in-OI
    // classifier + the option-leg seller read (Macro.fiiBiasSign, /fii-dii/bias) must not oppose the side;
    // fail-open on a null/neutral read. Real EOD data, so usable in backtest (unlike the OI gates).
    if (cfg.has("fii-dii-gate")) {
      diag.fails("fii-dii-gate", ScalperGates.fii(ctx.macro(), side), null);
    }
    // E3 constituent-gate (tag constituent-gate, §4.6): the index heavyweights' net push must not oppose
    // the side (Macro.constituentBias = /equity/index-contribution indexChangePct); fail-open on null/0.
    if (cfg.has("constituent-gate")) {
      diag.fails("constituent-gate", ScalperGates.constituent(ctx.macro(), side), null);
    }
    // W4 (tag directional-change-gate, S24 Day-20): only enter on a confirmed OI directional change —
    // the PE-CE tilt must have crossed within the window. Default-OFF; an unchanged/short series blocks.
    if (cfg.has("directional-change-gate")) {
      diag.fails("directional-change-gate", ScalperGates.directionalChange(ctx.oi()), null);
    }
    // #12 (section 3.12) Trend-Change: when the strategy declares it, a HARD reversal pre-gate - a price
    // structure break in the side's direction + the >=50% Trending-OI momentum shift + the §3.1
    // 2-candle confirm + the ~14:30 down-reversal cap. Fail-closed (any missing leg / null OI deltas
    // block); the broken swing pivot becomes the structural stop.
    if (cfg.requireTrendChange()) {
      TrendChangeGate.Verdict tc =
          TrendChangeGate.evaluate(future, index, side, cfg.signalIndex(), ctx.oi(), istTime);
      diag.failsBool(
          "trend-change", tc.pass(),
          tc.pass() ? "reversal structure + OI shift + 2-candle confirm" : "trend-change preconditions unmet");
      if (tc.pass()) {
        structuralStop = tc.stopLevel();
      }
    }
    // #2 (section 3.2) Open=High/Open=Low: when the strategy declares it, a HARD FNO-structure pre-gate -
    // the front-future OH/OL mark + the source-faithful Table-1/Table-2 HIGH tier (per-strike footprint,
    // NOT the OI quadrant) + the <=50% spurt reject rules + the 1st-half (~12:00) cutoff. The per-strike
    // footprint is fetched HERE (#2-only, not in the shared context fan-out). Fail-closed (a
    // MILD/LOW/STAND_ASIDE tier, null/empty stats or null OI blocks; null reject magnitudes do NOT
    // block); the front-future VWAP becomes the structural stop.
    // W4 6c (OIP-AI surfacing): the Open=High probability tier the #2 gate graded — carried onto the
    // Decision (LIVE-only side-channel) so the signal detail / scalp alert / Cockpit render the tier+%.
    // Null for every non-open-high-low strategy (no tier was graded).
    OpenHighLow.Tier ohTier = null;
    if (cfg.requireOpenHighLow()) {
      OpenHighLow.Marks futureMarks = OpenHighLow.marks(future, index);
      MarketOiClient.OpenHighStats stats =
          client.openHighStats(cfg.underlying(), chain.expiry(), oiProps.openHighWindow().intValue());
      OpenHighLowGate.Verdict ohl =
          OpenHighLowGate.evaluate(
              futureMarks, stats, side, oiProps, ctx.oi(), ctx.chart().vwap(), istTime,
              cfg.requireOpenHighOiVeto());
      diag.failsBool(
          "open-high-low", ohl.pass(),
          ohl.pass() ? "OH/OL + HIGH tier ok" : "open-high-low tier MILD/LOW/STAND_ASIDE or missing");
      if (ohl.pass()) {
        structuralStop = ohl.stopLevel();
        ohTier = ohl.tier();
      }
    }
    // #7 (section 7) Hero-Zero: when the strategy declares it, a HARD expiry-day end-of-day pre-gate -
    // expiry-day only (monthly-expiry blocks: prior-month OI is corrupt), after 14:30 / before the
    // 15:20 fresh-entry cap, a >50% OI+price "real move" on the side + short-covering on the side, RSI
    // not overbought/oversold, and the deploy bar closing toward the matching session extreme. Fail-
    // closed (any missing leg / null OI / null RSI blocks); the OPPOSITE session extreme becomes the
    // structural stop. tradeDate is the live bar's IST date (the same one driving the OI suppression).
    if (cfg.requireHeroZero()) {
      // E4 §3.7 hero-zero-iv-flat: pass the 6-strike IV averages ONLY when armed (else null → the
      // both-flat leg is skipped, byte-identical to the unarmed gate).
      boolean ivFlatArmed = cfg.has("hero-zero-iv-flat");
      // Per-root expiry flags (P1-9): a SENSEX hero-zero arms on the BSE Thursday, not NSE Tuesday.
      MarketCalendar rootCalendar = ScalperCalendars.forUnderlying(cfg.underlying());
      HeroZeroGate.Verdict hz =
          HeroZeroGate.evaluate(
              future, index, side, ctx.oi(), chart.rsi14(), istTime,
              rootCalendar.isWeeklyIndexExpiryDay(tradeDate),
              rootCalendar.isMonthlyIndexExpiryDay(tradeDate),
              cfg.has("herozero-side-oi"),
              ivFlatArmed ? ctx.macro().ceIvAvg6() : null,
              ivFlatArmed ? ctx.macro().peIvAvg6() : null);
      diag.failsBool(
          "hero-zero", hz.pass(),
          hz.pass() ? "expiry-day real-move + short-covering ok" : "hero-zero expiry-day preconditions unmet");
      if (hz.pass()) {
        structuralStop = hz.stopLevel();
      }
    }
    // #9 (section 3.9) Morning Trade: VWAP is "not actionable before 10:30" in the opening tick, so the
    // opening-tick path drops VWAP from the HARD validity gate before 10:30 IST (it stays a soft dot in
    // the aggregate). Every other path keeps the decisive hard-VWAP behaviour (vwapHardGate=true).
    boolean vwapHardGate = !(cfg.openingTick() && istTime.isBefore(ScalperConfig.VWAP_ACTIONABLE_FROM));
    // E4 iv-per-strike + E7 premium-skew (each armed via its tag): iv-per-strike adds the per-strike
    // IV-slope + 10-12 abs-band SOFT dots and the unilateral IV>40 stand-aside; premium-skew adds the
    // §3.7/§6.7 Hero-Zero "favor the lower-premium side" warning dot. Each absent ⇒ false ⇒ that dot is
    // not added ⇒ the aggregate is byte-identical (conditional-add parity).
    Confluence conf =
        ConnectTheDotsScorer.score(
            ctx, side, bias60m(bank, index), cfg.confluenceThreshold(), oiProps, vwapHardGate,
            cfg.has("iv-per-strike"), cfg.has("premium-skew"), cfg.has("dow-confluence"),
            // T24: the dot must test the SAME floor the rail did (effVolFloor above), not the
            // static per-index default it resolved on its own.
            effVolFloor,
            // A3: `iv_rank` is default-OFF. Its input is suppressed below the 60-trading-day
            // IvAnalyticsService history floor and would start resolving on a CALENDAR trigger
            // (~late Sep 2026) — arming it stays an owner decision (tag ⇒ republish), never a date.
            cfg.has("iv-rank-dot"));
    diag.confluence = conf;
    diag.confluenceThreshold = cfg.confluenceThreshold();
    boolean valid = side == OptionType.CE ? conf.bullish() : conf.bearish();
    diag.failsScore(
        "confluence-composite", valid, conf.aggregate(), cfg.confluenceThreshold(),
        compositeReason(conf, cfg.confluenceThreshold(), vwapHardGate));
    BigDecimal stop = structuralStop;
    // #7 (section 7) Hero-Zero buys the option ONE STRIKE INSIDE the short-covering strike (a CALL one
    // strike below the max-CE-OI strike for a bullish break, a PUT one above the max-PE-OI strike for a
    // bearish one) - never the already-covered strike. The SC strike + step come from the LIVE per-strike
    // OI ladder (live-only; the chosen leg is persisted at entry, so a replay reads it back, section
    // 12.9). The selector degrades to empty when the ladder / target strike is unavailable, so the shared
    // delta/premium band StrikePicker is the fallback (never a crash).
    Optional<StrikePicker.Pick> pick = Optional.empty();
    if (cfg.requireHeroZero()) {
      pick =
          HeroZeroStrikeSelector.select(
              chain.candidates(), chain.strikeOi(), chain.spot(), chain.basis(), side, barInstant,
              chain.expiry(), cfg.strikeParams().rate());
    }
    if (pick.isEmpty()) {
      pick =
          StrikePicker.pick(
              chain.candidates(), chain.spot(), chain.basis(), side, barInstant, chain.expiry(),
              cfg.strikeParams());
    }
    if (pick.isEmpty()) {
      diag.failsBool("strike-pick", false, "no strike met the delta/premium band");
    }
    // Shadow-book capture: the leg the pick resolved + the structural stop — recorded on the
    // diagnostic even when a rail blocks, so the shadow book can trade the vetoed entry virtually.
    diag.pick = pick.orElse(null);
    diag.structuralStop = stop;
    // All-eval: EVERY applicable rail (and the composite + strike pick) is now recorded above. The entry
    // fires only when NONE failed; a single failure blocks, but the DB row still carries the full matrix.
    if (diag.anyFailed()) {
      return diag.block();
    }
    OptionType decided = side;
    OpenHighLow.Tier decidedTier = ohTier;
    return diag.fire(
        new Decision(
            decided, List.of(new Leg(decided, pick.get())), conf, chain.expiry(), stop, decidedTier,
            ctx.oi().callPutDeltaImbalancePct(), ctx.macro().vixLevel()));
  }

  /**
   * T14 (signal-analysis 2026-07-25 bug queue B5): the composite verdict is decisive-legs AND
   * scalar ({@code ConnectTheDotsScorer:222} — VWAP side, 60m bias, stand-aside, aggregate ≥
   * threshold). Recording {@code aggregate − threshold} unconditionally logged a blocked row with a
   * POSITIVE margin whenever a decisive leg failed while the aggregate cleared the threshold (3
   * rows on 2026-07-24, 1 on 07-23) — a self-contradiction that also mis-attributes every §3.5
   * would-have-fired query. The margin is a SCALAR-shortfall diagnostic, so it is recorded only
   * when it is the thing that decided: passing rows keep the positive slack (informative), scalar
   * blocks keep the negative shortfall, and a decisive-leg block carries NO scalar margin — the
   * reason string ({@link #compositeReason}) already names the failed leg.
   */
  static BigDecimal compositeMargin(boolean valid, BigDecimal aggregate, BigDecimal threshold) {
    if (aggregate == null || threshold == null) {
      return null;
    }
    if (!valid && aggregate.compareTo(threshold) >= 0) {
      return null; // blocked by a decisive leg, not the scalar — a positive "margin" would lie
    }
    return aggregate.subtract(threshold);
  }

  /**
   * A human reason for a blocked confluence composite: which of the decisive legs failed (VWAP side
   * — decisive only while {@code vwapHardGate} holds; the #9 opening-tick path degrades it to a soft
   * dot before 10:30, where naming it "decisive" would be the exact T14 contradiction — the 60m
   * bias, the 40/40 stand-aside) or, when all held, the aggregate falling short of the threshold.
   */
  static String compositeReason(Confluence conf, BigDecimal threshold, boolean vwapHardGate) {
    if (conf.standAside()) {
      return "stand-aside (both-IV-high 40/40 suppression)";
    }
    if (vwapHardGate && !conf.vwapAligned()) {
      return "price on the wrong side of VWAP (decisive)";
    }
    if (!conf.biasAligned()) {
      return "60m bias opposes the side";
    }
    return "aggregate " + conf.aggregate() + " below threshold " + threshold;
  }

  /**
   * The entry-time structural stop on the index future: the 1st-candle extreme of a Two-Candle
   * formation ({@code null} when required but absent ⇒ the caller blocks the entry), the entry
   * (crossover) candle's extreme, the #9 Morning Trade FIRST session candle's extreme, or {@code null}
   * when the strategy sizes off structure/VWAP only.
   */
  private static BigDecimal structuralStop(
      ScalperConfig cfg, EngineSeries future, int index, OptionType side) {
    if (cfg.requireTwoCandle()) {
      // E6 #10 (tag two-candle-substitution): a light-volume 2nd candle is admitted when the deploy bar
      // carries the floor on the side colour. Absent ⇒ the strict detector (byte-identical).
      return TwoCandleGate.detect(
              future, index, side, cfg.signalIndex(), cfg.has("two-candle-substitution"))
          .stopLevel();
    }
    if (cfg.structuralStop() == StructuralStop.ENTRY_CANDLE && future != null && index >= 0) {
      return side == OptionType.CE ? future.candle(index).low() : future.candle(index).high();
    }
    // #9 (section 3.9) Morning Trade: SL = the FIRST session candle's low (CE) / high (PE).
    if (cfg.structuralStop() == StructuralStop.FIRST_CANDLE && future != null && index >= 0) {
      EngineCandle first = future.candle(future.sessionStart(index));
      return side == OptionType.CE ? first.low() : first.high();
    }
    return null;
  }

  /**
   * E8 §3.9: VWAP over the PRIOR completed session's warmed bars — {@code Σ((H+L+C)·V) / (3·ΣV)}, a
   * single division at the end (no intermediate rounding). null when no prior session is in the warmed
   * window ({@code sessionStart == 0}, e.g. a fresh boot) or the prior session carried zero volume.
   */
  static BigDecimal priorSessionVwap(EngineSeries future, int index) {
    if (future == null || index < 0) {
      return null;
    }
    int todayStart = future.sessionStart(index);
    if (todayStart <= 0) {
      return null; // no prior session warmed
    }
    int priorStart = future.sessionStart(todayStart - 1);
    BigDecimal sumPv = BigDecimal.ZERO;
    BigDecimal sumV = BigDecimal.ZERO;
    for (int i = priorStart; i < todayStart; i++) {
      EngineCandle c = future.candle(i);
      BigDecimal v = BigDecimal.valueOf(c.volume());
      sumPv = sumPv.add(c.high().add(c.low()).add(c.close()).multiply(v));
      sumV = sumV.add(v);
    }
    if (sumV.signum() == 0) {
      return null;
    }
    return sumPv.divide(sumV.multiply(BigDecimal.valueOf(3)), 4, java.math.RoundingMode.HALF_UP);
  }

  /** The PRIOR completed session's high / low / close over the warmed bars (#9 EOD precondition). */
  record PriorExtremes(BigDecimal high, BigDecimal low, BigDecimal close) {}

  /**
   * §3.9 #9: the prior completed session's HIGH / LOW / CLOSE from the warmed future series — same
   * session-walk as {@link #priorSessionVwap}. null when no prior session is warmed (sessionStart == 0).
   */
  static PriorExtremes priorSessionExtremes(EngineSeries future, int index) {
    if (future == null || index < 0) {
      return null;
    }
    int todayStart = future.sessionStart(index);
    if (todayStart <= 0) {
      return null; // no prior session warmed
    }
    int priorStart = future.sessionStart(todayStart - 1);
    BigDecimal high = null;
    BigDecimal low = null;
    for (int i = priorStart; i < todayStart; i++) {
      EngineCandle c = future.candle(i);
      high = high == null || c.high().compareTo(high) > 0 ? c.high() : high;
      low = low == null || c.low().compareTo(low) < 0 ? c.low() : low;
    }
    BigDecimal close = future.candle(todayStart - 1).close(); // the prior session's last (closing) bar
    return new PriorExtremes(high, low, close);
  }

  /**
   * E8: ATR(14) on the index future at the entry bar, via the engine's Wilder-ATR indicator (the same
   * computation the engine exit path uses). null when the warmed series is too short for a full ATR.
   */
  static BigDecimal atrAtEntry(EngineSeries future, int index) {
    if (future == null || index < ScalperConfig.ATR_STOP_PERIOD) {
      return null;
    }
    return in.arthayantra.strategyengine.indicators.IndicatorRegistry.create(
            "ATR", future, null, java.util.Map.of("period", ScalperConfig.ATR_STOP_PERIOD))
        .valueAt(index);
  }

  /**
   * E2 M6: the strike carrying the LARGEST STANDING CE/PE open interest in the chain ladder — the OI
   * S/R "wall". {@code null} when the ladder is empty/absent (so the gate degrades to pass). This is the
   * argmax over raw {@code oi}, distinct from the biggest |oiChange| mover.
   */
  private static BigDecimal maxOiStrike(List<MarketOiClient.StrikeOi> ladder, boolean ce) {
    BigDecimal best = null;
    long bestOi = Long.MIN_VALUE;
    for (MarketOiClient.StrikeOi row : ladder) {
      Long oi = ce ? row.ceOi() : row.peOi();
      if (oi != null && oi > bestOi) {
        bestOi = oi;
        best = row.strike();
      }
    }
    return best;
  }

  private Chart chart(BarValues bank, int index) {
    BigDecimal supertrend = bank.valueAt(SUPERTREND, index);
    // the SUPERTREND indicator outputs +1/-1 directly (Ta4jIndicators.supertrendDirection).
    int supertrendDir = supertrend == null ? 0 : supertrend.signum();
    return new Chart(
        bank.builtin("close", index),
        bank.builtin("vwap", index),
        bank.valueAt(VWMA, index),
        bank.valueAt(PSAR, index),
        supertrendDir,
        bank.valueAt(RSI, index),
        bank.builtin("volume", index),
        // E5 §3.2a: read the higher-TF RSI aliases ONLY when the YAML declares them (else valueAt throws
        // "alias not in the bank"); a strategy without the caps gets null and the gates are unarmed anyway.
        bank.has(RSI_5M) ? bank.valueAt(RSI_5M, index) : null,
        bank.has(RSI_DAILY) ? bank.valueAt(RSI_DAILY, index) : null);
  }

  /**
   * The volumes of up to {@code window} bars immediately BEFORE {@code index} (the deploy bar itself is
   * excluded so a quiet bar can never lower its own floor), read from the SAME series as {@link #chart}'s
   * {@code volume} so the relative floor is scale-consistent. Stops at the series start, so an early-
   * session bar yields a short list and {@link ScalperGates#relativeVolumeFloor} falls back to the fixed
   * §0B floor below its {@code minBars} warmup.
   */
  /**
   * G10: how many prior sessions must carry this time-of-day offset before the profile is trusted.
   * Below it {@link ScalperGates#relativeVolumeFloor} falls back to the caller's window floor — so a
   * cold boot, a fresh contract after a roll, or the first session after a long weekend all degrade
   * to exactly today's behaviour rather than to a floor computed off one sample.
   */
  private static final int MIN_TIME_OF_DAY_SESSIONS = 2;

  /**
   * The volume at the SAME IST TIME OF DAY in each of the previous {@code sessions} sessions — the
   * G10 profile sample. Volumes come from {@code bank.builtin("volume", …)}, byte-identical to what
   * the {@code volume} rail's own operand reads, so the profile and the operand can never drift onto
   * different scales; {@code series} is used ONLY to walk session boundaries.
   *
   * <p>⚠️ Matched on the WALL-CLOCK bucket time, never on the ordinal bar offset within the session
   * (cross-vendor review, 2026-07-30 — the first version matched by offset and was wrong). {@link
   * LiveSeriesStore} warms from an exact {@code now.minusDays(4)} instant, so the OLDEST covered
   * session routinely starts MID-SESSION: after a Monday 10:30 restart the Thursday slice begins at
   * 10:30, and its "offset 25" is 11:45, not 10:30. Offset matching silently sampled the wrong
   * bucket, and because the truncated session still yielded a value it satisfied {@link
   * #MIN_TIME_OF_DAY_SESSIONS} and the fail-safe never fired — a wrong floor with no signal.
   *
   * <p>The common case stays O(1): every ordinary session starts 09:15, so the offset-derived
   * candidate already carries the right bucket time and is accepted on the first check. Only a
   * truncated or gapped session falls through to the bounded scan, and a session with no bar at that
   * exact time contributes NOTHING — skipped, never substituted, so neither a half day nor a
   * mid-session warm-up start can drag the median.
   */
  // package-private for ScalperConfluenceGateTest — the session walk is the new logic here and it
  // deserves direct assertions rather than being inferred through a full gate evaluation.
  static List<BigDecimal> timeOfDayVolumes(
      BarValues bank, EngineSeries series, int index, int sessions) {
    List<BigDecimal> out = new ArrayList<>();
    java.time.LocalTime wanted = istBucketTime(series, index);
    int offset = index - series.sessionStart(index);
    int cursor = index;
    for (int s = 0; s < sessions; s++) {
      int previousEnd = series.previousSessionEnd(cursor);
      if (previousEnd < 0) {
        break; // no earlier session covered by the warm-up window
      }
      int previousStart = series.sessionStart(previousEnd);
      int match = -1;
      int candidate = previousStart + offset; // O(1) hit whenever both sessions open at 09:15
      if (candidate <= previousEnd && wanted.equals(istBucketTime(series, candidate))) {
        match = candidate;
      } else {
        for (int i = previousStart; i <= previousEnd; i++) {
          if (wanted.equals(istBucketTime(series, i))) {
            match = i;
            break;
          }
        }
      }
      if (match >= 0) {
        out.add(bank.builtin("volume", match));
      }
      cursor = previousEnd;
    }
    return out;
  }

  /** A bar's IST wall-clock bucket time — the key the G10 profile matches prior sessions on. */
  private static java.time.LocalTime istBucketTime(EngineSeries series, int index) {
    return series
        .candle(index)
        .bucketStart()
        .withOffsetSameInstant(EngineSeries.IST)
        .toLocalTime();
  }

  private static List<BigDecimal> priorVolumes(BarValues bank, int index, int window) {
    List<BigDecimal> out = new ArrayList<>();
    for (int j = 1; j <= window && index - j >= 0; j++) {
      out.add(bank.builtin("volume", index - j));
    }
    return out;
  }

  private int bias60m(BarValues bank, int index) {
    BigDecimal bias = bank.valueAt(BIAS_60M, index);
    return bias == null ? 0 : bias.signum();
  }

  /**
   * The #11 straddle is direction-NEUTRAL — no Connect-the-Dots side was scored. This stand-in carries
   * a zero aggregate, {@code side=null}, neither bullish nor bearish, and no dots, so the side-channel +
   * scalp-alert renderers stay uniform without a real directional confluence.
   */
  private static Confluence neutralConfluence() {
    return new Confluence(
        BigDecimal.ZERO, null, false, false, false, false, false, List.of());
  }
}
