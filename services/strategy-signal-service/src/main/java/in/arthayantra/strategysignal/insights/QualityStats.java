package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;
import java.util.List;

/**
 * The insight-quality statistics the QUALITY_REPORT generator consumes (INT design §10.2). Assembled
 * at the engine edge by {@link InsightRepository} over a trailing window from the {@code insights} +
 * {@code insight_feedback} tables. The report PROPOSES (a type with a dismiss rate &gt; the config
 * threshold is a retirement candidate) — it never self-tunes (§10.2, "the report proposes, never
 * self-tunes").
 *
 * @param windowDays the trailing window the stats cover
 * @param aBandActRate share of A-band SIGNAL_PRIORITY insights acted-on (ACKED/ACTED/DISMISSED)
 * @param aBandGenerated count of A-band SIGNAL_PRIORITY insights in-window
 * @param suppressedTotal count of suppressed insights in-window (the suppressed-by-caution ledger)
 * @param topSuppressedKeys the most-suppressed dedupe keys (is muting hiding value?)
 * @param perType per-type generated / feedback tally
 */
public record QualityStats(
    int windowDays,
    BigDecimal aBandActRate,
    long aBandGenerated,
    long suppressedTotal,
    List<KeyCount> topSuppressedKeys,
    List<TypeStat> perType) {

  /** One (dedupe key, count) tally for the suppression audit. */
  public record KeyCount(String dedupeKey, long count) {}

  /**
   * Per-type usefulness tally (INT design §10.2).
   *
   * @param generated insights of this type in-window
   * @param usefulVotes USEFUL feedback rows
   * @param notUsefulVotes NOT_USEFUL feedback rows
   * @param dismissRate NOT_USEFUL ÷ (USEFUL + NOT_USEFUL); null when no feedback
   */
  public record TypeStat(
      String type,
      long generated,
      long usefulVotes,
      long notUsefulVotes,
      BigDecimal dismissRate) {}
}
