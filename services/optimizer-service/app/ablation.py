"""EVO E5 — the pre-registered ablation protocol (design §5.2 / roadmap §12 item 14).

Structure mutations (add/remove a gate) are rarer, costlier, and more overfit-prone than parameter
changes, so they get their OWN stream with stricter rules (design §5). This module is that stream:

  * PRE-REGISTER a structure-mutation hypothesis (``POST /campaigns/{id}/ablations``) — one
    immutable ``evo_ablations`` row carrying the mutation, its canonical ``fingerprint``, and a
    ``pre_registered_at`` stamp (the §3.1 anti-snooping ledger: the hypothesis provably predates its
    measured evidence). Refused (409) if the structure is already in the campaign GRAVEYARD — the
    "never re-proposed silently" guarantee (§8.3).
  * EVALUATE it against PAIRED, IDENTICAL-fold evidence (``POST /ablations/{id}/evaluate``). The
    paired-lift math (``evaluate_ablation``, pure) applies the §5.2 acceptance criteria: OOS lift
    > 0 with paired p < 0.10; lift ≥ 0 in ≥ 2 of the 3 majority regimes; trade retention ≥ 70%;
    survives the DOF penalty. The keystone is the **IS-only-lift auto-reject**: a candidate whose
    lift exists ONLY in-sample (IS lift > 0 but OOS lift ≤ 0) is rejected + RECORDED (institutional
    memory against snooping) with verdict REJECTED_IS_ONLY.
  * On ANY rejection, the structure is BURIED in the graveyard (idempotent) so the gate-candidate
    suggesters (§5.1.2) never re-propose it; a REJECTED_IS_ONLY rejection ALSO emits a REVIEW_GATE
    proposal (the §5.2 "recorded so the same gate isn't re-proposed next quarter" inbox trail).

NOTHING self-arms — an ACCEPTED ablation records its verdict; composing it into a candidate is the
owner-gated proposal path (§5.3 / E4), not this stream.

The paired unit is per-fold OOS lift = candidate − parent, index-aligned on the SAME folds — the
caller (an orchestrator or a test) supplies the two measured fold series; pre-registration proved
the hypothesis predated them. Raw profit is never a component; the lift math is on OOS fold returns
only (design §6.2).
"""

from __future__ import annotations

import hashlib
import json
import math
import statistics
from collections.abc import Callable
from typing import Any

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app.errors import ApiError

router = APIRouter(prefix="/api/v1/evolution")

# §5.2 acceptance thresholds.
_PAIRED_P_MAX = 0.10          # OOS lift significance (one-sided paired t, H1: mean lift > 0)
_TRADE_RETENTION_MIN = 0.70   # a gate that deletes ≥30% of the sample "wins" by starvation — reject
_REGIME_TOP_N = 3             # the "3 majority regime labels" the lift must generalize across
_REGIME_MIN_NONNEG = 2        # lift ≥ 0 in ≥ 2 of them (not UP_QUIET-only)

# The verdict vocabulary (mirrors the V017 evo_ablations.verdict CHECK).
_ACCEPTED = "ACCEPTED"
_REJECTED_IS_ONLY = "REJECTED_IS_ONLY"
_REJECTED_NO_LIFT = "REJECTED_NO_LIFT"
_REJECTED_TRADE_STARVATION = "REJECTED_TRADE_STARVATION"
_REJECTED_REGIME = "REJECTED_REGIME"
_REJECTED_DOF = "REJECTED_DOF"

_REVIEW_GATE = "REVIEW_GATE"


# --- structure fingerprint ----------------------------------------------------------------------


