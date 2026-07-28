package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CSV/JSON file downloads for the four core artifacts of one completed backtest run. */
@RestController
@RequestMapping("/api/v1/backtests/{runId}/export")
public class ExportController {

  /** Matches the existing trades endpoint's hard cap. */
  static final int MAX_ROWS = 1_000;

  private static final List<String> TRADE_COLUMNS =
      List.of(
          "seq",
          "side",
          "qty",
          "entryTs",
          "entryPrice",
          "exitTs",
          "exitPrice",
          "pnl",
          "pnlPct",
          "exitReason",
          "barsHeld",
          "touchBasis",
          "contributions",
          "exchange",
          "tradingsymbol",
          "stopLoss",
          "takeProfit");
  private static final List<String> FOLD_COLUMNS =
      List.of(
          "fold",
          "train",
          "test",
          "trainMetrics",
          "oosMetrics",
          "regimeMix",
          "regimeOos",
          "tradeRegimes");
  private static final List<String> EQUITY_COLUMNS = List.of("ts", "value");
  private static final List<String> COMPARE_COLUMNS =
      List.of(
          "strategyVersionId",
          "runId",
          "sharpe",
          "totalReturn",
          "maxDrawdown",
          "completedAt",
          "equity");

  private final RunRepository runs;
  private final TradeRepository trades;
  private final ObjectMapper objectMapper;

  /** Wires the existing typed read sources; exports add no persistence. */
  public ExportController(RunRepository runs, TradeRepository trades, ObjectMapper objectMapper) {
    this.runs = runs;
    this.trades = trades;
    this.objectMapper = objectMapper;
  }

  /** A run's ordered trade rows. */
  @GetMapping("/trades")
  public ResponseEntity<String> trades(
      @PathVariable UUID runId, @RequestParam(defaultValue = "csv") String format) {
    requireRun(runId);
    List<Map<String, Object>> found =
        trades.findByRun(runId, MAX_ROWS + 1, 0, null, null, null);
    Capped<Map<String, Object>> capped = cap(found);
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("items", capped.items());
    json.put("limit", MAX_ROWS);
    json.put("offset", 0);
    return download(
        runId,
        "trades",
        format,
        json,
        TRADE_COLUMNS,
        mapRows(capped.items(), TRADE_COLUMNS),
        capped.truncated());
  }

  /** A run's persisted fold records (empty array for a full-window run). */
  @GetMapping("/folds")
  public ResponseEntity<String> folds(
      @PathVariable UUID runId, @RequestParam(defaultValue = "csv") String format) {
    requireRun(runId);
    Capped<JsonNode> capped = cap(arrayItems(runs.findFoldMetrics(runId)));
    ArrayNode json = objectMapper.createArrayNode();
    capped.items().forEach(json::add);
    return download(
        runId,
        "folds",
        format,
        json,
        FOLD_COLUMNS,
        jsonRows(capped.items(), FOLD_COLUMNS),
        capped.truncated());
  }

  /** A run's persisted downsampled equity curve. */
  @GetMapping("/equity")
  public ResponseEntity<String> equity(
      @PathVariable UUID runId, @RequestParam(defaultValue = "csv") String format) {
    RunResult result = requireRun(runId);
    Object curve = result.equityCurve();
    Capped<JsonNode> capped = cap(arrayItems(curve instanceof JsonNode node ? node : null));
    ArrayNode json = objectMapper.createArrayNode();
    capped.items().forEach(json::add);
    return download(
        runId,
        "equity",
        format,
        json,
        EQUITY_COLUMNS,
        jsonRows(capped.items(), EQUITY_COLUMNS),
        capped.truncated());
  }

