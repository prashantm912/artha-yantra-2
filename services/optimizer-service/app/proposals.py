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

    # --- E4 slice 2: the APPROVED → PUBLISH_PAPER action (§8.2 materialization, item 12) ---------

    def execute(self, proposal_id: str, actor: str | None) -> ExecutionResult:
        """Execute an APPROVED PUBLISH_PAPER proposal — create + publish the §8.2 sibling evo
        paper-lane clone, then stamp its linkage back onto the candidate + proposal. This is the
        ONLY path that publishes; it runs only on an already-APPROVED proposal via the explicit
        owner-clicked endpoint (approval alone arms nothing). No scheduler, no auto-execute.

        Errors: 404 unknown proposal; 422 wrong kind / not a candidate proposal / missing base
        lineage; 409 not-yet-approved / already-executed / the §1.4.3 per-family evo-paper CAP; 409
        registry slug-or-name collision; 502 any other registry failure."""
        if self._strategy is None or self._jobs_factory is None:
            raise ApiError(
                500, "EXECUTE_NOT_WIRED",
                "PUBLISH_PAPER execute needs the strategy-signal client + jobs factory wired",
            )
        ctx = self._load_execute_context(proposal_id)
        clone_config, meta = self._build_clone_config(ctx)
        self._check_family_cap(meta["family"])
        clone = self._create_and_publish(clone_config, meta)
        return self._stamp_execution(ctx, meta, clone, actor)

    def _load_execute_context(self, proposal_id: str) -> dict[str, Any]:
        """Read + validate the proposal / candidate / campaign / generation (repo closed before any
        HTTP). Enforces the execute preconditions: APPROVED, PUBLISH_PAPER, has a candidate, not
        already executed."""
        repo = self._repo_factory()
        try:
            proposal = repo.get_proposal(proposal_id)
            if proposal is None:
                raise ApiError(404, "NOT_FOUND_PROPOSAL", f"no proposal {proposal_id}")
            if proposal["kind"] != _PUBLISH_PAPER:
                raise ApiError(
                    422, "PROPOSAL_KIND_NOT_EXECUTABLE",
                    f"proposal {proposal_id} is {proposal['kind']} — only PUBLISH_PAPER is "
                    "executable this slice (PROMOTE/ROLLBACK land later)",
                )
            if proposal["status"] != "APPROVED":
                raise ApiError(
                    409, "PROPOSAL_NOT_APPROVED",
                    f"proposal {proposal_id} is {proposal['status']} — a PUBLISH_PAPER proposal "
                    "must be APPROVED before execute (approval is the owner gate)",
                )
            if ((proposal.get("evidence") or {}).get("execution") or {}).get("executed"):
                existing = proposal["evidence"]["execution"]
                raise ApiError(
                    409, "PROPOSAL_ALREADY_EXECUTED",
                    f"proposal {proposal_id} already published clone "
                    f"{existing.get('clonedSlug')} ({existing.get('clonedStrategyId')})",
                )
            candidate_id = proposal.get("candidateId")
            if not candidate_id:
                raise ApiError(
                    422, "PROPOSAL_HAS_NO_CANDIDATE",
                    f"proposal {proposal_id} has no candidate — cannot materialize a clone",
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
        registry strategies tagged ``evo`` whose tags include this family (the family book)."""
        evo_clones = self._strategy.list_strategies(tag=_EVO_TAG, status="published")
        family_lower = (family or "").lower()
        live = [
            s for s in evo_clones
            if family_lower and family_lower in [str(t).lower() for t in (s.get("tags") or [])]
        ]
        if len(live) >= self._evo_paper_cap:
            raise ApiError(
                409, "EVO_PAPER_CAP_REACHED",
                f"family {family!r} already has {len(live)} live evo paper clone(s) — the §1.4.3 "
                f"cap is {self._evo_paper_cap}; retire/archive one before publishing another",
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


@router.post("/proposals/{proposal_id}/execute", response_model=ExecutionResult)
def execute_proposal(
    proposal_id: str, request: Request, body: dict[str, Any] | None = None
) -> ExecutionResult:
    """Execute an APPROVED PUBLISH_PAPER proposal (§8.2 item 12): create + publish the sibling evo
    paper-lane clone and stamp its linkage back onto the candidate + proposal. The ONLY publish
    path — explicit + owner-clicked; approval alone arms nothing, and the clone lands in the PAPER
    lane (no real-order path exists). 404 unknown proposal; 409 not-approved / already-executed /
    per-family cap; 422 wrong kind / unresolved base config; 502 registry fault."""
    actor = (body or {}).get("actor") or "owner"
    return _service(request).execute(proposal_id, actor)


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
