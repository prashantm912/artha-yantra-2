package in.arthayantra.strategysignal.signals;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * SPI the engine consults at ENTRY emission (A12). DEFINED here in the signals module and
 * IMPLEMENTED by the paper module (the adapter holds the capital base + risk limits), so the module
 * graph stays acyclic — signals depends only on this port, never on paper. Absent (paper disabled)
 * ⇒ the engine treats entries as allowed and stamps no suggested qty.
 */
public interface EmissionGuard {

  /** False when the book's risk pauses ENTRY emission (daily-loss trip, kill switch, max open). */
  boolean entryAllowed(String book);

  /**
   * PF-03: the governor RAIL (an opaque label, e.g. {@code max_open_paper_positions} /
   * {@code kill_switch} / {@code heat_cap_pct}) pausing a new ENTRY on this book right now, or
   * {@link Optional#empty()} when entry is allowed. Behaviourally identical to {@link #entryAllowed}
   * — {@code entryAllowed(book) == entryVeto(book).isEmpty()} — but surfaces WHICH rail vetoed so the
   * engine can write a durable {@code risk_suppressions} record. The default derives from
   * {@link #entryAllowed} with an opaque {@code "unknown"} rail (the paper adapter overrides it with
   * the real rail from the risk governor). The label is opaque to the signals module — no coupling to
   * the paper risk-key constants.
   */
  default Optional<String> entryVeto(String book) {
    return entryAllowed(book) ? Optional.empty() : Optional.of("unknown");
  }

  /**
   * False when the scalper 5-sub-account discipline pauses a fresh scalper ENTRY for the IST day
   * (§12.7 — 5 losses froze all sub-accounts, or 5 wins banked the day). Consulted IN ADDITION to
   * {@link #entryAllowed()}, only on the scalper entry path. Default true (non-scalper / no paper).
   */
  default boolean scalperEntryAllowed() {
    return true;
  }

  /**
   * The strategy's position-sizing run against the BOOK's paper-account equity, lot-rounded for the
   * instrument; null when it sizes to zero or the equity is unknown. Stamped on the signal OUTSIDE
   * the frozen score breakdown.
   */
  BigDecimal suggestedQty(
      StrategyDefinition.SizingSpec sizing,
      String exchange,
      String tradingsymbol,
      BigDecimal price,
      BigDecimal stopDistance,
      String book);

  /**
   * The {@link #suggestedQty} variant that also applies an E8 §3.2 probability-graded size
   * {@code multiplier} (in {@code (0, 1]}) before lot-rounding. The default IGNORES the multiplier
   * (back-compat for impls that do not grade); the paper adapter overrides it to scale + re-lot-round
   * DOWN (never up, never below one lot for a fired entry). A null multiplier == the ungraded sizing.
   */
  default BigDecimal suggestedQty(
      StrategyDefinition.SizingSpec sizing,
      String exchange,
      String tradingsymbol,
      BigDecimal price,
      BigDecimal stopDistance,
      BigDecimal multiplier,
      String book) {
    return suggestedQty(sizing, exchange, tradingsymbol, price, stopDistance, book);
  }

  /**
   * The book's paper-account equity (the sizing base), or {@code null} when unknown (paper disabled).
   * Consulted by the Manas pyramiding gate (§3.4.3) to express the current + prospective open risk as a
   * fraction of book capital. Default null ⇒ the caller treats the risk cap as unknown and does not block.
   */
  default BigDecimal bookEquity(String book) {
    return null;
  }

  /**
   * The book's current aggregate OPEN risk in ₹: {@code Σ qty × max(0, avgEntry − effectiveStop)} over
   * its open positions (a position whose stop has trailed above its entry contributes 0 — "trailing
   * reduces open risk", §2.2/§3.5.B). The Manas pyramiding gate adds the prospective new lot's risk to
   * this and blocks the add/fresh-entry if the total would breach the ≤5–6% portfolio cap (§3.4.3).
   * Default 0 ⇒ paper disabled / no open positions (the gate then never blocks on risk).
   *
   * <p>{@code effectiveStop} is the persisted {@code stopLoss} for every family EXCEPT Manas, where
   * it is the IN-MEMORY {@code ManasGoverningStopCache} entry when the daily Chandelier trail has
   * armed (see {@link #cacheManasGoverningStop}), else {@code stopLoss} (the initial bracket, before
   * the trail arms or on a fresh boot). Before the M40 Critical 3 fix (2026-08-02) Manas's trail was
   * computed fresh every batch run but never read anywhere else, so "trailing reduces open risk" was
   * true in doctrine but not in this figure — always conservative (overstated), never current. The
   * fix is IN MEMORY ONLY, never a database write: {@code stopLoss} is ALSO the intraday
   * disaster-stop {@code PaperBracketEvaluator} polls every 15s with no book filter, so this figure
   * deliberately never reaches that column (or any new persisted column — two earlier drafts of this
   * fix tried exactly that and were reverted) — M40 owns the risk calculation only; whether {@code
   * stopLoss} itself should ever reflect the trail (making Manas intraday-trail-managed) is a
   * separate, later, owner decision.
   */
  default BigDecimal openRiskInr(String book) {
    return BigDecimal.ZERO;
  }

