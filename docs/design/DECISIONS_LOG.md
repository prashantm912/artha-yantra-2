# Decisions log — dated amendments (COMMON §4.5)

Running record of dated ADR amendments that postdate the frozen design set. Each
entry is a decision made during implementation that the design authority
(COMMON_REFERENCE + stage files) references but could not pin ahead of time —
spike outputs, calibrated defaults, accepted deviations. Newest first.

---

## 2026-06-13 — S3 pruner-calibration defaults (Stage D, Phase 33/34 entry gate)

**Context.** §D.13 mandates the fold-fed `MedianPruner` calibration be RUN and its
outputs recorded as a dated amendment before sweeps ship — or pruning stays
disabled (PHASE_GATES.md gate). The spike
(`services/optimizer-service/spikes/s3_pruner_calibration.py`, pure-Python,
deterministic, no backtest-service dependency) models walk-forward OOS folds with
a per-fold regime drift (fold 0 = benign trending regime that flatters every
trial — the early-regime-bias trap) plus trade-count-dependent Sharpe noise
(~1/√trades — the prune-on-noise hazard), then sweeps the pruner knobs measuring
false-prune of near-optimal trials vs true-prune of poor ones, and checks
TPE/NSGA-II convergence.

**Findings (seeded run).**

- Pruner knob sweep (score = true_prune − false_prune; higher better):

  | n_startup_trials | n_warmup_folds | false_prune | true_prune | score |
  |---|---|---|---|---|
  | 5 | 3 | 0.00 | 0.90 | 0.90 |
  | 5 | 2 | 0.06 | 0.86 | 0.80 |
  | 10 | 3 | 0.03 | 0.80 | 0.77 |
  | 5 | 1 | 0.19 | 0.94 | 0.75 |

  Warm-up of **3 folds** eliminates false-pruning of near-optimal trials (0.00)
  while keeping true-prune high (0.90); `n_warmup_folds=1` prunes ~19% of
  near-optimal trials on the benign opening regime alone — the early-regime bias
  §D.13 warns about, confirmed.
- Sampler convergence (|best − known optimum| @ 150 trials): **tpe 0.0096**,
  grid 0.0386, random 0.1168. NSGA-II returns a 5-point Pareto front @ 200.
  TPE/NSGA-II converge usefully under the chosen pruner settings.

**Decision — recorded pruner defaults (configured in Phase 34).**

| Setting | Value |
|---|---|
| `n_startup_trials` | **5** |
| `n_warmup_folds` (MedianPruner `n_warmup_steps`) | **3** |
| `n_min_trials` | **2** |
| default `max_trials` | **150** (tpe) / **200** (nsga2) |
| default sampler / pruner | TPE (constant-liar) + fold-fed MedianPruner |

Pruning is **enabled** in Phase 34 with these defaults (the gate's "or pruning
explicitly disabled" branch is not taken). Pruning is fed by **OOS fold medians**
only (guard 2), never train/OOS divergence (the rejected S1B design).
