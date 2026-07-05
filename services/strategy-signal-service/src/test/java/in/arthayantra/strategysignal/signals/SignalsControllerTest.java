package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.signals.SignalRepository.SignalRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** The signal detail DTO must surface the V009 scalper side-channel for the confirm panel. */
class SignalsControllerTest {

  private static final ObjectMapper OM = new ObjectMapper();

  private static SignalRow row(String tradeableExch, String tradeableSym, JsonNode scalperDetail) {
    return new SignalRow(
        1L, UUID.randomUUID(), "NSE", "NIFTY24JUNFUT", "3m", "ENTRY", "BUY",
        new BigDecimal("100"), new BigDecimal("95"), null, new BigDecimal("0.8"),
        OM.nullNode(), "ACTIVE", OffsetDateTime.parse("2026-06-20T10:00:00+05:30"), null,
        new BigDecimal("50"), tradeableExch, tradeableSym, scalperDetail, null, null);
  }

  @Test
  void detailExposesTheScalperSideChannel() throws Exception {
    SignalRepository repo = mock(SignalRepository.class);
    JsonNode detail =
        OM.readTree("{\"side\":\"CE\",\"manual_checks\":[{\"key\":\"news_clear\"}]}");
    when(repo.find(1L)).thenReturn(Optional.of(row("NFO", "NIFTY24JUN24000CE", detail)));
    SignalsController controller =
        new SignalsController(repo, mock(ApplicationEventPublisher.class));

    Map<String, Object> dto = controller.detail(1L);

    assertThat(dto.get("tradeableExchange")).isEqualTo("NFO");
    assertThat(dto.get("tradeableTradingsymbol")).isEqualTo("NIFTY24JUN24000CE");
    assertThat(dto.get("scalperDetail")).isEqualTo(detail);
  }

  @Test
  void detailLeavesTheSideChannelNullForNonScalperSignals() {
    SignalRepository repo = mock(SignalRepository.class);
    when(repo.find(1L)).thenReturn(Optional.of(row(null, null, null)));
    SignalsController controller =
        new SignalsController(repo, mock(ApplicationEventPublisher.class));

    Map<String, Object> dto = controller.detail(1L);

    assertThat(dto).containsKey("scalperDetail");
    assertThat(dto.get("scalperDetail")).isNull();
    assertThat(dto.get("tradeableExchange")).isNull();
  }
}
