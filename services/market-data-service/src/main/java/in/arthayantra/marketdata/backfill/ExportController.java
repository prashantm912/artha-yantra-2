package in.arthayantra.marketdata.backfill;

import in.arthayantra.marketdata.backfill.BackfillExportService.Export;
import in.arthayantra.marketdata.upstox.ExpiredBackfillRepository.ExportContract;
import java.time.LocalDate;
import java.util.List;
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

  private final BackfillExportService service;

  public ExportController(BackfillExportService service) {
    this.service = service;
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

  /** Builds + returns a CSV/JSON export of one contract's 1m candles. */
  @PostMapping("/export")
  public ResponseEntity<byte[]> export(@RequestBody DownloadRequest request) {
    Export export =
        service.export(
            request.exchange(), request.symbol(), request.from(), request.to(), request.format());
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.filename() + "\"")
        .contentType(MediaType.parseMediaType(export.contentType()))
        .body(export.body());
  }
}