  /** Latest-run comparison rows for the requested strategy versions, matching {@code /summary}. */
  @GetMapping("/compare")
  public ResponseEntity<String> compare(
      @PathVariable UUID runId,
      @RequestParam(required = false) List<UUID> strategyVersionIds,
      @RequestParam(defaultValue = "csv") String format) {
    requireRun(runId);
    List<UUID> requested = strategyVersionIds == null ? List.of() : strategyVersionIds;
    boolean truncated = requested.size() > MAX_ROWS;
    List<UUID> bounded = truncated ? requested.subList(0, MAX_ROWS) : requested;
    List<Map<String, Object>> items = runs.findLatestSummaries(bounded);
    Map<String, Object> json = Map.of("items", items);
    return download(
        runId,
        "compare",
        format,
        json,
        COMPARE_COLUMNS,
        mapRows(items, COMPARE_COLUMNS),
        truncated);
  }

  private RunResult requireRun(UUID runId) {
    return runs
        .findResult(runId)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCodes.NOT_FOUND_RESOURCE, "no such backtest run: " + runId));
  }

  private ResponseEntity<String> download(
      UUID runId,
      String artifact,
      String requestedFormat,
      Object json,
      List<String> columns,
      List<List<?>> rows,
      boolean truncated) {
    Format format = Format.parse(requestedFormat);
    String body = format == Format.JSON ? json(json) : csv(columns, rows);
    String filename = "backtest-" + runId + '-' + artifact + '.' + format.extension;
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .header("X-Result-Truncated", Boolean.toString(truncated))
        .header("X-Result-Rows", Integer.toString(rows.size()))
        .contentType(MediaType.parseMediaType(format.contentType))
        .body(body);
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new ApiException(500, ErrorCodes.INTERNAL_ERROR, "export serialization failed");
    }
  }

  private static String csv(List<String> columns, List<List<?>> rows) {
    StringBuilder body = new StringBuilder(CsvEncoder.row(new ArrayList<>(columns)));
    for (List<?> row : rows) {
      body.append(CsvEncoder.row(row));
    }
    return body.toString();
  }

  private List<List<?>> mapRows(
      List<Map<String, Object>> items, List<String> columns) {
    List<List<?>> rows = new ArrayList<>(items.size());
    for (Map<String, Object> item : items) {
      List<Object> row = new ArrayList<>(columns.size());
      for (String column : columns) {
        row.add(csvValue(item.get(column)));
      }
      rows.add(row);
    }
    return rows;
  }

  private static List<List<?>> jsonRows(List<JsonNode> items, List<String> columns) {
    List<List<?>> rows = new ArrayList<>(items.size());
    for (JsonNode item : items) {
      List<JsonNode> row = new ArrayList<>(columns.size());
      for (String column : columns) {
        row.add(item.path(column));
      }
      rows.add(row);
    }
    return rows;
  }

  private Object csvValue(Object value) {
    if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
      return objectMapper.valueToTree(value);
    }
    return value;
  }

  private static List<JsonNode> arrayItems(JsonNode array) {
    if (array == null || !array.isArray()) {
      return List.of();
    }
    List<JsonNode> items = new ArrayList<>(array.size());
    array.forEach(items::add);
    return items;
  }

  private static <T> Capped<T> cap(List<T> items) {
    boolean truncated = items.size() > MAX_ROWS;
    return new Capped<>(truncated ? List.copyOf(items.subList(0, MAX_ROWS)) : items, truncated);
  }

  private record Capped<T>(List<T> items, boolean truncated) {}

  private enum Format {
    CSV("csv", "text/csv"),
    JSON("json", "application/json");

    private final String extension;
    private final String contentType;

    Format(String extension, String contentType) {
      this.extension = extension;
      this.contentType = contentType;
    }

    private static Format parse(String raw) {
      String normalized = raw == null ? "csv" : raw.trim().toLowerCase(Locale.ROOT);
      return switch (normalized) {
        case "csv" -> CSV;
        case "json" -> JSON;
        default ->
            throw new ApiException(
                400, ErrorCodes.VALIDATION_FAILED, "format must be csv or json");
      };
    }
  }
}
