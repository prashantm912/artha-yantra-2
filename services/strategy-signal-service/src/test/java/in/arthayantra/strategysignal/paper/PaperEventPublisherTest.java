package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Unit-proves {@link PaperEventPublisher}: the close-reason → kind derivation and the {@code
 * paper.events} frame shape (audit §7.2.2). Mocked repos + Redis (no DB); the frame JSON is captured
 * and asserted so a shape regression is caught here, not only in the IT.
 */
class PaperEventPublisherTest {

  private final PaperEventRepository eventsRepo = Mockito.mock(PaperEventRepository.class);
  private final PaperPositionRepository positions = Mockito.mock(PaperPositionRepository.class);
  private final StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final PaperEventPublisher publisher =
      new PaperEventPublisher(eventsRepo, positions, redis, objectMapper);

  @Test
  void kindForClassifiesCloseReasons() {
    assertThat(PaperEventPublisher.kindFor("STOP_LOSS")).isEqualTo("BRACKET_HIT");
    assertThat(PaperEventPublisher.kindFor("TAKE_PROFIT")).isEqualTo("BRACKET_HIT");
    assertThat(PaperEventPublisher.kindFor("EXPIRY_SETTLEMENT")).isEqualTo("SETTLED");
    assertThat(PaperEventPublisher.kindFor("MANUAL")).isEqualTo("CLOSED");
    assertThat(PaperEventPublisher.kindFor("INTRADAY_MTM")).isEqualTo("CLOSED");
  }

  @Test
  void onOpenedWritesAnOpenedRowAndFrame() throws Exception {
    when(eventsRepo.insert(
            anyLong(), eq("OPENED"), anyString(), anyString(), anyString(), anyString(), anyLong(),
            isNull(), isNull()))
        .thenReturn(77L);

    publisher.onOpened(new PaperPositionOpened(
            42L, "scalper", "NFO", "NIFTY24JUL", "BUY", 50L,
            in.arthayantra.strategyengine.fills.InstrumentClass.OPTION));

    verify(eventsRepo)
        .insert(42L, "OPENED", "scalper", "NFO", "NIFTY24JUL", "BUY", 50L, null, null);
    JsonNode frame = captureFrame();
    assertThat(frame.get("id").asLong()).isEqualTo(77L);
    assertThat(frame.get("positionId").asLong()).isEqualTo(42L);
    assertThat(frame.get("kind").asText()).isEqualTo("OPENED");
    assertThat(frame.get("book").asText()).isEqualTo("scalper");
    assertThat(frame.get("qty").asLong()).isEqualTo(50L);
    assertThat(frame.get("reason").isNull()).isTrue();
    assertThat(frame.get("realizedPnl").isNull()).isTrue();
  }

  @Test
  void onClosedLooksUpThePositionAndDerivesTheKind() throws Exception {
    PositionRow closed =
        new PositionRow(
            42L, "NFO", "NIFTY24JUL", "BUY", 50L, new BigDecimal("100.00"),
            new BigDecimal("250.00"), "CLOSED", OffsetDateTime.now(), OffsetDateTime.now(),
            "STOP_LOSS", new BigDecimal("95.00"), new BigDecimal("140.00"), "scalper");
    when(positions.find(42L)).thenReturn(Optional.of(closed));
    when(eventsRepo.insert(
            anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
            any(), any()))
        .thenReturn(78L);

    publisher.onClosed(new PaperPositionClosed(42L, new BigDecimal("250.00"), "STOP_LOSS"));

    verify(eventsRepo)
        .insert(
            42L, "BRACKET_HIT", "scalper", "NFO", "NIFTY24JUL", "BUY", 50L, "STOP_LOSS",
            new BigDecimal("250.00"));
    JsonNode frame = captureFrame();
    assertThat(frame.get("kind").asText()).isEqualTo("BRACKET_HIT");
    assertThat(frame.get("reason").asText()).isEqualTo("STOP_LOSS");
    assertThat(frame.get("realizedPnl").asText()).isEqualTo("250.00");
  }

  private JsonNode captureFrame() throws Exception {
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(redis).convertAndSend(eq(PaperEventPublisher.CHANNEL), body.capture());
    return objectMapper.readTree(body.getValue());
  }
}