def structure_fingerprint(mutation: dict[str, Any]) -> str:
    """The canonical fingerprint of a structure mutation — the graveyard / re-proposal dedup key.
    A sha256 over the mutation's CANONICAL JSON (keys sorted, no whitespace), so two logically
    identical mutations (whoever proposed them) fingerprint identically. ``mutation`` MUST be the
    pure IDENTITY payload (op + gate identity); volatile metadata (source suggester, human text,
    evidence) is carried OUTSIDE it so it never perturbs identity."""
    canonical = json.dumps(mutation, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


# --- Student-t paired significance (pure, deterministic; no scipy dependency) --------------------


def _betacf(a: float, b: float, x: float) -> float:
    """The continued fraction for the incomplete beta function (Numerical Recipes ``betacf``).
    Deterministic double-precision — the only non-trivial numeric here, needed because
    ``statistics`` ships no Student-t and scipy is not a dependency."""
    max_iter = 200
    eps = 3.0e-16
    fpmin = 1.0e-300
    qab = a + b
    qap = a + 1.0
    qam = a - 1.0
    c = 1.0
    d = 1.0 - qab * x / qap
    if abs(d) < fpmin:
        d = fpmin
    d = 1.0 / d
    h = d
    for m in range(1, max_iter + 1):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        if abs(d) < fpmin:
            d = fpmin
        c = 1.0 + aa / c
        if abs(c) < fpmin:
            c = fpmin
        d = 1.0 / d
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        if abs(d) < fpmin:
            d = fpmin
        c = 1.0 + aa / c
        if abs(c) < fpmin:
            c = fpmin
        d = 1.0 / d
        step = d * c
        h *= step
        if abs(step - 1.0) < eps:
            break
    return h


def _betai(a: float, b: float, x: float) -> float:
    """The regularized incomplete beta function I_x(a, b) — the tail integral Student-t uses."""
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    ln_beta = math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b)
    front = math.exp(ln_beta + a * math.log(x) + b * math.log(1.0 - x))
    if x < (a + 1.0) / (a + b + 2.0):
        return front * _betacf(a, b, x) / a
    return 1.0 - front * _betacf(b, a, 1.0 - x) / b


def _t_survival(t: float, df: float) -> float:
    """P(T > t) for a Student-t with ``df`` degrees of freedom. Symmetric about 0: the tail past
    ``|t|`` is 0.5·I_x(df/2, 1/2) with x = df/(df + t²)."""
    x = df / (df + t * t)
    half_tail = 0.5 * _betai(df / 2.0, 0.5, x)
    return half_tail if t >= 0 else 1.0 - half_tail


def paired_p_value(diffs: list[float]) -> float | None:
    """One-sided paired-t p-value for H1: mean(diffs) > 0 (the §5.2 "paired p" of the OOS lift).
    ``None`` when < 2 paired folds (no dispersion to test — the caller treats an unestablished lift
    as NOT significant). A zero-variance sample is a degenerate limit: all-positive → p 0, all-equal
    zero → 0.5, all-negative → p 1."""
    n = len(diffs)
    if n < 2:
        return None
    mean = statistics.fmean(diffs)
    sd = statistics.stdev(diffs)  # sample stdev (ddof=1)
    if sd == 0.0:
        if mean > 0:
            return 0.0
        if mean < 0:
            return 1.0
        return 0.5
    t = mean / (sd / math.sqrt(n))
    return _t_survival(t, n - 1)


# --- paired-evaluation gates (pure) -------------------------------------------------------------


def _lift_gate(oos_lift: float | None, paired_p: float | None) -> dict[str, Any]:
    """The §5.2 OOS-lift gate: PASS iff lift > 0 AND paired p < 0.10. A positive-but-insignificant
    lift (or one over < 2 folds → p undefined) FAILs — an unestablished lift is not evidence."""
    if oos_lift is not None and oos_lift > 0 and paired_p is not None and paired_p < _PAIRED_P_MAX:
        return {"id": "oos_lift", "status": "PASS", "value": _round(oos_lift),
                "pairedP": _round(paired_p)}
    note = "OOS lift ≤ 0" if (oos_lift is None or oos_lift <= 0) else (
        "paired p ≥ 0.10 (lift not significant)" if paired_p is not None
        else "< 2 paired folds — lift significance is undefined"
    )
    return {"id": "oos_lift", "status": "FAIL", "value": _round(oos_lift),
            "pairedP": _round(paired_p), "note": note}


