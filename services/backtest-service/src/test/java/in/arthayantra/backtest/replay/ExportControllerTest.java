package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** Fast controller coverage independent of the Testcontainers integration rung. */
class ExportControllerTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final RunRepository runs = mock(RunRepository.class);
  private final TradeRepository trades = mock(TradeRepository.class);
  private ExportController controller;
  private UUID runId;

  @BeforeEach
  void setUp() {
    controller = new ExportController(runs, trades, mapper);
    runId = UUID.randomUUID();
  }

  @Test
  void jsonDownloadsReuseTheExistingArtifactPayloads() throws Exception {
    ArrayNode equity = mapper.createArrayNode();
    equity.addObject().put("ts", "2026-07-13T09:15:00+05:30").put("value", "100000.00");
    when(runs.findResult(runId)).thenReturn(Optional.of(Map.of("equityCurve", equity)));

    Map<String, Object> trade = new LinkedHashMap<>();
    trade.put("seq", 1);
    trade.put("entryPrice", new BigDecimal("100.2500"));
    when(trades.findByRun(eq(runId), eq(1_001), eq(0), isNull(), isNull(), isNull()))
        .thenReturn(List.of(trade));

    ArrayNode folds = mapper.createArrayNode();
    folds.addObject().put("fold", 0);
    when(runs.findFoldMetrics(runId)).thenReturn(folds);

    UUID strategyVersionId = UUID.randomUUID();
    Map<String, Object> summary = Map.of("strategyVersionId", strategyVersionId.toString());
    when(runs.findLatestSummaries(List.of(strategyVersionId))).thenReturn(List.of(summary));

    JsonNode tradesJson = json(controller.trades(runId, "json"));
    assertThat(tradesJson.path("items")).hasSize(1);
    assertThat(tradesJson.path("limit").asInt()).isEqualTo(1_000);
    assertThat(json(controller.folds(runId, "json"))).isEqualTo(folds);
    assertThat(json(controller.equity(runId, "json"))).isEqualTo(equity);
    assertThat(json(controller.compare(runId, List.of(strategyVersionId), "json")).path("items"))
        .hasSize(1);
  }

  @Test
  void tradeCapSetsTheExplicitTruncationHeaders() {
    when(runs.findResult(runId)).thenReturn(Optional.of(Map.of("equityCurve", mapper.createArrayNode())));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i <= ExportController.MAX_ROWS; i++) {
      rows.add(Map.of("seq", i));
    }
    when(trades.findByRun(eq(runId), eq(1_001), eq(0), isNull(), isNull(), isNull()))
        .thenReturn(rows);

    ResponseEntity<String> response = controller.trades(runId, "csv");

    assertThat(response.getHeaders().getFirst("X-Result-Truncated")).isEqualTo("true");
    assertThat(response.getHeaders().getFirst("X-Result-Rows")).isEqualTo("1000");
    assertThat(response.getBody().lines()).hasSize(1_001);
  }

  private JsonNode json(ResponseEntity<String> response) throws Exception {
    assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json");
    assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("attachment");
    return mapper.readTree(response.getBody());
  }
}
