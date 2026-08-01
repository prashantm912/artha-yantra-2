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
   * The book's current aggregate OPEN risk in ₹: {@code Σ qty × max(0, avgEntry − stopLoss)} over its
   * open positions (a position whose stop has trailed above its entry contributes 0 — "trailing reduces
   * open risk", §2.2/§3.5.B). The persisted stop is the position's ORIGINAL bracket, so this is a
   * conservative (never-understated) read. The Manas pyramiding gate adds the prospective new lot's risk
   * to this and blocks the add if the total would breach the ≤5–6% portfolio cap (§3.4.3). Default 0 ⇒
   * paper disabled / no open positions (the gate then never blocks on risk).
   */
  default BigDecimal openRiskInr(String book) {
    return BigDecimal.ZERO;
  }

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
   * M40 governor-coverage fix: records that a §3.4.3 pyramid ADD was blocked because it would have
   * breached the family's portfolio open-risk cap. Every OTHER RiskService threshold rail (daily-loss
   * / profit-target / deployment / heat-cap) writes a durable {@code risk_audit} row + pushes an ntfy
   * alert on trip; before this method existed, a pyramid-cap block only reached the application log
   * (SwingBatchEngine's own {@code log.info}), so re-arming pyramiding ({@code
   * artha.manas-arora.pyramid.enabled}, currently default OFF — this call site is UNREACHABLE today,
   * since a disabled policy's {@code hasRoom} never lets an add reach the risk-cap check) would have
   * silently omitted the one governor-trip TYPE from both the audit trail and ntfy. The signals module
   * owns only this port; the paper adapter supplies the durable implementation (mirrors {@link
   * #recordZeroSizedEntry}). Default no-op keeps non-paper and test adapters permissive.
   */
  default void recordPyramidRiskCapBreach(String book, String symbol, String detail) {}
}