def _regime_gate(
    parent_regimes: dict[str, list[float]] | None,
    candidate_regimes: dict[str, list[float]] | None,
) -> tuple[dict[str, Any], dict[str, float]]:
    """The §5.2 regime-generalization gate: lift ≥ 0 in ≥ 2 of the 3 majority regime labels (the
    labels with the most folds), so the lift is not UP_QUIET-only. Per-regime lift = mean(candidate)
    − mean(parent) over the regime's folds. SKIPPED (never a fabricated pass/fail) when fewer than 2
    regime labels carry evidence on both sides. Returns (gate, per-regime lift map)."""
    parent_regimes = parent_regimes or {}
    candidate_regimes = candidate_regimes or {}
    shared = [
        label for label in parent_regimes
        if label in candidate_regimes and parent_regimes[label] and candidate_regimes[label]
    ]
    regime_lift = {
        label: statistics.fmean(candidate_regimes[label]) - statistics.fmean(parent_regimes[label])
        for label in shared
    }
    if len(shared) < _REGIME_MIN_NONNEG:
        return (
            {"id": "regime_generalization", "status": "SKIPPED", "value": None,
             "note": f"< {_REGIME_MIN_NONNEG} regime labels with paired evidence — not evaluated"},
            {k: _round(v) for k, v in regime_lift.items()},
        )
    # "majority regime labels" = the labels with the most folds (parent side), top-N.
    majority = sorted(
        shared, key=lambda label: len(parent_regimes[label]), reverse=True
    )[:_REGIME_TOP_N]
    required = min(_REGIME_MIN_NONNEG, len(majority))
    nonneg = [label for label in majority if regime_lift[label] >= 0]
    passed = len(nonneg) >= required
    return (
        {
            "id": "regime_generalization",
            "status": "PASS" if passed else "FAIL",
            "value": {"majority": majority, "nonNegative": nonneg, "required": required},
        },
        {k: _round(v) for k, v in regime_lift.items()},
    )


def _trade_retention_gate(
    parent_trades: int | None, candidate_trades: int | None
) -> tuple[dict[str, Any], float | None]:
    """The §5.2 trade-retention gate: the gated candidate must keep ≥ 70 % of the parent's trades —
    a gate that "wins" by deleting the sample is starvation, not edge. SKIPPED (with a caveat) when
    either count is absent, or the parent traded 0 (retention undefined). Returns (gate, ratio)."""
    if parent_trades is None or candidate_trades is None:
        return (
            {"id": "trade_retention", "status": "SKIPPED", "value": None,
             "note": "parent/candidate trade counts not both supplied — not evaluated"},
            None,
        )
    if parent_trades <= 0:
        return (
            {"id": "trade_retention", "status": "SKIPPED", "value": None,
             "note": "parent traded 0 — retention ratio undefined"},
            None,
        )
    ratio = candidate_trades / parent_trades
    return (
        {"id": "trade_retention",
         "status": "PASS" if ratio >= _TRADE_RETENTION_MIN else "FAIL",
         "value": _round(ratio)},
        ratio,
    )


def _dof_gate(parent_score: float | None, candidate_score: float | None) -> dict[str, Any]:
    """The §5.2 / §6.2 DOF gate: the added structure gate costs 0.06 RobustScore (DOF penalty), so
    the gated candidate's net RobustScore must still be ≥ the parent's ("the robustness gain pays
    for the added knob"). The scores passed in are already net of penalties (scoring.py computes
    them). SKIPPED (with a caveat — never a fabricated pass) when either score is absent; a SKIPPED
    DOF does NOT block ACCEPT (the other four gates are authoritative), matching the house
    degrade-honest doctrine."""
    if parent_score is None or candidate_score is None:
        return {"id": "dof", "status": "SKIPPED", "value": None,
                "note": "parent/candidate RobustScore not both supplied — DOF gate not verified"}
    return {"id": "dof",
            "status": "PASS" if candidate_score >= parent_score else "FAIL",
            "value": _round(candidate_score - parent_score)}


