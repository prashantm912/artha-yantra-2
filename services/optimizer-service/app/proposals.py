"""The evolution PROPOSALS surface — inbox API + generation rules + ntfy (item 11, §8 / §12 E4)
PLUS the APPROVED → PUBLISH_PAPER action (slice 2, item 12).

Slice 1 built the PROPOSAL SUBSTRATE. Slice 2 adds the ``/proposals/{id}/execute`` action: on an
APPROVED PUBLISH_PAPER proposal it creates + publishes the §8.2 sibling evo paper-lane clone and
stamps the linkage back (see ``ProposalService.execute``). Execution happens ONLY via that explicit,
owner-clicked endpoint — approval alone arms nothing; there is no scheduler / auto-execute. The
substrate part:

  * GENERATES PUBLISH_PAPER proposals for candidates that meet the §8.2 bar — SURVIVOR + rankable
    (the QUERYABLE gates) + the near-champion RobustScore bar (RobustScore >= champion - 0.1) and
    holdout-consumed bar applied ONLY when their inputs are populated; where the champion score or
    the holdout state is not queryable yet, generation proceeds on the queryable predicates and
    stamps an honest ``pendingInputs`` caveat — it never fabricates a passing bar. Idempotent: one
    OPEN (PENDING) proposal per (candidate, kind); a regeneration REFRESHES the evidence in place.
  * exposes the INBOX — GET /proposals (filter status/kind/campaign) + POST approve / reject. A
    decision only MARKS the row (status + actor + decided_at); NOTHING self-arms. approve returns a
    ``nextSteps`` payload naming the slice-2 action it would trigger — honest that it is substrate.
  * pushes ONE fail-soft ntfy per NEW proposal (the existing platform ntfy convention).

Terminal proposal states are APPROVED / REJECTED / EXPIRED (the table is append-only — a decision
is an UPDATE of status, never a row removal; V011). RETIRE (the autonomous, auto-APPROVED §8.2 row)
rides the selection/domination logic, which is a later slice — not generated here.
"""

from __future__ import annotations

import re
from collections.abc import Callable
from typing import Any

import httpx
from fastapi import APIRouter, Request
from pydantic import BaseModel

from app import config_patch
from app.errors import ApiError
from app.path_grammar import InvalidParameterPath

router = APIRouter(prefix="/api/v1/evolution")

# §8.2 PUBLISH_PAPER: "SURVIVOR + RobustScore >= champion - ε (ε = 0.1) + holdout consumed".
_PUBLISH_PAPER = "PUBLISH_PAPER"
_EPSILON = 0.1

# §8.2 materialization: the sibling evo paper-lane clone's tags (family FIRST so Books.fromTags
# routes it to the family book, `evo` SECOND). Schema slug pattern `^[a-z][a-z0-9-]{2,63}$`.
_EVO_TAG = "evo"
_SLUG_MAX = 64
_NAME_MAX = 120

# E4 slice 3 (§8.1 state machine): the two owner-gated promotion proposals + the autonomous RETIRE
# acknowledge-row (auto-APPROVED, no gate). PROMOTE + ROLLBACK are executable (owner-clicked);
# RETIRE is an acknowledge-only audit row, never executed.
_PROMOTE = "PROMOTE"
_ROLLBACK = "ROLLBACK"
_RETIRE = "RETIRE"

# §8.2 TAKE_ELIGIBLE = existing F7 thresholds (the ≥20-trade bar, NOT F7's stricter ≥50+Sharpe
# GRADUATED auto-promotion marker) — over the evo clone's live paper book.
_TE_MIN_CLOSED_TRADES = 20
_TE_MIN_PF = 1.3
_TE_MAX_DD_PCT = 0.25

# §8.2 demoted-champion counterfactual: kept running for 6 weeks as the rollback comparator. The
# routing is by evidence policy — LIVE_FIRST (scalpers) → shadow variant (#733, the shadow book
# hosts scalper rejection-path entries only); SIM_FIRST/SIM_BLOCKED (swing/BTST) → a retained
# paper-lane clone (the shadow book cannot host a daily-batch swing strategy).
_COUNTERFACTUAL_RETENTION = (
    "demoted champion retained as the rollback counterfactual for 6 weeks (§8.2) — accrues live "
    "counterfactual P&L from day one"
)
_CF_TAG = "counterfactual"

# The full-book window bounds for a candidate's paper evidence read (explicit IST, never a UTC
# ::date — the in-container off-by-one trap). Sentinel-wide because a fresh evo clone version only
# exists since it was published, so its ENTIRE paper book IS the accrued TAKE_ELIGIBLE evidence
# (the §7.3 rolling-4-week window is a live-quality-watch refinement, not the eligibility read).
_BOOK_FROM = "1970-01-01T00:00:00+05:30"
_BOOK_TO = "2100-01-01T00:00:00+05:30"


# --- typed response models ----------------------------------------------------------------------


class ProposalModel(BaseModel):
    """One ``evo_proposals`` row (V011) — the inbox card. ``evidence`` is the §10 proposal-card
    JSONB passed through verbatim."""

    id: str
    campaignId: str
    candidateId: str | None = None
    kind: str
    evidence: dict[str, Any] | None = None
    status: str
    actor: str | None = None
    decidedAt: str | None = None
    expiresAt: str | None = None
    createdAt: str | None = None


class ProposalListResponse(BaseModel):
    items: list[ProposalModel]


class GenerateProposalsResponse(BaseModel):
    """The result of a generation pass over a campaign's candidates: how many proposals were newly
    minted vs refreshed in place (idempotent regeneration), plus the affected rows."""

    campaignId: str
    generated: int
    refreshed: int
    items: list[ProposalModel]


class NextSteps(BaseModel):
    """What the approved proposal WOULD trigger — named honestly as not-yet-wired substrate."""

    action: str
    status: str
    note: str


class ProposalDecision(BaseModel):
    """The approve/reject response: the decided row + (for approve) the nextSteps."""

    proposal: ProposalModel
    nextSteps: NextSteps | None = None


class ExecutionResult(BaseModel):
    """The result of executing an APPROVED PUBLISH_PAPER proposal (§8.2): the sibling evo paper-lane
    clone that was created + published, and the candidate it linked back to. The clone publishes
    into the PAPER lane via its family tag (auto-paper book routing) — it never touches a real-order
    path (none exists)."""

    proposalId: str
    campaignId: str
    candidateId: str
    clonedStrategyId: str
    clonedSlug: str
    clonedName: str
    clonedVersion: str | None = None
    clonedVersionId: str | None = None
    clonedTags: list[str]
    candidateState: str
    note: str


class AssessmentResponse(BaseModel):
    """The result of an assessment pass (TAKE_ELIGIBLE over PAPER candidates, or ROLLBACK over
    PROMOTED ones): how many candidates were assessed, how many met the bar / triggered, how many
    proposals were newly minted vs refreshed in place, and the affected proposal rows. NOTHING
    self-arms — an assessment only advances state to TAKE_ELIGIBLE (autonomous, no owner gate up to
    that point) and mints an inbox proposal; the PROMOTE/ROLLBACK action stays owner-clicked."""

    campaignId: str
    assessed: int
    eligible: int
    generated: int
    refreshed: int
    items: list[ProposalModel]


class PromotionResult(BaseModel):
    """The result of executing an APPROVED PROMOTE proposal (§8.2): the candidate's config published
    onto the BASE strategy (champion pointer moved) + the demoted-champion counterfactual that was
    registered to accrue live comparator P&L + the archive outcome for the candidate's now-redundant
    evo PAPER clone (fail-soft — ``cloneArchive.warning`` when it could not be archived). No
    real-order path is touched (paper/live-signal only)."""

    proposalId: str
    campaignId: str
    candidateId: str
    baseStrategyId: str
    championVersionId: str | None = None
    demotedChampionVersionId: str | None = None
    demotedChampionVersion: str | None = None
    counterfactual: dict[str, Any]
    cloneArchive: dict[str, Any] | None = None
    candidateState: str
    note: str


class RollbackResult(BaseModel):
    """The result of executing an APPROVED ROLLBACK proposal (§8.2): the base strategy copy-forward
    rolled back to the demoted champion's version (champion pointer restored) + the audit trail."""

    proposalId: str
    campaignId: str
    candidateId: str
    baseStrategyId: str
    restoredVersion: str
    candidateState: str
    note: str


# --- generation rules (pure) --------------------------------------------------------------------


def _champion_score(
    candidates: list[dict[str, Any]], champion_version_id: str | None
) -> float | None:
    """The incumbent champion's RobustScore, resolved by finding the campaign candidate whose
    ``versionId`` IS the champion version and reading its scorecard. ``None`` when there is no
    champion, or the champion version is not a scored candidate in this campaign (the near-champion
    bar is then unverifiable → a ``pendingInputs`` caveat, never a fabricated pass)."""
    if not champion_version_id:
        return None
    for cand in candidates:
        if cand.get("versionId") == champion_version_id:
            return _num((cand.get("scorecard") or {}).get("robustScore"))
    return None


