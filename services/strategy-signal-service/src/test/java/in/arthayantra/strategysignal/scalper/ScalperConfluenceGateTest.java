package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategyengine.eval.BarValues;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategysignal.scalper.MarketOiClient.ChainSnapshot;
import in.arthayantra.strategysignal.scalper.MarketOiClient.OpenHighStats;
import in.arthayantra.strategysignal.scalper.MarketOiClient.StrikeStat;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate.Decision;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The §12.3 confluence seam: chart-side pick + OI/macro confirmation → option, or block. */
class ScalperConfluenceGateTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 25);
  private static final Instant NOW = LocalDate.of(2026, 6, 20).atTime(10, 0).atOffset(IST).toInstant();
  private static final LocalTime IST_TIME = LocalTime.of(10, 0);
  private static final LocalDate EOD = LocalDate.of(2026, 6, 20);
  // the NSE calendar the seam consults ONLY for the #7 hero-zero expiry-day check (never reached by
  // the non-hero-zero cases below, so its expiry-day verdict is immaterial to them).
  private static final MarketCalendar CAL = MarketCalendar.nse();
  private static final ScalperConfig CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          false, ScalperConfig.StructuralStop.NONE, false, false, false, false, false, false, false,
          false);
  private static final ScalperConfig TWO_CANDLE_CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          true, ScalperConfig.StructuralStop.TWO_CANDLE_FIRST, false, false, false, false, false, false,
          false, false);
  // #5: a strategy with the oi-cross-filter HARD pre-gate enabled.
  private static final ScalperConfig OI_CROSS_CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          false, ScalperConfig.StructuralStop.NONE, true, false, false, false, false, false, false,
          false);
  // #4: a strategy with the gap-theory HARD gap-fill pre-gate enabled.
  private static final ScalperConfig GAP_CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          false, ScalperConfig.StructuralStop.GAP_TREND, false, true, false, false, false, false, false,
          false);
  // #12: a strategy with the trend-change HARD pre-gate enabled (structure break + >=50% OI shift).
  private static final ScalperConfig TREND_CHANGE_CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          false, ScalperConfig.StructuralStop.SWING_BREAK, false, false, true, false, false, false, false,
          false);
  // #2: a strategy with the open-high-low HARD FNO-structure pre-gate enabled.
  private static final ScalperConfig OPEN_HIGH_LOW_CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          false, ScalperConfig.StructuralStop.VWAP, false, false, false, true, false, false, false,
          false);
  // #9: a strategy with the opening-tick (Morning Trade) path enabled — its own opening-tick time
  // window + the VWAP HARD-gate degrade before 10:30 + a FIRST-CANDLE stop anchor.
  private static final ScalperConfig OPENING_TICK_CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          false, ScalperConfig.StructuralStop.FIRST_CANDLE, false, false, false, false, true, false, false,
          false);
  // #11: a strategy with the straddle (neutral two-leg) path enabled.
  private static final ScalperConfig STRADDLE_CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          false, ScalperConfig.StructuralStop.NONE, false, false, false, false, false, false, true,
          false);
  // W3 (rsi-s24-bands): a strategy carrying the S24 RSI-band tag — routes the hard RSI rail through
  // rsiS24Band (CE 50-75 / PE 25-40) instead of the legacy 60-80 / 20-40 band.
  private static final ScalperConfig RSI_S24_CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          false, ScalperConfig.StructuralStop.NONE, false, false, false, false, false, false, false,
          true);

  // a 3m index-future series: index 2 is the deploy bar; indices 0/1 are the forming candles.
  private static EngineSeries futureSeries(EngineCandle... candles) {
    return EngineSeries.of(new SeriesKey("NSE", "NIFTY-FUT", "3m"), List.of(candles));
  }

  // open, high, low, close, volume — a strong green candle (body 10, shadow 2), volume above 125k.
  private static EngineCandle strongGreen(int i) {
    return new EngineCandle(
        NOW.atOffset(IST).plusMinutes(i), bd("100"), bd("111"), bd("99"), bd("110"), 130_000);
  }

  // a weak candle (body 1, shadow 14 ≥ 2×body) — fails the 2nd-candle strength test.
  private static EngineCandle weakGreen(int i) {
    return new EngineCandle(
        NOW.atOffset(IST).plusMinutes(i), bd("100"), bd("110"), bd("95"), bd("101"), 130_000);
  }

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  // a bank whose close (100) sits above VWAP (99) → CE side; RSI 65 (in 60–80) + volume 130k (above
  // the NIFTY 125k floor) clear the §0B hard pre-flight gates the seam now enforces.
  private static BarValues bullBank() {
    return bullBankRsi(bd("65"));
  }

  // bullBank with a caller-chosen RSI (the §0B hard RSI rail reads chart.rsi14() off the bank).
  private static BarValues bullBankRsi(BigDecimal rsi) {
    Map<String, BigDecimal> builtins = Map.of("close", bd("100"), "vwap", bd("99"), "volume", bd("130000"));
    Map<String, BigDecimal> aliases =
        Map.of("vwma20", bd("98"), "psar", bd("97"), "rsi14", rsi, "supertrend", bd("1"));
    return new BarValues() {
      @Override
      public BigDecimal valueAt(String alias, int i) {
        return aliases.get(alias);
      }

      @Override
      public BigDecimal previousValueAt(String alias, int i) {
        return null;
      }

      @Override
      public BigDecimal builtin(String name, int i) {
        return builtins.get(name);
      }
    };
  }

  private static ScalperGateContext bullContext() {
    return new ScalperGateContext(
        "NIFTY 50", "NIFTY 50", IST_TIME,
        new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000")),
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("5"), bd("5"), null, null, null, false,
            false, null, null, null),
        new Macro(bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"), null, null));
  }

  // a bullish context whose OI carries a specific call-put dOI imbalance % (the #5 pre-gate operand).
  private static ScalperGateContext bullContextWithImbalance(BigDecimal imbalancePct) {
    return new ScalperGateContext(
        "NIFTY 50", "NIFTY 50", IST_TIME,
        new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000")),
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("5"), bd("5"), null, null,
            imbalancePct, false, false, null, null, null),
        new Macro(bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"), null, null));
  }

  private static ChainSnapshot chainWithInBandCe() {
    // spot 20000, basis 0, ~5d, iv 0.14 → 19850 CE lands delta ~0.68 (in 0.6–0.7); others out
    List<StrikePicker.Candidate> candidates =
        List.of(
            new StrikePicker.Candidate("NIFTY19850CE", bd("19850"), CE, bd("200"), bd("0.14")),
            new StrikePicker.Candidate("NIFTY20000CE", bd("20000"), CE, bd("120"), bd("0.14")));
    return new ChainSnapshot(EXPIRY, bd("20000"), bd("20000"), candidates);
  }

  @Test
  void confluenceConfirmsAndPicksTheInBandCe() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(bullContext());

    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL).evaluate(CFG, bullBank(), null, 0, NOW, IST_TIME, EOD);

    assertThat(decision).isPresent();
    assertThat(decision.get().side()).isEqualTo(CE);
    assertThat(decision.get().pick().candidate().tradingsymbol()).isEqualTo("NIFTY19850CE");
    assertThat(decision.get().confluence().bullish()).isTrue();
  }

  @Test
  void rsiS24BandsTagRoutesTheHardRailThroughTheNewBand() {
    // RSI 55: inside the S24 buy band (50-75) but in the LEGACY 40-60 no-trade gap. The hard RSI
    // rail must BLOCK a legacy strategy and PASS one carrying the rsi-s24-bands tag — chain +
    // confluence identical, the only difference is the tag-selected band.
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(bullContext());
    ScalperConfluenceGate gate = new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL);

    // legacy band: RSI 55 is in the 40-60 no-trade zone → the hard rail blocks.
    assertThat(gate.evaluate(CFG, bullBankRsi(bd("55")), null, 0, NOW, IST_TIME, EOD)).isEmpty();
    // rsi-s24-bands armed: RSI 55 is in the 50-75 buy band → the rail passes and the signal fires.
    Optional<Decision> s24 = gate.evaluate(RSI_S24_CFG, bullBankRsi(bd("55")), null, 0, NOW, IST_TIME, EOD);
    assertThat(s24).isPresent();
    assertThat(s24.get().confluence().bullish()).isTrue();
  }

  @Test
  void blocksWhenTheChainIsUnavailable() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.empty());

    assertThat(new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL).evaluate(CFG, bullBank(), null, 0, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void blocksWhenConfluenceFails() {
    // a bearish context for a CE side → confluence is not bullish → block
    ScalperGateContext bear =
        new ScalperGateContext(
            "NIFTY 50", "NIFTY 50", IST_TIME,
            new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000")),
            new Oi(
                OiQuadrant.SHORT_BUILDUP, OiQuadrant.SHORT_BUILDUP, bd("-10"), bd("-5"), bd("-5"), null, null, null,
                false, false, null, null, null),
            new Macro(bd("14"), bd("80"), bd("12"), Boolean.TRUE, 10, 40, bd("50"), null, null));
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(bear);

    assertThat(new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL).evaluate(CFG, bullBank(), null, 0, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void blocksInTheMiddayWindowBeforeAnyChainFetch() {
    MarketOiClient client = mock(MarketOiClient.class);
    // 11:30 IST is inside the §0B 11:00–13:00 block — blocked at the hard pre-flight, no HTTP
    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(CFG, bullBank(), null, 0, NOW, LocalTime.of(11, 30), EOD))
        .isEmpty();
    org.mockito.Mockito.verifyNoInteractions(client);
  }

  @Test
  void blocksWhenRsiSitsInTheDeadBand() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    // RSI 50 is in the 40–60 no-trade band → blocked even with everything else bullish
    BarValues deadRsi =
        new BarValues() {
          @Override
          public BigDecimal valueAt(String alias, int i) {
            return "rsi14".equals(alias) ? bd("50") : bd("98");
          }

          @Override
          public BigDecimal previousValueAt(String alias, int i) {
            return null;
          }

          @Override
          public BigDecimal builtin(String name, int i) {
            return Map.of("close", bd("100"), "vwap", bd("99"), "volume", bd("130000")).get(name);
          }
        };

    assertThat(new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL).evaluate(CFG, deadRsi, null, 0, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void blocksWhenConfluenceConfirmsButNoStrikeInBand() {
    MarketOiClient client = mock(MarketOiClient.class);
    // only deep-OTM CEs (delta well below 0.6) → confluence passes but StrikePicker finds nothing
    ChainSnapshot otmOnly =
        new ChainSnapshot(
            EXPIRY, bd("20000"), bd("20000"),
            List.of(new StrikePicker.Candidate("NIFTY20800CE", bd("20800"), CE, bd("110"), bd("0.14"))));
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(otmOnly));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(bullContext());

    assertThat(new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL).evaluate(CFG, bullBank(), null, 0, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void twoCandleStrategyConfirmsOnAFormationAndAnchorsTheStop() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(bullContext());
    // two strong green candles (0,1) then the deploy bar (2) → the §3.1 formation is present
    EngineSeries future = futureSeries(strongGreen(0), strongGreen(1), strongGreen(2));

    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
            .evaluate(TWO_CANDLE_CFG, bullBank(), future, 2, NOW, IST_TIME, EOD);

    assertThat(decision).isPresent();
    assertThat(decision.get().structuralStop()).isEqualByComparingTo("99"); // 1st candle low
  }

  @Test
  void twoCandleStrategyBlocksWhenTheFormationIsAbsent() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    // the 2nd candle is weak (wick ≥ 2×body) → no formation → the hard gate blocks the entry
    EngineSeries future = futureSeries(strongGreen(0), weakGreen(1), strongGreen(2));

    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(TWO_CANDLE_CFG, bullBank(), future, 2, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void oiCrossFilterStrategyBlocksBelowFiftyPercentAndPassesAtOrAbove() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    // imbalance 49.9% (< the default 50 floor) → the #5 HARD pre-gate blocks before any pick
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(bullContextWithImbalance(bd("49.9")));
    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(OI_CROSS_CFG, bullBank(), null, 0, NOW, IST_TIME, EOD))
        .isEmpty();

    // imbalance 50% (== the floor) → the pre-gate passes and the confluence picks the in-band CE
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(bullContextWithImbalance(bd("50")));
    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
            .evaluate(OI_CROSS_CFG, bullBank(), null, 0, NOW, IST_TIME, EOD);
    assertThat(decision).isPresent();
    assertThat(decision.get().pick().candidate().tradingsymbol()).isEqualTo("NIFTY19850CE");
  }

  // a strong green candle at an explicit price (open,high,low,close), volume above the 125k floor.
  private static EngineCandle gapBar(int i, String open, String high, String low, String close) {
    return new EngineCandle(
        NOW.atOffset(IST).plusMinutes(3L * i), bd(open), bd(high), bd(low), bd(close), 130_000);
  }

  @Test
  void gapTheoryStrategyBlocksWhileTheGapIsStillOpen() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(bullContext());
    // bar0 close 100; bar1 opens 105 (5 pt gap up) and stays above the origin -> unfilled -> block.
    EngineSeries future =
        futureSeries(
            gapBar(0, "99", "101", "98", "100"),
            gapBar(1, "105", "108", "104", "107"));

    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(GAP_CFG, bullBank(), future, 1, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void gapTheoryStrategyPassesWithTrendOnceTheGapHasFilledAndAnchorsThePreGapStop() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(bullContext());
    // 5 pt gap up (bar0->bar1), then bar2 low (96) retraces below the origin (100) -> filled -> pass.
    EngineSeries future =
        futureSeries(
            gapBar(0, "99", "101", "95", "100"),
            gapBar(1, "105", "108", "104", "107"),
            gapBar(2, "106", "107", "96", "98"));

    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
            .evaluate(GAP_CFG, bullBank(), future, 2, NOW, IST_TIME, EOD);

    assertThat(decision).isPresent();
    assertThat(decision.get().structuralStop()).isEqualByComparingTo("95"); // pre-gap candle low
  }

  @Test
  void gapTheoryStrategyIsInertAndTradesNormallyWhenNoSignificantGapExists() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(bullContext());
    // a flat series with no >3 pt jump -> the gap gate never fires -> the confluence picks normally.
    EngineSeries future =
        futureSeries(
            gapBar(0, "99", "101", "98", "100"),
            gapBar(1, "100", "102", "99", "101"),
            gapBar(2, "101", "103", "100", "102"));

    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
            .evaluate(GAP_CFG, bullBank(), future, 2, NOW, IST_TIME, EOD);

    assertThat(decision).isPresent();
    assertThat(decision.get().structuralStop()).isNull(); // inert => no gap stop anchor
  }

  // a bullish context whose OI carries the #12 trend-change shift (put-OI up, call-OI down, >=50%).
  private static ScalperGateContext bullContextWithTrendShift() {
    return new ScalperGateContext(
        "NIFTY 50", "NIFTY 50", IST_TIME,
        new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000")),
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("5"), bd("5"),
            bd("-200"), bd("300"), bd("60"), false, false, null, null, null),
        new Macro(bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"), null, null));
  }

  // a 3m future where bar2 is a swing-high (high 111), two green confirm bars, then the deploy bar5
  // closes above the swing-high -> an up-structure break that coincides with the §3.1 2-candle confirm.
  private static EngineSeries upReversalFuture() {
    return futureSeries(
        gapBar(0, "90", "95", "88", "94"),
        gapBar(1, "94", "100", "93", "98"),
        gapBar(2, "98", "111", "97", "105"), // swing-high pivot (high 111)
        gapBar(3, "103", "106", "102", "105"), // green confirm 1
        gapBar(4, "105", "109", "104", "108"), // green confirm 2 (strong body)
        gapBar(5, "108", "113", "107", "112")); // deploy: closes 112 > swing-high 111 -> break
  }

  @Test
  void trendChangeStrategyPassesOnAStructureBreakWithTheOiShiftAndAnchorsTheSwingStop() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(bullContextWithTrendShift());

    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
            .evaluate(TREND_CHANGE_CFG, bullBank(), upReversalFuture(), 5, NOW, IST_TIME, EOD);

    assertThat(decision).isPresent();
    assertThat(decision.get().structuralStop()).isEqualByComparingTo("111"); // the broken swing-high
  }

  @Test
  void trendChangeStrategyBlocksWithoutAStructureBreak() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(bullContextWithTrendShift());
    // the deploy bar closes 108 (below the swing-high 111) -> no break -> the hard pre-gate blocks.
    EngineSeries noBreak =
        futureSeries(
            gapBar(0, "90", "95", "88", "94"),
            gapBar(1, "94", "100", "93", "98"),
            gapBar(2, "98", "111", "97", "105"),
            gapBar(3, "103", "106", "102", "105"),
            gapBar(4, "105", "109", "104", "107"),
            gapBar(5, "107", "110", "106", "108"));

    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(TREND_CHANGE_CFG, bullBank(), noBreak, 5, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void trendChangeStrategyBlocksWhenTheOiShiftIsBelowFiftyPercent() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    // correct break + directional OI, but the imbalance is 49% (< the 50% floor) -> block.
    ScalperGateContext weakShift =
        new ScalperGateContext(
            "NIFTY 50", "NIFTY 50", IST_TIME,
            new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000")),
            new Oi(
                OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("5"), bd("5"),
                bd("-200"), bd("300"), bd("49"), false, false, null, null, null),
            new Macro(bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"), null, null));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(weakShift);

    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(TREND_CHANGE_CFG, bullBank(), upReversalFuture(), 5, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void nonTrendChangeStrategyIsUnaffectedByThePreGate() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(bullContext());
    // CFG carries no trend-change tag -> the structure-break/OI-shift pre-gate is never consulted.
    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(CFG, bullBank(), upReversalFuture(), 5, NOW, IST_TIME, EOD))
        .isPresent();
  }

  // a 3m future Open=High session: bar0 open 100 is the running session high (later bars never exceed
  // it). The deploy bar is index 2. (gapBar volume 130k clears the §0B NIFTY floor.)
  private static EngineSeries openHighFuture() {
    return futureSeries(
        gapBar(0, "100", "100", "95", "98"),
        gapBar(1, "98", "99", "94", "96"),
        gapBar(2, "96", "100", "93", "97"));
  }

  private static StrikeStat ohStrike(String strike, in.arthayantra.black76.Black76.OptionType type) {
    return new StrikeStat(bd(strike), type, true, false, bd("100"), bd("100"), bd("100"), null, null);
  }

  // a bullish HIGH footprint: 3 CE OH strikes + 1 PE OL, ATM 20000.
  private static OpenHighStats bullishHighFootprint() {
    return new OpenHighStats(
        bd("20000"),
        List.of(
            ohStrike("19900", CE),
            ohStrike("20000", CE),
            ohStrike("20100", CE),
            new StrikeStat(
                bd("19900"), in.arthayantra.black76.Black76.OptionType.PE, false, true, bd("40"),
                bd("40"), bd("45"), null, null)));
  }

  // a bullish context whose underlying quadrant (LONG_BUILDUP) confirms a CE OH + within-band spurts.
  private static ScalperGateContext bullContextWithSpurts(String spurtOiPct, String spurtPricePct) {
    return new ScalperGateContext(
        "NIFTY 50", "NIFTY 50", IST_TIME,
        new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000")),
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("5"), bd("5"), null, null,
            null, false, false, null,
            spurtOiPct == null ? null : bd(spurtOiPct),
            spurtPricePct == null ? null : bd(spurtPricePct)),
        new Macro(bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"), null, null));
  }

  @Test
  void openHighLowStrategyPassesOnAFutureOpenHighAtTheHighTierAndAnchorsTheVwapStop() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(bullContextWithSpurts("10", "20"));
    when(client.openHighStats(eq("NIFTY 50"), any(), eq(3))).thenReturn(bullishHighFootprint());

    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
            .evaluate(OPEN_HIGH_LOW_CFG, bullBank(), openHighFuture(), 2, NOW, IST_TIME, EOD);

    assertThat(decision).isPresent();
    assertThat(decision.get().structuralStop()).isEqualByComparingTo("99"); // the front-future VWAP
  }

  @Test
  void openHighLowStrategyBlocksAtTheMildTierWhenFewerThanMinStrikes() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(bullContextWithSpurts("10", "20"));
    // OH on the future but only 2 CE OH strikes (< minStrikes 3) -> MILD footprint -> block.
    OpenHighStats few =
        new OpenHighStats(bd("20000"), List.of(ohStrike("19900", CE), ohStrike("20000", CE)));
    when(client.openHighStats(eq("NIFTY 50"), any(), eq(3))).thenReturn(few);

    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(OPEN_HIGH_LOW_CFG, bullBank(), openHighFuture(), 2, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void openHighLowStrategyBlocksWhenTheFootprintIsUnavailable() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(bullContextWithSpurts("10", "20"));
    // the endpoint degraded to an empty footprint -> the gate blocks (never a false HIGH).
    when(client.openHighStats(eq("NIFTY 50"), any(), eq(3)))
        .thenReturn(OpenHighStats.EMPTY);

    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(OPEN_HIGH_LOW_CFG, bullBank(), openHighFuture(), 2, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void openHighLowStrategyBlocksWhenARejectSpurtExceedsFiftyPercent() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    // a HIGH-tier OH but the price spurt is 60% (> the 50% reject barrier) -> the move exhausted -> block.
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(bullContextWithSpurts("10", "60"));
    when(client.openHighStats(eq("NIFTY 50"), any(), eq(3))).thenReturn(bullishHighFootprint());

    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(OPEN_HIGH_LOW_CFG, bullBank(), openHighFuture(), 2, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void nonOpenHighLowStrategyIsUnaffectedByTheFutureOpenHigh() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(bullContext());
    // CFG carries no open-high-low tag -> the OH/OL pre-gate is never consulted; the confluence picks.
    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(CFG, bullBank(), openHighFuture(), 2, NOW, IST_TIME, EOD))
        .isPresent();
  }

  @Test
  void nonOiCrossFilterStrategyIsUnaffectedByALowImbalance() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    // a 1% imbalance would trip the #5 gate, but CFG (no oi-cross-filter tag) never consults it
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(bullContextWithImbalance(bd("1")));

    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(CFG, bullBank(), null, 0, NOW, IST_TIME, EOD))
        .isPresent();
  }

  // #9 Morning Trade -------------------------------------------------------------------------------

  private static final LocalTime OPEN_TICK = LocalTime.of(9, 16);

  // a bank whose close (100) EQUALS its VWAP (100): the seam still routes it CE (close >= vwap) but the
  // scorer's strictly-greater vwapSide is FALSE — the only way to drive a VWAP-misaligned CE through the
  // gate. RSI 65 / volume 130k clear the §0B rails so only the VWAP dot is off.
  private static BarValues closeEqualsVwapBank() {
    Map<String, BigDecimal> builtins = Map.of("close", bd("100"), "vwap", bd("100"), "volume", bd("130000"));
    Map<String, BigDecimal> aliases =
        Map.of("vwma20", bd("98"), "psar", bd("97"), "rsi14", bd("65"), "supertrend", bd("1"));
    return new BarValues() {
      @Override
      public BigDecimal valueAt(String alias, int i) {
        return aliases.get(alias);
      }

      @Override
      public BigDecimal previousValueAt(String alias, int i) {
        return null;
      }

      @Override
      public BigDecimal builtin(String name, int i) {
        return builtins.get(name);
      }
    };
  }

  // a strongly-bullish CE context: every OI/macro dot supports, so the aggregate clears 0.6 even with
  // the decisive VWAP dot off (proves the degrade actually fires on the rest of the confluence).
  private static ScalperGateContext strongBullContext(LocalTime ist) {
    return new ScalperGateContext(
        "NIFTY 50", "NIFTY 50", ist,
        new Chart(bd("100"), bd("100"), bd("98"), bd("97"), 1, bd("65"), bd("130000")),
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("80"), bd("5"), bd("5"),
            bd("-60000"), bd("70000"), bd("80"), true, false, bd("5"), bd("60"), bd("60")),
        new Macro(bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"), bd("0.20"), bd("0.05")));
  }

  @Test
  void openingTickStrategyPassesTheOpenWindowWhileADefaultStrategyIsBlocked() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ScalperGateContext(
                "NIFTY 50", "NIFTY 50", OPEN_TICK,
                new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000")),
                new Oi(
                    OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("5"), bd("5"), null, null,
                    null, false, false, null, null, null),
                new Macro(bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"), null, null)));
    EngineSeries future = futureSeries(strongGreen(0), strongGreen(1), strongGreen(2));

    // the opening-tick strategy admits 09:16 (its 09:15-09:30 window) and anchors the first-candle stop
    Optional<Decision> opening =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
            .evaluate(OPENING_TICK_CFG, bullBank(), future, 2, NOW, OPEN_TICK, EOD);
    assertThat(opening).isPresent();
    assertThat(opening.get().structuralStop()).isEqualByComparingTo("99"); // first session candle low

    // a default strategy at the same 09:16 is blocked at the §0B "after 09:45" floor (no HTTP)
    MarketOiClient idle = mock(MarketOiClient.class);
    assertThat(
            new ScalperConfluenceGate(idle, ScalperOiProps.defaults(), CAL)
                .evaluate(CFG, bullBank(), future, 2, NOW, OPEN_TICK, EOD))
        .isEmpty();
    org.mockito.Mockito.verifyNoInteractions(idle);
  }

  @Test
  void openingTickStrategyFiresOnAVwapMisalignedSetupBeforeTenThirty() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(strongBullContext(OPEN_TICK));
    EngineSeries future = futureSeries(strongGreen(0), strongGreen(1), strongGreen(2));

    // close == vwap (vwapSide false) but the OI/macro confluence is strong: before 10:30 the opening
    // tick degrades VWAP from the HARD gate, so the CE still fires.
    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
            .evaluate(OPENING_TICK_CFG, closeEqualsVwapBank(), future, 2, NOW, OPEN_TICK, EOD);
    assertThat(decision).isPresent();
    assertThat(decision.get().side()).isEqualTo(CE);
    assertThat(decision.get().confluence().bullish()).isTrue();
    assertThat(decision.get().confluence().vwapAligned()).isFalse(); // VWAP truly was misaligned
  }

  @Test
  void nonOpeningTickStrategyKeepsTheHardVwapGate() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(strongBullContext(IST_TIME));
    EngineSeries future = futureSeries(strongGreen(0), strongGreen(1), strongGreen(2));

    // the SAME VWAP-misaligned (close == vwap) setup at a normal 10:00: a default strategy keeps the
    // decisive hard-VWAP gate, so the otherwise-strong confluence is blocked.
    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(CFG, closeEqualsVwapBank(), future, 2, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  // #11 Straddle -----------------------------------------------------------------------------------

  // a chain carrying BOTH legs of three strikes; 20000 is the ATM (forward 20000): a complete CE+PE
  // pair nearest the forward. 19900/20100 also have both legs (the picker must still choose ATM 20000).
  private static ChainSnapshot chainWithAtmPair() {
    List<StrikePicker.Candidate> candidates =
        List.of(
            new StrikePicker.Candidate("NIFTY19900CE", bd("19900"), CE, bd("160"), bd("0.14")),
            new StrikePicker.Candidate(
                "NIFTY19900PE", bd("19900"), in.arthayantra.black76.Black76.OptionType.PE,
                bd("70"), bd("0.14")),
            new StrikePicker.Candidate("NIFTY20000CE", bd("20000"), CE, bd("120"), bd("0.14")),
            new StrikePicker.Candidate(
                "NIFTY20000PE", bd("20000"), in.arthayantra.black76.Black76.OptionType.PE,
                bd("110"), bd("0.14")),
            new StrikePicker.Candidate("NIFTY20100CE", bd("20100"), CE, bd("80"), bd("0.14")),
            new StrikePicker.Candidate(
                "NIFTY20100PE", bd("20100"), in.arthayantra.black76.Black76.OptionType.PE,
                bd("150"), bd("0.14")));
    return new ChainSnapshot(EXPIRY, bd("20000"), bd("20000"), candidates);
  }

  @Test
  void straddleStrategyPicksBothAtmLegsNeutrallyWithNoStructuralStop() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithAtmPair()));
    // the seam never reads the OI/macro context for a straddle (no directional confluence) — so the
    // context mock is deliberately left unstubbed to prove it is not consulted.

    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
            .evaluate(STRADDLE_CFG, bullBank(), null, 0, NOW, IST_TIME, EOD);

    assertThat(decision).isPresent();
    Decision d = decision.get();
    assertThat(d.neutral()).isTrue();
    assertThat(d.side()).isNull(); // direction-neutral
    assertThat(d.structuralStop()).isNull(); // SL is the combined-premium %, not a future structure
    // exactly two legs: the ATM 20000 CE + the ATM 20000 PE (same strike, both BUY)
    assertThat(d.legs()).hasSize(2);
    assertThat(d.legs().get(0).optionType()).isEqualTo(CE);
    assertThat(d.legs().get(1).optionType())
        .isEqualTo(in.arthayantra.black76.Black76.OptionType.PE);
    assertThat(d.legs().get(0).pick().candidate().tradingsymbol()).isEqualTo("NIFTY20000CE");
    assertThat(d.legs().get(1).pick().candidate().tradingsymbol()).isEqualTo("NIFTY20000PE");
    // the same ATM strike on both legs (§3.11)
    assertThat(d.legs().get(0).pick().candidate().strike())
        .isEqualByComparingTo(d.legs().get(1).pick().candidate().strike());
    // pick() returns the primary (CE) leg — the frozen tradeable_* + scalp event read it
    assertThat(d.pick().candidate().tradingsymbol()).isEqualTo("NIFTY20000CE");
    org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
        .context(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void straddleStrategyBlocksBelowTheVolumeFloor() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithAtmPair()));
    // volume 1000 is below the NIFTY 125k §0B floor — the side-agnostic rail blocks the straddle too
    BarValues thin =
        new BarValues() {
          @Override
          public BigDecimal valueAt(String alias, int i) {
            return bd("98");
          }

          @Override
          public BigDecimal previousValueAt(String alias, int i) {
            return null;
          }

          @Override
          public BigDecimal builtin(String name, int i) {
            return Map.of("close", bd("100"), "vwap", bd("99"), "volume", bd("1000")).get(name);
          }
        };

    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(STRADDLE_CFG, thin, null, 0, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void straddleStrategyBlocksWhenNoCompleteAtmPairExists() {
    MarketOiClient client = mock(MarketOiClient.class);
    // only CE legs in the chain → no strike carries BOTH legs → no straddle possible → block
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));

    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(STRADDLE_CFG, bullBank(), null, 0, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void nonStraddleStrategyIsUnaffectedByThePresenceOfAnAtmPair() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithAtmPair()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any())).thenReturn(bullContext());
    // CFG carries no straddle tag → the neutral branch is never taken; it picks the directional CE
    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
            .evaluate(CFG, bullBank(), null, 0, NOW, IST_TIME, EOD);
    assertThat(decision).isPresent();
    assertThat(decision.get().neutral()).isFalse();
    assertThat(decision.get().side()).isEqualTo(CE);
    assertThat(decision.get().legs()).hasSize(1);
  }
}
