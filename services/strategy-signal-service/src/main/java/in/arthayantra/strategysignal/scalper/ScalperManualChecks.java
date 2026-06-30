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
              "2.9",
              "Signals fire on economic-event days TOO, by design (no auto-lockout) — YOU decide whether"
                  + " to take. Scan the news feed + the day's calendar (RBI/MPC, Fed, Budget, CPI, major"
                  + " results) first; news overrides the data."),
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
              "If you have to talk yourself into it, skip it."),
          new Check(
              "fii_ls_ratio",
              "FII futures Long/Short ratio is not against the trade (heavy short = sell every"
                  + " level; a move toward 50 percent is a short-covering trigger).",
              "4.17.4",
              "Read the NSE FII index-futures Long/Short ratio; FII outweighs Pro, DII, Client."),
          new Check(
              "constituent_contribution",
              "Index direction is confirmed by its heaviest constituents and sector sync, not one"
                  + " stray stock.",
              "4.14.4",
              "Check the top movers and sector breadth (e.g. Bank top-3 dominate BankNifty)."),
          new Check(
              "pre_open_bias",
              "Pre-open positioning and advances/declines agree with the intended morning bias.",
              "4.14.5",
              "Read the 9:00-9:07 pre-open A/D and positioning before the first trade."),
          new Check(
              "sensex_participation",
              "Sensex has real participation; if thin, prefer Nifty (or pick the chain with nearer"
                  + " expiry / richer premium).",
              "4.17.2",
              "Compare Sensex vs Nifty option volume/OI; skip a thin Sensex day."),
          new Check(
              "oi_intraday_positional",
              "Intraday and positional OI agree (over 50 percent call-vs-put gap on BOTH), with the"
                  + " PCR progressing the right way.",
              "4.17.3",
              "Cross-check today-vs-(yesterday+today) OI and the PCR ladder (1.2 -> 1.5 -> 2)."),
          new Check(
              "iv_crush_awareness",
              "Aware of IV crush risk: IV falls in the second half of expiry day and right after a"
                  + " scheduled event.",
              "4.17.5",
              "On an expiry afternoon or post-event, expect call IVs to drop - size accordingly."),
          new Check(
              "straddle_vwap_entry",
              "For a straddle, entry is a combined Call+Put premium close above its own VWAP with"
                  + " volume; manage one leg, SL near straddle VWAP +10-15pt.",
              "4.15.2",
              "Time the entry off the combined-premium VWAP break, not the index chart."),
          new Check(
              "time_of_day_vwap",
              "Reference window matches the clock: prev-day data and prev-day VWAP until 11 AM,"
                  + " intraday and current VWAP after.",
              "4.14.5",
              "Before 11 AM weight prior-session levels; after 11 AM use the current-session VWAP."),
          new Check(
              "vix_regime_bands",
              "India VIX absolute band and the VIX-vs-price grid suit the trade (10-11 lower-bullish"
                  + " / 12-14 medium / 15-16 seller-favoured / 17+ higher).",
              "4.14.1",
              "Map India VIX to its band and compare to the prev-day close; ignore erratic intraday VIX."));

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
