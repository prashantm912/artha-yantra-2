"""Pure scoring library (design §6): hard gates + RobustScore + §6.3 scorecard assembly.

Side-effect-free and deterministic — no wall-clock, no random, no I/O. The caller
(``RetroScoreService``) assembles the normalized per-candidate metric dicts from a sweep's
trials + backtest-run evidence; this module turns a COHORT of those dicts into §6.3 scorecards,
z-scoring each RobustScore component WITHIN the cohort (the retro stand-in for "normalized within
the campaign", §6.2 — a sweep's trials ARE the cohort). Same cohort in ⇒ byte-identical scorecards
out.

E1 scope (design §12 item 2): the SIM_FIRST hard gates + RobustScore + the recovery/turnover/
frequency metric semantics, applied retroactively. E2 (design §12 item 6) adds the deflated-Sharpe
multiplicity gate and the DOF penalties (both below). E3 (§12 item 10) wires the §7.2 live-gap gate
+ the ``live_alignment`` component + the DIVERGENT diagnosis checklist off a candidate's attached
``reconciliation`` row (the caller resolves the latest applicable (version, window) row per §7.1.4;
scoring stays pure — it only reads the dict). Everything still deferred is marked IN-BAND,
never silently dropped:
  * cost-stress re-runs → ``cost_resilience`` z=0 with a "no stress runs (E2)" caveat;
  * importance/brittleness insights → out of scope.
Missing evidence degrades, never fabricates: no sharpe or trade count ⇒ the deflated-Sharpe gate is
SKIPPED (never fabricated); no live evidence ⇒ ``live_alignment`` z=0 (§6.2 comment); NULL engine
SHA (runs predating #703) ⇒ comparability gate UNKNOWN (not FAIL); no holdout run linked ⇒ holdout
gate SKIPPED. A candidate is "rankable" iff no gate is FAIL — SKIPPED / UNKNOWN / NOT_IMPLEMENTED
never block ranking.

This EXTENDS the ``/best`` guard-aware leaderboard shape (leaderboard.plateau_scores is reused for
the plateau/neighbor stability signal), it does not replace it (design §6.3 / audit §13.11).
"""

from __future__ import annotations

import math
import statistics
from typing import Any

from app import leaderboard

# Gate / component statuses (design §6.1 + the E1 degradation contract).
PASS = "PASS"
FAIL = "FAIL"
SKIPPED = "SKIPPED"
UNKNOWN = "UNKNOWN"
NOT_IMPLEMENTED = "NOT_IMPLEMENTED"

# --- Two distinct verdicts (audit PF-01 / AY-SL-08) -------------------------------------------
# NEVER conflated (the original defect conflated them into one fail-open flag):
#   * rankable       — DESCRIPTIVE: "no hard-gate FAIL". Drives the retro/leaderboard DISPLAY, the
#                      RobustScore ranking, and the stress top-K (which candidate is worth probing).
#                      Permissive by design — a degraded card still shows on the board.
#   * stageReadiness — PROMOTION ADMISSION: fail-CLOSED, computed PER evidencePolicy (design §1.2).
#                      A REQUIRED gate that is anything other than PASS blocks; a FAIL on any gate
#                      blocks; a gate legitimately not-applicable at the stage is excluded (its
#                      non-PASS neither passes nor blocks). This is what SURVIVOR selection consumes
#                      — a sim-smoke must NEVER become selectable (§1.2 LIVE_FIRST).
#
# SCORED→SURVIVOR SIM_FIRST required set — every §6.1 SIM_FIRST hard gate a walk-forward cohort must
# AFFIRMATIVELY pass. #703 (engine SHA) and #705 (regime-label vocab) are CLOSED (design doc lines
# 29-34), so comparability + regime_floor are now affirmatively computable and REQUIRED. Foldless /
# <4-neighbor = INCOMPLETE evidence = blocks (not a free pass). Excluded from THIS stage — and only
# this stage — are the two gates a sim SURVIVOR decision genuinely cannot own: ``holdout`` (§8.2 —
# consumed at the PUBLISH_PAPER bar, after selection) and ``live_gap`` (§6.1 "when live evidence
# exists" — a sim sweep has none). A FAIL on either still blocks via the FAIL rule.
_SIM_FIRST_SURVIVOR_REQUIRED = frozenset({
    "evidence_floor", "oos_sign", "deflated_sharpe", "fold_consistency",
    "drawdown_cap", "regime_floor", "stability_floor", "comparability",
})

# Interim (flagged follow-up): the LIVE_FIRST SURVIVOR gate is live-evidence-led (shadow-vs-paper
# challenger variants — design §1.2/§1.3), a producer NOT built here. A LIVE_FIRST campaign's sim
# cohort is functional-smoke ONLY (never ranks, §1.2), so it is never stage-ready from sim evidence.
_LIVE_FIRST_SIM_SMOKE_BLOCK = (
    "evidencePolicy:LIVE_FIRST — sim evidence is functional-smoke only (design §1.2); the SURVIVOR "
    "gate is live-evidence-led (shadow-vs-paper) and is NOT computed from a sim cohort"
)


def gate_readiness(
    gates: list[dict[str, Any]], required_ids: frozenset[str]
) -> tuple[bool, list[str]]:
    """Fail-CLOSED readiness over gates against a required-id set. Returns ``(ready, blocked)``
    where ``blocked`` names WHY as ``"<gateId>:<status>"`` entries — a FAILed gate, or a REQUIRED
    gate that was not affirmatively evaluated (SKIPPED / UNKNOWN / INSUFFICIENT / NOT_IMPLEMENTED /
    ABSENT). A gate id NOT in ``required_ids`` never blocks on a non-PASS status (excluded), but a
    FAIL on ANY gate always blocks. ``ready`` iff ``blocked`` is empty. Pure — the WHY is returned
    for the caller to surface/log, never logged here. Reused by proposals.py for the TAKE stage."""
    by_id = {g.get("id"): g for g in gates}
    blocked = [f"{g.get('id')}:{FAIL}" for g in gates if g.get("status") == FAIL]
    for req in sorted(required_ids):
        gate = by_id.get(req)
        status = gate.get("status") if gate else "ABSENT"
        if status not in (PASS, FAIL):  # a required-gate FAIL is already captured above
            blocked.append(f"{req}:{status}")
    return not blocked, blocked


