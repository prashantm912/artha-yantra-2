"""The evolution PROPOSALS substrate — inbox API + generation rules + ntfy (design §8 / §12 E4
item 11).

This slice builds the PROPOSAL SUBSTRATE, not the publish/promote ACTIONS (those are slice 2, §12
item 12+). It:

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

from collections.abc import Callable
from typing import Any

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app.errors import ApiError

router = APIRouter(prefix="/api/v1/evolution")

# §8.2 PUBLISH_PAPER: "SURVIVOR + RobustScore >= champion - ε (ε = 0.1) + holdout consumed".
_PUBLISH_PAPER = "PUBLISH_PAPER"
_EPSILON = 0.1


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
    """The approve/reject response: the decided row + (for approve) the slice-2 nextSteps."""

    proposal: ProposalModel
    nextSteps: NextSteps | None = None


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
            "substrate only (EVO E4 item 11): approving MARKS the proposal row; the actual "
            "publish (cloning the candidate into a sibling paper lane, item 12) is slice 2 and is "
            "NOT wired — nothing self-arms"
        ),
    }


def _next_steps(kind: str) -> NextSteps:
    """The slice-2 action an approval would trigger — named honestly as not-yet-wired substrate."""
    if kind == _PUBLISH_PAPER:
        return NextSteps(
            action=_PUBLISH_PAPER,
            status="PENDING_SLICE_2",
            note=(
                "this approval only MARKED the proposal row (substrate, EVO E4 item 11). "
                "The actual publish — cloning the candidate into a sibling evo paper-lane strategy "
                "(slug {base}--evo-g{gen}-{shortId}, §8.2) — is slice 2 (item 12) and is NOT yet "
                "wired; nothing is armed."
            ),
        )
    return NextSteps(
        action=kind,
        status="PENDING_SLICE_2",
        note="the approved action is not yet wired (E4 slice 2+); it marks the row only.",
    )


# --- service ------------------------------------------------------------------------------------


class ProposalService:
    """Generates PUBLISH_PAPER proposals from a campaign's candidates and serves the inbox
    (list / approve / reject). Collaborators injected (the EvoRepo factory + a fail-soft ntfy
    client), so tests drive it with the in-memory fakes. No autonomy — generation is owner/
    orchestrator-triggered; a decision only marks the row (nothing self-arms)."""

    def __init__(self, repo_factory: Callable[[], Any], ntfy: Any) -> None:
        self._repo_factory = repo_factory
        self._ntfy = ntfy

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
