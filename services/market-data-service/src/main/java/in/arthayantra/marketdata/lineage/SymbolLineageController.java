package in.arthayantra.marketdata.lineage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The symbol-lineage surface (N2 / #1285) — read the map, and re-derive it. Typed records
 * throughout, so it enumerates into the springdoc contract and does not bump the market-data
 * Map-return ratchet. Path sits under {@code /api/v1/market/**}, which the edge-gateway allowlist
 * already covers.
 *
 * <p>The A/B measurement ("what would opting in buy?") deliberately lives NEXT DOOR, in {@code
 * screener/LineageScreenImpactController}: {@code screener.minervini} and {@code screener.manas} are
 * internal packages of the {@code screener} Modulith module, so reaching into them from here would
 * be a boundary violation ({@code ModularityTest}).
 */
@RestController
@RequestMapping("/api/v1/market/symbol-lineage")
public class SymbolLineageController {

  /** One predecessor→successor link. */
  @Schema(name = "SymbolLineageRow")
  public record Row(
      String exchange,
      String predecessorSymbol,
      String successorSymbol,
      @Schema(types = {"string", "null"}) LocalDate switchDate,
      @Schema(types = {"integer", "null"}) Integer gapSessions,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal boundaryPrice,
      @Schema(types = {"string", "null"}) String confidence,
      @Schema(types = {"string", "null"}) String evidence,
      String status,
      @Schema(types = {"string", "null"}) String statusReason,
      String source) {}

  /** The {items} envelope, matching the platform's list-endpoint convention. */
  @Schema(name = "SymbolLineageResponse")
  public record LineageResponse(List<Row> items, int count) {}

  /** What one detection pass found and changed. */
  @Schema(name = "SymbolLineageDetectResponse")
  public record DetectResponse(
      @Schema(types = {"string", "null"}) LocalDate asOf,
      int detected,
      int inserted,
      int refreshed,
      int confirmed,
      int inferred,
      int refuted) {}

  private final SymbolLineageRepository repo;
  private final SymbolLineageDetector detector;

  /** Wires the lineage store + the detector. */
  public SymbolLineageController(SymbolLineageRepository repo, SymbolLineageDetector detector) {
    this.repo = repo;
    this.detector = detector;
  }

  /**
   * Every recorded link for {@code exchange}, or only the stitchable ones with {@code activeOnly}.
   *
   * <p>{@code operationId} is PINNED. springdoc auto-names a collision-free handler {@code list},
   * {@code list_1}, {@code list_2}… in classpath-scan order, so adding this endpoint renumbered
   * {@code GET /api/v1/instruments} from {@code list_2} to {@code list_3} — a silent break for any
   * generated client keyed on the operation id, and one openapi-diff does NOT gate.
   */
  @Operation(operationId = "listSymbolLineage")
  @GetMapping
  public LineageResponse list(
      @RequestParam(defaultValue = "NSE") String exchange,
      @RequestParam(defaultValue = "false") boolean activeOnly) {
    List<SymbolLineage> rows = activeOnly ? repo.active(exchange) : repo.all(exchange);
    List<Row> items = rows.stream().map(SymbolLineageController::toRow).toList();
    return new LineageResponse(items, items.size());
  }

  /**
   * Re-derives every link from the EOD tape and upserts. Idempotent, and an owner's {@code WITHHELD}
   * verdict survives it — the upsert deliberately omits {@code status} from its update list.
   */
  @Operation(operationId = "detectSymbolLineage")
  @PostMapping("/detect")
  public DetectResponse detect() {
    SymbolLineageDetector.DetectionResult r = detector.detect();
    return new DetectResponse(
        r.asOf(), r.detected(), r.inserted(), r.refreshed(), r.confirmed(), r.inferred(),
        r.refuted());
  }

  private static Row toRow(SymbolLineage l) {
    return new Row(
        l.exchange(),
        l.predecessorSymbol(),
        l.successorSymbol(),
        l.switchDate(),
        l.gapSessions(),
        l.boundaryPrice(),
        l.confidence(),
        l.evidence(),
        l.status(),
        l.statusReason(),
        l.source());
  }
}