def survivor_stage_readiness(
    gates: list[dict[str, Any]], policy: str
) -> tuple[bool, list[str], list[str]]:
    """The SCORED→SURVIVOR promotion-admission verdict, PER evidencePolicy. Returns
    ``(ready, blocked, required_ids)``. SIM_FIRST → fail-closed over the SIM_FIRST required set.
    LIVE_FIRST → a sim cohort is functional-smoke only and NEVER ranks (§1.2), so it is never
    stage-ready from sim evidence (the live-evidence LIVE_FIRST survivor path is a flagged
    follow-up). Any other/unknown policy (e.g. SIM_BLOCKED, which the recorder refuses before
    scoring) → blocked, defensively."""
    if policy == "SIM_FIRST":
        required = sorted(_SIM_FIRST_SURVIVOR_REQUIRED)
        ready, blocked = gate_readiness(gates, _SIM_FIRST_SURVIVOR_REQUIRED)
        return ready, blocked, required
    if policy == "LIVE_FIRST":
        return False, [_LIVE_FIRST_SIM_SMOKE_BLOCK], []
    return False, [f"evidencePolicy:{policy} — not a sim-scorable promotion plane"], []

# §6.2 RobustScore weights (SIM_FIRST family default; they sum to 1.00). The owner can override
# per campaign — the weights actually used are echoed into every scorecard (reproducible ranking).
SIM_FIRST_WEIGHTS: dict[str, float] = {
    "oos_return": 0.22,
    "stability": 0.16,
    "risk_adjusted": 0.14,
    "drawdown_quality": 0.12,
    "regime_consistency": 0.10,
    "cost_resilience": 0.08,
    "live_alignment": 0.08,
    "explainability": 0.05,
    "efficiency": 0.05,
}

# §6.1 SIM_FIRST hard-gate thresholds (swing family defaults).
_EVIDENCE_FLOOR_TRADES = 60
_FOLD_CONSISTENCY_MIN = 0.60
_DRAWDOWN_CAP_PCT = 40.0
_REGIME_MIN_COVERED = 3
_REGIME_MIN_RATIO = -0.5  # regimeOosMin ≥ −0.5 × regimeOosMean
_STABILITY_PLATEAU_RATIO = 0.8
_STABILITY_MIN_NEIGHBORS = 4

_CAVEAT_UNIT_PENALTY = 0.05  # §6.2: 0.05 per unresolved data caveat / oiGateCoverage < 80%
_OI_COVERAGE_FLOOR = 0.80

# §6.2 DOF (degrees-of-freedom) penalty: charge the candidate for every knob it spends. The first
# 4 tuned params are "free"; beyond that each costs 0.03, and each structure gate costs 0.06.
_DOF_FREE_PARAMS = 4
_DOF_PARAM_PENALTY = 0.03      # per active (tuned) param over the free 4
_DOF_STRUCTURE_PENALTY = 0.06  # per structure gate (0 until structure mutations exist — E5)

# §7.2 live-gap thresholds (E3, design §12 item 10). ``gapZ = returnGap / σ(fold returns)`` is the
# reconciliation computer's √t-normalized divergence z (V012 ``reconciliations.gap_z``). The
# ``live_alignment`` COMPONENT reads gapZ for its proportional signal; the ``live_gap`` GATE reads
# the persisted ``verdict`` string — the reconciliation computer's authoritative §7.2 classification
# (single source of truth: scoring NEVER re-derives the −1.5 / evidence-floor thresholds).
_LIVE_ALIGNED_MIN = -0.5  # gapZ ≥ −0.5 → aligned; §4 line 477 "−live-gap magnitude"
# Verdicts that COUNT into scoring. INSUFFICIENT (no live / no fold-return σ / floor unmet) never
# counts (z=0 + the standing "no live evidence yet" note — unchanged pre-item-10 behaviour).
_COUNTED_VERDICTS = frozenset({"ALIGNED", "PENALIZED", "DIVERGENT"})


