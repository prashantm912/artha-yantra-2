package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient;
import in.arthayantra.strategysignal.paper.PaperAccountService;
import in.arthayantra.strategysignal.paper.PaperEmissionGuard;
import in.arthayantra.strategysignal.paper.PaperOrderRejectionRecorder;
import in.arthayantra.strategysignal.paper.PaperPositionRepository;
import in.arthayantra.strategysignal.paper.RiskService;
import in.arthayantra.strategysignal.paper.ScalperAccountModel;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate;
import in.arthayantra.strategysignal.scalper.StrikePicker;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignalEngineTradeableLegTest {

  @Test
  void optionLegDrivesSizingWithItsPremiumAndLot() {
    SignalEngine.TradeableLeg leg =
        SignalEngine.tradeableLeg(
            "NFO",
            "NIFTY26AUGFUT",
            new BigDecimal("24092.00"),
            "NIFTY 50",
            decision("NIFTY26AUG25000CE", "152.65"));
    PaperEmissionGuard guard = paperGuard(65);
    StrategyDefinition.SizingSpec sizing =
        new StrategyDefinition.SizingSpec("premium_budget", Map.of("budget_inr", new BigDecimal("15000")));

    assertThat(leg.exchange()).isEqualTo("NFO");
    assertThat(leg.tradingsymbol()).isEqualTo("NIFTY26AUG25000CE");
    assertThat(leg.premium()).isEqualByComparingTo("152.65");
    assertThat(guard.suggestedQty(sizing, leg.exchange(), leg.tradingsymbol(), leg.premium(), null, "scalper"))
        .isEqualByComparingTo("65");
  }

  @Test
  void sensexOptionLegUsesBfoEvenWhenTheSignalFutureIsNfo() {
    SignalEngine.TradeableLeg leg =
        SignalEngine.tradeableLeg(
            "NFO",
            "NIFTY26AUGFUT",
            new BigDecimal("24092.00"),
            "SENSEX",
            decision("SENSEX26JUL76300CE", "776"));

    assertThat(leg.exchange()).isEqualTo("BFO");
    assertThat(leg.tradingsymbol()).isEqualTo("SENSEX26JUL76300CE");
    assertThat(leg.premium()).isEqualByComparingTo("776");
  }

  private static ScalperConfluenceGate.Decision decision(String symbol, String premium) {
    StrikePicker.Candidate candidate =
        new StrikePicker.Candidate(
            symbol, new BigDecimal("25000"), OptionType.CE, new BigDecimal(premium), new BigDecimal("0.2"));
    return new ScalperConfluenceGate.Decision(
        OptionType.CE,
        List.of(
            new ScalperConfluenceGate.Leg(
                OptionType.CE, new StrikePicker.Pick(candidate, new BigDecimal("0.65")))),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static PaperEmissionGuard paperGuard(long lotSize) {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    org.mockito.Mockito.when(instruments.meta(anyString(), anyString()))
        .thenReturn(
            new InstrumentMetaClient.InstrumentMeta(
                InstrumentClass.OPTION, new BigDecimal("0.05"), lotSize));
    return new PaperEmissionGuard(
        mock(RiskService.class),
        mock(PaperAccountService.class),
        instruments,
        mock(ScalperAccountModel.class),
        mock(PaperPositionRepository.class),
        mock(PaperOrderRejectionRecorder.class));
  }
}
