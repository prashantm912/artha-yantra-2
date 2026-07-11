"""Pure scoring library (design §6): hard gates + RobustScore + §6.3 scorecard assembly.

Side-effect-free and deterministic — no wall-clock, no random, no I/O. The caller
(``RetroScoreService``) assembles the normalized per-candidate metric dicts from a sweep's
trials + backtest-run evidence; this module turns a COHORT of those dicts into §6.3 scorecards,
z-scoring each RobustScore component WITHIN the cohort (the retro stand-in for "normalized within
the campaign", §6.2 — a sweep's trials ARE the cohort). Same cohort in ⇒ byte-identical scorecards
out.

E1 scope (design §12 item 2): the SIM_FIRST hard gates + RobustScore + the recovery/turnover/
frequency metric semantics, applied retroactively. Everything §12 E2 explicitly defers is marked
IN-BAND, never silently dropped:
  * deflated-Sharpe multiplicity gate → a gate entry with status NOT_IMPLEMENTED;
  * DOF penalties → ``penalties.dof = 0.0`` carrying an E2 note;
  * cost-stress re-runs → ``cost_resilience`` z=0 with a "no stress runs (E2)" caveat;
  * importance/brittleness → out of scope.
Missing evidence degrades, never fabricates: no live evidence ⇒ ``live_alignment`` z=0 (§6.2
comment); NULL engine SHA (runs predating #703) ⇒ comparability gate UNKNOWN (not FAIL); no
holdout run linked ⇒ holdout gate SKIPPED. A candidate is "rankable" iff no gate is FAIL —
SKIPPED / UNKNOWN / NOT_IMPLEMENTED never block ranking.

This EXTENDS the ``/best`` guard-aware leaderboard shape (leaderboard.plateau_scores is reused for
the plateau/neighbor stability signal), it does not replace it (design §6.3 / audit §13.11).
"""

from __future__ import annotations

import statistics
from typing import Any

from app import leaderboard

# Gate / component statuses (design §6.1 + the E1 degradation contract).
PASS = "PASS"
FAIL = "FAIL"
SKIPPED = "SKIPPED"
UNKNOWN = "UNKNOWN"
NOT_IMPLEMENTED = "NOT_IMPLEMENTED"

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