def score_cohort(
    candidates: list[dict[str, Any]],
    parameters: list[dict[str, Any]],
    direction: str = "maximize",
    policy: str = "SIM_FIRST",
    weights: dict[str, float] | None = None,
    n_trials: int | None = None,
) -> list[dict[str, Any]]:
    """Score a whole cohort (a sweep's trials) → one §6.3 scorecard dict per candidate, in the
    input order. ``parameters`` are the sweep's tunable specs (drive the plateau/neighbor stability
    signal via ``leaderboard.plateau_scores`` AND are the ONE source of the tuned-param count for
    both the DOF penalty and the explainability activeDOF term). ``direction`` is echoed for
    provenance AND drives the single maximize-space normalization of the plateau math
    (``_stability_inputs``). ``n_trials`` is the multiplicity N for the §4 deflated-Sharpe gate —
    the total trials the search ran (retro: the sweep's full trial count, all states) — defaulting
    to the scored cohort size when the caller does not pass a fuller count. z-scoring is within THIS
    cohort, so a single-candidate cohort yields all-zero z's (nothing to normalize against) —
    expected, and flagged by the empty spread rather than invented."""
    weights = weights or SIM_FIRST_WEIGHTS
    n = len(candidates) if n_trials is None else n_trials
    n_params = len(parameters)
    # A stress round (E2, §3.2.5) writes ``costResilience`` on the stressed candidates; its presence
    # ANYWHERE in the cohort flips cost_resilience from the retro z=0 stub to a live z-column, and
    # switches the not-yet-stressed candidates from the uniform "no stress runs" caveat to a
    # per-candidate "not stress-tested this round" note (below).
    cohort_has_stress = any(_num(c.get("costResilience")) is not None for c in candidates)
    # A reconciliation row (attached by the caller, keyed (version, window) per §7.1.4) on ANY
    # candidate flips live_alignment from the retro z=0 stub to a live z-column (E3, §12 item 10);
    # candidates without a countable row then carry a per-candidate note (below), mirroring stress.
    cohort_has_live = any(_counts_toward_live(c.get("reconciliation")) for c in candidates)
    # §1.3 comparability comparator: the cohort's representative engine SHA + data epoch (first
    # non-null of each — the same source the recorder lifts, evolution.py). Every candidate's SHA +
    # epoch must MATCH it (audit PF-01 #4) — a differing side is not comparable and is refused.
    comparator_sha = next((c.get("engineSha") for c in candidates if c.get("engineSha")), None)
    comparator_epoch = next((c.get("dataHash") for c in candidates if c.get("dataHash")), None)
    stab = _stability_inputs(candidates, _plateau(candidates, parameters), direction)

    # Per-component z's (cohort-normalized) + the raw constituents + any structural caveat.
    comp_zs: dict[str, list[float]] = {}
    comp_raws: list[dict[str, dict[str, Any]]] = [dict() for _ in candidates]
    comp_caveats: dict[str, str | None] = {}
    for comp, subsignals in _component_subsignals(candidates, stab, n_params).items():
        zs, raws = _component_z(subsignals, len(candidates))
        comp_zs[comp] = zs
        comp_caveats[comp] = None if subsignals else _EMPTY_COMPONENT_CAVEATS.get(comp)
        for i, raw in enumerate(raws):
            comp_raws[i][comp] = raw

    scorecards: list[dict[str, Any]] = []
    for i, cand in enumerate(candidates):
        components = [
            {"id": comp, "z": _round(comp_zs[comp][i]), "raw": comp_raws[i][comp],
             **({"caveat": comp_caveats[comp]} if comp_caveats[comp] else {})}
            for comp in weights
        ]
        weighted = sum(weights[comp] * comp_zs[comp][i] for comp in weights)
        penalties = _penalties(cand, n_params)
        robust = _round(weighted - penalties["dof"] - penalties["caveats"])
        gates = _gates(cand, stab[i], n, comparator_sha, comparator_epoch)
        # DESCRIPTIVE rankability (unchanged, permissive): no hard-gate FAIL. Drives display /
        # ranking / stress top-K — NOT promotion admission.
        rankable = all(g["status"] != FAIL for g in gates)
        # PROMOTION-ADMISSION readiness (audit PF-01), fail-closed and PER evidencePolicy — this is
        # what SURVIVOR selection consumes, so a SKIPPED/UNKNOWN required gate no longer reads as a
        # pass and a LIVE_FIRST sim-smoke is never selectable.
        stage_ready, stage_blocked, stage_required = survivor_stage_readiness(gates, policy)
        scorecards.append({
            "trialNumber": cand.get("trialNumber"),
            "runId": cand.get("runId"),
            "params": cand.get("params"),
            "policy": policy,
            "direction": direction,
            "robustScore": robust,
            "rank": None,  # assigned below, over rankable candidates only
            "rankable": rankable,
            # audit PF-01: the per-policy promotion-admission verdict, persisted so SURVIVOR
            # selection consumes it (not `rankable`) and the owner sees WHY a candidate did not
            # survive (which required gate FAILed or was never affirmatively evaluated).
            "stageReadiness": {
                "evidencePolicy": policy,
                "stage": "SCORED_TO_SURVIVOR",
                "ready": stage_ready,
                "requiredGates": stage_required,
                "blockedBy": stage_blocked,
            },
            "weights": dict(weights),
            "gates": gates,
            "components": components,
            "penalties": penalties,
            "flags": _flags(cand),
            "caveats": _caveats(cand, comp_caveats, cohort_has_stress, cohort_has_live),
            "evidence": {
                "simRuns": [cand["runId"]] if cand.get("runId") else [],
                "holdoutRun": cand.get("holdoutRunId"),
                "liveWindow": _live_window(cand.get("reconciliation")),
            },
            "liveGap": _live_gap_card(cand.get("reconciliation")),
            "comparator": None,  # retro has no campaign champion to compare against (§6.3)
        })

    _assign_ranks(scorecards)
    return scorecards


# --- RobustScore components (§6.2) ------------------------------------------------------------

def _stability_inputs(
    candidates: list[dict[str, Any]], plateau: list[dict[str, Any]], direction: str
) -> list[dict[str, Any]]:
    """The ONE maximize-space normalization for all plateau math: a minimize-direction sweep's
    ``plateauObjective``/``rawObjective`` are negated here, so the §3.2.3/§6.1 multiplication form
    (``plateau ≥ 0.8 × raw``) reads correctly downstream — the division form (ratio ≥ 0.8) INVERTS
    for raw ≤ 0 and for minimize sweeps. Negating after ``plateau_scores`` is sound: the median is
    an order statistic, so median(−x) = −median(x). Per candidate: ``plat``/``raw`` (maximize
    space), the signed ``margin`` = plat − 0.8·raw (sign-safe stability signal), the display
    ``ratio`` (only when raw > 0 — it is meaningless at raw ≤ 0), and ``neighbors``."""
    sign = -1.0 if direction == "minimize" else 1.0
    out: list[dict[str, Any]] = []
    for cand, p in zip(candidates, plateau, strict=True):
        raw = _num(cand.get("rawObjective"))
        plat = _num(p.get("plateauObjective"))
        raw_m = None if raw is None else sign * raw
        plat_m = None if plat is None else sign * plat
        margin = (
            None if raw_m is None or plat_m is None
            else plat_m - _STABILITY_PLATEAU_RATIO * raw_m
        )
        out.append({
            "plat": plat_m,
            "raw": raw_m,
            "margin": margin,
            "ratio": _ratio(plat_m, raw_m) if raw_m is not None and raw_m > 0 else None,
            "neighbors": p.get("neighborCount", 0),
        })
    return out


