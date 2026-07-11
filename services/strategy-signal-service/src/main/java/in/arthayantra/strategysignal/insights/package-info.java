/**
 * Insight plane (Intelligence layer I1, INT design §1.1 plane 2): the decision-support module that
 * ranks the signals/insights/actions in front of the owner NOW — "what deserves attention, in what
 * order, and why". It layers context/trust/risk ON TOP of a strategy's own frozen score, never
 * re-scoring it (§0 hard boundary).
 *
 * <p><b>Deterministic, not ML (§0).</b> Every insight is templated string interpolation over named
 * evidence with config-pinned weights and a stamped engine SHA + config hash, so identical inputs
 * replay byte-identical (golden-testable, §10.1). No free text, no model.
 *
 * <p><b>Module boundaries.</b> Consumes {@code signals.SignalEmitted} in-process (the
 * {@code notifier←signals} direction: {@code signals}/{@code paper} must never import {@code
 * insights}, so no cycle — {@code ModularityTest} covers it). Reads market-data health rails over
 * REST via {@link in.arthayantra.strategysignal.insights.ContextClient} (the {@code MarketOiClient}
 * structural precedent). Per-book risk heat is a read-only SQL view of the persisted paper margin
 * annotations — the {@code insights} module never imports or mutates the {@code paper} module.
 *
 * <p><b>I1 is SHADOW mode.</b> Insights are rows + read APIs only — NO ntfy/Telegram/WS delivery
 * (that arms in I3/I4). Three generators ship: {@code SIGNAL_PRIORITY}, {@code DATA_TRUST},
 * {@code RISK_HEAT} — all display-only.
 */
package in.arthayantra.strategysignal.insights;
