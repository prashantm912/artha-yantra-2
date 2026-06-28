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
  static final String SUPERTREND = "supertrend";
  // optional 60-minute bias confirmation (e.g. SUPERTREND@60m); absent ⇒ unknown ⇒ never blocks.
  static final String BIAS_60M = "bias60m";

  private final MarketOiClient client;
  private final ScalperOiProps oiProps;
  private final MarketCalendar calendar;

  /** Wires the market-data OI/chain client, the Tier-1 OI-analytics thresholds + the NSE calendar. */
  public ScalperConfluenceGate(
      MarketOiClient client, ScalperOiProps oiProps, MarketCalendar calendar) {
    this.client = client;
    this.oiProps = oiProps;
    this.calendar = calendar;
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
      OpenHighLow.Tier ohTier) {

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
    // §0B hard pre-flight (§12.1): the time window — the one the YAML session cannot express (the
    // 11:00–13:00 midday block). Blocked early, before the chain fetch, to skip the HTTP fan-out.
    // #9 (section 3.9) Morning Trade: the opening-tick path uses its own opening window instead (the
    // ~09:16-09:30 opening tick the general "after 09:45" rule must NOT block — owner-confirmed).
    boolean timeOk =
        cfg.openingTick()
            ? ScalperGates.timeWindow(istTime, ScalperConfig.OPENING_FROM, ScalperConfig.OPENING_TO).pass()
            : cfg.has("s24-trade-window")
                // W4 (S24 Shared-S2): the explicit 09:45-14:30 window — no 11:00-13:00 midday block, cap 14:30.
                ? ScalperGates.timeWindow(istTime, ScalperGates.NO_TRADE_BEFORE, ScalperGates.S24_WINDOW_TO).pass()
                : ScalperGates.timeWindow(istTime).pass();
    if (!timeOk) {
      return Optional.empty();
    }
    Optional<ChainSnapshot> chainOpt = client.chain(cfg.underlying());
    if (chainOpt.isEmpty()) {
      return Optional.empty();
    }
    ChainSnapshot chain = chainOpt.get();
    Chart chart = chart(bank, index);
    // #11 (section 3.11) Straddle: a direction-NEUTRAL volatility position trading BOTH legs of the
    // SAME ATM strike (delta≈0.5 each). It must NOT take the CE/PE directional split below — there is no
    // single side — so it branches here on the side-agnostic §0B rails (time window already passed +
    // the volume floor). §3.11 gives no chart-RSI rule for a straddle, and the combined-premium-vs-VWAP
    // entry trigger + the low-IV gate are LIVE market-data series the deterministic seam cannot recompute
    // (deferred to live management); v1 emits the two-leg draft once an ATM pair exists. Short straddle
    // (SELL legs) is SPAN-deferred — StraddleLegPicker only ever returns BUY legs.
    if (cfg.requireStraddle()) {
      if (!ScalperGates.volume(cfg.signalIndex(), chart.volume()).pass()) {
        return Optional.empty();
      }
      return StraddleLegPicker.pick(
              chain.candidates(), chain.spot(), chain.basis(), barInstant, chain.expiry(),
              cfg.strikeParams().rate())
          .map(
              s ->
                  new Decision(
                      null,
                      List.of(new Leg(OptionType.CE, s.call()), new Leg(OptionType.PE, s.put())),
                      neutralConfluence(),
                      chain.expiry(),
                      null,
                      null)); // #11 straddle is direction-neutral — no Open=High tier
    }
    // §0B VWAP-decisive: CE above VWAP, PE below — the side the rest of the confluence must confirm.
    OptionType side =
        chart.close() != null && chart.vwap() != null && chart.close().compareTo(chart.vwap()) >= 0
            ? OptionType.CE
            : OptionType.PE;
    // W4 (tag gap-size-side-gate, S24 #9): a large gap-down suppresses the PE (put-buy) side
    // ("300-400 gap-down no-put"). Default-OFF; reads the same session gap GapState detects (pure).
    if (cfg.has("gap-size-side-gate")
        && !ScalperGates.gapSizeSide(GapState.detect(future, index), side, ScalperGates.GAP_SIDE_SUPPRESS_PTS)
            .pass()) {
      return Optional.empty();
    }
    // §0B hard "no trade" rails: volume floor + the RSI gate (both are blocks, not the soft dots the
    // scorer also weighs — a strong-everything-else signal must still respect them). #2 (open-high-low)
    // relaxes RSI to the source's ">50" floor; a strategy carrying the W3 rsi-s24-bands tag uses the
    // ratified 50-75 / 40-50 / 40-25 band (rsiS24Band); both are per-strategy overrides — the shared
    // rsiBand (60-80 / 20-40) is unchanged for every other strategy, so the goldens never move.
    boolean rsiOk =
        cfg.requireOpenHighLow()
            ? ScalperGates.rsiAbove(chart.rsi14(), oiProps.openHighRsiFloor()).pass()
            : cfg.requireRsiS24Bands()
                ? ScalperGates.rsiS24Band(chart.rsi14(), side).pass()
                : ScalperGates.rsiBand(chart.rsi14(), side).pass();
    if (!ScalperGates.volume(cfg.signalIndex(), chart.volume()).pass() || !rsiOk) {
      return Optional.empty();
    }
    // E3 volume-pump (tag volume-pump, §4.15.3): the deploy candle must be a floor-clearing pump closing
    // in the side's direction (dark-green/dark-red attribution). Reads the bar OHLCV off the future
    // series already in scope (no Chart extension). Default-OFF; a null future degrades to pass.
    if (cfg.has("volume-pump") && future != null && index >= 0) {
      EngineCandle bar = future.candle(index);
      if (!ScalperGates.volumePump(
              bar.close(), bar.open(), BigDecimal.valueOf(bar.volume()), cfg.signalIndex(), side)
          .pass()) {
        return Optional.empty();
      }
    }
    // W4 PARAM #5 (tag indicator-distance-veto): a chart-only overextension veto — block when price has
    // run far from the vwap/vwma/psar cluster (mean-reversion risk). Default-OFF; a null/absent cluster
    // degrades to pass inside the gate, so it never blocks on missing data.
    if (cfg.has("indicator-distance-veto")
        && !ScalperGates.indicatorDistance(chart, ScalperGates.INDICATOR_DISTANCE_MAX_PCT).pass()) {
      return Optional.empty();
    }
    // W4 PARAM #10 (tag divergence-vol-gate): the S24 Day-21 counter-trend confirm — a heavyweight ~125k
    // bar regardless of the index floor. Default-OFF; pairs with trend-change but is independent.
    if (cfg.has("divergence-vol-gate") && !ScalperGates.divergenceVolume(chart.volume()).pass()) {
      return Optional.empty();
    }
    // W4 (tag overbought-defer, S24 §3.1): stand aside while the tape is exhaustion-overbought (CE) /
    // oversold (PE). Default-OFF; a null RSI degrades to pass inside the gate.
    if (cfg.has("overbought-defer") && !ScalperGates.overboughtDefer(chart.rsi14(), side).pass()) {
      return Optional.empty();
    }
    // §3.1 Two-Candle: when the strategy declares it, the multi-bar formation is a HARD entry gate
    // (the chart-only YAML grammar cannot express it). The 1st-candle extreme becomes the stop.
    BigDecimal structuralStop = structuralStop(cfg, future, index, side);
    if (cfg.requireTwoCandle() && structuralStop == null) {
      return Optional.empty();
    }
    // #4 (section 3.4) Gap-Theory: when the strategy declares it, a still-open significant gap BLOCKS
    // the entry until it fills; once filled the with-trend entry passes and the pre-gap extreme
    // becomes the stop. No significant gap => the gate is INERT and leaves the entry to the confluence.
    if (cfg.requireGapFill()) {
      GapTheoryGate.Verdict gap = GapTheoryGate.evaluate(future, index, side, cfg.signalIndex());
      if (!gap.pass()) {
        return Optional.empty();
      }
      structuralStop = gap.stopLevel();
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
    // #5 (T2.1): the oi-cross-filter strategies HARD-require a >=50% call-put dOI imbalance before
    // the confluence is even consulted. Fail-closed like the volume/RSI rails; a null imbalance
    // (data unavailable / flat-OI caveat) DEGRADES to pass inside the gate, so it never blocks then.
    if (cfg.requireCallPutDeltaFilter()
        && !ScalperGates.callPutDeltaFilter(ctx.oi(), oiProps.crossFilterPct()).pass()) {
      return Optional.empty();
    }
    // E2 M1 (tag oi-cross-required, Trending-OI #5 defining trigger): a COMPLETED fresh PE-over-CE /
    // CE-over-PE cross favouring the side is a HARD precondition — stricter than the soft trending_cross
    // dot (gapWidening alone does not satisfy it). Fail-closed; degrades to NEUTRAL on derived history.
    if (cfg.has("oi-cross-required") && !ScalperGates.oiCrossRequired(ctx.oi(), side).pass()) {
      return Optional.empty();
    }
    // E2 M2 (tag oi-slope-agree, Trending-OI #5): the active-strike sentiment LEVEL and SLOPE must both
    // favour the side (a hard conjunction of the two soft sentiment dots). Fail-closed on null.
    if (cfg.has("oi-slope-agree") && !ScalperGates.oiSlopeAgree(ctx.oi(), side).pass()) {
      return Optional.empty();
    }
    // E2 M3 (tag oi-divergence-magnitude, Trending-OI #5): the OI lines must diverge by a real magnitude
    // (>=20% of total OI) with a corroborating price impulse (>=50%). Fail-closed; NEUTRAL on history.
    if (cfg.has("oi-divergence-magnitude")
        && !ScalperGates.oiDivergenceMagnitude(
                ctx.oi(), ScalperGates.OI_DIVERGENCE_MIN_PCT, ScalperGates.PRICE_IMPULSE_MIN_PCT)
            .pass()) {
      return Optional.empty();
    }
    // E2 M4 (tag flat-oi-stand-aside, the doc's flat-OI trap): a null/flat call-put imbalance STANDS
    // ASIDE (block) — the deliberate inverse of #5's fail-open (keep the two tags mutually exclusive).
    if (cfg.has("flat-oi-stand-aside") && !ScalperGates.flatOiStandAside(ctx.oi()).pass()) {
      return Optional.empty();
    }
    // E2 M6 (tag max-oi-sr-gate): the entry must not trade INTO the dominant standing-OI wall on its
    // side (max-CE-OI strike = overhead resistance, max-PE-OI strike = support). Walls come from the
    // chain's per-strike OI ladder already in hand (no new fetch); fail-open on a missing ladder/spot.
    if (cfg.has("max-oi-sr-gate")
        && !ScalperGates.oiWallClear(
                maxOiStrike(chain.strikeOi(), true), maxOiStrike(chain.strikeOi(), false),
                chain.spot(), side)
            .pass()) {
      return Optional.empty();
    }
    // FU2 — soft-dots-to-hard-gates: each of these confluence reads is ALSO a scored soft dot; arming the
    // tag makes it a STRICT requirement (the scorer/den is unchanged → parity-safe). Every operand is
    // already in hand. The natural home is the #10 "Connect-the-Dots" strategy, whose identity IS
    // requiring the whole confluence to align. Each is default-OFF.
    // - indicator-alignment: VWAP+VWMA+PSAR+ST all on the side (fail-closed on a missing/opposed leg).
    if (cfg.has("indicator-alignment-gate") && !ScalperGates.indicatorAlignment(chart, side).pass()) {
      return Optional.empty();
    }
    // - futures-oi: the futures OI quadrant must support the side (CE wants LB/SC; fail-closed on NEUTRAL).
    if (cfg.has("futures-oi-gate") && !ScalperGates.oiQuadrant(ctx.oi(), side).pass()) {
      return Optional.empty();
    }
    // - breadth: advances/declines > 32 for the side (fail-closed on a 0/0 / unavailable read).
    if (cfg.has("breadth-gate") && !ScalperGates.breadth(ctx.macro(), side).pass()) {
      return Optional.empty();
    }
    // - basis: the futures basis must agree (premium→CE, discount→PE); fail-OPEN on a null basis.
    if (cfg.has("basis-gate") && !ScalperGates.futuresBasis(ctx.oi(), side).pass()) {
      return Optional.empty();
    }
    // E3 P1 — directional-VIX HARD gate: VIX direction must confirm the side (falling→CE, rising→PE);
    // fail-OPEN on unknown direction, so it stays inert until the VIX feed is wired (vixRising is null today).
    if (cfg.has("directional-vix-gate") && !ScalperGates.vix(ctx.macro(), side).pass()) {
      return Optional.empty();
    }
    // E4 (tag iv-buyer-cap, IV>40 -> sellers' market): block a long-premium BUY when the traded side's
    // 6-strike IV is too rich (> 0.40 fraction). Fail-OPEN on a null side IV. A standalone risk veto.
    if (cfg.has("iv-buyer-cap")
        && !ScalperGates.ivBuyerCap(ctx.macro(), side, ScalperGates.IV_BUYER_CAP).pass()) {
      return Optional.empty();
    }
    // E3 fii-bias (tag fii-bias, §4.6): the FII L/S flow must not oppose the side (long% >= 50 for CE).
    // Reads the existing Macro.fiiLongPct (no new feed); fail-open on a null/neutral read.
    if (cfg.has("fii-bias") && !ScalperGates.fiiBias(ctx.macro(), side).pass()) {
      return Optional.empty();
    }
    // E3 constituent-gate (tag constituent-gate, §4.6): the index heavyweights' net push must not oppose
    // the side (Macro.constituentBias = /equity/index-contribution indexChangePct); fail-open on null/0.
    if (cfg.has("constituent-gate") && !ScalperGates.constituent(ctx.macro(), side).pass()) {
      return Optional.empty();
    }
    // W4 (tag directional-change-gate, S24 Day-20): only enter on a confirmed OI directional change —
    // the PE-CE tilt must have crossed within the window. Default-OFF; an unchanged/short series blocks.
    if (cfg.has("directional-change-gate") && !ScalperGates.directionalChange(ctx.oi()).pass()) {
      return Optional.empty();
    }
    // #12 (section 3.12) Trend-Change: when the strategy declares it, a HARD reversal pre-gate - a price
    // structure break in the side's direction + the >=50% Trending-OI momentum shift + the §3.1
    // 2-candle confirm + the ~14:30 down-reversal cap. Fail-closed (any missing leg / null OI deltas
    // block); the broken swing pivot becomes the structural stop.
    if (cfg.requireTrendChange()) {
      TrendChangeGate.Verdict tc =
          TrendChangeGate.evaluate(future, index, side, cfg.signalIndex(), ctx.oi(), istTime);
      if (!tc.pass()) {
        return Optional.empty();
      }
      structuralStop = tc.stopLevel();
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
      if (!ohl.pass()) {
        return Optional.empty();
      }
      structuralStop = ohl.stopLevel();
      ohTier = ohl.tier();
    }
    // #7 (section 7) Hero-Zero: when the strategy declares it, a HARD expiry-day end-of-day pre-gate -
    // expiry-day only (monthly-expiry blocks: prior-month OI is corrupt), after 14:30 / before the
    // 15:20 fresh-entry cap, a >50% OI+price "real move" on the side + short-covering on the side, RSI
    // not overbought/oversold, and the deploy bar closing toward the matching session extreme. Fail-
    // closed (any missing leg / null OI / null RSI blocks); the OPPOSITE session extreme becomes the
    // structural stop. tradeDate is the live bar's IST date (the same one driving the OI suppression).
    if (cfg.requireHeroZero()) {
      HeroZeroGate.Verdict hz =
          HeroZeroGate.evaluate(
              future, index, side, ctx.oi(), chart.rsi14(), istTime,
              calendar.isWeeklyIndexExpiryDay(tradeDate), calendar.isMonthlyIndexExpiryDay(tradeDate),
              cfg.has("herozero-side-oi"));
      if (!hz.pass()) {
        return Optional.empty();
      }
      structuralStop = hz.stopLevel();
    }
    // #9 (section 3.9) Morning Trade: VWAP is "not actionable before 10:30" in the opening tick, so the
    // opening-tick path drops VWAP from the HARD validity gate before 10:30 IST (it stays a soft dot in
    // the aggregate). Every other path keeps the decisive hard-VWAP behaviour (vwapHardGate=true).
    boolean vwapHardGate = !(cfg.openingTick() && istTime.isBefore(ScalperConfig.VWAP_ACTIONABLE_FROM));
    Confluence conf =
        ConnectTheDotsScorer.score(
            ctx, side, bias60m(bank, index), cfg.confluenceThreshold(), oiProps, vwapHardGate);
    boolean valid = side == OptionType.CE ? conf.bullish() : conf.bearish();
    if (!valid) {
      return Optional.empty();
    }
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
    OptionType decided = side;
    OpenHighLow.Tier decidedTier = ohTier;
    return pick.map(
        p -> new Decision(decided, List.of(new Leg(decided, p)), conf, chain.expiry(), stop, decidedTier));
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
        bank.builtin("volume", index));
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