def _component_subsignals(
    candidates: list[dict[str, Any]], stab: list[dict[str, Any]], n_params: int
) -> dict[str, list[tuple[str, list[float | None]]]]:
    """Each RobustScore component is built from one or more raw sub-signals; the component z is the
    mean of its present sub-signals' cohort z-scores (a missing sub-signal drops out of the mean, a
    fully-absent component → z=0). ``live_alignment`` reads each candidate's attached reconciliation
    row (E3, §12 item 10) for §7.2's −live-gap signal — absent / INSUFFICIENT / scalper-whitelisted
    it drops out to z=0 with the standing caveat (retro, or a sim-only cohort). ``cost_resilience``
    reads the stress-orchestrator's
    per-candidate ``costResilience`` slope (E2, §3.2.5) when a stress round has run — absent (retro,
    or a not-yet-stressed candidate) it drops out to z=0 with the standing caveat.
    ``risk_adjusted`` reads Sortino ONLY — no Sharpe fallback (mixing the two metrics in one cohort
    z-column would bias fallback candidates; an absent Sortino simply drops out)."""
    return {
        "oos_return": [("oosReturn", [c.get("oosReturn") for c in candidates])],
        "stability": [
            ("negOosFoldStd", [_neg(c.get("oosFoldStd")) for c in candidates]),
            ("plateauMargin", [s["margin"] for s in stab]),
        ],
        "risk_adjusted": [("sortino", [c.get("sortino") for c in candidates])],
        "drawdown_quality": [
            ("negMaxDrawdown", [_neg(c.get("maxDrawdown")) for c in candidates]),
            ("negDdDurationBars", [_neg(c.get("ddDurationBars")) for c in candidates]),
            ("recoveryFactor", [c.get("recoveryFactor") for c in candidates]),
        ],
        "regime_consistency": [
            ("regimeOosMin", [c.get("regimeOosMin") for c in candidates]),
            ("regimeOosMean", [c.get("regimeOosMean") for c in candidates]),
            ("coveredCount", [float(len(c.get("regimesCovered") or [])) for c in candidates]),
        ],
        "cost_resilience": _cost_resilience_subsignals(candidates),
        "live_alignment": _live_alignment_subsignals(candidates),
        "explainability": [
            ("explainability", [_explainability(c, n_params) for c in candidates])
        ],
        "efficiency": [("expectancy", [c.get("expectancy") for c in candidates])],
    }


def _cost_resilience_subsignals(
    candidates: list[dict[str, Any]],
) -> list[tuple[str, list[float | None]]]:
    """The ``cost_resilience`` sub-signal column (E2, design §3.2.5 / §6.2): the stress
    orchestrator writes a per-candidate ``costResilience`` = the least-squares slope of the primary
    objective across 1×/2×/4× slippage, normalized by |base| and oriented so a SMALLER
    worse-direction drop scores HIGHER (design z(−degradation slope)). When NO candidate carries it
    (retro, or a cohort with no stress round yet) the column is EMPTY → the component drops to z=0
    with the standing caveat, byte-identical to the pre-E2 behaviour. A candidate that was NOT
    stress-tested in a round that DID stress others carries None here → it drops out of the cohort z
    (0) and is caveated per-candidate in ``_caveats``."""
    values = [_num(c.get("costResilience")) for c in candidates]
    return [("costResilience", values)] if any(v is not None for v in values) else []


# --- live_alignment / live_gap (§7.2, E3 §12 item 10) -----------------------------------------
# A candidate may carry a ``reconciliation`` row (the repo read-envelope shape — verdict / gapZ /
# gap.mode / evidenceFloorMet / diagnosis), attached by the caller as the latest applicable
# (version, window) row per §7.1.4 (mirroring how the stress orchestrator attaches costResilience).
# scoring stays PURE — it only READS the attached dict, never the DB.


def _live_alignment_subsignals(
    candidates: list[dict[str, Any]],
) -> list[tuple[str, list[float | None]]]:
    """The ``live_alignment`` sub-signal column (§4 line 477 "−live-gap magnitude"; §7.2). The
    countable signal is the below-aligned SHORTFALL, negated: ``min(0, gapZ − (−0.5))`` — 0 at/above
    the −0.5 aligned line (an aligned candidate is thereby REWARDED relative to diverging cohort
    peers), negative-proportional below it (§7.2 "penalized proportionally"), and MONOTONIC in
    gapZ. A row absent, INSUFFICIENT (no live / no fold-return σ / floor unmet),
    or a whitelisted scalper raw backtest-vs-live (§7.2 final para) is NOT counted → None (drops out
    → z=0). When the WHOLE column is empty (a sim-only cohort) the component drops to z=0 with the
    standing "no live evidence yet" caveat — byte-identical to the pre-item-10 behaviour."""
    values = [_live_alignment_value(c.get("reconciliation")) for c in candidates]
    return [("liveAlignment", values)] if any(v is not None for v in values) else []


def _live_alignment_value(recon: Any) -> float | None:
    """One candidate's raw ``live_alignment`` signal: ``min(0, gapZ + 0.5)`` — the negated
    below-aligned shortfall (§4 "−live-gap magnitude"). None when the row does not count (absent /
    whitelisted scalper / INSUFFICIENT / gapZ NULL)."""
    if not _counts_toward_live(recon):
        return None
    gap_z = _num(recon.get("gapZ"))
    if gap_z is None:
        return None
    return min(0.0, gap_z - _LIVE_ALIGNED_MIN)


def _counts_toward_live(recon: Any) -> bool:
    """A reconciliation row feeds the live_alignment component AND the live_gap gate iff it is a
    NON-whitelisted plane carrying a COUNTED verdict. §7.2 final para: a scalper backtest-vs-live
    reconciliation is a KNOWN structural divergence — whitelisted, stamped, never counted. An
    INSUFFICIENT verdict (no live evidence / no fold-return σ / evidence floor unmet) never counts
    either (z=0 + the standing note — unchanged behaviour)."""
    return (
        isinstance(recon, dict)
        and not _is_scalper_whitelisted(recon)
        and recon.get("verdict") in _COUNTED_VERDICTS
    )


def _is_scalper_whitelisted(recon: dict[str, Any]) -> bool:
    """§7.2 LIVE_FIRST whitelist: a reconciliation whose pairing ``gap.mode`` is ``scalper``
    (intraday / btst / expiry_day option legs) is a STRUCTURAL divergence BY DESIGN — the sim trade
    set ≠ live trade set (OI/Dow/IV muted on derived history → ~0 armed trades). It is stamped on
    the card and NEVER counted; only shadow-vs-paper reconciliations may gate a scalper."""
    gap = recon.get("gap")
    return isinstance(gap, dict) and gap.get("mode") == "scalper"


def _component_z(
    subsignals: list[tuple[str, list[float | None]]], n: int
) -> tuple[list[float], list[dict[str, Any]]]:
    """Reduce a component's sub-signals to a per-candidate z (mean of present sub-signal z's) plus a
    per-candidate raw-value dict. A component with no sub-signals (cost_resilience / live_alignment)
    → all z=0 (§6.2 — 0 when there is nothing to normalize); its caveat is set by the caller."""
    if not subsignals:
        return [0.0] * n, [dict() for _ in range(n)]

    z_columns = {label: _zscores(values) for label, values in subsignals}
    raws: list[dict[str, Any]] = []
    zs: list[float] = []
    for i in range(n):
        present = [z_columns[lbl][i] for lbl, _ in subsignals if z_columns[lbl][i] is not None]
        zs.append(statistics.fmean(present) if present else 0.0)
        raws.append({
            label: _round(values[i]) for label, values in subsignals if values[i] is not None
        })
    return zs, raws


