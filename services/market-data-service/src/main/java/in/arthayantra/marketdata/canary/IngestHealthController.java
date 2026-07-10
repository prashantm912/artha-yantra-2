package in.arthayantra.marketdata.canary;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read surface for the EOD ingest health board (audit item A11, §6.3). Sibling of {@code
 * /api/v1/market/health/data} (the data-plane canary): evaluates fresh on every GET so the Data-Ops
 * page reflects the current ledger. Stays under the already-allowlisted {@code /api/v1/market/**}
 * gateway prefix (no new route entry / gateway rebuild needed).
 */
@RestController
@RequestMapping("/api/v1/market/health")
public class IngestHealthController {

  private final IngestHealthBoard board;

  /** Wires the board service. */
  public IngestHealthController(IngestHealthBoard board) {
    this.board = board;
  }

  /**
   * Per-source ingest health over the last {@code days} settled trading days (default 10, clamped to
   * {@code [1, 30]}).
   */
  @GetMapping("/ingest")
  public IngestHealthBoard.BoardReport ingest(
      @RequestParam(name = "days", defaultValue = "10") int days) {
    return board.board(days);
  }
}