def _publish_paper_eligibility(
    cand: dict[str, Any], champion_score: float | None, champion_version_id: str | None
) -> tuple[bool, list[str]]:
    """Apply the §8.2 PUBLISH_PAPER bar. The QUERYABLE predicates GATE (SURVIVOR state + rankable —
    a FAILed hard gate is RETIRE material, never PUBLISH_PAPER). The near-champion RobustScore bar
    and the holdout-consumed bar are applied ONLY when their inputs are populated: a VERIFIED miss
    (RobustScore < champion - 0.1) blocks; an UNPOPULATED input (no champion score / no holdout run)
    never blocks — it stamps a ``pendingInputs`` caveat. Returns ``(eligible, pending_inputs)``."""
    scorecard = cand.get("scorecard") or {}
    # the incumbent champion is not a challenger — never propose publishing it to a paper clone.
    if champion_version_id is not None and cand.get("versionId") == champion_version_id:
        return False, []
    if cand.get("state") != "SURVIVOR":
        return False, []
    if not scorecard.get("rankable"):
        return False, []

    pending: list[str] = []
    robust = _num(scorecard.get("robustScore"))

    # near-champion bar — verified only when both scores are queryable.
    if champion_version_id is None:
        pending.append(
            "no champion set on the campaign — the near-champion bar "
            "(RobustScore >= champion - 0.1) is not applicable (first-mover challenger)"
        )
    elif champion_score is None:
        pending.append(
            "champion RobustScore not queryable (the champion version is not a scored candidate in "
            "this campaign) — the near-champion bar (>= champion - 0.1) was NOT applied"
        )
    elif robust is None:
        pending.append(
            "candidate RobustScore missing from the scorecard — the near-champion bar "
            "was NOT applied"
        )
    elif robust < champion_score - _EPSILON:
        return False, pending  # a VERIFIED miss of the near-champion bar → not eligible

    # holdout-consumed bar (§8.2) — a populated holdout_run_id satisfies it; absent → caveat.
    if not cand.get("holdoutRunId"):
        pending.append(
            "holdout not yet consumed (candidate has no holdout_run_id) — the §8.2 "
            "holdout-consumed bar was NOT verified"
        )
    return True, pending


def _evidence_card(
    cand: dict[str, Any],
    campaign: dict[str, Any],
    champion_score: float | None,
    pending_inputs: list[str],
) -> dict[str, Any]:
    """The §8.2 / §10 proposal card: what changes, the evidence summary, the gates table, the
    champion gap ("why superior"), the blast radius (book/plane), the rollback plan, and the honest
    pending-input caveats. Substrate-only: it states plainly that approval marks the row, and the
    actual publish is slice 2."""
    scorecard = cand.get("scorecard") or {}
    robust = _num(scorecard.get("robustScore"))
    gates = [{"id": g.get("id"), "status": g.get("status")} for g in scorecard.get("gates") or []]
    margin = (
        (robust - champion_score) if robust is not None and champion_score is not None else None
    )
    return {
        "kind": _PUBLISH_PAPER,
        "candidateId": cand.get("id"),
        "campaignId": campaign["id"],
        "whatChanges": (
            "publish this candidate as a SIBLING evo paper-lane clone (§8.2) — a new sim-lane "
            "strategy carrying the candidate's YAML; the champion keeps its published pointer"
        ),
        "robustScore": robust,
        "championVersionId": campaign.get("championVersionId"),
        "championRobustScore": champion_score,
        "epsilon": _EPSILON,
        "margin": _round(margin),
        "gates": gates,
        "state": cand.get("state"),
        "holdoutConsumed": bool(cand.get("holdoutRunId")),
        "blastRadius": {"family": campaign.get("family"), "plane": "paper (evo sibling clone)"},
        "rollbackPlan": (
            "close/archive the evo clone — the engine unloads it (existing archive semantics); the "
            "champion pointer is untouched"
        ),
        "pendingInputs": pending_inputs,
        "note": (
            "approving MARKS the proposal row — nothing self-arms. The publish (cloning the "
            "candidate into a sibling evo paper-lane strategy, §8.2 / item 12) runs ONLY via the "
            "explicit owner-clicked POST /proposals/{id}/execute on the APPROVED proposal"
        ),
    }


def _next_steps(kind: str) -> NextSteps:
    """The action an approval unlocks — for PUBLISH_PAPER the execute endpoint is now wired (slice
    2); approval alone still ARMS NOTHING (the owner must explicitly POST /execute)."""
    if kind == _PUBLISH_PAPER:
        return NextSteps(
            action=_PUBLISH_PAPER,
            status="READY_TO_EXECUTE",
            note=(
                "this approval only MARKED the proposal row — nothing is armed. To materialize the "
                "sibling evo paper-lane clone (slug {base}--evo-g{gen}-{shortId}, §8.2), POST "
                "/api/v1/evolution/proposals/{id}/execute: the explicit, owner-clicked action that "
                "creates + publishes the clone into the PAPER lane (no real-order path exists)."
            ),
        )
    return NextSteps(
        action=kind,
        status="NOT_EXECUTABLE",
        note=(
            "only PUBLISH_PAPER is executable this slice; PROMOTE/ROLLBACK land later "
            "(this decision marks the row only)."
        ),
    )


# --- TAKE_ELIGIBLE / ROLLBACK assessment (pure) -------------------------------------------------


def _capital_base(config: dict[str, Any] | None) -> float | None:
    """The paper-book capital base for the maxDD% gate, read from the clone's config (the paper book
    seeds capital from the strategy config). Absent → maxDD% is not computable → SKIPPED + a pending
    caveat, never a fabricated percentage."""
    config = config or {}
    for candidate in (
        config.get("capital"),
        (config.get("paper") or {}).get("capital"),
        (config.get("risk") or {}).get("capital"),
    ):
        base = _num(candidate)
        if base is not None and base > 0:
            return base
    return None


def _paper_max_drawdown_pct(closed: list[dict[str, Any]], capital: float | None) -> float | None:
    """The peak-to-trough drawdown of the realized-equity curve, as a fraction of the running peak
    equity (capital + cumulative realized P&L), over the closed trades in close order. ``None`` when
    no capital base is known (the % is undefined without it)."""
    if capital is None or not closed:
        return None
    ordered = sorted(closed, key=lambda t: (t.get("closedAt") or t.get("openedAt") or ""))
    equity = capital
    peak = capital
    max_dd = 0.0
    for trade in ordered:
        equity += _num(trade.get("realizedPnl")) or 0.0
        peak = max(peak, equity)
        if peak > 0:
            max_dd = max(max_dd, (peak - equity) / peak)
    return max_dd


def _take_eligible_assessment(
    cand: dict[str, Any],
    trades: list[dict[str, Any]],
    recon: dict[str, Any] | None,
    config: dict[str, Any] | None,
    campaign: dict[str, Any],
    champion_score: float | None,
) -> tuple[bool, dict[str, Any]]:
    """Apply the §8.2 TAKE_ELIGIBLE bar over the evo clone's paper book. The QUERYABLE gates
    (≥20 closed trades + PF ≥ 1.3 + expectancy > 0, plus maxDD ≤ 25 % when a capital base is known,
    plus the §7.2 live-gap gate = NOT DIVERGENT) GATE — any hard FAIL blocks. The bars whose inputs
    are absent (maxDD without a capital base; RobustScore(live-weighted) ≥ champion — a
    live-weighted re-score is a follow-up) never fabricate a pass; they SKIP and stamp an honest
    ``pendingInputs`` caveat. Returns ``(eligible, evidence_card)``."""
    closed = [t for t in trades if t.get("realizedPnl") is not None]
    n_closed = len(closed)
    pnls = [_num(t.get("realizedPnl")) or 0.0 for t in closed]
    gross_profit = sum(p for p in pnls if p > 0)
    gross_loss = -sum(p for p in pnls if p < 0)
    if gross_loss > 0:
        pf: float | None = gross_profit / gross_loss
    elif gross_profit > 0:
        pf = float("inf")  # no losing trades yet — PF unbounded (passes the floor)
    else:
        pf = 0.0
    expectancy = (sum(pnls) / n_closed) if n_closed else None
    capital = _capital_base(config)
    max_dd = _paper_max_drawdown_pct(closed, capital)

    pending: list[str] = []
    gates: list[dict[str, Any]] = []

    gates.append({
        "id": "paper_trade_floor",
        "status": "PASS" if n_closed >= _TE_MIN_CLOSED_TRADES else "FAIL",
        "value": n_closed,
    })
    gates.append({
        "id": "profit_factor",
        "status": "PASS" if (pf is not None and pf >= _TE_MIN_PF) else "FAIL",
        "value": _round(pf) if pf != float("inf") else "inf",
    })
    gates.append({
        "id": "expectancy",
        "status": "PASS" if (expectancy is not None and expectancy > 0) else "FAIL",
        "value": _round(expectancy),
    })
    if max_dd is None:
        gates.append({"id": "max_drawdown", "status": "SKIPPED", "value": None})
        pending.append(
            "maxDD % not computable — no capital base on the clone config; the ≤25 % drawdown "
            "gate was NOT applied"
        )
    else:
        gates.append({
            "id": "max_drawdown",
            "status": "PASS" if max_dd <= _TE_MAX_DD_PCT else "FAIL",
            "value": _round(max_dd),
        })

    gates.append(_live_gap_gate_from_recon(recon, pending))

    # RobustScore(live-weighted) ≥ champion — the live-weighted re-score is a follow-up; never
    # fabricate a pass. Stamp the SIM comparison as informational only.
    sim_robust = _num((cand.get("scorecard") or {}).get("robustScore"))
    gates.append({"id": "robust_vs_champion", "status": "SKIPPED", "value": None})
    if champion_score is None:
        pending.append(
            "no champion RobustScore on the campaign — the RobustScore(live-weighted) ≥ champion "
            "bar (§8.2) is not applicable (first-mover champion)"
        )
    else:
        pending.append(
            "RobustScore(live-weighted) ≥ champion NOT evaluated this slice — a live-weighted "
            f"re-score is a follow-up; sim RobustScore {sim_robust} vs champion {champion_score}"
        )

    eligible = all(g["status"] != "FAIL" for g in gates)
    card = {
        "kind": _PROMOTE,
        "candidateId": cand.get("id"),
        "campaignId": campaign["id"],
        "whatChanges": (
            "publish this candidate's config onto the BASE strategy — the champion pointer moves; "
            "the demoted champion keeps running as the rollback counterfactual (§8.2)"
        ),
        "paperEvidence": {
            "closedTrades": n_closed,
            "profitFactor": _round(pf) if pf != float("inf") else "inf",
            "expectancy": _round(expectancy),
            "grossProfit": _round(gross_profit),
            "grossLoss": _round(gross_loss),
            "maxDrawdownPct": _round(max_dd),
            "capitalBase": capital,
        },
        "gates": gates,
        "robustScore": sim_robust,
        "championVersionId": campaign.get("championVersionId"),
        "championRobustScore": champion_score,
        "liveGap": (
            {"verdict": recon.get("verdict"), "gapZ": recon.get("gapZ")} if recon else None
        ),
        "blastRadius": {
            "family": campaign.get("family"),
            "plane": "live champion (registry published pointer)",
        },
        "rollbackPlan": (
            "copy-forward rollback to the demoted champion's version "
            "(POST /strategies/{id}/rollback andPublish); the retained counterfactual proves it"
        ),
        "pendingInputs": pending,
        "note": (
            "approving MARKS the proposal row — nothing self-arms. PROMOTE (publishing the "
            "candidate onto the base strategy + registering the demoted-champion counterfactual, "
            "§8.2) runs ONLY via the explicit owner-clicked POST /proposals/{id}/execute on the "
            "APPROVED row."
        ),
    }
    return eligible, card