def score_cohort(
    candidates: list[dict[str, Any]],
    parameters: list[dict[str, Any]],
    direction: str = "maximize",
    policy: str = "SIM_FIRST",
    weights: dict[str, float] | None = None,
) -> list[dict[str, Any]]:
    """Score a whole cohort (a sweep's trials) → one §6.3 scorecard dict per candidate, in the
    input order. ``parameters`` are the sweep's tunable specs (drive the plateau/neighbor stability
    signal via ``leaderboard.plateau_scores``). ``direction`` is echoed for provenance AND drives
    the single maximize-space normalization of the plateau math (``_stability_inputs``). z-scoring
    is within THIS cohort, so a single-candidate cohort yields all-zero z's (nothing to normalize
    against) — expected, and flagged by the empty spread rather than invented."""
    weights = weights or SIM_FIRST_WEIGHTS
    stab = _stability_inputs(candidates, _plateau(candidates, parameters), direction)

    # Per-component z's (cohort-normalized) + the raw constituents + any structural caveat.
    comp_zs: dict[str, list[float]] = {}
    comp_raws: list[dict[str, dict[str, Any]]] = [dict() for _ in candidates]
    comp_caveats: dict[str, str | None] = {}
    for comp, subsignals in _component_subsignals(candidates, stab).items():
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
        penalties = _penalties(cand)
        robust = _round(weighted - penalties["dof"] - penalties["caveats"])
        gates = _gates(cand, stab[i])
        rankable = all(g["status"] != FAIL for g in gates)
        scorecards.append({
            "trialNumber": cand.get("trialNumber"),
            "runId": cand.get("runId"),
            "params": cand.get("params"),
            "policy": policy,
            "direction": direction,
            "robustScore": robust,
            "rank": None,  # assigned below, over rankable candidates only
            "rankable": rankable,
            "weights": dict(weights),
            "gates": gates,
            "components": components,
            "penalties": penalties,
            "flags": _flags(cand),
            "caveats": _caveats(cand, comp_caveats),
            "evidence": {
                "simRuns": [cand["runId"]] if cand.get("runId") else [],
                "holdoutRun": cand.get("holdoutRunId"),
                "liveWindow": None,
            },
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
    candidates: list[dict[str, Any]], stab: list[dict[str, Any]]
) -> dict[str, list[tuple[str, list[float | None]]]]:
    """Each RobustScore component is built from one or more raw sub-signals; the component z is the
    mean of its present sub-signals' cohort z-scores (a missing sub-signal drops out of the mean, a
    fully-absent component → z=0). ``cost_resilience`` + ``live_alignment`` carry NO sub-signals in
    retro (no stress runs, no live evidence) → they resolve to z=0 by construction (§6.2).
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
        "cost_resilience": [],
        "live_alignment": [],
        "explainability": [("explainability", [_explainability(c) for c in candidates])],
        "efficiency": [("expectancy", [c.get("expectancy") for c in candidates])],
    }


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

def _gates(cand: dict[str, Any], stab: dict[str, Any]) -> list[dict[str, Any]]:
    """The §6.1 SIM_FIRST hard gates as {id, status, value[, note]}. Retro degradations: no holdout
    run → holdout SKIPPED; NULL engine SHA → comparability UNKNOWN; no live evidence → live_gap
    SKIPPED; the deflated-Sharpe multiplicity term is E2 → its own NOT_IMPLEMENTED entry."""
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
        {"id": "deflated_sharpe", "status": NOT_IMPLEMENTED, "value": None,
         "note": "DSR multiplicity gate deferred to E2 (design §12 item 6)"},
        _fold_consistency_gate(fold_returns),
        _gate("drawdown_cap", None if max_dd is None else max_dd <= _DRAWDOWN_CAP_PCT, max_dd,
              unknown=max_dd is None,
              note="p95(maxDD) needs Monte Carlo (E2); value is the worst OOS-fold drawdown proxy"),
        _regime_floor_gate(regime_min, regime_mean, covered),
        _stability_floor_gate(stab),
        {"id": "holdout", "status": SKIPPED, "value": None,
         "note": "no holdout run linked to a historical sweep trial (retro)"},
        _comparability_gate(cand.get("engineSha")),
        {"id": "live_gap", "status": SKIPPED, "value": None,
         "note": "no live evidence on a sim sweep (§7 applies only once a candidate is live)"},
    ]


def _comparability_gate(engine_sha: str | None) -> dict[str, Any]:
    """Same engine SHA + data epoch as the comparator (§6.1). A run predating #703 SHA-stamping has
    a NULL SHA → UNKNOWN (can't be established, didn't fail); a stamped run PASSes (the sweep's own
    trials share the SHA — a fuller cross-comparator check is E3's reconciliation)."""
    if engine_sha is None:
        return {"id": "comparability", "status": UNKNOWN, "value": None,
                "note": "NULL engine SHA (run predates #703 stamping) — not establishable"}
    return {"id": "comparability", "status": PASS, "value": engine_sha}


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

def _penalties(cand: dict[str, Any]) -> dict[str, float]:
    """DOF penalty is E2 (design §12 item 6) → 0.0 with a note carried in the scorecard caveats.
    The caveat penalty (§6.2) IS in E1: 0.05 per unresolved DATA caveat on the run
    (``metrics.caveats[]``) + 0.05 if oiGateCoverage < 80% (parsed from its "42/45" label). The
    structural E2 component caveats (no-stress-runs etc.) are NOT charged here — they are the same
    for every candidate, so charging them would be a meaningless constant offset."""
    data_caveats = len(cand.get("caveats") or [])
    oi = 1 if _oi_below_floor(cand.get("oiGateCoverage")) else 0
    return {"dof": 0.0, "caveats": _round(_CAVEAT_UNIT_PENALTY * (data_caveats + oi))}


def _flags(cand: dict[str, Any]) -> list[str]:
    """§4: a candidate that wins in too few regimes is REGIME_DEPENDENT (rankable but flagged). Only
    raised when regime evidence exists (covered > 0) but is thin (< 3 of 4 labels)."""
    covered = cand.get("regimesCovered") or []
    if 0 < len(covered) < _REGIME_MIN_COVERED:
        return [f"REGIME_DEPENDENT:{'/'.join(covered)}"]
    return []


def _caveats(cand: dict[str, Any], comp_caveats: dict[str, str | None]) -> list[str]:
    """Scorecard-level caveats = the run's data caveats (metrics.caveats[]) + the structural E2
    component caveats + the standing retro degradations, de-duplicated in a stable order so the FE
    card can render "what to distrust" honestly."""
    out: list[str] = []
    for c in cand.get("caveats") or []:
        out.append(c)
    if _oi_below_floor(cand.get("oiGateCoverage")):
        out.append(f"oiGateCoverage {cand['oiGateCoverage']} below 80%")
    for comp, note in comp_caveats.items():
        if note:
            out.append(f"{comp}: {note}")
    out.append("efficiency: turnover/tradeFrequency absent — MetricsCalculator adds land in E1/E2")
    out.append("explainability: entry-side only — exit-reason attribution needs audit P1-3")
    out.append("penalties.dof=0.0 — DOF penalty deferred to E2 (design §12 item 6)")
    if cand.get("foldless"):
        out.append("full-window run (no OOS fold structure) — components read on run-level metrics")
    return _dedupe(out)


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


def _explainability(cand: dict[str, Any]) -> float:
    """The only §6.2 explainability term computable on retro data: 0.3·(1 − activeDOF/12). The
    entry/exit-trace and gate-operand terms need per-trade completeness (audit P1-3) not available
    here — they are noted as a caveat, not fabricated."""
    active_dof = int(cand.get("activeParamCount") or 0) + int(cand.get("structureGateCount") or 0)
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
