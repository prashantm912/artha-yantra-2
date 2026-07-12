package in.arthayantra.strategysignal.swing;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The durable swing sell-decision surface (audit §7.2.4 + §10 Phase 2). The two per-family
 * {@code /api/v1/signals/<family>-swing/sell-decisions} endpoints stay the LIVE recompute-on-read triad;
 * these serve the PERSISTED history the 20:05 batch writes (V037) and let the owner acknowledge a row.
 * Under {@code /api/v1/signals/**} so the edge-gateway allowlist already covers it; typed records only
 * (never a Map — the strategy ratchet). The read is {items}-enveloped, paged, optionally filtered by
 * book / not-yet-acknowledged.
 */
@RestController
@RequestMapping("/api/v1/signals/sell-decisions")
public class SellDecisionsController {

  private final SellDecisionRepository repository;

  /** Wires the durable sell-decision store. */
  public SellDecisionsController(SellDecisionRepository repository) {
    this.repository = repository;
  }

  /**
   * Recorded sell decisions newest first. {@code book} filters to a swing family
   * ({@code minervini}/{@code manas-arora}; blank = both); {@code unacknowledged=true} returns only the
   * not-yet-acknowledged rows. {@code limit} clamps to 1..500, {@code offset} to >= 0.
   */
  @GetMapping
  public SellDecisionRepository.SellDecisions list(
      @RequestParam(required = false) String book,
      @RequestParam(defaultValue = "false") boolean unacknowledged,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return new SellDecisionRepository.SellDecisions(
        repository.recent(
            book, unacknowledged, Math.min(Math.max(limit, 1), 500), Math.max(offset, 0)));
  }

  /**
   * Acknowledges one recorded decision (idempotent — an already-acknowledged row returns unchanged) and
   * returns the current row. 404 when the id does not exist.
   */
  @PostMapping("/{id}/ack")
  public SellDecisionRepository.SellDecisionRow acknowledge(@PathVariable long id) {
    return repository
        .acknowledge(id)
        .orElseThrow(
            () -> new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "no such sell decision " + id));
  }
}