def _live_gap_gate_from_recon(
    recon: dict[str, Any] | None, pending: list[str]
) -> dict[str, Any]:
    """The §7.2 live-gap gate over the latest reconciliation: DIVERGENT → FAIL (promotion blocked);
    ALIGNED/PENALIZED → PASS; no reconciliation / INSUFFICIENT → SKIPPED + a pending caveat
    (assessed as NOT-DIVERGENT on absent/insufficient evidence, never a fabricated pass)."""
    if recon is None:
        pending.append(
            "no reconciliation yet for the clone version — the §7.2 live-gap gate is SKIPPED "
            "(assessed as not-DIVERGENT on absent evidence)"
        )
        return {"id": "live_gap", "status": "SKIPPED", "value": None}
    verdict = recon.get("verdict")
    gap_z = recon.get("gapZ")
    if verdict == "DIVERGENT":
        return {"id": "live_gap", "status": "FAIL", "value": gap_z, "note": "§7.2 DIVERGENT"}
    if verdict in ("ALIGNED", "PENALIZED"):
        return {"id": "live_gap", "status": "PASS", "value": gap_z}
    pending.append(
        f"reconciliation verdict {verdict!r} is not DIVERGENT but below the evidence floor — the "
        "§7.2 live-gap gate is SKIPPED (assessed as not-DIVERGENT)"
    )
    return {"id": "live_gap", "status": "SKIPPED", "value": gap_z}


def _rollback_assessment(
    cand: dict[str, Any],
    recons: list[dict[str, Any]],
    promote_evidence: dict[str, Any] | None,
    campaign: dict[str, Any],
) -> tuple[bool, dict[str, Any]]:
    """Apply the §8.2 ROLLBACK trigger rules over a PROMOTED candidate's live evidence. This slice
    implements the cleanly-computable, unit-safe **DIVERGENT ×2** trigger (§7.3 live-quality watch:
    two consecutive DIVERGENT reconciliations → automatic ROLLBACK proposal). The other two OR-ed
    triggers — the rolling-4-week live-RobustScore < pre-promotion band AND < the concurrent
    counterfactual, and the daily-loss risk-event — need the live-RobustScore stream (§7.3) + the
    risk feed and are stamped as ``pendingInputs`` (NOT evaluated here), never fabricated. The
    rollback TARGET (base strategy + demoted-champion semver) is copied from the candidate's prior
    PROMOTE evidence so execute can restore it. Returns ``(triggered, evidence_card)``."""
    verdicts = [r.get("verdict") for r in recons[:2]]
    divergent_x2 = len(verdicts) >= 2 and all(v == "DIVERGENT" for v in verdicts)

    triggers: list[str] = []
    if divergent_x2:
        triggers.append(
            "live-quality watch: 2 consecutive DIVERGENT reconciliations (§7.3) — the promoted "
            "version is not tracking its own backtest"
        )
    pending = [
        "the rolling-4-week live-RobustScore < pre-promotion band AND < concurrent-counterfactual "
        "trigger (§8.2) and the daily-loss risk-event trigger are NOT evaluated this slice — they "
        "need the rolling live-RobustScore stream (§7.3) + the risk feed; only DIVERGENT ×2 fires "
        "autonomously here"
    ]
    promotion = (promote_evidence or {}).get("promotion") or {}
    rollback_target = {
        "baseStrategyId": promotion.get("baseStrategyId"),
        "demotedChampionVersion": promotion.get("demotedChampionVersion"),
        "demotedChampionVersionId": promotion.get("demotedChampionVersionId"),
        "promotedChampionVersionId": promotion.get("championVersionId"),
    }
    card = {
        "kind": _ROLLBACK,
        "candidateId": cand.get("id"),
        "campaignId": campaign["id"],
        "whatChanges": (
            "roll the BASE strategy back to the demoted champion's version (copy-forward + "
            "publish) — the champion pointer returns to the prior version"
        ),
        "triggers": triggers,
        "reconVerdicts": verdicts,
        "rollbackTarget": rollback_target,
        "counterfactual": promotion.get("counterfactual"),
        "incidentNote": (
            "2 consecutive DIVERGENT windows on the promoted version (§7.3) — auto-attached to the "
            "campaign" if divergent_x2 else "no autonomous trigger fired"
        ),
        "blastRadius": {
            "family": campaign.get("family"),
            "plane": "live champion (registry published pointer)",
        },
        "pendingInputs": pending,
        "note": (
            "approving MARKS the proposal row — nothing self-arms. ROLLBACK (the copy-forward "
            "registry rollback, §8.2) runs ONLY via the explicit owner-clicked "
            "POST /proposals/{id}/execute on the APPROVED row."
        ),
    }
    return bool(triggers), card


# --- service ------------------------------------------------------------------------------------


