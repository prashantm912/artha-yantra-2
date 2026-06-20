package in.arthayantra.strategysignal.scalper;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * The canonical human-verification checklist for an index-option scalper signal — the parts of the
 * Siva method that CANNOT be reduced to a chart/OI rule and must be confirmed by the owner before a
 * trade is taken. It rides the V009 {@code scalper_detail} side-channel (as a {@code manual_checks}
 * array) and is rendered on the signal card; the frontend gates the "Take" action until every item
 * is ticked. Doc refs point at the in-repo consolidated strategy
 * ({@code strategy-documents/options-scalper-siva/Options_Scalper_Siva_Consolidated_Strategy.md}).
 *
 * <p>Strings are ASCII-only (avoids the PS5.1 UTF-8/checkstyle encoding traps); the frontend
 * prefixes {@code §} onto {@code doc_ref} for display.
 */
public final class ScalperManualChecks {

  /** One human-verify item: a stable key, the owner-facing label, the doc section, and a hint. */
  public record Check(String key, String label, String docRef, String assist) {}

  /** The fixed list, stamped onto every scalper signal. */
  public static final List<Check> CHECKS =
      List.of(
          new Check(
              "news_clear",
              "No market-moving news or event against this trade (news overrides the data).",
              "2.13",
              "Scan your news feed and the day's economic calendar before entering."),
          new Check(
              "level_respected",
              "Price is reacting at the right support/resistance zone, not mid-range or into a wall.",
              "4.11",
              "Check the 1-day and 15-min S/R zones you marked pre-market."),
          new Check(
              "not_parabolic",
              "Entry is not chasing a parabolic or vertical move.",
              "3.1",
              "If the last few candles went vertical, wait for a pullback."),
          new Check(
              "regime_ok",
              "Market regime suits the setup (trending, not choppy or range-bound).",
              "3.10",
              "More than 2-3 VWAP crossovers so far today usually means a choppy day - stand aside."),
          new Check(
              "vix_normal",
              "India VIX is not abnormally spiking (gap and whipsaw risk).",
              "4.5",
              "Glance at India VIX versus the last few sessions."),
          new Check(
              "global_cues_ok",
              "Global cues are not against the trade (DOW futures, Asian indices, crude, USD).",
              "4.7",
              "Check DOW futures and Asian index direction."),
          new Check(
              "clean_setup",
              "This is a clean 'one good trade' setup, not a forced or marginal entry.",
              "3.1",
              "If you have to talk yourself into it, skip it."));

  private ScalperManualChecks() {}

  /** Appends the canonical checklist as a {@code manual_checks} array on a scalper_detail node. */
  public static void appendTo(ObjectNode root) {
    ArrayNode arr = root.putArray("manual_checks");
    for (Check c : CHECKS) {
      ObjectNode n = arr.addObject();
      n.put("key", c.key());
      n.put("label", c.label());
      n.put("doc_ref", c.docRef());
      n.put("assist", c.assist());
    }
  }
}
