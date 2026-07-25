package in.arthayantra.marketdata.screener;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Shared typed shapes for the screener time-machine (app-platform audit 2026-07-10 §6.7 / §2.6 —
 * "Screener time machine"): the persisted-date list, the day-over-day entered/left diff, and the
 * funnel stage-attrition counts. Both the Minervini and Manas screeners expose these over identical
 * response shapes (their {@code screen_date, symbol, passes_all, rs_rank} + per-gate booleans are the
 * same substrate), so the records live here once rather than being duplicated per controller. Every
 * value is COMPUTED-ON-READ over already-persisted per-gate boolean rows — no new writes, no
 * migration (audit §3.3: "Compute-on-read SQL view over (screen_date, symbol)").
 */
public final class ScreenerHistory {

  private ScreenerHistory() {}

  /** One persisted screen date + how many names were scanned and how many passed all gates. */
  public record ScreenDate(LocalDate date, int coverage, int passers) {}

  /** The {@code {items}} envelope for the recent-screen-dates picker list. */
  public record ScreenDatesResponse(List<ScreenDate> items) {}

  /** One name that entered or left the passers set between two screen dates (ranked by RS-rank). */
  public record DiffRow(String symbol, @Schema(types = {"number", "null"}) BigDecimal rsRank) {}

  /**
   * The day-over-day passer diff: names that PASS all gates on {@code screenDate} but did not on
   * {@code priorDate} (entered), and vice-versa (left). {@code priorDate} null ⇒ no earlier screen
   * exists, so nothing to diff against (entered = all of the day's passers, left empty).
   */
  public record ScreenDiff(
      @Schema(types = {"string", "null"}) LocalDate screenDate,
      @Schema(types = {"string", "null"}) LocalDate priorDate,
      int enteredCount,
      int leftCount,
      List<DiffRow> entered,
      List<DiffRow> left) {}

  /** How many scanned names passed one individual gate (the per-gate bottleneck view). */
  public record GateCount(int gate, long passed) {}

  /** The raw attrition counts a screen table can answer alone (buckets are joined by the caller). */
  public record AttritionCounts(long scanned, List<GateCount> gates, long passesAll) {}

  /**
   * The full funnel stage-attrition for a screen date (audit §6.7 "scanned → per-gate → buyable"):
   * {@code scanned} names, how many passed each individual gate, how many pass ALL gates, and how the
   * all-gate passers split across the actionable funnel buckets ({@code buyable}/{@code onDeck}/{@code
   * watch}) — the buckets are the family funnel's three lists (buyable + onDeck + watch = passesAll).
   */
  public record FunnelAttrition(
      LocalDate screenDate,
      long scanned,
      List<GateCount> gates,
      long passesAll,
      int buyable,
      int onDeck,
      int watch) {}
}
