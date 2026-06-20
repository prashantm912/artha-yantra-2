package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.strategyengine.eval.BarValues;
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
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"));

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  // a bank whose close (100) sits above VWAP (99) → the gate selects the CE side
  private static BarValues bullBank() {
    Map<String, BigDecimal> builtins = Map.of("close", bd("100"), "vwap", bd("99"), "volume", bd("130000"));
    return new BarValues() {
      @Override
      public BigDecimal valueAt(String alias, int i) {
        return null; // the confluence reads the STUBBED context, not the bank, for the dots
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
        new Oi(OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("5"), bd("5")),
        new Macro(bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50")));
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
        new ScalperConfluenceGate(client).evaluate(CFG, bullBank(), 0, NOW, IST_TIME, EOD);

    assertThat(decision).isPresent();
    assertThat(decision.get().side()).isEqualTo(CE);
    assertThat(decision.get().pick().candidate().tradingsymbol()).isEqualTo("NIFTY19850CE");
    assertThat(decision.get().confluence().bullish()).isTrue();
  }

  @Test
  void blocksWhenTheChainIsUnavailable() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.empty());

    assertThat(new ScalperConfluenceGate(client).evaluate(CFG, bullBank(), 0, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void blocksWhenConfluenceFails() {
    // a bearish context for a CE side → confluence is not bullish → block
    ScalperGateContext bear =
        new ScalperGateContext(
            "NIFTY 50", IST_TIME,
            new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000")),
            new Oi(OiQuadrant.SHORT_BUILDUP, OiQuadrant.SHORT_BUILDUP, bd("-10"), bd("-5"), bd("-5")),
            new Macro(bd("14"), bd("80"), bd("12"), Boolean.TRUE, 10, 40, bd("50")));
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any())).thenReturn(bear);

    assertThat(new ScalperConfluenceGate(client).evaluate(CFG, bullBank(), 0, NOW, IST_TIME, EOD))
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

    assertThat(new ScalperConfluenceGate(client).evaluate(CFG, bullBank(), 0, NOW, IST_TIME, EOD))
        .isEmpty();
  }
}