def evaluate_ablation(
    parent: dict[str, Any], candidate: dict[str, Any]
) -> tuple[str, dict[str, Any]]:
    """The §5.2 paired ablation evaluation (pure). Both sides carry index-aligned per-fold OOS
    returns over the SAME folds; the lift is candidate − parent, paired. Applies all five §5.2
    criteria and returns (verdict, evaluation). Verdict priority is deterministic:

      1. IS-only (IS lift > 0 but OOS lift ≤ 0)   → REJECTED_IS_ONLY  (the anti-snooping keystone)
      2. OOS-lift gate FAIL                        → REJECTED_NO_LIFT
      3. trade-retention gate FAIL                 → REJECTED_TRADE_STARVATION
      4. regime-generalization gate FAIL           → REJECTED_REGIME
      5. DOF gate FAIL                             → REJECTED_DOF
      else                                         → ACCEPTED

    A SKIPPED gate (absent input) never blocks ACCEPT and never fabricates a pass — it is recorded
    with its reason (degrade-honest)."""
    p_oos = [float(v) for v in parent["oosFoldReturns"]]
    c_oos = [float(v) for v in candidate["oosFoldReturns"]]
    oos_diffs = [c - p for p, c in zip(p_oos, c_oos, strict=True)]
    oos_lift = statistics.fmean(oos_diffs) if oos_diffs else None
    paired_p = paired_p_value(oos_diffs)

    is_lift = _paired_is_lift(parent.get("isFoldReturns"), candidate.get("isFoldReturns"))
    is_only = is_lift is not None and is_lift > 0 and (oos_lift is None or oos_lift <= 0)

    lift = _lift_gate(oos_lift, paired_p)
    trade, retention = _trade_retention_gate(parent.get("tradeCount"), candidate.get("tradeCount"))
    regime, regime_lift = _regime_gate(
        parent.get("regimeFoldReturns"), candidate.get("regimeFoldReturns")
    )
    dof = _dof_gate(parent.get("robustScore"), candidate.get("robustScore"))
    gates = [lift, trade, regime, dof]

    if is_only:
        verdict = _REJECTED_IS_ONLY
    elif lift["status"] == "FAIL":
        verdict = _REJECTED_NO_LIFT
    elif trade["status"] == "FAIL":
        verdict = _REJECTED_TRADE_STARVATION
    elif regime["status"] == "FAIL":
        verdict = _REJECTED_REGIME
    elif dof["status"] == "FAIL":
        verdict = _REJECTED_DOF
    else:
        verdict = _ACCEPTED

    caveats = [g["note"] for g in gates if g.get("status") == "SKIPPED" and g.get("note")]
    evaluation = {
        "verdict": verdict,
        "oosLift": _round(oos_lift),
        "pairedP": _round(paired_p),
        "perFoldLift": [_round(d) for d in oos_diffs],
        "foldCount": len(oos_diffs),
        "isLift": _round(is_lift),
        "isOnly": is_only,
        "regimeLift": regime_lift,
        "tradeRetention": _round(retention),
        "gates": gates,
        "caveats": caveats,
    }
    return verdict, evaluation


def _paired_is_lift(
    parent_is: list[float] | None, candidate_is: list[float] | None
) -> float | None:
    """The mean IN-SAMPLE (train-fold) lift, paired index-aligned — the numerator of the IS-only
    auto-reject. ``None`` when either side lacks IS returns, or the lengths differ (unpaired)."""
    if not parent_is or not candidate_is or len(parent_is) != len(candidate_is):
        return None
    is_diffs = [float(c) - float(p) for p, c in zip(parent_is, candidate_is, strict=True)]
    return statistics.fmean(is_diffs) if is_diffs else None


# --- typed response models ----------------------------------------------------------------------


class AblationModel(BaseModel):
    """One ``evo_ablations`` row (V017) — the pre-registered structure-mutation hypothesis + (once
    evaluated) its verdict/evaluation."""

    id: str
    campaignId: str
    parentCandidateId: str | None = None
    mutation: dict[str, Any] | None = None
    fingerprint: str
    status: str
    verdict: str | None = None
    evaluation: dict[str, Any] | None = None
    createdBy: str | None = None
    preRegisteredAt: str | None = None
    evaluatedAt: str | None = None


class AblationListResponse(BaseModel):
    items: list[AblationModel]


class EvaluatedAblation(AblationModel):
    """The evaluate response: the now-EVALUATED ablation plus what the verdict triggered — whether
    a NEW grave was written (idempotent), and the REVIEW_GATE proposal id minted for a
    REJECTED_IS_ONLY (the §5.2 institutional-memory trail)."""

    buried: bool = False
    reviewGateProposalId: str | None = None
    note: str