# The two components with NO retro sub-signals resolve to z=0; each says WHY, distinctly (§6.2).
_EMPTY_COMPONENT_CAVEATS = {
    "cost_resilience": "no stress runs (E2 — the 2×/4× slippage re-runs)",
    "live_alignment": "no live evidence yet (§6.2 — 0 until a candidate is live)",
}


def _zscores(values: list[float | None]) -> list[float | None]:
    """Population z-scores over the PRESENT values in a cohort column; a missing value stays None
    (drops out of its component's mean), a constant or <2-sample column is all zeros (no spread to
    normalize against). Population (not Bessel) std so the cohort is treated AS the population it
    is — deterministic and hand-checkable."""
    present = [v for v in values if v is not None]
    if len(present) < 2:
        return [0.0 if v is not None else None for v in values]
    mean = statistics.fmean(present)
    sd = statistics.pstdev(present)
    if sd == 0:
        return [0.0 if v is not None else None for v in values]
    return [((v - mean) / sd) if v is not None else None for v in values]


# --- Hard gates (§6.1, SIM_FIRST column) ------------------------------------------------------

def _gates(
    cand: dict[str, Any], stab: dict[str, Any], n_trials: int,
    comparator_sha: str | None = None, comparator_epoch: str | None = None,
) -> list[dict[str, Any]]:
    """The §6.1 SIM_FIRST hard gates as {id, status, value[, note]}. Retro degradations: no holdout
    run → holdout SKIPPED; NULL engine SHA → comparability UNKNOWN; no reconciliation / INSUFFICIENT
    / whitelisted-scalper → live_gap SKIPPED (§7.2, item 10); no sharpe / trade count → the
    deflated-Sharpe multiplicity gate SKIPPED. ``comparator_sha``/``comparator_epoch`` are the
    cohort's representative engine SHA + data epoch — comparability FAILs a candidate whose own
    differs (§1.3)."""
    oos_return = cand.get("oosReturn")
    fold_returns = cand.get("foldReturns")
    trades = cand.get("oosTradeCount")
    max_dd = cand.get("maxDrawdown")
    regime_min = cand.get("regimeOosMin")
    regime_mean = cand.get("regimeOosMean")
    covered = len(cand.get("regimesCovered") or [])

    return [
        _gate("evidence_floor", None if trades is None else trades >= _EVIDENCE_FLOOR_TRADES,
              trades, unknown=trades is None),
        _gate("oos_sign", None if oos_return is None else oos_return > 0, oos_return,
              unknown=oos_return is None),
        _deflated_sharpe_gate(cand.get("sharpe"), trades, n_trials),
        _fold_consistency_gate(fold_returns),
        _gate("drawdown_cap", None if max_dd is None else max_dd <= _DRAWDOWN_CAP_PCT, max_dd,
              unknown=max_dd is None,
              note="p95(maxDD) needs Monte Carlo (E2); value is the worst OOS-fold drawdown proxy"),
        _regime_floor_gate(regime_min, regime_mean, covered),
        _stability_floor_gate(stab),
        {"id": "holdout", "status": SKIPPED, "value": None,
         "note": "no holdout run linked to a historical sweep trial (retro)"},
        _comparability_gate(
            cand.get("engineSha"), cand.get("dataHash"), comparator_sha, comparator_epoch
        ),
        _live_gap_gate(cand.get("reconciliation")),
    ]


def _deflated_sharpe_gate(
    sharpe: Any, trades: Any, n_trials: int
) -> dict[str, Any]:
    """§4 deflated-Sharpe multiplicity gate — the formal data-snooping correction: the engine
    charges itself for every trial it ran. ``deflatedSharpe = (S_oos − S₀(N)) / se(S_oos)``, gate
    ``> 0``, where:

      * S₀(N) = se(S_oos) · √(2·ln N) — the multiplicity haircut. √(2·ln N) is the leading term of
        E[max of N i.i.d. standard-normal draws] (the expected best-of-N under pure noise); scaling
        it by the Sharpe standard error puts the haircut into Sharpe units (design §4: "expected-max
        -of-N adjustment … scaled appropriately"). This is the DSR of Bailey & López de Prado (2014)
        with the standard-normal expected-max in place of their exact percentile form.
      * se(S_oos) = √((1 + 0.5·S²) / (T − 1)) — the Sharpe-ratio standard error under IID-normal
        returns (Lo 2002; the Bailey-LdP DSR form with skew=0, excess-kurtosis=0). T = the OOS trade
        count (each trade is one return observation — the only observation count the retro metric
        bag carries). Higher-moment terms (skew/kurtosis) are unavailable retroactively → omitted;
        self-flagged in the receipt. On folded runs S is the MEAN of per-fold OOS Sharpes while T
        pools trades ACROSS folds — a conscious, slightly lenient approximation (the pooled T
        overstates the observations behind the averaged S, shrinking se), accepted until per-fold
        return series are carried.

    Because S₀(N) = se·√(2·ln N), the ratio reduces to ``S/se − √(2·ln N)`` — a Sharpe t-statistic
    measured against the best-of-N-noise bar; the gate direction depends only on the numerator sign,
    but the value is reported as the full standardized statistic. N is clamped to ≥ 1 (N=1 ⇒ ln 1=0
    ⇒ no multiplicity haircut, so a lone untried candidate is gated on S > 0 alone). Sharpe is
    direction-agnostic (higher is always better), so no maximize/minimize handling. Degrades, never
    fabricates: absent sharpe or < 2 trade observations ⇒ SKIPPED (a Sharpe SE needs T ≥ 2)."""
    s = _num(sharpe)
    t = _num(trades)
    if s is None or t is None or t < 2:
        return {"id": "deflated_sharpe", "status": SKIPPED, "value": None,
                "note": "no sharpe or < 2 trade observations — Sharpe standard error unassessable"}
    n = max(int(n_trials), 1)
    se = math.sqrt((1 + 0.5 * s * s) / (t - 1))
    s0 = se * math.sqrt(2 * math.log(n))
    deflated = (s - s0) / se
    return {"id": "deflated_sharpe", "status": PASS if deflated > 0 else FAIL,
            "value": _round(deflated),
            "note": f"N={n} trials; S={_round(s)}, se={_round(se)}, S0={_round(s0)} "
                    f"(deflatedSharpe=(S-S0)/se, gate >0)"}


