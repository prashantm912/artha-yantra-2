package in.arthayantra.strategysignal.paper;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * The paper read-surface response shapes, typed so the contract gate can see them (ledger D3 slice
 * 1).
 *
 * <p>Retypings of the {@code Map.of} / {@code LinkedHashMap} bodies {@link PaperController} and
 * {@link PaperService#pnl} used to assemble. Key NAMES, VALUE TYPES and null-vs-absent behaviour are
 * unchanged — only the SPEC gains the shape, because springdoc cannot enumerate a {@code
 * Map<String, Object>} and so the breaking-change gate was blind to any key rename here.
 *
 * <p><b>On key order — this is NOT byte-identical, and the difference is worth naming.</b> Only
 * {@link PnlSummary} came from a {@code LinkedHashMap}; its order was already fixed, so its
 * component order is load-bearing and must stay {@code realizedTotal, trades, winRate, expectancy}
 * or the bytes move. Every other body here came from a MULTI-KEY {@code Map.of}, whose iteration
 * order is not merely unspecified in the javadoc but genuinely VARIES BETWEEN JVM RUNS ({@code
 * ImmutableCollections} randomizes its probe order per JVM, so {@code EquityPoint} really did
 * serialize as both {@code date,equity} and {@code equity,date} depending on the process). Records
 * FIX that order.
 *
 * <p>So this is a deliberate normalization of previously nondeterministic key order, not a
 * preservation of it. It is safe because JSON object members are unordered by specification and
 * every client here binds by key — but "the wire is byte-identical" would be false, and the
 * single-key envelopes ({@link PositionList}) are the only ones where it is trivially true.
 *
 * <p>The item types were already records ({@link PaperService.PositionDto} / {@link
 * PaperService.TradeDto}), each already carrying its own {@code types = {"x", "null"}} nullability,
 * so the envelopes were the only opaque part — typing them pulls the fully enumerated item schemas
 * into the spec rather than leaving an {@code array of object} behind.
 */
public final class PaperViews {

  private PaperViews() {}

  /** {@code GET /api/v1/paper/positions} — open positions with mark-to-market P&amp;L. */
  public record PositionList(List<PaperService.PositionDto> items) {}

  /** {@code GET /api/v1/paper/trades} — the closed-trade ledger, echoing the bounded page window. */
  public record TradePage(List<PaperService.TradeDto> items, int limit, int offset) {}

  /** One point on the cumulative realized-equity curve ({@code date} is an IST calendar day). */
  public record EquityPoint(String date, @Schema(type = "string") BigDecimal equity) {}

  /**
   * The closed-trade rollup for a book.
   *
   * <p>{@code winRate} and {@code expectancy} are NULL when the book has no closed trades — a
   * deliberate "undefined", not zero, since 0/0 is not a 0% win rate. Component order mirrors the
   * former {@code LinkedHashMap}'s put order.
   */
  public record PnlSummary(
      @Schema(type = "string") BigDecimal realizedTotal,
      int trades,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal winRate,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal expectancy) {}

  /** {@code GET /api/v1/paper/pnl} — the daily equity curve plus its summary. */
  public record Pnl(List<EquityPoint> points, PnlSummary summary) {}

  /**
   * One strategy's measured share of a book, decomposed from the V057 per-signal lots.
   *
   * <p>{@code slug} is NULL for fills with no signal (a manual ticket) — reported rather than
   * dropped, so the rows always sum back to the tagged total instead of quietly losing quantity.
   *
   * <p>{@code attributedRealizedPnl} is {@code Σ realized_pnl × lot.qty / position.qty} over CLOSED
   * positions. The division is exact, not an estimate: a paper position exits every unit against one
   * {@code avg_entry_price} and closes in full, so a qty share IS a P&amp;L share.
   */
  public record AttributionRow(
      @Schema(types = {"string", "null"}) String slug,
      String book,
      int closedPositions,
      long closedQty,
      long openQty,
      @Schema(type = "string") BigDecimal attributedRealizedPnl) {}

  /**
   * How much of the book the lots cover — the denominator that makes {@link AttributionRow}
   * readable.
   *
   * <p>Positions opened before V057 have no lots and there is no backfill, so {@code
   * closedPositionsTagged} starts at 0 against a non-zero {@code closedPositions}. Without this
   * beside the rows an empty decomposition reads as "this book never traded" rather than "this book
   * traded before tagging existed" — a false negative of exactly the kind the tagging exists to
   * remove.
   *
   * <p>{@code *QtyTagged} can be LESS than {@code *Qty} for a tagged position too: a position that
   * predates V057 and later takes an add carries a lot for the add only.
   */
  public record AttributionCoverage(
      int closedPositions,
      int closedPositionsTagged,
      long closedQty,
      long closedQtyTagged,
      int openPositions,
      int openPositionsTagged,
      long openQty,
      long openQtyTagged) {}

  /** {@code GET /api/v1/paper/attribution} — per-strategy P&amp;L decomposition plus its coverage. */
  public record Attribution(List<AttributionRow> items, AttributionCoverage coverage) {}
}