class GraveyardModel(BaseModel):
    """One ``evo_graveyard`` row (V017) — a buried structure. Its ``fingerprint`` is what the
    suggesters check to never re-propose it (§8.3)."""

    id: str
    campaignId: str
    ablationId: str | None = None
    fingerprint: str
    mutation: dict[str, Any] | None = None
    verdict: str
    evidence: dict[str, Any] | None = None
    buriedAt: str | None = None


class GraveyardListResponse(BaseModel):
    items: list[GraveyardModel]


# --- service ------------------------------------------------------------------------------------


class AblationService:
    """The structure-mutation stream (design §5.2). Collaborators injected (a StructureRepo factory)
    so tests drive it with an in-memory fake. optimizer-service is the evo schema's sole writer."""

    def __init__(self, repo_factory: Callable[[], Any]) -> None:
        self._repo_factory = repo_factory

    def register(self, campaign_id: str, body: dict[str, Any]) -> AblationModel:
        """Pre-register a structure-mutation hypothesis. 400 missing/invalid mutation; 404 unknown
        campaign / parent candidate; 409 the structure is graveyarded (never re-proposed silently,
        §8.3) OR already pre-registered for this (campaign, parent). The mutation is the pure
        IDENTITY payload — its fingerprint is the graveyard/dedup key."""
        mutation = body.get("mutation")
        if not isinstance(mutation, dict) or not mutation:
            raise ApiError(400, "VALIDATION_FAILED", "missing/invalid required field: mutation")
        parent_candidate_id = body.get("parentCandidateId")
        fingerprint = structure_fingerprint(mutation)

        repo = self._repo_factory()
        try:
            if repo.get_campaign(campaign_id) is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            if parent_candidate_id and not repo.candidate_exists(parent_candidate_id):
                raise ApiError(
                    404, "NOT_FOUND_CANDIDATE", f"no candidate {parent_candidate_id}"
                )
            if repo.is_graveyarded(campaign_id, fingerprint):
                raise ApiError(
                    409, "CONFLICT_STRUCTURE_GRAVEYARDED",
                    f"structure {fingerprint[:12]} is in the campaign graveyard — a rejected "
                    "structure is never re-proposed silently (§8.3). Inspect GET "
                    f"/campaigns/{campaign_id}/graveyard before re-registering.",
                )
            if repo.find_ablation_by_key(campaign_id, parent_candidate_id, fingerprint) is not None:
                raise ApiError(
                    409, "CONFLICT_ABLATION_REGISTERED",
                    f"an ablation for structure {fingerprint[:12]} on this parent is already "
                    "pre-registered for the campaign",
                )
            row = repo.insert_ablation(
                campaign_id, parent_candidate_id, mutation, fingerprint, body.get("createdBy")
            )
        finally:
            repo.close()
        return AblationModel(**row)

    def evaluate(self, ablation_id: str, body: dict[str, Any]) -> EvaluatedAblation:
        """Evaluate a pre-registered ablation against paired, identical-fold evidence (§5.2). Body
        ``{parent, candidate}`` each an evidence block (oosFoldReturns [+ optional isFoldReturns /
        regimeFoldReturns / tradeCount / robustScore]). 400 missing evidence; 422 the two OOS fold
        series are not paired (differing lengths); 404 unknown ablation; 409 already evaluated. A
        rejection buries the structure (idempotent) so the suggesters never re-propose it; a
        REJECTED_IS_ONLY additionally mints a REVIEW_GATE proposal (institutional memory)."""
        parent = _evidence(body.get("parent"), "parent")
        candidate = _evidence(body.get("candidate"), "candidate")
        if len(parent["oosFoldReturns"]) != len(candidate["oosFoldReturns"]):
            raise ApiError(
                422, "FOLDS_NOT_PAIRED",
                "parent and candidate oosFoldReturns must be the SAME length (paired on identical "
                f"folds, §5.2): got {len(parent['oosFoldReturns'])} vs "
                f"{len(candidate['oosFoldReturns'])}",
            )
        if not parent["oosFoldReturns"]:
            raise ApiError(422, "FOLDS_NOT_PAIRED", "oosFoldReturns is empty — no folds to pair")

        repo = self._repo_factory()
        try:
            ablation = repo.get_ablation(ablation_id)
            if ablation is None:
                raise ApiError(404, "NOT_FOUND_ABLATION", f"no ablation {ablation_id}")
            if ablation["status"] == "EVALUATED":
                raise ApiError(
                    409, "ABLATION_ALREADY_EVALUATED",
                    f"ablation {ablation_id} is already EVALUATED (verdict "
                    f"{ablation.get('verdict')}) — a verdict is written exactly once",
                )

            verdict, evaluation = evaluate_ablation(parent, candidate)
            updated = repo.update_ablation_verdict(ablation_id, verdict, evaluation)
            if updated is None:
                # The PRE_REGISTERED guard matched nothing — a concurrent evaluate won the race.
                raise ApiError(
                    409, "ABLATION_ALREADY_EVALUATED",
                    f"ablation {ablation_id} was evaluated concurrently — verdict already written",
                )

            buried = False
            proposal_id = None
            if verdict != _ACCEPTED:
                buried = repo.bury(
                    ablation["campaignId"], ablation_id, ablation["fingerprint"],
                    ablation["mutation"] or {}, verdict, evaluation,
                )
                if verdict == _REJECTED_IS_ONLY:
                    proposal_id = self._record_is_only(repo, ablation, evaluation)
        finally:
            repo.close()

        return EvaluatedAblation(
            **updated,
            buried=buried,
            reviewGateProposalId=proposal_id,
            note=_verdict_note(verdict, buried),
        )

    def _record_is_only(
        self, repo: Any, ablation: dict[str, Any], evaluation: dict[str, Any]
    ) -> str:
        """§5.2 institutional memory: a REJECTED_IS_ONLY rejection mints (or refreshes) a
        REVIEW_GATE proposal so the same gate isn't re-proposed next quarter. Idempotent on
        (campaign, fingerprint). candidate_id = the parent (the rejection is about that base +
        gate)."""
        campaign_id = ablation["campaignId"]
        fingerprint = ablation["fingerprint"]
        evidence = {
            "kind": _REVIEW_GATE,
            "source": "ablation_is_only_rejection",
            "fingerprint": fingerprint,
            "mutation": ablation["mutation"],
            "ablationId": ablation["id"],
            "verdict": _REJECTED_IS_ONLY,
            "isLift": evaluation.get("isLift"),
            "oosLift": evaluation.get("oosLift"),
            "suggestion": (
                "a structure gate that lifts IN-SAMPLE but NOT out-of-sample — REJECTED and buried "
                "(§5.2 IS-only auto-reject). Recorded so it is not re-proposed next quarter "
                "(institutional memory against snooping); it will not surface as a gate-candidate "
                "suggestion while buried."
            ),
            "wires": False,
        }
        existing = repo.find_open_review_gate(campaign_id, fingerprint)
        if existing is not None:
            repo.refresh_review_gate(existing["id"], evidence)
            return existing["id"]
        row = repo.insert_review_gate(campaign_id, ablation["parentCandidateId"], evidence)
        return row["id"]

    def list_ablations(
        self, campaign_id: str, limit: int, offset: int
    ) -> AblationListResponse:
        repo = self._repo_factory()
        try:
            if repo.get_campaign(campaign_id) is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            rows = repo.list_ablations(campaign_id, limit, offset)
        finally:
            repo.close()
        return AblationListResponse(items=[AblationModel(**r) for r in rows])

    def list_graveyard(
        self, campaign_id: str, limit: int, offset: int
    ) -> GraveyardListResponse:
        repo = self._repo_factory()
        try:
            if repo.get_campaign(campaign_id) is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            rows = repo.list_graveyard(campaign_id, limit, offset)
        finally:
            repo.close()
        return GraveyardListResponse(items=[GraveyardModel(**r) for r in rows])