def _comparability_gate(
    engine_sha: str | None, data_hash: str | None,
    comparator_sha: str | None, comparator_epoch: str | None,
) -> dict[str, Any]:
    """§6.1 / §1.3 "refuse to compare across differing engine SHA / data epoch" (audit PF-01 #4 —
    was "any non-null SHA PASSes", which never actually compared). The comparator = the cohort's
    representative (engine SHA, data epoch) — the first non-null of each, the same source the
    recorder lifts for the generation. The candidate PASSes ONLY when its SHA AND its data epoch
    MATCH the comparator:
      * NULL engine SHA (run predates #703 stamping) → UNKNOWN (not establishable — blocks a
        REQUIRED gate, never fabricates a match);
      * engine SHA ≠ comparator SHA                 → FAIL (cross-SHA comparison refused);
      * a comparator data epoch exists and the candidate's differs / is absent → FAIL (cross-epoch);
      * SHA present + matches, and the epoch matches (or no comparator epoch to check) → PASS."""
    if engine_sha is None:
        return {"id": "comparability", "status": UNKNOWN, "value": None,
                "note": "NULL engine SHA (run predates #703 stamping) — not establishable"}
    if comparator_sha is not None and engine_sha != comparator_sha:
        return {"id": "comparability", "status": FAIL, "value": engine_sha,
                "note": f"engine SHA {engine_sha} ≠ comparator {comparator_sha} — §1.3 refuses a "
                        "cross-SHA comparison (the stale side is re-queued, never ranked together)"}
    if comparator_epoch is not None and data_hash != comparator_epoch:
        return {"id": "comparability", "status": FAIL, "value": data_hash,
                "note": f"data epoch {data_hash} ≠ comparator {comparator_epoch} — §1.3 refuses a "
                        "cross-data-epoch comparison"}
    return {"id": "comparability", "status": PASS, "value": engine_sha}


def _live_gap_gate(recon: Any) -> dict[str, Any]:
    """§7.2 live-gap hard gate (E3, §12 item 10), from the candidate's attached reconciliation row.
    The GATE keys off the persisted ``verdict`` — the reconciliation computer's authoritative §7.2
    classification (single source of truth; scoring never re-derives the −1.5 / evidence-floor
    thresholds — the COMPONENT ``_live_alignment_value`` carries the proportional gapZ signal):
      * no row / verdict INSUFFICIENT (no live, no fold-return σ, or floor unmet) → SKIPPED (the
        pre-item-10 degradation is preserved);
      * whitelisted scalper backtest-vs-live (§7.2 final para)                → SKIPPED (stamped);
      * verdict DIVERGENT (gapZ ≤ −1.5 AND ≥20 paired trades or ≥4 weeks)         → FAIL (promotion
        blocked; the §7.2.1-5 diagnosis rides the card's ``liveGap.diagnosis``);
      * verdict ALIGNED / PENALIZED                                              → PASS (not
        divergent — a PENALIZED gapZ is still penalized proportionally in the component)."""
    if not isinstance(recon, dict):
        return {"id": "live_gap", "status": SKIPPED, "value": None,
                "note": "no live evidence on a sim sweep (§7 applies once a candidate is live)"}
    gap_z = _round(_num(recon.get("gapZ")))
    if _is_scalper_whitelisted(recon):
        return {"id": "live_gap", "status": SKIPPED, "value": gap_z,
                "note": "§7.2 whitelist — scalper raw backtest-vs-live is a STRUCTURAL divergence "
                        "(judge on shadow-vs-paper); stamped, not gated"}
    verdict = recon.get("verdict")
    if verdict == "DIVERGENT":
        return {"id": "live_gap", "status": FAIL, "value": gap_z,
                "note": f"§7.2 DIVERGENT — gapZ={gap_z} ≤ −1.5 with the evidence floor met "
                        f"({recon.get('pairedTrades')} paired trades); promotion blocked — "
                        "see liveGap.diagnosis"}
    if verdict in ("ALIGNED", "PENALIZED"):
        return {"id": "live_gap", "status": PASS, "value": gap_z,
                "note": f"§7.2 {verdict} — gapZ={gap_z} > −1.5 (not divergent)"}
    # INSUFFICIENT (or, defensively, a verdict outside the V012 CHECK set): not gate-eligible.
    return {"id": "live_gap", "status": SKIPPED, "value": gap_z,
            "note": "§7.2 INSUFFICIENT — no live evidence, no fold-return σ, or the evidence floor "
                    "is unmet (<20 paired trades AND <4 weeks); not gate-eligible"}


def _gate(
    gate_id: str, passed: bool | None, value: Any, *, unknown: bool = False, note: str | None = None
) -> dict[str, Any]:
    status = UNKNOWN if unknown or passed is None else (PASS if passed else FAIL)
    out = {"id": gate_id, "status": status, "value": value}
    if note:
        out["note"] = note
    return out


def _fold_consistency_gate(fold_returns: list[float] | None) -> dict[str, Any]:
    if not fold_returns:
        return {"id": "fold_consistency", "status": UNKNOWN, "value": None,
                "note": "no per-fold OOS returns (full-window run, no walk-forward structure)"}
    positive = sum(1 for r in fold_returns if r > 0) / len(fold_returns)
    return _gate("fold_consistency", positive >= _FOLD_CONSISTENCY_MIN, _round(positive))


def _regime_floor_gate(
    regime_min: float | None, regime_mean: float | None, covered: int
) -> dict[str, Any]:
    if regime_min is None or regime_mean is None:
        # regimesCovered empty on real folds until the vocabulary fix (#705) — a data artifact on
        # historical runs, so UNKNOWN (not FAIL): the gate can't be evaluated, it didn't fail.
        return {"id": "regime_floor", "status": UNKNOWN, "value": None,
                "note": "no per-regime OOS aggregates (regimesCovered empty — pre-#705 runs)"}
    passed = regime_min >= _REGIME_MIN_RATIO * regime_mean and covered >= _REGIME_MIN_COVERED
    return {"id": "regime_floor", "status": PASS if passed else FAIL, "value": _round(regime_min),
            "note": f"{covered} of 4 regimes covered"}


