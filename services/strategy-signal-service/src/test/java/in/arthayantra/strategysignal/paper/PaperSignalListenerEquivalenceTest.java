package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The paper-open level derivation half of the shared premium-exit contract. */
class PaperSignalListenerEquivalenceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode fixture() throws Exception {
    Path p = Path.of("..", "..", "contracts", "fixtures", "exit-equivalence.json");
    return JSON.readTree(Files.readString(p));
  }

  @Test
  void paperSignalListenerMatchesTheSharedEquivalenceFixture() throws Exception {
    JsonNode fx = fixture();
    assertThat(fx.path("scenarios").findValuesAsText("name"))
        .contains(
            "single-bar-entry-only",
            "only-stop",
            "only-target",
            "both-levels-round-to-same-paise-stop-first",
            "take-profit-subpaise-rounding");

    for (JsonNode sc : fx.path("scenarios")) {
      String name = sc.path("name").asText();
      BigDecimal entry =
          new BigDecimal(
              (sc.has("entryPremium") ? sc.path("entryPremium") : fx.path("entryPremium")).asText());
      JsonNode config = sc.has("config") ? sc.path("config") : fx.path("config");
      JsonNode levels = sc.has("expectedLevels") ? sc.path("expectedLevels") : fx.path("expectedLevels");
      UUID versionId = UUID.randomUUID();

      PaperService paper = mock(PaperService.class);
      ScalperAccountModel accounts = mock(ScalperAccountModel.class);
      when(accounts.nextFreeAccount()).thenReturn(1);
      SignalRepository signals = mock(SignalRepository.class);
      JsonNode detail =
          JSON.readTree(
              "{\"side\":\"LONG_CE\",\"option_ltp\":\""
                  + entry.toPlainString()
                  + "\"}");
      when(signals.find(7L))
          .thenReturn(
              Optional.of(
                  new SignalRepository.SignalRow(
                      7L,
                      versionId,
                      "NFO",
                      "NIFTY26JULFUT",
                      "3m",
                      "ENTRY",
                      "BUY",
                      new BigDecimal("25000"),
                      new BigDecimal("24800"),
                      new BigDecimal("25400"),
                      new BigDecimal("0.8"),
                      null,
                      "TAKEN",
                      null,
                      null,
                      new BigDecimal("75"),
                      "NFO",
                      "NIFTY26JUL25100CE",
                      detail,
                      null,
                      null,
                      null)));
      when(signals.versionConfig(versionId)).thenReturn(Optional.of(config));

      new PaperSignalListener(paper, accounts, signals)
          .onSignalTaken(new SignalTaken(7L, 75, new BigDecimal("25000"), true));

      ArgumentCaptor<PaperService.OrderRequest> request =
          ArgumentCaptor.forClass(PaperService.OrderRequest.class);
      verify(paper).openScalperOrder(request.capture()); // scalper take: assignment happens downstream
      assertLevel(request.getValue().stopLoss(), levels.path("stopLoss"), name + " stopLoss");
      assertLevel(request.getValue().takeProfit(), levels.path("takeProfit"), name + " takeProfit");
    }
  }

  private static void assertLevel(BigDecimal actual, JsonNode expected, String name) {
    if (expected.isNull() || expected.isMissingNode()) {
      assertThat(actual).as(name).isNull();
    } else {
      assertThat(actual).as(name).isEqualByComparingTo(expected.asText());
    }
  }
}
