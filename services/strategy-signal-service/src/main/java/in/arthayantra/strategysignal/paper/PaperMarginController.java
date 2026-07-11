package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F9 advisory heat read: a paper book's total SPAN margin at risk, computed by pricing its open
 * option legs through market-data's Upstox margin endpoint ({@link PaperMarginClient}). Purely
 * advisory — no threshold, no enforcement (the governor + heat cap are the owner-numbers phase of
 * F9); it just shows how much broker margin the open book would tie up. Fail-soft: an unpriced quote
 * (market-data/analytics off) returns {@code priced=false} with a reason, never a 5xx.
 *
 * <p>Audit M1: scope the basket with {@code ?book=} (absent = every book's F&amp;O legs as one
 * basket). SPAN applies to F&amp;O legs only, so cash-equity legs (swing books) are EXCLUDED — a
 * single equity leg makes Upstox return the whole basket unpriced, which used to leave the read dead
 * whenever any swing position was open. And a basket over the 20-leg Upstox limit is reported LOUD
 * ({@code priced=false} + a reason to narrow with {@code ?book=}), never silently truncated.
 */
@RestController
@RequestMapping("/api/v1/paper")
public class PaperMarginController {

  /** Upstox caps a margin basket at 20 legs; over this we refuse LOUD rather than truncate silently. */
  private static final int MAX_LEGS = 20;

  /** Exchanges Upstox SPAN-margins (F&amp;O). Cash-equity legs carry no SPAN margin and are excluded. */
  private static final Set<String> FNO_EXCHANGES = Set.of("NFO", "BFO");

  private final PaperPositionRepository positions;
  private final PaperMarginClient marginClient;

  /** Wires the paper ledger + the margin client. */
  public PaperMarginController(PaperPositionRepository positions, PaperMarginClient marginClient) {
    this.positions = positions;
    this.marginClient = marginClient;
  }

  /**
   * A book's margin-at-risk aggregate (advisory). {@code openPositions} counts every OPEN position in
   * scope; {@code pricedLegs} counts the F&amp;O legs actually sent to Upstox — {@code openPositions >
   * pricedLegs} means some in-scope positions were cash-equity (no SPAN margin) and excluded.
   */
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
      OffsetDateTime asOf) {

    /** An unpriced heat read (fail-soft) carrying the reason + the in-scope open count. */
    static MarginHeat unpriced(String reason, int openPositions, OffsetDateTime asOf) {
      return new MarginHeat(false, reason, null, null, null, null, null, openPositions, 0, asOf);
    }
  }

  /**
   * Prices a book's open F&amp;O legs as one basket → its SPAN margin at risk (advisory). {@code book}
   * absent → every book's F&amp;O legs. Cash-equity legs are excluded (no SPAN margin); over the 20-leg
   * Upstox limit the read is refused LOUD, never truncated.
   */
  @GetMapping("/margin-heat")
  public MarginHeat marginHeat(@RequestParam(value = "book", required = false) String book) {
    List<PositionRow> open = positions.listOpen(book);
    OffsetDateTime asOf = OffsetDateTime.now();
    if (open.isEmpty()) {
      return MarginHeat.unpriced("no open positions", 0, asOf);
    }
    List<PaperMarginClient.Leg> legs =
        open.stream()
            .filter(p -> FNO_EXCHANGES.contains(p.exchange()))
            .map(
                p ->
                    new PaperMarginClient.Leg(
                        p.exchange(), p.tradingsymbol(), (int) p.qty(), p.side(), "D"))
            .toList();
    if (legs.isEmpty()) {
      return MarginHeat.unpriced(
          "no F&O legs to margin (book holds only cash-equity positions)", open.size(), asOf);
    }
    if (legs.size() > MAX_LEGS) {
      // Upstox caps a basket at 20 legs — NEVER silently truncate (audit M1): an under-counted margin
      // is worse than none. Surface it loud so the caller narrows the read with ?book=.
      return MarginHeat.unpriced(
          "basket exceeds the 20-leg Upstox limit (" + legs.size() + " F&O legs) — narrow with ?book=",
          open.size(),
          asOf);
    }
    PaperMarginClient.Quote q = marginClient.margin(legs);
    return new MarginHeat(
        q.priced(), q.unpricedReason(), q.spanMargin(), q.exposureMargin(), q.totalMargin(),
        q.requiredMargin(), q.finalMargin(), open.size(), legs.size(), asOf);
  }
}