  /**
   * M40 Critical 3 fix, round 3 (owner ruling, 2026-08-02): caches a held Manas position's CURRENT
   * governing stop — IN MEMORY ONLY, never a database write, never {@code stopLoss}, never any new
   * column — so {@link #openRiskInr} (and the Manas aggregate open-risk cap it feeds) reads the
   * position's LIVE governing stop instead of the stale one frozen at open, with ZERO effect on the
   * column the paper module's intraday bracket poller reads. Pure accounting: this never itself
   * closes a position or otherwise changes what the engine's own {@code ExitEvaluator} decides, and
   * cannot affect intraday exit behaviour by construction — there is nothing here for a 15-second
   * poll to read. The paper adapter enforces "never loosens" AND "LONG only" in the cache itself (a
   * no-op when {@code newStop} is not strictly tighter than whatever is already cached, or when
   * {@code side} is not {@code "BUY"} — Manas trades long-only, but a known parked SELL row exists in
   * the live book, so this rejects rather than silently applying a higher-is-tighter comparison that
   * would be backwards for a short). Empty on a fresh boot / before the trail arms — callers then
   * fall back to {@code stopLoss}, the SAME conservative reading as before this whole M40 effort, not
   * a regression. Default no-op keeps non-paper and test adapters permissive.
   */
  default void cacheManasGoverningStop(
      String book, String exchange, String tradingsymbol, String side, BigDecimal newStop) {}

  /**
   * §3.7 hero-zero profit-funded sizing — the lot-rounded qty for an expiry-day hero-zero leg sized to
   * "deploy ~10% of accumulated realised PROFIT, never capital" (mode a), with a ₹2-3k minimum deploy
   * when profits are thin (mode b — owner: "a if we have enough profit, else b"). Computed off the
   * OPTION premium + lot (the deploy is in premium terms, unlike the index-priced {@link #suggestedQty}).
   * Default null (non-paper / no equity) ⇒ the caller keeps the ordinary advisory qty.
   *
   * <p><b>Takes the sizing spec so {@code max_lots} binds here too.</b> This quantity OVERRIDES the
   * ordinary {@link #suggestedQty}, so a cap applied only in {@code PositionSizer} left the
   * hero-zero family completely uncapped — the config declared a cap that did nothing, while the
   * backtest replay ({@code OptionsPremiumReplay}) capped correctly, so live and replay sized
   * differently for exactly the strategies whose premium is cheapest.
   *
   * @param sizing the strategy's sizing spec — only {@code max_lots} is read; the BUDGET is
   *     hero-zero's own profit-funded one, not {@code budget_inr}
   * @param exchange the option's exchange
   * @param tradingsymbol the option tradingsymbol (drives the lot size)
   * @param premium the option premium (ltp) per unit
   */
  default BigDecimal heroZeroSuggestedQty(
      in.arthayantra.strategyengine.config.StrategyDefinition.SizingSpec sizing,
      String exchange,
      String tradingsymbol,
      BigDecimal premium) {
    return null;
  }

  /**
   * Records a fired entry whose final paper size was zero. The signals module owns only this port;
   * the paper adapter supplies the durable implementation. Default no-op keeps non-paper and test
   * adapters permissive.
   */
  default void recordZeroSizedEntry(
      long signalId,
      String strategySlug,
      StrategyDefinition.SizingSpec sizing,
      String book,
      String exchange,
      String tradingsymbol,
      BigDecimal premium,
      BigDecimal stopDistance,
      String side) {}

  /**
   * Records that a §3.4.3 pyramid ADD, or (M40, 2026-08-02) a FRESH Manas entry, was blocked because it
   * would have breached the family's portfolio open-risk cap — see {@code
   * docs/signal-analysis/2026-08-02-m40-fresh-entry-risk-cap-gap.md} for the gap this closes. Three of
   * RiskService's four audited threshold rails — daily-loss, profit-target, heat-cap — write a durable
   * {@code risk_audit} row AND push an ntfy alert on trip; the fourth, deployment, audits only (no
   * alert). Before this method existed (E4 §2f), a pyramid-cap block matched NEITHER group — it only
   * reached the application log (SwingBatchEngine's own {@code log.info}). The ADD call site stays
   * UNREACHABLE while pyramiding is disabled ({@code artha.manas-arora.pyramid.enabled}, default OFF —
   * a disabled policy's {@code hasRoom} never lets an add reach the risk-cap check); the FRESH-entry
   * call site is live regardless of that flag, since the aggregate cap on a first lot never depended on
   * pyramiding. The signals module owns only this port; the paper adapter supplies the durable
   * implementation (mirrors {@link #recordZeroSizedEntry}). Default no-op keeps non-paper and test
   * adapters permissive.
   */
  default void recordPyramidRiskCapBreach(String book, String symbol, String detail) {}
}
