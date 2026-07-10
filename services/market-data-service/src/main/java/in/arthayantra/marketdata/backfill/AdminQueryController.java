package in.arthayantra.marketdata.backfill;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.backfill.AdminQueryService.QueryResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-data admin surface — read-only SQL console (B5 Query/Scan). {@code POST /query} runs a single
 * validated SELECT/WITH (see {@link AdminQueryService} for the defense-in-depth guards) and returns a
 * column/row grid; {@code POST /query/export} streams the same result as CSV. Behind the gateway auth
 * filter on a loopback-only deploy.
 */
@RestController
@RequestMapping("/api/v1/market/admin")
public class AdminQueryController {

  /** Query request: a single read-only SELECT/WITH + an optional row limit (capped server-side). */
  public record QueryRequest(String sql, Integer rowLimit) {}

  /** Export request: a single read-only SELECT/WITH + the format (CSV only in this build). */
  public record ExportRequest(String sql, String format) {}

  private final AdminQueryService service;

  public AdminQueryController(AdminQueryService service) {
    this.service = service;
  }

  /** Runs a read-only query, returning {columns, rows, rowCount, truncated}. */
  @PostMapping("/query")
  public QueryResult query(@RequestBody QueryRequest request) {
    return service.run(request.sql(), request.rowLimit());
  }

  /**
   * Runs a read-only query and returns the result as a CSV download. When the export row cap clips the
   * result, the truncation is made EXPLICIT (audit §7.2.5 / §10 Phase-1: "truncated made explicit"): the
   * response carries {@code X-Result-Truncated: true} + {@code X-Result-Rows} so the caller knows the CSV
   * is partial rather than silently receiving a clipped file.
   */
  @PostMapping("/query/export")
  public ResponseEntity<byte[]> export(@RequestBody ExportRequest request) {
    String format = request.format() == null ? "csv" : request.format().toLowerCase(java.util.Locale.ROOT);
    if (!"csv".equals(format)) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "only csv export is supported");
    }
    QueryResult result = service.runForExport(request.sql());
    byte[] body = toCsv(result).getBytes(StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"query.csv\"")
        .header("X-Result-Truncated", Boolean.toString(result.truncated()))
        .header("X-Result-Rows", Integer.toString(result.rowCount()))
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(body);
  }

  private static String toCsv(QueryResult result) {
    StringBuilder sb = new StringBuilder();
    appendRow(sb, result.columns());
    for (List<String> row : result.rows()) {
      appendRow(sb, row);
    }
    return sb.toString();
  }

  private static void appendRow(StringBuilder sb, List<String> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(csvField(cells.get(i)));
    }
    sb.append('\n');
  }

  private static String csvField(String value) {
    if (value == null) {
      return "";
    }
    if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
      return '"' + value.replace("\"", "\"\"") + '"';
    }
    return value;
  }
}