def _stability_floor_gate(stab: dict[str, Any]) -> dict[str, Any]:
    """§6.1 stability floor via the MULTIPLICATION form ``plateau ≥ 0.8 × raw`` in maximize space
    (never the ratio — ratio ≥ 0.8 inverts for raw ≤ 0 / minimize sweeps; see _stability_inputs).
    ``value`` shows the familiar ratio when raw > 0, else the signed margin (named in the note)."""
    neighbors = stab["neighbors"]
    value = stab["ratio"] if stab["ratio"] is not None else stab["margin"]
    form = "ratio" if stab["ratio"] is not None else "margin (raw ≤ 0 — ratio undefined)"
    if neighbors < _STABILITY_MIN_NEIGHBORS:
        return {"id": "stability_floor", "status": SKIPPED, "value": _round(value),
                "note": f"only {neighbors} neighbors (<{_STABILITY_MIN_NEIGHBORS}) — plateau "
                        "under-determined, not assessable"}
    plat, raw = stab["plat"], stab["raw"]
    passed = plat is not None and raw is not None and plat >= _STABILITY_PLATEAU_RATIO * raw
    return {"id": "stability_floor", "status": PASS if passed else FAIL,
            "value": _round(value), "note": f"{neighbors} neighbors; value={form}"}


# --- Penalties, flags, caveats (§6.2 / §6.3) --------------------------------------------------

def _penalties(cand: dict[str, Any], n_params: int) -> dict[str, float]:
    """The §6.2 subtractive penalties. DOF penalty (E2, design §12 item 6): 0.03 per tuned param
    over 4 + 0.06 per structure gate — charges the candidate for every knob it spends. The caveat
    penalty: 0.05 per unresolved DATA caveat on the run (``metrics.caveats[]``) + 0.05 if
    oiGateCoverage < 80% (parsed from its "42/45" label). The structural E2 component caveats
    (no-stress-runs etc.) are NOT charged here — they are the same for every candidate, so charging
    them would be a meaningless constant offset."""
    data_caveats = len(cand.get("caveats") or [])
    oi = 1 if _oi_below_floor(cand.get("oiGateCoverage")) else 0
    return {
        "dof": _round(_dof_penalty(n_params, cand)),
        "caveats": _round(_CAVEAT_UNIT_PENALTY * (data_caveats + oi)),
    }


def _dof_penalty(n_params: int, cand: dict[str, Any]) -> float:
    """§6.2 DOF penalty: 0.03 per active (tuned) param beyond the free 4 + 0.06 per structure gate.
    activeParams = the sweep's tuned-parameter count (``len(parameters)``, the authoritative search
    dimensionality — SAME source as _explainability's activeDOF term, so the two can never drift);
    structureGates = 0 until structure mutations exist (E5)."""
    over = max(0, n_params - _DOF_FREE_PARAMS)
    return _DOF_PARAM_PENALTY * over + _DOF_STRUCTURE_PENALTY * _structure_gates(cand)


def _structure_gates(cand: dict[str, Any]) -> int:
    """A candidate's structure-gate count (``structureGateCount``): 0 for a PARAMS candidate, > 0
    only once structure mutations exist (E5). The ONE reader of the field — shared by the DOF
    penalty (0.06 each) and the explainability activeDOF term — so they cannot drift."""
    return int(cand.get("structureGateCount") or 0)


def _flags(cand: dict[str, Any]) -> list[str]:
    """§4: a candidate that wins in too few regimes is REGIME_DEPENDENT (rankable but flagged). Only
    raised when regime evidence exists (covered > 0) but is thin (< 3 of 4 labels)."""
    covered = cand.get("regimesCovered") or []
    if 0 < len(covered) < _REGIME_MIN_COVERED:
        return [f"REGIME_DEPENDENT:{'/'.join(covered)}"]
    return []


def _caveats(
    cand: dict[str, Any], comp_caveats: dict[str, str | None], cohort_has_stress: bool = False,
    cohort_has_live: bool = False,
) -> list[str]:
    """Scorecard-level caveats = the run's data caveats (metrics.caveats[]) + the structural E2
    component caveats + the §7.2 per-candidate live-gap notes + the standing retro degradations,
    de-duplicated in a stable order so the FE card can render "what to distrust" honestly."""
    out: list[str] = []
    for c in cand.get("caveats") or []:
        out.append(c)
    if _oi_below_floor(cand.get("oiGateCoverage")):
        out.append(f"oiGateCoverage {cand['oiGateCoverage']} below 80%")
    # A stress round ran on the cohort but THIS candidate has no cost_resilience — its z drops out
    # (0), so say why per-candidate: it was either not in the top-K, or stressed but < 2 runs came
    # back ("stress incomplete", set by the orchestrator). The uniform "no stress runs (E2)" note
    # fires only when NO candidate was stressed (comp_caveats), so the two are mutually exclusive.
    if cohort_has_stress and _num(cand.get("costResilience")) is None:
        if cand.get("stressIncomplete"):
            out.append("cost_resilience: stress incomplete — < 2 usable points, z drops out (0)")
        else:
            out.append("cost_resilience: not stress-tested this round — z drops out (0)")
    out.extend(_live_caveats(cand, cohort_has_live))
    for comp, note in comp_caveats.items():
        if note:
            out.append(f"{comp}: {note}")
    out.append("efficiency: turnover/tradeFrequency absent — MetricsCalculator adds land in E1/E2")
    out.append("explainability: entry-side only — exit-reason attribution needs audit P1-3")
    out.append("penalties.dof charges tuned params over 4; structure-gate DOF is 0 until E5")
    if cand.get("foldless"):
        out.append("full-window run (no OOS fold structure) — components read on run-level metrics")
    return _dedupe(out)


