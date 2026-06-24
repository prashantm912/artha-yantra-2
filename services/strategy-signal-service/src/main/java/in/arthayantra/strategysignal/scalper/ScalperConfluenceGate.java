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

  /**
   * The chosen option, the side, the confluence that justified it, and the entry-time structural
   * stop-loss on the index future ({@code null} when the strategy sizes off structure/VWAP only).
   */
  public record Decision(
      OptionType side,
      StrikePicker.Pick pick,
      Confluence confluence,
      LocalDate expiry,
      BigDecimal structuralStop) {}

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
    // §0B VWAP-decisive: CE above VWAP, PE below — the side the rest of the confluence must confirm.
    OptionType side =
        chart.close() != null && chart.vwap() != null && chart.close().compareTo(chart.vwap()) >= 0
            ? OptionType.CE
            : OptionType.PE;
    // §0B hard "no trade" rails: volume floor + the RSI gate (both are blocks, not the soft dots the
    // scorer also weighs — a strong-everything-else signal must still respect them). #2 (open-high-low)
    // relaxes RSI to the source's ">50" floor instead of the shared 60-80/20-40 band; the shared
    // rsiBand is unchanged for every other strategy.
    boolean rsiOk =
        cfg.requireOpenHighLow()
            ? ScalperGates.rsiAbove(chart.rsi14(), oiProps.openHighRsiFloor()).pass()
            : ScalperGates.rsiBand(chart.rsi14(), side).pass();
    if (!ScalperGates.volume(cfg.underlying(), chart.volume()).pass() || !rsiOk) {
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
      GapTheoryGate.Verdict gap = GapTheoryGate.evaluate(future, index, side, cfg.underlying());
      if (!gap.pass()) {
        return Optional.empty();
      }
      structuralStop = gap.stopLevel();
    }
    // The live bar's IST date drives the S24 monthly-expiry OI suppression (distinct from eodDate,
    // the prior completed session used for breadth/FII).
    LocalDate tradeDate = barInstant.atZone(Ist.ZONE).toLocalDate();
    ScalperGateContext ctx =
        client.context(cfg.underlying(), istTime, eodDate, chain.expiry(), tradeDate, chart);
    // #5 (T2.1): the oi-cross-filter strategies HARD-require a >=50% call-put dOI imbalance before
    // the confluence is even consulted. Fail-closed like the volume/RSI rails; a null imbalance
    // (data unavailable / flat-OI caveat) DEGRADES to pass inside the gate, so it never blocks then.
    if (cfg.requireCallPutDeltaFilter()
        && !ScalperGates.callPutDeltaFilter(ctx.oi(), oiProps.crossFilterPct()).pass()) {
      return Optional.empty();
    }
    // #12 (section 3.12) Trend-Change: when the strategy declares it, a HARD reversal pre-gate - a price
    // structure break in the side's direction + the >=50% Trending-OI momentum shift + the §3.1
    // 2-candle confirm + the ~14:30 down-reversal cap. Fail-closed (any missing leg / null OI deltas
    // block); the broken swing pivot becomes the structural stop.
    if (cfg.requireTrendChange()) {
      TrendChangeGate.Verdict tc =
          TrendChangeGate.evaluate(future, index, side, cfg.underlying(), ctx.oi(), istTime);
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
    if (cfg.requireOpenHighLow()) {
      OpenHighLow.Marks futureMarks = OpenHighLow.marks(future, index);
      MarketOiClient.OpenHighStats stats =
          client.openHighStats(cfg.underlying(), chain.expiry(), oiProps.openHighWindow().intValue());
      OpenHighLowGate.Verdict ohl =
          OpenHighLowGate.evaluate(
              futureMarks, stats, side, oiProps, ctx.oi(), ctx.chart().vwap(), istTime);
      if (!ohl.pass()) {
        return Optional.empty();
      }
      structuralStop = ohl.stopLevel();
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
              calendar.isWeeklyIndexExpiryDay(tradeDate), calendar.isMonthlyIndexExpiryDay(tradeDate));
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
    return StrikePicker.pick(
            chain.candidates(), chain.spot(), chain.basis(), side, barInstant, chain.expiry(),
            cfg.strikeParams())
        .map(pick -> new Decision(side, pick, conf, chain.expiry(), stop));
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
      return TwoCandleGate.detect(future, index, side, cfg.underlying()).stopLevel();
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
}
