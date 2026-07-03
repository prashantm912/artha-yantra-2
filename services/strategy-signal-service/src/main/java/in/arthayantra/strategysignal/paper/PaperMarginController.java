package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F9 advisory heat read: the open paper book's total SPAN margin at risk, computed by pricing every
 * open option leg through market-data's Upstox margin endpoint ({@link PaperMarginClient}). Purely
 * advisory — no threshold, no enforcement (the governor + heat cap are the owner-numbers phase of
 * F9); it just shows how much broker margin the open book would tie up. Fail-soft: an unpriced quote
 * (market-data/analytics off) returns {@code priced=false} with a reason, never a 5xx.
 */
@RestController
@RequestMapping("/api/v1/paper")
public class PaperMarginController {

  /** Upstox caps a margin basket at 20 legs; the paper book is small, but cap defensively. */
  private static final int MAX_LEGS = 20;

  private final PaperPositionRepository positions;
  private final PaperMarginClient marginClient;

  /** Wires the paper ledger + the margin client. */
  public PaperMarginController(PaperPositionRepository positions, PaperMarginClient marginClient) {
    this.positions = positions;
    this.marginClient = marginClient;
  }

  /** The open book's margin-at-risk aggregate (advisory). */
  public record MarginHeat(
      boolean priced,
      String unpricedReason,
      BigDecimal spanMargin,
      BigDecimal exposureMargin,
      BigDecimal totalMargin,
      BigDecimal requiredMargin,
      BigDecimal finalMargin,
      int openPositions,
      int pricedLegs,
      OffsetDateTime asOf) {}

  /** Prices the whole open paper book as one basket → total SPAN margin at risk. */
  @GetMapping("/margin-heat")
  public MarginHeat marginHeat() {
    List<PositionRow> open = positions.listOpen();
    OffsetDateTime asOf = OffsetDateTime.now();
    if (open.isEmpty()) {
      return new MarginHeat(false, "no open positions", null, null, null, null, null, 0, 0, asOf);
    }
    List<PaperMarginClient.Leg> legs =
        open.stream()
            .limit(MAX_LEGS)
            .map(
                p ->
                    new PaperMarginClient.Leg(
                        p.exchange(), p.tradingsymbol(), (int) p.qty(), p.side(), "D"))
            .toList();
    PaperMarginClient.Quote q = marginClient.margin(legs);
    return new MarginHeat(
        q.priced(), q.unpricedReason(), q.spanMargin(), q.exposureMargin(), q.totalMargin(),
        q.requiredMargin(), q.finalMargin(), open.size(), legs.size(), asOf);
  }
}
