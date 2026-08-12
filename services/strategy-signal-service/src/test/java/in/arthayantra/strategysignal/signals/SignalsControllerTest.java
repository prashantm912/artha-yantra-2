package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.signals.SignalRepository.SignalRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** The signal detail DTO must surface the V009 scalper side-channel for the confirm panel. */
class SignalsControllerTest {

  /** Modules registered so the wire-shape assertions can serialize the {@code OffsetDateTime}s. */
  private static final ObjectMapper OM = new ObjectMapper().findAndRegisterModules();

  private static SignalRow row(String tradeableExch, String tradeableSym, JsonNode scalperDetail) {
    return new SignalRow(
        1L, UUID.randomUUID(), "NSE", "NIFTY24JUNFUT", "3m", "ENTRY", "BUY",
        new BigDecimal("100"), new BigDecimal("95"), null, new BigDecimal("0.8"),
        OM.nullNode(), "ACTIVE", OffsetDateTime.parse("2026-06-20T10:00:00+05:30"), null,
        new BigDecimal("50"), tradeableExch, tradeableSym, scalperDetail, null, null, null);
  }

  @Test
  void detailExposesTheScalperSideChannel() throws Exception {
    SignalRepository repo = mock(SignalRepository.class);
    JsonNode detail =
        OM.readTree("{\"side\":\"CE\",\"manual_checks\":[{\"key\":\"news_clear\"}]}");
    when(repo.find(1L)).thenReturn(Optional.of(row("NFO", "NIFTY24JUN24000CE", detail)));
    SignalsController controller =
        new SignalsController(repo, mock(ApplicationEventPublisher.class));

    SignalViews.SignalDto dto = controller.detail(1L);

    assertThat(dto.tradeableExchange()).isEqualTo("NFO");
    assertThat(dto.tradeableTradingsymbol()).isEqualTo("NIFTY24JUN24000CE");
    assertThat(dto.scalperDetail()).isEqualTo(detail);
  }

  /**
   * The D3 retyping's load-bearing claim: {@code SignalViews.SignalDto} must serialize the SAME keys
   * in the SAME order the {@code LinkedHashMap} did, because Jackson emits a record in
   * canonical-constructor order and a moved component moves the bytes. Asserted on the SERIALIZED
   * form, not the accessors — accessors cannot see key order, and order is precisely what a record
   * conversion can silently break.
   *
   * <p>The list is also the record of what this surface deliberately does NOT emit: {@code
   * SignalRow} carries {@code minerviniDetail} and {@code manasAroraDetail}, and neither has ever
   * appeared here.
   *
   * <p>Scope, stated so it is not over-read: this pins KEYS and ORDER only. It uses a bare mapper,
   * not the Spring-configured one, so it says nothing about value FORMATTING — that is set at the
   * mapper ({@code ArthaJacksonAutoConfiguration} serializes every {@code BigDecimal} through
   * {@code ToStringSerializer}) and therefore applies to a record exactly as it did to the map.
   */
  @Test
  void theDtoSerializesTheFormerLinkedHashMapKeysInOrder() {
    SignalRepository repo = mock(SignalRepository.class);
    when(repo.find(1L)).thenReturn(Optional.of(row("NFO", "NIFTY24JUN24000CE", OM.nullNode())));
    SignalsController controller =
        new SignalsController(repo, mock(ApplicationEventPublisher.class));

    List<String> keys = new ArrayList<>();
    OM.valueToTree(controller.detail(1L)).fieldNames().forEachRemaining(keys::add);

    assertThat(keys)
        .containsExactly(
            "id", "strategyVersionId", "exchange", "tradingsymbol", "interval", "signalType",
            "side", "entryPrice", "stopLoss", "target", "compositeScore", "scoreBreakdown",
            "status", "generatedAt", "expiresAt", "suggestedQty", "tradeableExchange",
            "tradeableTradingsymbol", "scalperDetail", "exitReason");
  }

  @Test
  void detailLeavesTheSideChannelNullForNonScalperSignals() {
    SignalRepository repo = mock(SignalRepository.class);
    when(repo.find(1L)).thenReturn(Optional.of(row(null, null, null)));
    SignalsController controller =
        new SignalsController(repo, mock(ApplicationEventPublisher.class));

    SignalViews.SignalDto dto = controller.detail(1L);

    assertThat(dto.scalperDetail()).isNull();
    assertThat(dto.tradeableExchange()).isNull();
    // The key must still be PRESENT-and-null on the wire, as the LinkedHashMap made it — the panel
    // distinguishes "not a scalper signal" from "field missing". A record only preserves that while
    // the service configures no NON_NULL inclusion, so assert the emitted JSON, not the accessor.
    assertThat(OM.valueToTree(dto).has("scalperDetail")).isTrue();
    assertThat(OM.valueToTree(dto).get("scalperDetail").isNull()).isTrue();
  }

  private static SignalRow exitRow(String exitReason) {
    return new SignalRow(
        2L, UUID.randomUUID(), "NFO", "NIFTY24JUN24000CE", "3m", "EXIT", "SELL",
        new BigDecimal("112"), null, null, new BigDecimal("0.8"),
        OM.nullNode(), "ACTIVE", OffsetDateTime.parse("2026-06-20T10:03:00+05:30"), null,
        new BigDecimal("50"), null, null, null, null, null, exitReason);
  }

  /**
   * Both operator read surfaces must carry the V048 reason: the JSON dto AND the CSV export — the
   * export enumerates its columns by hand, so a repository field does not reach the file unless
   * someone wires it (cross-vendor review, round 1: the CSV had been forgotten).
   */
  @Test
  void detailAndCsvExportBothCarryTheExitReason() {
    SignalRepository repo = mock(SignalRepository.class);
    when(repo.find(2L)).thenReturn(Optional.of(exitRow("CONFLUENCE_FLIP")));
    when(repo.list(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(exitRow("CONFLUENCE_FLIP")));
    SignalsController controller =
        new SignalsController(repo, mock(ApplicationEventPublisher.class));

    assertThat(controller.detail(2L).exitReason()).isEqualTo("CONFLUENCE_FLIP");

    String csv =
        new String(
            controller.export(null, null, null, null, null, null, null).getBody(),
            StandardCharsets.UTF_8);
    String[] lines = csv.split("\r?\n");
    assertThat(lines[0]).endsWith(",exit_reason");
    assertThat(lines[1]).endsWith(",CONFLUENCE_FLIP");
  }

  /** An ENTRY row's reason cell is empty, not the string "null". */
  @Test
  void csvExportLeavesTheReasonCellEmptyOnEntryRows() {
    SignalRepository repo = mock(SignalRepository.class);
    when(repo.list(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(row(null, null, null)));
    SignalsController controller =
        new SignalsController(repo, mock(ApplicationEventPublisher.class));

    String csv =
        new String(
            controller.export(null, null, null, null, null, null, null).getBody(),
            StandardCharsets.UTF_8);
    assertThat(csv.split("\r?\n")[1]).endsWith(",");
  }
}