def _evidence(raw: Any, side: str) -> dict[str, Any]:
    """Validate + normalize one side's evidence block. ``oosFoldReturns`` (a non-empty numeric list)
    is required; the rest are optional (absent → the corresponding gate SKIPs honestly)."""
    if not isinstance(raw, dict):
        raise ApiError(400, "VALIDATION_FAILED", f"missing required field: {side}")
    oos = raw.get("oosFoldReturns")
    if not isinstance(oos, list) or not oos:
        raise ApiError(
            400, "VALIDATION_FAILED", f"{side}.oosFoldReturns must be a non-empty list of numbers"
        )
    try:
        oos_floats = [float(v) for v in oos]
    except (TypeError, ValueError) as exc:
        raise ApiError(
            400, "VALIDATION_FAILED", f"{side}.oosFoldReturns must be numeric: {exc}"
        ) from exc
    return {
        "oosFoldReturns": oos_floats,
        "isFoldReturns": raw.get("isFoldReturns"),
        "regimeFoldReturns": raw.get("regimeFoldReturns"),
        "tradeCount": raw.get("tradeCount"),
        "robustScore": raw.get("robustScore"),
    }


def _verdict_note(verdict: str, buried: bool) -> str:
    if verdict == _ACCEPTED:
        return (
            "ACCEPTED — the structure gate paid for its added DOF (§5.2). Composing it into a "
            "named candidate variant is the owner-gated proposal path (§5.3), not this stream; "
            "nothing self-arms."
        )
    tail = " and buried in the graveyard" if buried else " (already buried — no new grave)"
    if verdict == _REJECTED_IS_ONLY:
        return (
            f"REJECTED_IS_ONLY — lift exists only in-sample{tail}; a REVIEW_GATE proposal records "
            "it so it is not re-proposed next quarter (§5.2 institutional memory)."
        )
    return (
        f"{verdict} — rejected by the paired evaluation{tail}; the suggesters "
        "will not re-propose it."
    )