class ProposalService:
    """Generates PUBLISH_PAPER proposals from a campaign's candidates and serves the inbox
    (list / approve / reject). Collaborators injected (the EvoRepo factory + a fail-soft ntfy
    client), so tests drive it with the in-memory fakes. No autonomy — generation is owner/
    orchestrator-triggered; a decision only marks the row (nothing self-arms)."""

    def __init__(
        self,
        repo_factory: Callable[[], Any],
        ntfy: Any,
        strategy_client: Any = None,
        jobs_factory: Callable[[], Any] | None = None,
        evo_paper_cap: int = 2,
        live_factory: Callable[[], Any] | None = None,
        recon_factory: Callable[[], Any] | None = None,
    ) -> None:
        self._repo_factory = repo_factory
        self._ntfy = ntfy
        # The slice-2 execute collaborators — the strategy-signal registry client (create + publish
        # the clone) and the jobs repo factory (read the candidate's sweep for the base config).
        # None on the substrate-only constructions (generation/inbox tests); execute guards on them.
        self._strategy = strategy_client
        self._jobs_factory = jobs_factory
        # §1.4.3 safety invariant: <= 2 concurrent evo paper strategies per family book.
        self._evo_paper_cap = evo_paper_cap
        # The slice-3 assessment collaborators — the strategy-schema live-evidence repo (the evo
        # clone's paper book + its config, keyed by the clone's published version) and the
        # reconciliation-store repo (the §7.2 live-gap verdict). None on the substrate-only /
        # execute-only constructions; assess_take_eligible / assess_rollback guard on them.
        self._live_factory = live_factory
        self._recon_factory = recon_factory

    def generate_for_campaign(self, campaign_id: str) -> GenerateProposalsResponse:
        """Generate/refresh PUBLISH_PAPER proposals for every eligible candidate in the campaign.
        Idempotent: one OPEN proposal per (candidate, kind) — an existing OPEN one is REFRESHED in
        place (no duplicate, no second ntfy); a new one is minted + ntfy'd. 404 unknown campaign."""
        repo = self._repo_factory()
        new_alerts: list[tuple[dict[str, Any], dict[str, Any]]] = []
        try:
            campaign = repo.get_campaign(campaign_id)
            if campaign is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            candidates = repo.list_candidates_for_campaign(campaign_id)
            champion_version_id = campaign.get("championVersionId")
            champion_score = _champion_score(candidates, champion_version_id)

            generated: list[dict[str, Any]] = []
            refreshed: list[dict[str, Any]] = []
            for cand in candidates:
                eligible, pending = _publish_paper_eligibility(
                    cand, champion_score, champion_version_id
                )
                if not eligible:
                    continue
                evidence = _evidence_card(cand, campaign, champion_score, pending)
                existing = repo.find_open_proposal(cand["id"], _PUBLISH_PAPER)
                if existing is not None:
                    refreshed.append(repo.refresh_proposal_evidence(existing["id"], evidence))
                else:
                    row = repo.insert_proposal(campaign_id, cand["id"], _PUBLISH_PAPER, evidence)
                    generated.append(row)
                    new_alerts.append((campaign, evidence))
        finally:
            repo.close()

        # ntfy AFTER the repo write + close (a push must not hold the DB connection or block the
        # row); the client is fail-soft, so a dead ntfy never fails generation.
        for campaign_row, evidence in new_alerts:
            self._notify_new(campaign_row, evidence)

        items = generated + refreshed
        return GenerateProposalsResponse(
            campaignId=campaign_id,
            generated=len(generated),
            refreshed=len(refreshed),
            items=[ProposalModel(**r) for r in items],
        )

    def list_proposals(
        self,
        status: str | None,
        kind: str | None,
        campaign_id: str | None,
        limit: int,
        offset: int,
    ) -> ProposalListResponse:
        repo = self._repo_factory()
        try:
            rows = repo.list_proposals(status, kind, campaign_id, limit, offset)
        finally:
            repo.close()
        return ProposalListResponse(items=[ProposalModel(**r) for r in rows])

    def approve(self, proposal_id: str, actor: str | None) -> ProposalDecision:
        row = self._decide(proposal_id, "APPROVED", actor)
        return ProposalDecision(proposal=ProposalModel(**row), nextSteps=_next_steps(row["kind"]))

    def reject(self, proposal_id: str, actor: str | None) -> ProposalDecision:
        row = self._decide(proposal_id, "REJECTED", actor)
        return ProposalDecision(proposal=ProposalModel(**row))

    # --- E4 slice 3: TAKE_ELIGIBLE / ROLLBACK assessment (§8.1-8.2 / §12 item 13) ---------------

    def assess_take_eligible(self, campaign_id: str) -> AssessmentResponse:
        """Assess the campaign's PAPER (and already-TAKE_ELIGIBLE) candidates against the §8.2
        TAKE_ELIGIBLE bar over each evo clone's live paper book, advancing the eligible ones to
        TAKE_ELIGIBLE (autonomous — no owner gate up to this state) and generating/refreshing an
        owner-inbox PROMOTE proposal for each. Idempotent: one OPEN PROMOTE per candidate (refreshed
        in place); a candidate that no longer meets the bar keeps its state (never demoted here).
        404 unknown campaign. NOTHING self-arms — the PROMOTE action is owner-clicked (execute)."""
        if self._live_factory is None or self._recon_factory is None:
            raise ApiError(
                500, "ASSESS_NOT_WIRED",
                "TAKE_ELIGIBLE assessment needs the live-evidence + reconciliation repos wired",
            )
        repo = self._repo_factory()
        try:
            campaign = repo.get_campaign(campaign_id)
            if campaign is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            candidates = repo.list_candidates_for_campaign(campaign_id)
        finally:
            repo.close()

        champion_score = _champion_score(candidates, campaign.get("championVersionId"))
        assessable = [c for c in candidates if c.get("state") in ("PAPER", "TAKE_ELIGIBLE")]
        evidence_by_id = self._read_paper_evidence(assessable)

        eligible: list[tuple[dict[str, Any], dict[str, Any]]] = []
        for cand in assessable:
            ev = evidence_by_id.get(cand["id"]) or {}
            ok, card = _take_eligible_assessment(
                cand, ev.get("trades") or [], ev.get("recon"), ev.get("config"),
                campaign, champion_score,
            )
            if ok:
                eligible.append((cand, card))

        generated, refreshed, new_alerts = self._persist_promote_proposals(
            campaign, eligible
        )
        for evidence in new_alerts:
            self._notify_new_kind(campaign, evidence, _PROMOTE)
        items = generated + refreshed
        return AssessmentResponse(
            campaignId=campaign_id,
            assessed=len(assessable),
            eligible=len(eligible),
            generated=len(generated),
            refreshed=len(refreshed),
            items=[ProposalModel(**r) for r in items],
        )

    def _persist_promote_proposals(
        self, campaign: dict[str, Any], eligible: list[tuple[dict[str, Any], dict[str, Any]]]
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
        """Advance each eligible candidate PAPER→TAKE_ELIGIBLE (idempotent — only when the state
        actually changes) and generate/refresh its OPEN PROMOTE proposal. One repo session; ntfy
        happens after the caller closes it."""
        generated: list[dict[str, Any]] = []
        refreshed: list[dict[str, Any]] = []
        new_alerts: list[dict[str, Any]] = []
        repo = self._repo_factory()
        try:
            for cand, card in eligible:
                if cand.get("state") != "TAKE_ELIGIBLE":
                    scorecard = {**(cand.get("scorecard") or {}), "takeEligible": {
                        "gates": card["gates"], "pendingInputs": card["pendingInputs"],
                    }}
                    repo.update_candidate_selection(cand["id"], "TAKE_ELIGIBLE", scorecard)
                existing = repo.find_open_proposal(cand["id"], _PROMOTE)
                if existing is not None:
                    refreshed.append(repo.refresh_proposal_evidence(existing["id"], card))
                else:
                    row = repo.insert_proposal(campaign["id"], cand["id"], _PROMOTE, card)
                    generated.append(row)
                    new_alerts.append(card)
        finally:
            repo.close()
        return generated, refreshed, new_alerts

    def assess_rollback(self, campaign_id: str) -> AssessmentResponse:
        """Assess the campaign's PROMOTED candidates against the §8.2 ROLLBACK trigger rules over
        their live evidence, generating/refreshing an owner-inbox ROLLBACK proposal for each that
        triggers (this slice: DIVERGENT ×2, §7.3). Idempotent: one OPEN ROLLBACK per candidate. 404
        unknown campaign. NOTHING self-arms — the ROLLBACK ACTION stays owner-clicked (execute)."""
        if self._recon_factory is None:
            raise ApiError(
                500, "ASSESS_NOT_WIRED",
                "ROLLBACK assessment needs the reconciliation repo wired",
            )
        repo = self._repo_factory()
        try:
            campaign = repo.get_campaign(campaign_id)
            if campaign is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            candidates = repo.list_candidates_for_campaign(campaign_id)
            promoted = [c for c in candidates if c.get("state") == "PROMOTED"]
            promote_evidence = {
                c["id"]: repo.find_latest_proposal(c["id"], _PROMOTE) for c in promoted
            }
        finally:
            repo.close()

        recons_by_id = self._read_recons(promoted)
        triggered: list[tuple[dict[str, Any], dict[str, Any]]] = []
        for cand in promoted:
            fired, card = _rollback_assessment(
                cand, recons_by_id.get(cand["id"]) or [],
                (promote_evidence.get(cand["id"]) or {}).get("evidence"), campaign,
            )
            if fired:
                triggered.append((cand, card))

        generated: list[dict[str, Any]] = []
        refreshed: list[dict[str, Any]] = []
        new_alerts: list[dict[str, Any]] = []
        repo = self._repo_factory()
        try:
            for cand, card in triggered:
                existing = repo.find_open_proposal(cand["id"], _ROLLBACK)
                if existing is not None:
                    refreshed.append(repo.refresh_proposal_evidence(existing["id"], card))
                else:
                    row = repo.insert_proposal(campaign["id"], cand["id"], _ROLLBACK, card)
                    generated.append(row)
                    new_alerts.append(card)
        finally:
            repo.close()
        for evidence in new_alerts:
            self._notify_new_kind(campaign, evidence, _ROLLBACK)
        items = generated + refreshed
        return AssessmentResponse(
            campaignId=campaign_id,
            assessed=len(promoted),
            eligible=len(triggered),
            generated=len(generated),
            refreshed=len(refreshed),
            items=[ProposalModel(**r) for r in items],
        )

    def _read_paper_evidence(
        self, candidates: list[dict[str, Any]]
    ) -> dict[str, dict[str, Any]]:
        """Read each candidate's evo-clone paper book + config (live-evidence repo) and its latest
        reconciliation (reconciliation repo), keyed by the clone's published ``versionId``. A
        candidate with no versionId (not yet published — shouldn't happen in PAPER state) returns
        empty evidence. Both repos are closed before the caller's writes (the execute phasing)."""
        out: dict[str, dict[str, Any]] = {}
        live = self._live_factory()
        recon = self._recon_factory()
        try:
            for cand in candidates:
                version_id = cand.get("versionId")
                if not version_id:
                    out[cand["id"]] = {"trades": [], "recon": None, "config": None}
                    continue
                resolved = live.resolve_version(version_id) or {}
                trades = live.paper_trades_for_version(version_id, _BOOK_FROM, _BOOK_TO)
                recons = recon.list_by_version(version_id, 1, 0)
                out[cand["id"]] = {
                    "trades": trades,
                    "recon": recons[0] if recons else None,
                    "config": resolved.get("config") or {},
                }
        finally:
            live.close()
            recon.close()
        return out

    def _read_recons(
        self, candidates: list[dict[str, Any]]
    ) -> dict[str, list[dict[str, Any]]]:
        """The latest 2 reconciliations per PROMOTED candidate's version (the §7.3 rolling window —
        list_by_version is created_at DESC), for the DIVERGENT ×2 trigger."""
        out: dict[str, list[dict[str, Any]]] = {}
        recon = self._recon_factory()
        try:
            for cand in candidates:
                version_id = cand.get("versionId")
                out[cand["id"]] = recon.list_by_version(version_id, 2, 0) if version_id else []
        finally:
            recon.close()
        return out

    # --- E4 slice 2/3: the APPROVED-proposal actions (§8.2 materialization, items 12-13) ---------

    def execute(
        self, proposal_id: str, actor: str | None
    ) -> ExecutionResult | PromotionResult | RollbackResult:
        """Execute an APPROVED proposal — dispatched by kind: PUBLISH_PAPER creates + publishes the
        §8.2 sibling evo paper-lane clone (slice 2); PROMOTE publishes the candidate onto the base
        strategy + registers the demoted-champion counterfactual (slice 3); ROLLBACK copy-forward
        rolls the base back to the demoted champion (slice 3). This is the ONLY path that mutates
        the registry, and only on an already-APPROVED proposal via the explicit owner-clicked call
        (approval alone arms nothing). RETIRE is acknowledge-only → 422 (never executed).

        Errors: 404 unknown proposal / candidate / campaign; 422 unexecutable kind / no candidate /
        unresolved base lineage; 409 not-yet-approved / already-executed / the §1.4.3 per-family
        evo-paper CAP / registry slug-or-name collision; 502 any other registry failure."""
        if self._strategy is None or self._jobs_factory is None:
            raise ApiError(
                500, "EXECUTE_NOT_WIRED",
                "execute needs the strategy-signal client + jobs factory wired",
            )
        ctx = self._load_execute_context(proposal_id)
        kind = ctx["proposal"]["kind"]
        if kind == _PUBLISH_PAPER:
            return self._execute_publish_paper(ctx, actor)
        if kind == _PROMOTE:
            return self._execute_promote(ctx, actor)
        if kind == _ROLLBACK:
            return self._execute_rollback(ctx, actor)
        raise ApiError(
            422, "PROPOSAL_KIND_NOT_EXECUTABLE",
            f"proposal {proposal_id} is {kind} — not an executable action (RETIRE rows are "
            "auto-APPROVED acknowledge items, never executed)",
        )

    def _load_execute_context(self, proposal_id: str) -> dict[str, Any]:
        """Read + validate the proposal / candidate / campaign / generation (repo closed before any
        HTTP). Enforces the SHARED execute preconditions: APPROVED + has a candidate. The per-kind
        already-executed guard + the action live in each ``_execute_*`` handler."""
        repo = self._repo_factory()
        try:
            proposal = repo.get_proposal(proposal_id)
            if proposal is None:
                raise ApiError(404, "NOT_FOUND_PROPOSAL", f"no proposal {proposal_id}")
            if proposal["status"] != "APPROVED":
                raise ApiError(
                    409, "PROPOSAL_NOT_APPROVED",
                    f"proposal {proposal_id} is {proposal['status']} — a proposal must be APPROVED "
                    "before execute (approval is the owner gate)",
                )
            candidate_id = proposal.get("candidateId")
            if not candidate_id:
                raise ApiError(
                    422, "PROPOSAL_HAS_NO_CANDIDATE",
                    f"proposal {proposal_id} has no candidate — cannot execute an action",
                )
            candidate = repo.get_candidate(candidate_id)
            if candidate is None:
                raise ApiError(
                    404, "NOT_FOUND_CANDIDATE", f"no candidate {candidate_id}"
                )
            campaign = repo.get_campaign(proposal["campaignId"])
            if campaign is None:
                raise ApiError(
                    404, "NOT_FOUND_CAMPAIGN", f"no campaign {proposal['campaignId']}"
                )
            generation = repo.get_generation(candidate["generationId"])
        finally:
            repo.close()
        return {
            "proposal": proposal,
            "candidate": candidate,
            "campaign": campaign,
            "generation": generation,
        }

    @staticmethod
    def _guard_not_executed(proposal: dict[str, Any], marker: str) -> None:
        """409 if this proposal's action (``marker`` = the evidence sub-object: execution /
        promotion / rollback) already ran — the single-execute guard (append-only stamp)."""
        existing = (proposal.get("evidence") or {}).get(marker) or {}
        if existing.get("executed"):
            raise ApiError(
                409, "PROPOSAL_ALREADY_EXECUTED",
                f"proposal {proposal['id']} already executed its {marker} action",
            )

    def _execute_publish_paper(
        self, ctx: dict[str, Any], actor: str | None
    ) -> ExecutionResult:
        """Slice 2: create + publish the §8.2 sibling evo paper-lane clone, capped per §1.4.3."""
        self._guard_not_executed(ctx["proposal"], "execution")
        clone_config, meta = self._build_clone_config(ctx)
        self._check_family_cap(meta["family"])
        clone = self._create_and_publish(clone_config, meta)
        return self._stamp_execution(ctx, meta, clone, actor)

    def _build_clone_config(
        self, ctx: dict[str, Any]
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        """Reconstruct the candidate's config = the base version's YAML with the candidate's tuned
        params overlaid (config_patch, mirroring the promote path), then rewrite the sibling
        identity fields (id/slug, name, tags) per §8.2. Returns (clone_config, meta)."""
        candidate = ctx["candidate"]
        campaign = ctx["campaign"]
        generation = ctx["generation"]
        gen_n = generation["n"] if generation else 0

        sweep_id = candidate.get("sweepJobId")
        if not sweep_id:
            raise ApiError(
                422, "CANDIDATE_HAS_NO_SWEEP",
                f"candidate {candidate['id']} has no sweep lineage — base config unresolvable",
            )
        jobs = self._jobs_factory()
        try:
            job = jobs.get(sweep_id)
        finally:
            jobs.close()
        request = (job or {}).get("request") or {}
        base_strategy_id = request.get("strategyId") or campaign.get("strategyId")
        base_version = request.get("strategyVersion")
        if not base_strategy_id:
            raise ApiError(
                422, "BASE_STRATEGY_UNRESOLVED",
                f"candidate {candidate['id']}'s sweep {sweep_id} carries no strategyId — cannot "
                "resolve the base config",
            )
        if base_version:
            base_config = self._strategy.version_config(base_strategy_id, base_version)
        else:
            base_version, base_config = self._strategy.resolve(base_strategy_id, None)

        try:
            patched = config_patch.apply_overrides(base_config, candidate.get("params") or {})
        except InvalidParameterPath as exc:
            raise ApiError(
                422, "INVALID_PARAMETER_PATH",
                f"candidate params do not resolve against the base config: {exc}",
            ) from exc

        base_slug = str(base_config.get("id") or "")
        base_name = str(base_config.get("name") or base_slug or "strategy")
        short = _short_id(candidate["id"])
        clone_slug = _clone_slug(base_slug, gen_n, short)
        clone_name = _clone_name(base_name, gen_n, short)
        family = campaign.get("family")
        clone_tags = _clone_tags(family, patched.get("tags"))

        patched["id"] = clone_slug
        patched["name"] = clone_name
        patched["tags"] = clone_tags

        meta = {
            "family": family,
            "cloneSlug": clone_slug,
            "cloneName": clone_name,
            "cloneTags": clone_tags,
            "baseSlug": base_slug,
            "baseVersion": base_version,
            "generationN": gen_n,
        }
        return patched, meta

    def _check_family_cap(self, family: str | None) -> None:
        """§1.4.3: refuse to publish past the per-family evo-paper cap (default 2). Counts PUBLISHED
        registry strategies tagged ``evo`` whose tags include this family (the family book) —
        EXCLUDING demoted-champion counterfactuals (the ``counterfactual`` tag): their 6-week
        retention is design-MANDATED (§8.2), so it must not consume the budget that governs NEW
        candidate clones (else one promote jams the family at the cap)."""
        evo_clones = self._strategy.list_strategies(tag=_EVO_TAG, status="published")
        family_lower = (family or "").lower()
        live = []
        for s in evo_clones:
            tags_lower = [str(t).lower() for t in (s.get("tags") or [])]
            if family_lower and family_lower in tags_lower and _CF_TAG not in tags_lower:
                live.append(s)
        if len(live) >= self._evo_paper_cap:
            raise ApiError(
                409, "EVO_PAPER_CAP_REACHED",
                f"family {family!r} already has {len(live)} live evo paper clone(s) — the §1.4.3 "
                f"cap is {self._evo_paper_cap}; retire/archive one before publishing another "
                "(demoted-champion counterfactuals are exempt — mandated retention)",
            )

    def _create_and_publish(
        self, clone_config: dict[str, Any], meta: dict[str, Any]
    ) -> dict[str, Any]:
        """Create the clone strategy (draft), publish it, and read back the published version id.
        A registry 409 (duplicate slug OR name) surfaces as a clean 409; any other HTTP error is a
        502 (a registry fault, not a client error)."""
        campaign_family = meta["family"]
        description = (
            f"cloned_from {meta['baseSlug']}@{meta['baseVersion']} (evo g{meta['generationN']}); "
            f"sibling evo paper-lane clone (§8.2)"
        )
        created_by = f"evo:{campaign_family}"
        try:
            created = self._strategy.create(
                name=meta["cloneName"],
                description=description,
                tags=meta["cloneTags"],
                config=clone_config,
                created_by=created_by,
            )
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 409:
                raise ApiError(
                    409, "CLONE_ALREADY_EXISTS",
                    f"a strategy with slug {meta['cloneSlug']!r} or name {meta['cloneName']!r} "
                    "already exists — the clone was not created",
                ) from exc
            raise ApiError(
                502, "REGISTRY_CREATE_FAILED",
                f"registry rejected the clone create ({exc.response.status_code})",
            ) from exc
        clone_id = str(created["id"])
        self._strategy.publish(
            clone_id, notes=f"evo paper clone publish ({meta['cloneSlug']})"
        )
        detail = self._strategy.detail(clone_id)
        return {
            "id": clone_id,
            "versionId": detail.get("versionId"),
            "version": detail.get("version"),
            "status": detail.get("status"),
        }

    def _stamp_execution(
        self,
        ctx: dict[str, Any],
        meta: dict[str, Any],
        clone: dict[str, Any],
        actor: str | None,
    ) -> ExecutionResult:
        """Link the published clone back onto the candidate (version_id + state PAPER) and the
        proposal (the ``execution`` evidence sub-object). The SURVIVOR→PAPER transition marks the
        candidate as accruing live evidence (§8.1)."""
        proposal = ctx["proposal"]
        candidate = ctx["candidate"]
        execution = {
            "executed": True,
            "clonedStrategyId": clone["id"],
            "clonedSlug": meta["cloneSlug"],
            "clonedName": meta["cloneName"],
            "clonedVersion": clone["version"],
            "clonedVersionId": clone["versionId"],
            "clonedTags": meta["cloneTags"],
            "executedBy": actor,
        }
        merged_evidence = {**(proposal.get("evidence") or {}), "execution": execution}
        repo = self._repo_factory()
        try:
            repo.update_candidate_publish(candidate["id"], clone["versionId"], "PAPER")
            repo.record_proposal_execution(proposal["id"], merged_evidence)
        finally:
            repo.close()
        return ExecutionResult(
            proposalId=proposal["id"],
            campaignId=proposal["campaignId"],
            candidateId=candidate["id"],
            clonedStrategyId=clone["id"],
            clonedSlug=meta["cloneSlug"],
            clonedName=meta["cloneName"],
            clonedVersion=clone["version"],
            clonedVersionId=clone["versionId"],
            clonedTags=meta["cloneTags"],
            candidateState="PAPER",
            note=(
                "published the sibling evo paper-lane clone into the PAPER lane (family-tag book "
                "routing); no real-order path touched. The candidate is now PAPER — live evidence "
                "accrues for a later TAKE_ELIGIBLE assessment (a separate slice)."
            ),
        )

    # --- E4 slice 3: the APPROVED → PROMOTE / ROLLBACK actions (§8.2, item 13) -------------------

    def _resolve_base_strategy_id(self, ctx: dict[str, Any]) -> str:
        """The campaign's BASE strategy UUID — the promote/rollback target. Prefer the campaign's
        own ``strategyId``; fall back to the candidate's sweep request (the slice-2 lineage source).
        422 when neither resolves."""
        campaign = ctx["campaign"]
        base = campaign.get("strategyId")
        if base:
            return str(base)
        candidate = ctx["candidate"]
        sweep_id = candidate.get("sweepJobId")
        if sweep_id:
            jobs = self._jobs_factory()
            try:
                job = jobs.get(sweep_id)
            finally:
                jobs.close()
            base = ((job or {}).get("request") or {}).get("strategyId")
            if base:
                return str(base)
        raise ApiError(
            422, "BASE_STRATEGY_UNRESOLVED",
            f"candidate {ctx['candidate']['id']} has no resolvable base strategy (campaign "
            "strategyId + sweep strategyId both absent) — cannot promote",
        )

    def _execute_promote(self, ctx: dict[str, Any], actor: str | None) -> PromotionResult:
        """§8.2 PROMOTE: publish the candidate's config onto the BASE strategy (champion pointer
        moves), register the demoted-champion counterfactual so its P&L accrues live, and archive
        the candidate's now-redundant evo PAPER clone ("closing/retiring archives the clone" —
        fail-soft: an archive failure only stamps a warning, the promote already succeeded). The
        candidate advances TAKE_ELIGIBLE→PROMOTED; the campaign champion pointer moves. No
        real-order path is touched (paper/live-signal only)."""
        self._guard_not_executed(ctx["proposal"], "promotion")
        candidate = ctx["candidate"]
        campaign = ctx["campaign"]
        base_id = self._resolve_base_strategy_id(ctx)
        # The candidate's own evo PAPER clone (to archive after the champion move) — its strategy
        # id lives on the prior PUBLISH_PAPER proposal's execution stamp. Read BEFORE any HTTP.
        clone_strategy_id = self._find_candidate_clone_id(candidate["id"])

        # The DEMOTED champion = the base strategy's CURRENT published version, captured BEFORE the
        # candidate is published onto it (config + version UUID + semver — the rollback target).
        demoted = self._strategy.detail(base_id)
        demoted_config = demoted.get("config") or {}
        if not demoted_config:
            raise ApiError(
                422, "CHAMPION_CONFIG_UNRESOLVED",
                f"base strategy {base_id} detail carries no config — cannot build the promoted "
                "version or the counterfactual",
            )
        demoted_version_id = demoted.get("versionId")
        demoted_semver = demoted.get("version")

        # The promoted config = the demoted champion config with the candidate's tuned params
        # overlaid (KEEPS the base identity — it is published onto the base, not as a sibling).
        try:
            promoted_config = config_patch.apply_overrides(
                demoted_config, candidate.get("params") or {}
            )
        except InvalidParameterPath as exc:
            raise ApiError(
                422, "INVALID_PARAMETER_PATH",
                f"candidate params do not resolve against the base champion config: {exc}",
            ) from exc

        created_by = f"evo:{campaign.get('family')}"
        new_champion_version_id = self._publish_onto_base(
            base_id, promoted_config, candidate, created_by
        )
        # Champion has moved; register the demoted-champion counterfactual (fail-loud: a failure
        # surfaces 502 so the owner knows the counterfactual did NOT register — see open-doubts on
        # the non-transactional cross-service ordering).
        counterfactual = self._register_counterfactual(
            campaign, demoted_config, demoted_semver, candidate, created_by
        )
        # Archive the candidate's now-redundant evo PAPER clone (§8.2 "closing/retiring archives
        # the clone") — fail-soft AFTER the champion move: a failure stamps a warning, never a 502.
        clone_archive = self._archive_candidate_clone(clone_strategy_id)
        return self._stamp_promotion(
            ctx, base_id, new_champion_version_id, demoted_version_id, demoted_semver,
            counterfactual, clone_archive, actor,
        )

    def _find_candidate_clone_id(self, candidate_id: str) -> str | None:
        """The candidate's own evo PAPER clone strategy id — stamped on its prior PUBLISH_PAPER
        proposal's ``execution.clonedStrategyId`` (slice 2). ``None`` when the lineage is absent
        (e.g. a directly-seeded candidate) — archive then degrades to a warning, never a failure."""
        repo = self._repo_factory()
        try:
            prior = repo.find_latest_proposal(candidate_id, _PUBLISH_PAPER)
        finally:
            repo.close()
        execution = ((prior or {}).get("evidence") or {}).get("execution") or {}
        clone_id = execution.get("clonedStrategyId")
        return str(clone_id) if clone_id else None

    def _archive_candidate_clone(self, clone_strategy_id: str | None) -> dict[str, Any]:
        """Archive the candidate's redundant evo PAPER clone, FAIL-SOFT: the promote itself already
        succeeded, so an archive failure (or an unknown clone id) returns a warning block that is
        stamped on the promotion evidence + noted in the response — never a 5xx."""
        if not clone_strategy_id:
            return {
                "archived": False,
                "warning": (
                    "no PUBLISH_PAPER execution lineage on the candidate — its evo clone strategy "
                    "id is unknown; archive the redundant clone manually"
                ),
            }
        try:
            self._strategy.archive(clone_strategy_id)
        except httpx.HTTPError as exc:
            return {
                "archived": False,
                "clonedStrategyId": clone_strategy_id,
                "warning": (
                    f"archiving the redundant evo clone failed ({exc}) — the promote itself "
                    "succeeded; archive the clone manually"
                ),
            }
        return {"archived": True, "clonedStrategyId": clone_strategy_id}

    def _publish_onto_base(
        self, base_id: str, config: dict[str, Any], candidate: dict[str, Any], created_by: str
    ) -> str | None:
        """Create a new draft version on the BASE strategy from the promoted config, publish it (the
        champion pointer moves), and read back the new published version UUID. A registry HTTP fault
        surfaces as 502."""
        notes = f"evo PROMOTE — candidate {candidate['id']} (§8.2 champion move)"
        try:
            self._strategy.create_draft(base_id, config, notes, created_by=created_by)
            self._strategy.publish(base_id, notes=notes)
            detail = self._strategy.detail(base_id)
        except httpx.HTTPStatusError as exc:
            raise ApiError(
                502, "REGISTRY_PROMOTE_FAILED",
                f"registry rejected the champion move ({exc.response.status_code})",
            ) from exc
        return detail.get("versionId")

    def _register_counterfactual(
        self,
        campaign: dict[str, Any],
        demoted_config: dict[str, Any],
        demoted_semver: str | None,
        candidate: dict[str, Any],
        created_by: str,
    ) -> dict[str, Any]:
        """§8.2 demoted-champion counterfactual, routed by evidence policy: LIVE_FIRST (scalpers) →
        register the demoted champion as a shadow challenger variant (#733, the shadow book hosts
        scalper rejection-path entries only); else (swing / BTST) → a RETAINED paper-lane clone of
        the demoted champion (do NOT archive it — it accrues counterfactual P&L). A registry fault
        surfaces as 502 (or 409 on a clone slug/name collision)."""
        family = campaign.get("family")
        short = _short_id(candidate["id"])
        if campaign.get("evidencePolicy") == "LIVE_FIRST":
            spec, spec_caveats = _counterfactual_shadow_spec(demoted_config)
            name = _counterfactual_variant_name(family, short)
            try:
                row = self._strategy.register_shadow_variant(
                    name, campaign["id"], spec, created_by=created_by
                )
            except httpx.HTTPStatusError as exc:
                raise ApiError(
                    502, "COUNTERFACTUAL_REGISTER_FAILED",
                    f"registry rejected the demoted-champion shadow variant "
                    f"({exc.response.status_code}) — champion already moved; register the "
                    "counterfactual manually or roll back",
                ) from exc
            return {
                "mode": "shadow-variant",
                "name": name,
                "variantId": str(row.get("id")) if row.get("id") is not None else None,
                "spec": spec,
                "caveats": spec_caveats,
                "demotedChampionVersion": demoted_semver,
                "retention": _COUNTERFACTUAL_RETENTION,
            }
        cf_config, cf_meta = _counterfactual_clone_config(demoted_config, family, short)
        description = (
            f"demoted-champion counterfactual (evo, §8.2) — retained paper-lane clone of "
            f"{cf_meta['baseSlug']}@{demoted_semver}; do NOT archive"
        )
        try:
            created = self._strategy.create(
                name=cf_meta["name"], description=description, tags=cf_meta["tags"],
                config=cf_config, created_by=created_by,
            )
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 409:
                raise ApiError(
                    409, "COUNTERFACTUAL_ALREADY_EXISTS",
                    f"a strategy with slug {cf_meta['slug']!r} or name {cf_meta['name']!r} already "
                    "exists — the counterfactual clone was not created (champion already moved)",
                ) from exc
            raise ApiError(
                502, "COUNTERFACTUAL_REGISTER_FAILED",
                f"registry rejected the counterfactual clone create ({exc.response.status_code}) — "
                "champion already moved; register the counterfactual manually or roll back",
            ) from exc
        cf_id = str(created["id"])
        self._strategy.publish(cf_id, notes=f"evo counterfactual publish ({cf_meta['slug']})")
        detail = self._strategy.detail(cf_id)
        return {
            "mode": "retained-paper-clone",
            "clonedStrategyId": cf_id,
            "clonedSlug": cf_meta["slug"],
            "clonedName": cf_meta["name"],
            "clonedVersionId": detail.get("versionId"),
            "clonedTags": cf_meta["tags"],
            "demotedChampionVersion": demoted_semver,
            "retention": _COUNTERFACTUAL_RETENTION + " — do NOT archive",
        }

    def _stamp_promotion(
        self,
        ctx: dict[str, Any],
        base_id: str,
        new_champion_version_id: str | None,
        demoted_version_id: str | None,
        demoted_semver: str | None,
        counterfactual: dict[str, Any],
        clone_archive: dict[str, Any],
        actor: str | None,
    ) -> PromotionResult:
        """Advance the candidate TAKE_ELIGIBLE→PROMOTED (version_id = the new champion version),
        move the campaign champion pointer, and stamp the ``promotion`` evidence (incl. the rollback
        target: base + demoted-champion version, and the clone-archive outcome) onto the proposal
        (append-only)."""
        proposal = ctx["proposal"]
        candidate = ctx["candidate"]
        campaign = ctx["campaign"]
        promotion = {
            "executed": True,
            "baseStrategyId": base_id,
            "championVersionId": new_champion_version_id,
            "demotedChampionVersionId": demoted_version_id,
            "demotedChampionVersion": demoted_semver,
            "counterfactual": counterfactual,
            "cloneArchive": clone_archive,
            "retentionNote": _COUNTERFACTUAL_RETENTION,
            "executedBy": actor,
        }
        merged = {**(proposal.get("evidence") or {}), "promotion": promotion}
        repo = self._repo_factory()
        try:
            repo.update_candidate_publish(candidate["id"], new_champion_version_id, "PROMOTED")
            repo.record_proposal_execution(proposal["id"], merged)
            repo.update_campaign_champion(campaign["id"], new_champion_version_id)
        finally:
            repo.close()
        if clone_archive.get("archived"):
            archive_note = "The redundant evo PAPER clone was archived (engine unloads it)."
        else:
            archive_note = f"WARNING: {clone_archive.get('warning')}"
        return PromotionResult(
            proposalId=proposal["id"],
            campaignId=proposal["campaignId"],
            candidateId=candidate["id"],
            baseStrategyId=base_id,
            championVersionId=new_champion_version_id,
            demotedChampionVersionId=demoted_version_id,
            demotedChampionVersion=demoted_semver,
            counterfactual=counterfactual,
            cloneArchive=clone_archive,
            candidateState="PROMOTED",
            note=(
                "published the candidate onto the base strategy — the champion pointer moved. The "
                "demoted champion runs as the rollback counterfactual "
                f"({counterfactual.get('mode')}) for 6 weeks (§8.2). {archive_note} "
                "No real-order path touched."
            ),
        )

    def _execute_rollback(self, ctx: dict[str, Any], actor: str | None) -> RollbackResult:
        """§8.2 ROLLBACK: copy-forward the base strategy back to the demoted champion's version (the
        rollback target stamped on the ROLLBACK proposal at generation time) and republish it — the
        champion pointer returns. The candidate advances PROMOTED→ROLLED_BACK; the campaign champion
        pointer restores. No real-order path is touched."""
        self._guard_not_executed(ctx["proposal"], "rollback")
        proposal = ctx["proposal"]
        candidate = ctx["candidate"]
        campaign = ctx["campaign"]
        target = (proposal.get("evidence") or {}).get("rollbackTarget") or {}
        base_id = target.get("baseStrategyId")
        restore_version = target.get("demotedChampionVersion")
        demoted_version_id = target.get("demotedChampionVersionId")
        if not base_id or not restore_version:
            raise ApiError(
                422, "ROLLBACK_TARGET_UNRESOLVED",
                f"proposal {proposal['id']} has no rollback target (base strategy + "
                "demoted-champion version) — its PROMOTE lineage is missing; cannot roll back",
            )
        try:
            result = self._strategy.rollback(base_id, restore_version, and_publish=True)
            # A copy-forward MINTS a new version (a copy of the target) — read the base's current
            # published version UUID as the actual restored champion (the rollback response is a
            # generic Map; detail() is the authoritative version-id source).
            restored_version_id = self._strategy.detail(base_id).get("versionId")
        except httpx.HTTPStatusError as exc:
            raise ApiError(
                502, "REGISTRY_ROLLBACK_FAILED",
                f"registry rejected the rollback ({exc.response.status_code})",
            ) from exc

        rollback = {
            "executed": True,
            "baseStrategyId": base_id,
            "restoredVersion": restore_version,
            "restoredVersionId": restored_version_id,
            "targetDemotedVersionId": demoted_version_id,
            "registryResult": {"version": result.get("version"), "status": result.get("status")},
            "incidentNote": proposal.get("evidence", {}).get("incidentNote"),
            "executedBy": actor,
        }
        merged = {**(proposal.get("evidence") or {}), "rollback": rollback}
        repo = self._repo_factory()
        try:
            # The candidate keeps its promoted version_id (its identity) — only the state flips to
            # ROLLED_BACK. The CAMPAIGN champion pointer moves to the restored version.
            promoted_vid = candidate.get("versionId")
            repo.update_candidate_publish(candidate["id"], promoted_vid, "ROLLED_BACK")
            repo.record_proposal_execution(proposal["id"], merged)
            repo.update_campaign_champion(campaign["id"], restored_version_id)
        finally:
            repo.close()
        return RollbackResult(
            proposalId=proposal["id"],
            campaignId=proposal["campaignId"],
            candidateId=candidate["id"],
            baseStrategyId=base_id,
            restoredVersion=restore_version,
            candidateState="ROLLED_BACK",
            note=(
                "rolled the base strategy back to the demoted champion's version (copy-forward + "
                "publish) — the champion pointer restored. The promoted candidate is ROLLED_BACK; "
                "the incident is on the campaign audit trail. No real-order path touched."
            ),
        )

    def _decide(self, proposal_id: str, status: str, actor: str | None) -> dict[str, Any]:
        repo = self._repo_factory()
        try:
            row = repo.decide_proposal(proposal_id, status, actor)
            if row is None:
                existing = repo.get_proposal(proposal_id)
                if existing is None:
                    raise ApiError(404, "NOT_FOUND_PROPOSAL", f"no proposal {proposal_id}")
                raise ApiError(
                    409,
                    "PROPOSAL_ALREADY_DECIDED",
                    f"proposal {proposal_id} is {existing['status']} — only a PENDING proposal can "
                    "be approved/rejected",
                )
        finally:
            repo.close()
        return row

    def _notify_new(self, campaign: dict[str, Any], evidence: dict[str, Any]) -> None:
        family = campaign.get("family") or "?"
        robust = evidence.get("robustScore")
        title = f"Evo proposal: PUBLISH_PAPER ({family})"
        message = (
            f"Candidate {evidence.get('candidateId')} in campaign {campaign['id']} "
            f"(family {family}) reached the PUBLISH_PAPER bar (RobustScore={robust}). "
            "Review in the proposals inbox — approving only marks the row (substrate, item 11); "
            "nothing self-arms."
        )
        self._ntfy.send(title, message)

    def _notify_new_kind(
        self, campaign: dict[str, Any], evidence: dict[str, Any], kind: str
    ) -> None:
        """One fail-soft ntfy per NEW slice-3 proposal (PROMOTE / ROLLBACK). Same convention as
        ``_notify_new`` — the review is owner-gated; nothing self-arms."""
        family = campaign.get("family") or "?"
        if kind == _PROMOTE:
            bar = "reached the TAKE_ELIGIBLE bar (§8.2) — owner-approve to promote to champion"
        else:
            bar = "hit a ROLLBACK trigger (§7.3) — owner-approve to roll the champion back"
        title = f"Evo proposal: {kind} ({family})"
        message = (
            f"Candidate {evidence.get('candidateId')} in campaign {campaign['id']} "
            f"(family {family}) {bar}. Review in the proposals inbox — approving only marks the "
            "row; the action stays owner-clicked (execute). Nothing self-arms."
        )
        self._ntfy.send(title, message)


def _service(request: Request) -> ProposalService:
    return request.app.state.proposals


@router.post(
    "/campaigns/{campaign_id}/proposals", response_model=GenerateProposalsResponse
)
def generate_proposals(campaign_id: str, request: Request) -> GenerateProposalsResponse:
    """Generate/refresh PUBLISH_PAPER proposals for a campaign's eligible candidates (§8.2 bar).
    Idempotent — one OPEN proposal per (candidate, kind); regeneration refreshes. 404 unknown
    campaign. NOTHING self-arms: a proposal is an owner-inbox item, not an armed action."""
    return _service(request).generate_for_campaign(campaign_id)


@router.get("/proposals", response_model=ProposalListResponse)
def list_proposals(
    request: Request,
    status: str | None = None,
    kind: str | None = None,
    campaignId: str | None = None,
    limit: int = 50,
    offset: int = 0,
) -> ProposalListResponse:
    """The owner inbox — proposals newest-first, filterable by status / kind / campaign."""
    bounded_limit = min(max(limit, 1), 200)
    return _service(request).list_proposals(status, kind, campaignId, bounded_limit, max(offset, 0))


@router.post("/proposals/{proposal_id}/approve", response_model=ProposalDecision)
def approve_proposal(
    proposal_id: str, request: Request, body: dict[str, Any] | None = None
) -> ProposalDecision:
    """Approve a PENDING proposal — marks status APPROVED + actor + decided_at, and returns the
    slice-2 ``nextSteps`` it WOULD trigger (honest that the publish is not yet wired; nothing arms).
    404 unknown proposal; 409 if already decided/expired."""
    actor = (body or {}).get("actor") or "owner"
    return _service(request).approve(proposal_id, actor)


@router.post("/proposals/{proposal_id}/reject", response_model=ProposalDecision)
def reject_proposal(
    proposal_id: str, request: Request, body: dict[str, Any] | None = None
) -> ProposalDecision:
    """Reject a PENDING proposal — marks status REJECTED + actor + decided_at (§8.3 rejection is
    persisted, never silent). 404 unknown proposal; 409 if already decided/expired."""
    actor = (body or {}).get("actor") or "owner"
    return _service(request).reject(proposal_id, actor)


@router.post("/proposals/{proposal_id}/execute", response_model=None)
def execute_proposal(
    proposal_id: str, request: Request, body: dict[str, Any] | None = None
) -> ExecutionResult | PromotionResult | RollbackResult:
    """Execute an APPROVED proposal (§8.2 items 12-13), dispatched by kind: PUBLISH_PAPER →
    create+publish the sibling evo paper-lane clone; PROMOTE → publish the candidate onto the base
    strategy (champion move) + register the demoted-champion counterfactual; ROLLBACK → copy-forward
    roll the base back to the demoted champion. The ONLY registry-mutating path — explicit +
    owner-clicked; approval alone arms nothing; no real-order path exists. 404 unknown proposal /
    candidate / campaign; 409 not-approved / already-executed / per-family cap / registry collision;
    422 unexecutable kind (RETIRE) / unresolved base lineage; 502 registry fault."""
    actor = (body or {}).get("actor") or "owner"
    return _service(request).execute(proposal_id, actor)


@router.post(
    "/campaigns/{campaign_id}/take-eligible", response_model=AssessmentResponse
)
def assess_take_eligible(campaign_id: str, request: Request) -> AssessmentResponse:
    """Assess the campaign's PAPER candidates against the §8.2 TAKE_ELIGIBLE bar over each evo
    clone's live paper book (F7 thresholds + §7.2 live-gap gate), advancing the eligible ones to
    TAKE_ELIGIBLE and generating/refreshing an owner-inbox PROMOTE proposal. 404 unknown campaign.
    NOTHING self-arms — the PROMOTE ACTION stays owner-clicked (approve + execute)."""
    return _service(request).assess_take_eligible(campaign_id)


@router.post(
    "/campaigns/{campaign_id}/rollback-check", response_model=AssessmentResponse
)
def assess_rollback(campaign_id: str, request: Request) -> AssessmentResponse:
    """Assess the campaign's PROMOTED candidates against the §8.2 ROLLBACK trigger rules over their
    live evidence (this slice: DIVERGENT ×2, §7.3), generating/refreshing an owner-inbox ROLLBACK
    proposal for each that triggers. 404 unknown campaign. NOTHING self-arms — the ROLLBACK ACTION
    stays owner-clicked (approve + execute)."""
    return _service(request).assess_rollback(campaign_id)


# --- §8.2 sibling-clone identity helpers (slug/name/tags rewrite) --------------------------------


def _short_id(candidate_id: str) -> str:
    """A short, collision-resistant, slug-safe token from the candidate id — the TRAILING 8
    alphanumerics (the discriminating tail of a UUID; for a fixture id like ``gen-3-cand-1`` the
    tail still distinguishes siblings). Guarantees the ``g{gen}-{shortId}`` suffix is unique."""
    cleaned = re.sub(r"[^a-z0-9]", "", str(candidate_id).lower())
    return cleaned[-8:] or "cand"


def _clone_slug(base_slug: str, gen_n: int, short: str) -> str:
    """``{base-slug}--evo-g{gen}-{shortId}`` (§8.2), bounded to the schema slug max (64) — the
    suffix carries the uniqueness, so an over-long BASE is trimmed, never the suffix."""
    suffix = f"--evo-g{gen_n}-{short}"
    base = base_slug or "strategy"
    room = _SLUG_MAX - len(suffix)
    if len(base) > room:
        base = base[:max(room, 1)].rstrip("-") or base[:1]
    return f"{base}{suffix}"


def _clone_name(base_name: str, gen_n: int, short: str) -> str:
    """A unique, <=120-char display name for the clone (the registry 409s on a duplicate name too),
    trimming the base name if the suffix would overflow."""
    suffix = f" (evo g{gen_n} {short})"
    base = base_name or "strategy"
    room = _NAME_MAX - len(suffix)
    if len(base) > room:
        base = base[:max(room, 1)].rstrip()
    return f"{base}{suffix}"


def _clone_tags(family: str | None, base_tags: Any) -> list[str]:
    """§8.2: family tag FIRST (Books.fromTags routes to the family book), ``evo`` SECOND, then the
    base config's remaining tags (order-preserved, deduped, family/evo never repeated). A missing
    family degrades to just ``[evo, ...]`` (honest, never fabricated)."""
    ordered: list[str] = []
    if family:
        ordered.append(family)
    ordered.append(_EVO_TAG)
    for tag in base_tags or []:
        if tag not in ordered:
            ordered.append(tag)
    return ordered


# --- §8.2 demoted-champion counterfactual identity + shadow-spec helpers -------------------------


def _counterfactual_slug(base_slug: str, short: str) -> str:
    """``{base-slug}--evo-cf-{shortId}`` — the retained paper-lane counterfactual clone's slug. The
    ``cf`` infix keeps it distinct from a candidate's own ``--evo-g{gen}-`` paper clone; bounded to
    the schema slug max (64), the suffix carries the uniqueness so an over-long base is trimmed."""
    suffix = f"--evo-cf-{short}"
    base = base_slug or "strategy"
    room = _SLUG_MAX - len(suffix)
    if len(base) > room:
        base = base[:max(room, 1)].rstrip("-") or base[:1]
    return f"{base}{suffix}"


def _counterfactual_name(base_name: str, short: str) -> str:
    """A unique, <=120-char display name for the counterfactual clone (registry 409s on a dup name
    too), trimming the base name if the suffix would overflow."""
    suffix = f" (evo counterfactual {short})"
    base = base_name or "strategy"
    room = _NAME_MAX - len(suffix)
    if len(base) > room:
        base = base[:max(room, 1)].rstrip()
    return f"{base}{suffix}"


def _counterfactual_tags(family: str | None) -> list[str]:
    """Family tag FIRST (family-book routing), ``evo`` + ``counterfactual`` after — so it accrues in
    the family book AND is filterable as the demoted-champion comparator."""
    ordered: list[str] = []
    if family:
        ordered.append(family)
    ordered.extend([_EVO_TAG, _CF_TAG])
    return ordered


def _counterfactual_clone_config(
    demoted_config: dict[str, Any], family: str | None, short: str
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Build the retained paper-lane counterfactual clone config = a deep copy of the demoted
    champion config with the sibling identity (id/slug, name, tags) rewritten (§8.2). Returns
    ``(config, meta)``."""
    cf = config_patch.apply_overrides(demoted_config, {})  # deep copy, values untouched
    base_slug = str(demoted_config.get("id") or "")
    base_name = str(demoted_config.get("name") or base_slug or "strategy")
    slug = _counterfactual_slug(base_slug, short)
    name = _counterfactual_name(base_name, short)
    tags = _counterfactual_tags(family)
    cf["id"] = slug
    cf["name"] = name
    cf["tags"] = tags
    return cf, {"slug": slug, "name": name, "tags": tags, "baseSlug": base_slug}


def _counterfactual_variant_name(family: str | None, short: str) -> str:
    """The shadow-variant NAME for a scalper counterfactual — immutable + campaign-distinct
    (``evo-cf-{family}-{shortId}``). Names are immutable in the registry (a retire is terminal), so
    the candidate-tail short keeps re-promotions distinct."""
    fam = re.sub(r"[^a-z0-9]", "-", (family or "evo").lower()).strip("-") or "evo"
    return f"evo-cf-{fam}-{short}"


def _counterfactual_shadow_spec(
    demoted_config: dict[str, Any],
) -> tuple[dict[str, Any], list[str]]:
    """Project the demoted champion config onto the shadow-variant vocabulary body
    ``{rails, compositeThreshold}`` (#733). Best-effort: the composite threshold is lifted from a
    known config location; per-rail overrides are NOT derived (the full gate does not map onto the
    limited shadow vocabulary), so ``rails`` stays empty and the projection is caveated — the
    counterfactual re-scores the rejection stream at the champion threshold only, never fabricating
    a fuller spec."""
    composite = None
    for candidate in (
        (demoted_config.get("oi_confluence_gate") or {}).get("composite_threshold"),
        (demoted_config.get("entry_rules") or {}).get("scoring", {}).get("threshold"),
        demoted_config.get("compositeThreshold"),
    ):
        if candidate is not None:
            composite = candidate
            break
    spec: dict[str, Any] = {"rails": [], "compositeThreshold": composite}
    caveats = [
        "counterfactual shadow spec is a best-effort projection of the demoted champion's "
        "composite threshold onto the shadow vocabulary {rails, compositeThreshold}; per-rail gate "
        "semantics do not transfer — the counterfactual re-scores the rejection stream at the "
        "composite threshold only"
    ]
    return spec, caveats


# --- small numeric helpers (mirror scoring's; kept local so proposals stays self-contained) ------


def _num(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _round(value: Any) -> Any:
    return round(value, 4) if isinstance(value, int | float) else value