def _live_caveats(cand: dict[str, Any], cohort_has_live: bool) -> list[str]:
    """Per-candidate live_alignment / live_gap caveats (§7.2, item 10), mirroring cost_resilience's
    per-candidate pattern. A whitelisted scalper or a PENALIZED / DIVERGENT verdict is stamped
    explicitly. A candidate with NO countable reconciliation gets the "z drops out" note ONLY when
    OTHERS in the cohort ARE live (``cohort_has_live``) — otherwise the standing "no live evidence
    yet" component caveat already covers the whole sim-only cohort, and adding a per-candidate note
    would be redundant noise."""
    recon = cand.get("reconciliation")
    if isinstance(recon, dict) and _is_scalper_whitelisted(recon):
        return [
            "live_alignment: scalper raw backtest-vs-live is §7.2-whitelisted (structural "
            "divergence) — not counted; judge on shadow-vs-paper"
        ]
    if _counts_toward_live(recon):
        verdict, gap_z = recon.get("verdict"), _round(_num(recon.get("gapZ")))
        if verdict == "PENALIZED":
            return [f"live_alignment: §7.2 PENALIZED (gapZ={gap_z}) — live-gap penalty applied "
                    "proportionally to the component"]
        if verdict == "DIVERGENT":
            return [f"live_gap: §7.2 DIVERGENT (gapZ={gap_z}) — hard-gate FAIL, promotion blocked; "
                    "see liveGap.diagnosis"]
        return []  # ALIGNED — no caveat (rewarded, nothing to distrust)
    # No countable reconciliation for THIS candidate — noted only when the cohort is elsewhere live.
    if cohort_has_live:
        if isinstance(recon, dict):
            return ["live_alignment: reconciliation INSUFFICIENT (no live / no fold σ / floor "
                    "unmet) — z drops out (0)"]
        return ["live_alignment: no live reconciliation for this candidate — z drops out (0)"]
    return []


def _live_gap_card(recon: Any) -> dict[str, Any] | None:
    """The §6.3 card's live-gap panel (design §7.2 / §10 "live-gap tile"): the reconciliation
    verdict + gapZ + lived window + evidence-floor state, whether it was COUNTED into the score
    (False for a whitelisted scalper or an INSUFFICIENT verdict), and — ONLY for a DIVERGENT verdict
    — the §7.2.1-5 diagnosis checklist the reconciliation computer already assembled (reused
    verbatim, never re-fabricated). None when the candidate carries no reconciliation (a sim-only
    cohort — the card omits the panel)."""
    if not isinstance(recon, dict):
        return None
    gap = recon.get("gap") if isinstance(recon.get("gap"), dict) else {}
    card: dict[str, Any] = {
        "verdict": recon.get("verdict"),
        "gapZ": _round(_num(recon.get("gapZ"))),
        "windowFrom": recon.get("windowFrom"),
        "windowTo": recon.get("windowTo"),
        "pairedTrades": recon.get("pairedTrades"),
        "evidenceFloorMet": recon.get("evidenceFloorMet"),
        "counted": _counts_toward_live(recon),
        "whitelisted": _is_scalper_whitelisted(recon),
        "mode": gap.get("mode"),
        "reconciliationId": recon.get("id"),
    }
    # The §7.2.1-5 checklist attaches only when the DIVERGENT actually hard-gates (a whitelisted
    # scalper is SKIPPED, not failed — its structural divergence is not promotion-blocking).
    if (recon.get("verdict") == "DIVERGENT" and not _is_scalper_whitelisted(recon)
            and recon.get("diagnosis") is not None):
        card["diagnosis"] = recon.get("diagnosis")  # the checklist, verbatim
    return card


def _live_window(recon: Any) -> dict[str, Any] | None:
    """The reconciliation's lived window ``{from, to}`` for the §6.3 evidence chain — None on a
    sim-only candidate (no reconciliation attached)."""
    if not isinstance(recon, dict):
        return None
    return {"from": recon.get("windowFrom"), "to": recon.get("windowTo")}


# --- ranking + small numeric helpers ----------------------------------------------------------

def _assign_ranks(scorecards: list[dict[str, Any]]) -> None:
    """Rank the RANKABLE candidates by RobustScore desc (ties broken by trialNumber for stability);
    non-rankable candidates keep rank=None (a FAILed hard gate is out of the ranking, not last)."""
    rankable = sorted(
        (s for s in scorecards if s["rankable"]),
        key=lambda s: (-s["robustScore"], _sort_key(s["trialNumber"])),
    )
    for position, card in enumerate(rankable, start=1):
        card["rank"] = position


def _plateau(
    candidates: list[dict[str, Any]], parameters: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Reuse the leaderboard's plateau/neighbor computation on the sweep's own objective — the
    stability signal (§6.2) and the stability_floor gate both read it, so it is computed once."""
    trials = [
        {"params": c.get("params") or {}, "objective": _num(c.get("rawObjective")) or 0.0}
        for c in candidates
    ]
    return leaderboard.plateau_scores(trials, parameters)


def _explainability(cand: dict[str, Any], n_params: int) -> float:
    """The only §6.2 explainability term computable on retro data: 0.3·(1 − activeDOF/12). activeDOF
    = the sweep's tuned-param count (``n_params`` = len(parameters)) + structure gates — the SAME
    two inputs the DOF penalty spends, so the penalty and this term can never drift. The
    entry/exit-trace and gate-operand terms need per-trade completeness (audit P1-3) not available
    here — they are noted as a caveat, not fabricated."""
    active_dof = n_params + _structure_gates(cand)
    return 0.3 * (1 - min(active_dof, 12) / 12)


def _oi_below_floor(label: str | None) -> bool:
    frac = _parse_fraction(label)
    return frac is not None and frac < _OI_COVERAGE_FLOOR


def _parse_fraction(label: str | None) -> float | None:
    """Parse an oiGateCoverage "42/45" label to a fraction (design §6.2 note); None if unparseable
    or a zero denominator."""
    if not isinstance(label, str) or "/" not in label:
        return None
    num, _, den = label.partition("/")
    try:
        numerator, denominator = float(num), float(den)
    except ValueError:
        return None
    return numerator / denominator if denominator else None


def _ratio(numerator: Any, denominator: Any) -> float | None:
    num, den = _num(numerator), _num(denominator)
    if num is None or den is None or den == 0:
        return None
    return num / den


def _neg(value: Any) -> float | None:
    num = _num(value)
    return None if num is None else -num


def _num(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _round(value: Any) -> Any:
    return round(value, 4) if isinstance(value, int | float) else value


def _sort_key(trial_number: Any) -> tuple[int, Any]:
    return (0, trial_number) if isinstance(trial_number, int) else (1, str(trial_number))


def _dedupe(items: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out
