package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.strategyengine.eval.BarValues;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategysignal.scalper.MarketOiClient.ChainSnapshot;
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
  private static final ScalperConfig CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          false, ScalperConfig.StructuralStop.NONE);
  private static final ScalperConfig TWO_CANDLE_CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          true, ScalperConfig.StructuralStop.TWO_CANDLE_FIRST);

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
    Map<String, BigDecimal> builtins = Map.of("close", bd("100"), "vwap", bd("99"), "volume", bd("130000"));
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

  private static ScalperGateContext bullContext() {
    return new ScalperGateContext(
        "NIFTY 50", IST_TIME,
        new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000")),
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("5"), bd("5"), null, null, null, false,
            false, null, null, null),
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
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any())).thenReturn(bullContext());

    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults()).evaluate(CFG, bullBank(), null, 0, NOW, IST_TIME, EOD);

    assertThat(decision).isPresent();
    assertThat(decision.get().side()).isEqualTo(CE);
    assertThat(decision.get().pick().candidate().tradingsymbol()).isEqualTo("NIFTY19850CE");
    assertThat(decision.get().confluence().bullish()).isTrue();
  }

  @Test
  void blocksWhenTheChainIsUnavailable() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.empty());

    assertThat(new ScalperConfluenceGate(client, ScalperOiProps.defaults()).evaluate(CFG, bullBank(), null, 0, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void blocksWhenConfluenceFails() {
    // a bearish context for a CE side → confluence is not bullish → block
    ScalperGateContext bear =
        new ScalperGateContext(
            "NIFTY 50", IST_TIME,
            new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000")),
            new Oi(
                OiQuadrant.SHORT_BUILDUP, OiQuadrant.SHORT_BUILDUP, bd("-10"), bd("-5"), bd("-5"), null, null, null,
                false, false, null, null, null),
            new Macro(bd("14"), bd("80"), bd("12"), Boolean.TRUE, 10, 40, bd("50"), null, null));
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any())).thenReturn(bear);

    assertThat(new ScalperConfluenceGate(client, ScalperOiProps.defaults()).evaluate(CFG, bullBank(), null, 0, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void blocksInTheMiddayWindowBeforeAnyChainFetch() {
    MarketOiClient client = mock(MarketOiClient.class);
    // 11:30 IST is inside the §0B 11:00–13:00 block — blocked at the hard pre-flight, no HTTP
    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults())
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

    assertThat(new ScalperConfluenceGate(client, ScalperOiProps.defaults()).evaluate(CFG, deadRsi, null, 0, NOW, IST_TIME, EOD))
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
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any())).thenReturn(bullContext());

    assertThat(new ScalperConfluenceGate(client, ScalperOiProps.defaults()).evaluate(CFG, bullBank(), null, 0, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void twoCandleStrategyConfirmsOnAFormationAndAnchorsTheStop() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any())).thenReturn(bullContext());
    // two strong green candles (0,1) then the deploy bar (2) → the §3.1 formation is present
    EngineSeries future = futureSeries(strongGreen(0), strongGreen(1), strongGreen(2));

    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults())
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
            new ScalperConfluenceGate(client, ScalperOiProps.defaults())
                .evaluate(TWO_CANDLE_CFG, bullBank(), future, 2, NOW, IST_TIME, EOD))
        .isEmpty();
  }
}