def _round(value: Any) -> Any:
    """6dp round for a float value, pass-through for None / non-floats (keeps the JSONB compact and
    deterministic — the same idiom as scoring._round / evolution)."""
    return round(value, 6) if isinstance(value, float) else value


def _service(request: Request) -> AblationService:
    return request.app.state.ablation


@router.post(
    "/campaigns/{campaign_id}/ablations", response_model=AblationModel, status_code=201
)
def register_ablation(
    campaign_id: str, body: dict[str, Any], request: Request
) -> AblationModel:
    """Pre-register a structure-mutation ablation (§5.2). Body ``{mutation, parentCandidateId?,
    createdBy?}``. 400 missing/invalid mutation; 404 unknown campaign / parent; 409 graveyarded or
    already pre-registered."""
    return _service(request).register(campaign_id, body)


@router.post("/ablations/{ablation_id}/evaluate", response_model=EvaluatedAblation)
def evaluate_ablation_endpoint(
    ablation_id: str, body: dict[str, Any], request: Request
) -> EvaluatedAblation:
    """Evaluate a pre-registered ablation against paired identical-fold evidence (§5.2). Body
    ``{parent, candidate}``. Rejections bury the structure (never re-proposed); a REJECTED_IS_ONLY
    also mints a REVIEW_GATE proposal. 400/422 bad evidence; 404 unknown; 409 already evaluated."""
    return _service(request).evaluate(ablation_id, body)


@router.get("/campaigns/{campaign_id}/ablations", response_model=AblationListResponse)
def list_ablations(
    campaign_id: str, request: Request, limit: int = 50, offset: int = 0
) -> AblationListResponse:
    """A campaign's ablations (pre-registered + evaluated), newest first; 404 unknown campaign."""
    bounded = min(max(limit, 1), 200)
    return _service(request).list_ablations(campaign_id, bounded, max(offset, 0))


@router.get("/campaigns/{campaign_id}/graveyard", response_model=GraveyardListResponse)
def list_graveyard(
    campaign_id: str, request: Request, limit: int = 50, offset: int = 0
) -> GraveyardListResponse:
    """A campaign's graveyard — the rejected structures never re-proposed silently (§8.3); 404
    unknown campaign."""
    bounded = min(max(limit, 1), 200)
    return _service(request).list_graveyard(campaign_id, bounded, max(offset, 0))
