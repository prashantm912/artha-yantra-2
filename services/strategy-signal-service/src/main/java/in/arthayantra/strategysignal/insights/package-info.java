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
 * structural precedent). Per-book risk heat, rejections, portfolio, sell-decisions and the strategy
 * snapshot rows are read-only same-schema SQL views (the {@code BookHeatReader} precedent: SQL is not
 * a Java import) — the module never MUTATES any of them. The ONE deliberate cross-module read edge is
 * {@link in.arthayantra.strategysignal.insights.StrategyEvidenceReader} consuming
 * {@code paper.GraduationService#board()} read-only: re-deriving the graduation money-math (PF /
 * expectancy / drawdown walk) in SQL would be a divergence risk on a money surface (§0 "no
 * re-scoring"), so I3 reuses the board rather than copies it. This is a one-way edge (paper never
 * imports insights → acyclic, {@code ModularityTest}-verified).
 *
 * <p><b>Delivery is OFF by default.</b> The insights WS and I4 ntfy/Telegram phone channels are
 * gated by the delivery flags in {@link in.arthayantra.strategysignal.insights.InsightPublisher};
 * nothing pushes until the owner arms a channel (Stage 1, §10.3). Generators shipped: I1 {@code SIGNAL_PRIORITY}/{@code
 * DATA_TRUST}/{@code RISK_HEAT}; I2 context/rejection/hygiene/expiry breadth; I3 {@code
 * STRATEGY_EVIDENCE} + {@code SELL_DECISION}. I3 also adds the PROPOSE {@code /act} (prefill /
 * idempotent-instruction + audit, never places an order), {@code /compare}, and the strategy dossier.
 */
package in.arthayantra.strategysignal.insights;
