package in.arthayantra.marketdata.backfill;

import in.arthayantra.marketdata.backfill.BackfillExportService.Export;
import in.arthayantra.marketdata.upstox.ExpiredBackfillRepository.ExportContract;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-data admin surface — backfilled-candle export (B6 wizard). The wizard walks underlying →
 * expiry → contract → format; this exposes the expiry/contract pickers + the per-contract download.
 * Auto-proxied by the edge-gateway {@code /api/v1/market/**} route.
 */
@RestController
@RequestMapping("/api/v1/market/admin")
public class ExportController {

  /** {@code {items:[...]}} envelope for the expiry picker. */
  public record ExpiriesResponse(List<LocalDate> items) {}

  /** {@code {items:[...]}} envelope for the contract picker. */
  public record ContractsResponse(List<ExportContract> items) {}

  /** Export request: one registered contract + the session-date range + format (csv|json). */
  public record DownloadRequest(
      String exchange, String symbol, LocalDate from, LocalDate to, String format) {}

  /** Bulk export request: EVERY contract of one (underlying, expiry) + the date range + format. */
  public record BulkRequest(
      String underlying, LocalDate expiry, LocalDate from, LocalDate to, String format) {}

  private final BackfillExportService service;
  private final AdminAuditLedger audit;

  public ExportController(BackfillExportService service, AdminAuditLedger audit) {
    this.service = service;
    this.audit = audit;
  }

  /** Distinct expiries for an underlying (newest first). */
  @GetMapping("/export/expiries")
  public ExpiriesResponse expiries(@RequestParam String underlying) {
    return new ExpiriesResponse(service.expiries(underlying));
  }

  /** Registered contracts for one (underlying, expiry). */
  @GetMapping("/export/contracts")
  public ContractsResponse contracts(
      @RequestParam String underlying,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiry) {
    return new ContractsResponse(service.contracts(underlying, expiry));
  }

  /** Builds + returns a CSV/JSON export of one contract's 1m candles, audited (audit V14). */
  @PostMapping("/export")
  public ResponseEntity<byte[]> export(@RequestBody DownloadRequest request) {
    Export export =
        service.export(
            request.exchange(), request.symbol(), request.from(), request.to(), request.format());
    Map<String, Object> detail =
        detail(
            "exchange", request.exchange(),
            "symbol", request.symbol(),
            "from", request.from(),
            "to", request.to(),
            "format", request.format());
    audit.recordExport(AdminAuditLedger.ACTION_CANDLE_EXPORT, detail, export.rowCount(), export.truncated());
    return download(export);
  }

  /** Builds + returns a ZIP of the per-contract exports of a whole (underlying, expiry) chain, audited. */
  @PostMapping("/export/bulk")
  public ResponseEntity<byte[]> exportBulk(@RequestBody BulkRequest request) {
    Export export =
        service.exportBulk(
            request.underlying(), request.expiry(), request.from(), request.to(), request.format());
    Map<String, Object> detail =
        detail(
            "underlying", request.underlying(),
            "expiry", request.expiry(),
            "from", request.from(),
            "to", request.to(),
            "format", request.format());
    audit.recordExport(AdminAuditLedger.ACTION_CANDLE_EXPORT_BULK, detail, export.rowCount(), export.truncated());
    return download(export);
  }

  /**
   * The download response. When the row cap clipped the export the truncation is made EXPLICIT (audit
   * §7.2.5 / §10 Phase-1): {@code X-Result-Truncated: true} + {@code X-Result-Rows} tell the caller the
   * artifact is partial, rather than silently handing back a clipped file.
   */
  private static ResponseEntity<byte[]> download(Export export) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.filename() + "\"")
        .header("X-Result-Truncated", Boolean.toString(export.truncated()))
        .header("X-Result-Rows", Long.toString(export.rowCount()))
        .contentType(MediaType.parseMediaType(export.contentType()))
        .body(export.body());
  }

  /** Builds an ordered detail map (String-valued, null-safe) for the audit row — no date-module reliance. */
  private static Map<String, Object> detail(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1] == null ? null : String.valueOf(kv[i + 1]));
    }
    return m;
  }
}
