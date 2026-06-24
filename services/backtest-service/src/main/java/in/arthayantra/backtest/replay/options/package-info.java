/**
 * The §D.15 options replay fidelity contract + the premium-as-primary replay (Part 2).
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
 *   <li>{@link in.arthayantra.backtest.replay.options.OptionContractSelector} +
 *       {@link in.arthayantra.backtest.replay.options.CandlePremiumReader} +
 *       {@link in.arthayantra.backtest.replay.options.PremiumExitEvaluator} +
 *       {@link in.arthayantra.backtest.replay.options.OptionsPremiumReplay} — the premium-as-primary
 *       replay (Part 2): the selector resolves the ATM contract at each entry from the spot, the
 *       reader serves its OWN backfilled 1m premium series ({@code candles}, {@code source='BACKFILL'}),
 *       and the replay fills/marks/exits on that premium. WIRED into {@code BacktestRunner}, which
 *       routes an options strategy here (the candle-close {@code ReplayEngine} stays the path for every
 *       other strategy). Pinned by {@code OptionsPremiumGoldenTest} (its own fixture golden).
 *   <li>{@link in.arthayantra.backtest.replay.options.SnapshotPremiumReader} — reads the premium
 *       series from {@code marketdata.options_chain_snapshots} at the archive's native 5-minute
 *       granularity (never interpolated finer), with the archive-coverage pre-flight (422 {@code
 *       DATA_GAP}) and the sub-5m snapshot-grade refusal. BUILT and unit/IT-tested; an UNWIRED
 *       alternate source — the active premium path reads the finer {@code CANDLE_1M} series instead.
 *   <li>{@link in.arthayantra.backtest.replay.options.SyntheticPremium} — the clearly-labelled
 *       Black-76 reconstruction of the premium series from underlying 1m candles, with the flat-IV
 *       and nearest-snapshot-IV caveats surfaced as run-level honesty flags. BUILT and unit-tested;
 *       an UNWIRED alternate source (same as the snapshot reader above).
 * </ul>
 *
 * <p><b>Premium-as-primary (landed).</b> Per the §D.15 deliverable, an options strategy's replay
 * tradeable price IS the option PREMIUM series (the contract's own backfilled 1m candles → {@link
 * in.arthayantra.backtest.replay.options.PremiumSource#CANDLE_1M}) rather than the underlying candle
 * close, while the underlying-side indicators keep reading candles at their declared timeframes
 * (parity-preserving — signals are generated on the underlying). This lives in {@link
 * in.arthayantra.backtest.replay.options.OptionsPremiumReplay}, a SEPARATE path from the candle-close
 * {@code ReplayEngine}, so the Phase-30 parity goldens ({@code BacktestParityTest}) that pin
 * candle-close fills byte-identically are never perturbed; the premium path gets its own dedicated
 * golden ({@code OptionsPremiumGoldenTest}). The path is deterministic — protective levels are
 * computed at entry, the premium series is read from the store (never random) — so the golden holds.
 */
package in.arthayantra.backtest.replay.options;
