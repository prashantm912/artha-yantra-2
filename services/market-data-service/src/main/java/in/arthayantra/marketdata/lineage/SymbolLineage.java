package in.arthayantra.marketdata.lineage;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One predecessor→successor ticker-change link, as read from {@code marketdata.symbol_lineage}.
 *
 * <p><b>Data, not identity.</b> The canonical instrument key is and remains {@code (exchange,
 * tradingsymbol)} (docs/symbol-normalization.md). Nothing here is a key: it is a view-time join a
 * reader opts into when it wants a renamed symbol's pre-rename history.
 *
 * @param confidence {@code confirmed} when BSE independently carried both tickers on one {@code
 *     scrip_code}; {@code inferred} when only the NSE price-continuity rule fired. Null on a
 *     policy row the detector has not reached yet.
 * @param status {@code ACTIVE} — lineage-expanded readers stitch this pair. {@code WITHHELD} — an
 *     owner judgement that the pre-switch series is a different asset (a demerger or amalgamation
 *     the exchange nevertheless carried the price series through). The detector never overwrites
 *     it.
 */
public record SymbolLineage(
    String exchange,
    String predecessorSymbol,
    String successorSymbol,
    LocalDate switchDate,
    Integer gapSessions,
    BigDecimal boundaryPrice,
    String confidence,
    String evidence,
    String status,
    String statusReason,
    String source) {

  /** Whether a lineage-expanded reader may stitch this pair's history. */
  public boolean active() {
    return "ACTIVE".equals(status);
  }
}
