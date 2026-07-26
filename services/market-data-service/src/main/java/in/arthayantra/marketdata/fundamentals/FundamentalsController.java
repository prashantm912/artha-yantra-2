package in.arthayantra.marketdata.fundamentals;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minervini fundamentals surface (ADR-0004). {@code POST /refresh?symbols=CSV} pulls the Upstox
 * Company Fundamentals for the named symbols and upserts the snapshot; {@code GET /{symbol}} reads
 * it. Typed records only (no {@code Map}). Under {@code /api/v1/market/**} (gateway allowlist covers
 * it). When the analytics token is off the refresh reports everything skipped (never a 5xx).
 */
@RestController
@RequestMapping("/api/v1/market/fundamentals")
public class FundamentalsController {

  /** Availability + refresh outcome. */
  public record RefreshResponse(boolean available, int requested, int written, int skipped) {}

  private final FundamentalsService service;
  private final EquityFundamentalsRepository repo;

  /** Wires the service + repository. */
  public FundamentalsController(FundamentalsService service, EquityFundamentalsRepository repo) {
    this.service = service;
    this.repo = repo;
  }

  /** Refreshes the CSV symbol list from Upstox. */
  @PostMapping("/refresh")
  public RefreshResponse refresh(@RequestParam String symbols) {
    List<String> syms =
        Arrays.stream(symbols.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    FundamentalsService.RefreshResult r = service.refresh(syms);
    return new RefreshResponse(service.available(), r.requested(), r.written(), r.skipped());
  }

  /** Reads one symbol's persisted snapshot (404 if none). */
  @ApiResponse(responseCode = "200", description = "OK")
  // The 404 body is EMPTY (ResponseEntity.of on an empty Optional). Without content = @Content
  // springdoc inherits the 200 schema and the spec — and the generated TypeScript — declare an
  // EquityFundamentals body on 404 that never arrives.
  @ApiResponse(
      responseCode = "404",
      description = "Fundamentals not found for symbol",
      content = @Content)
  @GetMapping("/{symbol}")
  public ResponseEntity<EquityFundamentals> get(@PathVariable String symbol) {
    return ResponseEntity.of(repo.find(symbol.trim().toUpperCase()));
  }
}
