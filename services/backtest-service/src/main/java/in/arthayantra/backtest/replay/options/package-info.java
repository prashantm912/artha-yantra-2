/**
 * The §D.15 options replay fidelity contract + synthetic premium mode (Phase 30A).
 *
 * <p><b>What is built in this package (and where each piece is wired):</b>
 *
 * <ul>
 *   <li>{@link in.arthayantra.backtest.replay.options.PremiumSource} — the {@code SNAPSHOT |
 *       SYNTHETIC_B76 | NA} provenance enum, persisted on every run and the {@code likeForLike}
 *       mixed-source comparison flag (parallel to {@code dataHash} mismatches). WIRED via
 *       {@code RunRepository.insert}.
 *   <li>{@link in.arthayantra.backtest.replay.options.PremiumProvenance} — resolves the per-run
 *       provenance and is WIRED into {@code BacktestRunner} so every run carries a non-null
 *       {@code premium_source}, echoed (with caveats) by {@code GET /backtests/{id}/results}.
 *   <li>{@link in.arthayantra.backtest.replay.options.SnapshotPremiumReader} — reads the premium
 *       series from {@code marketdata.options_chain_snapshots} at the archive's native 5-minute
 *       granularity (never interpolated finer), with the archive-coverage pre-flight (422 {@code
 *       DATA_GAP}) and the sub-5m snapshot-grade refusal. BUILT and unit/IT-tested but NOT YET
 *       invoked by a run path — it becomes live with the premium-as-primary swap below.
 *   <li>{@link in.arthayantra.backtest.replay.options.SyntheticPremium} — the clearly-labelled
 *       Black-76 reconstruction of the premium series from underlying 1m candles, with the flat-IV
 *       and nearest-snapshot-IV caveats surfaced as run-level honesty flags. BUILT and unit-tested
 *       but NOT YET invoked by a run path (same deferred swap).
 * </ul>
 *
 * <p><b>The remaining DEEP piece (premium-as-primary integration).</b> Per the §D.15 deliverable,
 * for an options strategy the replay's tradeable price series should be the option PREMIUM series
 * (snapshot LTP or synthetic) rather than the underlying candle close — while the underlying-side
 * indicators continue to read candles at their declared timeframes (parity-preserving). The
 * Phase-30 candle replay ({@code ReplayEngine}) currently marks-to-market and fills against the
 * underlying candle close. Swapping the fill/mark series to the premium series cannot be done within
 * Phase 30A scope WITHOUT a dedicated premium-as-primary parity golden, because the existing
 * Phase-30 parity goldens ({@code BacktestParityTest}) pin candle-close fills byte-identically and
 * must not be perturbed. Rather than fake snapshot-grade fidelity, the candle-replay path records
 * {@link in.arthayantra.backtest.replay.options.PremiumSource#NA}; the reader + synthetic + provenance
 * machinery here is fully built and tested so the integration is a localized, parity-safe swap of the
 * replay tradeable series once a premium-as-primary golden exists.
 */
package in.arthayantra.backtest.replay.options;
