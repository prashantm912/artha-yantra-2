package in.arthayantra.marketdata.options;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Phase-15 options surface (B-1): live chain, stored history, manual snapshot. */
@RestController
@RequestMapping("/api/v1/market/options")
public class OptionsChainController {

  /** Manual snapshot request. */
  public record SnapshotRequest(String underlying, LocalDate expiry) {}

  private final OptionsChainService chainService;
  private final OptionsSnapshotService snapshotService;
  private final OptionsSnapshotRepository snapshotRepository;
  private final Clock clock;

  /** Wires the options surface. */
  public OptionsChainController(
      OptionsChainService chainService,
      OptionsSnapshotService snapshotService,
      OptionsSnapshotRepository snapshotRepository,
      Clock clock) {
    this.chainService = chainService;
    this.snapshotService = snapshotService;
    this.snapshotRepository = snapshotRepository;
    this.clock = clock;
  }

  /**
   * The live chain (expiry defaults to nearest). A read path, so it degrades to the last captured
   * chain ({@code lastCaptured: true} + the capture {@code asOf}) when no live spot quote exists,
   * rather than refusing off-hours; 503 {@code DATA_STALE} only when nothing was ever captured.
   */
  @GetMapping("/chain")
  public OptionsChainService.Chain chain(
      @RequestParam String underlying,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate expiry) {
    return chainService.chainOrLastCaptured(underlying, expiry);
  }

  /** The stored snapshot nearest to {@code at} (defaults to now). */
  @GetMapping("/chain/history")
  public ChainHistoryResponse history(
      @RequestParam String underlying,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate expiry,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime at) {
    LocalDate resolvedExpiry = chainService.resolveExpiry(underlying, expiry);
    OffsetDateTime when = at == null ? OffsetDateTime.now(clock) : at;
    OffsetDateTime ts =
        snapshotRepository
            .nearestSnapshotTs(underlying, resolvedExpiry, when)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCodes.NOT_FOUND_RESOURCE,
                        "no stored snapshot for " + underlying + " " + resolvedExpiry));
    List<OptionsSnapshotRepository.SnapshotRow> rows =
        snapshotRepository.rowsAt(underlying, resolvedExpiry, ts);
    return new ChainHistoryResponse(underlying, resolvedExpiry, ts, rows);
  }

  /**
   * One stored chain snapshot. {@code expiry}/{@code ts} were emitted as the raw
   * {@code LocalDate}/{@code OffsetDateTime} (Jackson-serialised), so the types are unchanged.
   * Multi-key {@code Map.of} before D3 — order NORMALISED, not preserved.
   */
  public record ChainHistoryResponse(
      String underlying,
      LocalDate expiry,
      OffsetDateTime ts,
      List<OptionsSnapshotRepository.SnapshotRow> rows) {}

  /** Manual snapshot trigger — 202 + jobId. */
  @PostMapping("/snapshot")
  public ResponseEntity<Map<String, String>> snapshot(@RequestBody SnapshotRequest request) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(snapshotService.triggerAsync(request.underlying(), request.expiry()));
  }
}
