package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.eval.BarValues;
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

  /** Wires the market-data OI/chain client + the Tier-1 OI-analytics thresholds. */
  public ScalperConfluenceGate(MarketOiClient client, ScalperOiProps oiProps) {
    this.client = client;
    this.oiProps = oiProps;
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
    if (!ScalperGates.timeWindow(istTime).pass()) {
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
    // §0B hard "no trade" rails: volume floor + the RSI 40–60 dead band (both are blocks, not the
    // soft dots the scorer also weighs — a strong-everything-else signal must still respect them).
    if (!ScalperGates.volume(cfg.underlying(), chart.volume()).pass()
        || !ScalperGates.rsiBand(chart.rsi14(), side).pass()) {
      return Optional.empty();
    }
    // §3.1 Two-Candle: when the strategy declares it, the multi-bar formation is a HARD entry gate
    // (the chart-only YAML grammar cannot express it). The 1st-candle extreme becomes the stop.
    BigDecimal structuralStop = structuralStop(cfg, future, index, side);
    if (cfg.requireTwoCandle() && structuralStop == null) {
      return Optional.empty();
    }
    ScalperGateContext ctx = client.context(cfg.underlying(), istTime, eodDate, chain.expiry(), chart);
    // #5 (T2.1): the oi-cross-filter strategies HARD-require a >=50% call-put dOI imbalance before
    // the confluence is even consulted. Fail-closed like the volume/RSI rails; a null imbalance
    // (data unavailable / flat-OI caveat) DEGRADES to pass inside the gate, so it never blocks then.
    if (cfg.requireCallPutDeltaFilter()
        && !ScalperGates.callPutDeltaFilter(ctx.oi(), oiProps.crossFilterPct()).pass()) {
      return Optional.empty();
    }
    Confluence conf =
        ConnectTheDotsScorer.score(ctx, side, bias60m(bank, index), cfg.confluenceThreshold(), oiProps);
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
   * (crossover) candle's extreme, or {@code null} when the strategy sizes off structure/VWAP only.
   */
  private static BigDecimal structuralStop(
      ScalperConfig cfg, EngineSeries future, int index, OptionType side) {
    if (cfg.requireTwoCandle()) {
      return TwoCandleGate.detect(future, index, side, cfg.underlying()).stopLevel();
    }
    if (cfg.structuralStop() == StructuralStop.ENTRY_CANDLE && future != null && index >= 0) {
      return side == OptionType.CE ? future.candle(index).low() : future.candle(index).high();
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
