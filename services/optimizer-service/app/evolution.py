"""The evolution-engine read surface under ``/api/v1/evolution`` (design §11 / §12 E1).

E1 ships the READ-ONLY view of the experiment model: list campaigns, a campaign detail
(with its generations), and a campaign's candidates. Every endpoint returns a typed
Pydantic model (house rule: never a bare ``dict``/Map shape), and lists use the
``{items: [...]}`` envelope. The write/scoring/recorder path is a later PR — nothing here
mutates. Empty tables (the state at deploy) return clean empty envelopes.
"""

from __future__ import annotations

import statistics
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import httpx
from fastapi import APIRouter, Request
from pydantic import BaseModel

from app import leaderboard, scoring
from app.errors import ApiError
from app.service import _PROMOTABLE, _primary_objective

router = APIRouter(prefix="/api/v1/evolution")


class GenerationModel(BaseModel):
    """One propose→evaluate→select iteration within a campaign (``evo_generations``)."""

    id: str
    campaignId: str
    n: int
    proposal: dict[str, Any] | None = None
    searchSpaceHash: str | None = None
    engineSha: str | None = None
    dataEpoch: dict[str, Any] | None = None
    stressTouches: int
    status: str | None = None
    startedAt: str | None = None
    finishedAt: str | None = None


class CampaignModel(BaseModel):
    """A long-lived evolution program for one base strategy (``evo_campaigns``)."""

    id: str
    strategyId: str | None = None
    family: str | None = None
    evidencePolicy: str
    objectiveSpec: dict[str, Any] | None = None
    searchSpace: dict[str, Any] | None = None
    budget: dict[str, Any] | None = None
    status: str
    championVersionId: str | None = None
    createdAt: str | None = None
    updatedAt: str | None = None


class CampaignDetail(CampaignModel):
    """A campaign plus its generations (the ``/campaigns/{id}`` detail shape)."""

    generations: list[GenerationModel] = []


class CampaignListResponse(BaseModel):
    items: list[CampaignModel]


class CandidateModel(BaseModel):
    """One concrete version under evaluation (``evo_candidates``); ``scorecard`` is the
    §6 gates/RobustScore JSONB passed through verbatim."""

    id: str
    generationId: str
    versionId: str | None = None
    parentCandidateId: str | None = None
    mutationKind: str | None = None
    params: dict[str, Any] | None = None
    structureDiff: dict[str, Any] | None = None
    sweepJobId: str | None = None
    holdoutRunId: str | None = None
    scorecard: dict[str, Any] | None = None
    state: str | None = None
    updatedAt: str | None = None


class CandidateListResponse(BaseModel):
    items: list[CandidateModel]


class EvoReadService:
    """Read-only orchestration over EvoRepo (opened per call via a factory, mirroring how
    SweepService uses its jobs/trials factories). Maps rows to the typed models and raises
    the shared 404 for an unknown campaign id."""

    def __init__(self, repo_factory: Callable[[], Any]) -> None:
        self._repo_factory = repo_factory

    def list_campaigns(self, limit: int, offset: int) -> CampaignListResponse:
        repo = self._repo_factory()
        try:
            rows = repo.list_campaigns(limit, offset)
        finally:
            repo.close()
        return CampaignListResponse(items=[CampaignModel(**r) for r in rows])

    def campaign_detail(self, campaign_id: str) -> CampaignDetail:
        repo = self._repo_factory()
        try:
            campaign = repo.get_campaign(campaign_id)
            if campaign is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            generations = repo.list_generations(campaign_id)
        finally:
            repo.close()
        return CampaignDetail(
            **campaign, generations=[GenerationModel(**g) for g in generations]
        )

    def campaign_candidates(self, campaign_id: str) -> CandidateListResponse:
        repo = self._repo_factory()
        try:
            if repo.get_campaign(campaign_id) is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            rows = repo.list_candidates_for_campaign(campaign_id)
        finally:
            repo.close()
        return CandidateListResponse(items=[CandidateModel(**r) for r in rows])


def _service(request: Request) -> EvoReadService:
    return request.app.state.evo


@router.get("/campaigns", response_model=CampaignListResponse)
def campaigns(request: Request, limit: int = 50, offset: int = 0) -> CampaignListResponse:
    """List evolution campaigns, newest first."""
    bounded_limit = min(max(limit, 1), 200)
    return _service(request).list_campaigns(bounded_limit, max(offset, 0))


@router.get("/campaigns/{campaign_id}", response_model=CampaignDetail)
def campaign(campaign_id: str, request: Request) -> CampaignDetail:
    """One campaign with its generations; 404 if unknown."""
    return _service(request).campaign_detail(campaign_id)


@router.get("/campaigns/{campaign_id}/candidates", response_model=CandidateListResponse)
def candidates(campaign_id: str, request: Request) -> CandidateListResponse:
    """The campaign's candidates (incl. the scorecard JSONB); 404 if the campaign is unknown."""
    return _service(request).campaign_candidates(campaign_id)


# --- Retro-scoring (§12 E1 item 2): the §6 scoring lib applied to an EXISTING sweep --------------
# Read-only. Scores a historical sweep's trials as a cohort — no evo_* writes (the campaign recorder
# is a later PR). The scorecard shape is scoring.score_cohort's output (design §6.3), served typed.


class GateResult(BaseModel):
    """One §6.1 hard gate: PASS / FAIL / SKIPPED / UNKNOWN / NOT_IMPLEMENTED + assessed value."""

    id: str
    status: str
    value: Any | None = None
    note: str | None = None


class ComponentScore(BaseModel):
    """One §6.2 RobustScore component: its cohort z-score + the raw constituents that fed it."""

    id: str
    z: float
    raw: dict[str, Any] = {}
    caveat: str | None = None


class Penalties(BaseModel):
    """§6.2 subtractive penalties: ``dof`` = 0.03 per tuned param over 4 + 0.06 per structure gate
    (E2, design §12 item 6); ``caveats`` = 0.05 per unresolved data caveat / sub-80% oiCoverage."""

    dof: float
    caveats: float


class Evidence(BaseModel):
    """The evidence chain behind a scorecard (§6.3). Retro has sim runs only — no holdout/live."""

    simRuns: list[str] = []
    holdoutRun: str | None = None
    liveWindow: dict[str, Any] | None = None


class StageReadiness(BaseModel):
    """The audit-PF-01 promotion-admission verdict for the SCORED→SURVIVOR stage, PER evidencePolicy
    (design §1.2) — fail-closed, DISTINCT from the descriptive ``rankable``. ``ready`` gates
    SURVIVOR selection; ``blockedBy`` names the required gates that FAILed or were never
    affirmatively evaluated. A typed field (not a loose dict) so the read never strips it (#7)."""

    evidencePolicy: str
    stage: str
    ready: bool
    requiredGates: list[str] = []
    blockedBy: list[str] = []
    # round-5 #3: True when the candidate is on the STALE signature side (a complete SHA/epoch
    # different from the cohort's dominant one) — selection REQUEUES it rather than retiring it.
    staleSignature: bool = False


class Scorecard(BaseModel):
    """A §6.3 scorecard for one sweep trial, cohort-normalized. Extends the ``/best`` leaderboard
    identity (trialNumber/runId/params) with the gates + RobustScore + penalties, rather than
    replacing it (audit §13.11)."""

    trialNumber: int | None = None
    runId: str | None = None
    params: dict[str, Any] | None = None
    policy: str
    direction: str
    robustScore: float
    rank: int | None = None
    rankable: bool
    stageReadiness: StageReadiness | None = None
    # round-5 #4: the candidate's OWN (engineSha, dataHash) provenance (not the cohort's) — stress
    # compares each candidate against this, so a minority-signature bag never falsely reads drifted.
    provenance: dict[str, Any] | None = None
    weights: dict[str, float]
    gates: list[GateResult]
    components: list[ComponentScore]
    penalties: Penalties
    flags: list[str] = []
    caveats: list[str] = []
    evidence: Evidence
    # §7.2 live-gap panel (E3, §12 item 10): verdict / gapZ / window / evidence-floor + whether
    # counted, and the §7.2.1-5 diagnosis checklist when DIVERGENT. None on a sim-only candidate.
    liveGap: dict[str, Any] | None = None
    comparator: dict[str, Any] | None = None


class RetroScoreResponse(BaseModel):
    """The scored cohort for one sweep — the ``{items: [...]}`` envelope plus the objective/policy
    context the FE leaderboard needs to render the ranking. ``caveats`` carries response-level
    warnings (e.g. a cohort truncated at the trial cap — its z-scores are over a partial cohort)."""

    sweepJobId: str
    metric: str
    direction: str
    policy: str
    caveats: list[str] = []
    items: list[Scorecard]


# The cohort read cap (mirrors the /best leaderboard's bound). At the cap the cohort MAY be
# truncated — flagged as a response caveat, never silent (z-scores over a partial cohort).
_COHORT_CAP = 1000

# Every retro scorecard carries this standing caveat (auditor resolution of the LIVE_FIRST doubt):
# retro-scoring is a descriptive read over sim evidence; it never routes or ranks by evidence
# policy — that arrives with the campaign recorder.
_RETRO_CAVEAT = (
    "retro-score is descriptive (sim evidence); for LIVE_FIRST families this never ranks — "
    "evidence-policy routing lands with campaigns"
)

# The standalone retro read has no campaign context, so its multiplicity-t-stat N is the
# sweep's own trial count only — design §4 charges "total trials-to-date", which the RECORDER
# computes (campaign-cumulative). Stamped on standalone retro cards only, never recorder cards.
_RETRO_N_CAVEAT = (
    "multiplicity N = this sweep only; campaign-cumulative N applies when recorded into a campaign"
)


@dataclass(frozen=True)
class AssembledCohort:
    """A sweep's cohort ASSEMBLED but not yet scored — the shared pre-scoring stage of
    ``score_sweep``, extracted so the E2 stress orchestrator can attach its per-candidate
    ``costResilience`` slope to the evidence bags BEFORE ``scoring.score_cohort`` runs (the retro
    read + recorder score straight through). ``n_trials`` is the §4 multiplicity N
    (this sweep's full trial count + any ``prior_trials``) the score MUST be computed with — carried
    here so a re-score reproduces the recorded card's multiplicity-t-stat gate exactly."""

    job: dict[str, Any]
    objective: dict[str, Any]
    metric: str
    direction: str
    parameters: list[dict[str, Any]]
    candidates: list[dict[str, Any]]
    n_trials: int
    truncated: bool


@dataclass(frozen=True)
class ScoredSweep:
    """The shared output of scoring one existing sweep's cohort — consumed by BOTH the retro-score
    READ (``retro_score``) and the campaign RECORDER (``EvoRecorderService.record_generation``), so
    the assembly + scoring lives in exactly ONE place. ``cards`` are the §6.3 scorecards WITHOUT the
    standing retro caveat appended — each caller decides whether to stamp it (the read always does;
    the recorder only for LIVE_FIRST). ``job`` / ``objective`` / ``parameters`` are the sweep's
    frozen context the recorder pre-registers into a generation's ``proposal`` (§3.1 snooping
    ledger); ``candidates`` are the assembled metric bags (carry engineSha / dataHash)."""

    job: dict[str, Any]
    objective: dict[str, Any]
    metric: str
    direction: str
    parameters: list[dict[str, Any]]
    candidates: list[dict[str, Any]]
    cards: list[dict[str, Any]]
    truncated: bool


def attach_reconciliations(
    candidates: list[dict[str, Any]], recon_repo: Any
) -> list[dict[str, Any]]:
    """E4 item-10 closure (design §7.1.4 / roadmap §12 item 10): for each candidate carrying a lived
    ``versionId``, fetch the LATEST applicable reconciliation for that version and attach it as the
    ``reconciliation`` dict that ``scoring.score_cohort`` already consumes — the §7.2 live-gap gate
    (verdict DIVERGENT → hard-fail, #738) and the ``live_alignment`` component then fire end-to-end.

    "latest applicable (version, window §7.1.4)" = the newest reconciliation row for the version
    (``ReconciliationRepo.list_by_version`` is created_at DESC — the current rolling 4-week window,
    §7.3); a version with no reconciliation attaches ``None`` (scoring treats it as sim-only —
    live_gap SKIPPED). Candidates WITHOUT a versionId are left untouched (byte-identical to the
    pre-item-10 retro path). The attached row is the repo read-envelope shape — verdict / gapZ /
    gap.mode / pairedTrades / evidenceFloorMet / diagnosis — exactly what scoring reads. Mutates in
    place and returns the list. scoring stays PURE (it only reads the dict, never the DB — this is
    the attach step, mirroring how the E2 stress orchestrator attaches ``costResilience``)."""
    for cand in candidates:
        version_id = cand.get("versionId")
        if not version_id:
            continue
        rows = recon_repo.list_by_version(version_id, 1, 0)
        cand["reconciliation"] = rows[0] if rows else None
    return candidates


class RetroScoreService:
    """Applies the §6 scoring library to an EXISTING sweep. Assembles each COMPLETE trial's
    normalized metric bag from its ``optimization_trials`` row + its backtest run's OOS fold array
    and run-level results (via BacktestClient — read-only §D.5 surfaces, never a recompute), then
    scores the whole cohort with ``scoring.score_cohort``. No ``evo_*`` writes (the recorder is a
    later PR). Collaborators injected (jobs/trials factories + backtest client), so tests drive it
    with the in-memory fakes.

    Per-run reads are a SEQUENTIAL N+1 fan-out (folds + results per trial) — accepted for E1's
    read-only retro surface (bounded by ``_COHORT_CAP``, backtest-service is loopback-local);
    campaign-scale orchestration (E3+) is where concurrency would land if ever needed."""

    def __init__(
        self,
        jobs_factory: Callable[[], Any],
        trials_factory: Callable[[], Any],
        backtest_client: Any,
        recon_factory: Callable[[], Any] | None = None,
    ) -> None:
        self._jobs_factory = jobs_factory
        self._trials_factory = trials_factory
        self._backtest = backtest_client
        # E4 item-10 closure: the reconciliation-store repo factory. Optional so the retro read /
        # recorder / stress paths (whose cohorts have no lived versionId) construct unchanged; when
        # present it feeds the (version, window §7.1.4) attach in assemble_cohort.
        self._recon_factory = recon_factory

    def count_trials(self, sweep_id: str) -> int:
        """One sweep's FULL trial count (every state — failed/pruned trials are still "looks" the
        search took at the data), capped at ``_COHORT_CAP``: the per-sweep contribution to the §4
        campaign-cumulative multiplicity N ("the campaign records total trials-to-date N")."""
        trials = self._trials_factory()
        try:
            return len(trials.list_for_sweep(sweep_id, None, _COHORT_CAP, 0))
        finally:
            trials.close()

    def assemble_cohort(self, sweep_id: str, prior_trials: int = 0) -> AssembledCohort:
        """Assemble a sweep's COMPLETE trials into the scoring lib's candidate bags, UNscored — the
        shared pre-scoring stage reused by ``score_sweep`` (the retro read + recorder) AND the E2
        stress orchestrator (which attaches ``costResilience`` before scoring). Reads the sweep job
        + its trials + each trial's backtest evidence. ``prior_trials`` is the campaign's
        trials-to-date from PRIOR generations, added to this sweep's own full trial count to form
        the multiplicity-t-stat N (§4). Raises 404 for an unknown/non-OPTIMIZATION job."""
        jobs = self._jobs_factory()
        trials = self._trials_factory()
        try:
            job = jobs.get(sweep_id)
            if job is None or job.get("kind") != "OPTIMIZATION":
                raise ApiError(404, "NOT_FOUND_JOB", f"no such sweep: {sweep_id}")
            request = job.get("request") or {}
            parameters = request.get("parameters", [])
            objective = request.get("objective", {})
            metric, direction = _primary_objective(objective)
            rows = trials.list_for_sweep(sweep_id, _PROMOTABLE, _COHORT_CAP, 0)
            # Multiplicity N for the multiplicity-t-stat gate (§4) = the sweep's FULL count (every
            # state — failed/pruned trials are still "looks" the search took at the data), NOT just
            # the COMPLETE cohort scored below, PLUS the campaign's prior-generation trials when the
            # recorder supplies them. A dedicated count keeps the complete-cohort read (and its
            # truncation caveat) byte-identical; capped at _COHORT_CAP like the cohort.
            n_trials = len(trials.list_for_sweep(sweep_id, None, _COHORT_CAP, 0)) + prior_trials
        finally:
            jobs.close()
            trials.close()
        candidates = [self._assemble(row, metric) for row in rows]
        self._attach_reconciliations(candidates)
        return AssembledCohort(
            job=job,
            objective=objective,
            metric=metric,
            direction=direction,
            parameters=parameters,
            candidates=candidates,
            n_trials=n_trials,
            truncated=len(rows) >= _COHORT_CAP,
        )

    def _attach_reconciliations(self, candidates: list[dict[str, Any]]) -> None:
        """E4 item-10 closure: for each candidate carrying a lived ``versionId``, attach its latest
        applicable reconciliation as the ``reconciliation`` dict scoring.py already consumes (the
        §7.2 live-gap gate / live_alignment component then fire off it). A no-op — and NO DB
        connection opened — when no candidate carries a versionId (the retro read + recorder +
        stress cohorts, whose trials have no lived version), so scoring is byte-identical. Guarded
        on the injected recon factory so the 3-arg constructions are unaffected."""
        if self._recon_factory is None or not any(c.get("versionId") for c in candidates):
            return
        recon = self._recon_factory()
        try:
            attach_reconciliations(candidates, recon)
        finally:
            recon.close()

    def score_sweep(
        self, sweep_id: str, prior_trials: int = 0, policy: str = "SIM_FIRST"
    ) -> ScoredSweep:
        """Assemble + score an existing sweep's COMPLETE trials as a cohort — the shared core reused
        by the retro-score read AND the campaign recorder (do NOT duplicate this assembly). Reads
        the sweep job + its trials + each trial's backtest evidence, then scores the whole cohort
        via ``scoring.score_cohort``. ``prior_trials`` is the campaign's trials-to-date from PRIOR
        generations, added to this sweep's own full trial count to form the multiplicity-t-stat
        N (§4) — the recorder passes it; the standalone retro read has no campaign context so it
        stays 0 (per-sweep N, flagged by ``_RETRO_N_CAVEAT``). ``policy`` is the campaign's
        evidencePolicy (design §1.2) — it drives the fail-closed STAGE-READINESS verdict (a
        LIVE_FIRST sim cohort is functional-smoke only → never stage-ready); the recorder passes the
        campaign's policy, the standalone retro read stays SIM_FIRST (descriptive). Returns the raw
        cards (no retro caveat appended — the caller stamps it) plus the frozen job/objective/
        parameters context. Raises 404 for an unknown / non-OPTIMIZATION job (the shared idiom)."""
        cohort = self.assemble_cohort(sweep_id, prior_trials)
        cards = scoring.score_cohort(
            cohort.candidates, cohort.parameters,
            direction=cohort.direction, n_trials=cohort.n_trials, policy=policy,
        )
        return ScoredSweep(
            job=cohort.job,
            objective=cohort.objective,
            metric=cohort.metric,
            direction=cohort.direction,
            parameters=cohort.parameters,
            candidates=cohort.candidates,
            cards=cards,
            truncated=cohort.truncated,
        )

    def retro_score(self, sweep_id: str) -> RetroScoreResponse:
        scored = self.score_sweep(sweep_id)
        for card in scored.cards:
            card["caveats"].append(_RETRO_CAVEAT)
            card["caveats"].append(_RETRO_N_CAVEAT)
        response_caveats = []
        if scored.truncated:
            response_caveats.append(
                f"cohort read capped at {_COHORT_CAP} COMPLETE trials — z-scores may be "
                "normalized over a partial cohort"
            )
        return RetroScoreResponse(
            sweepJobId=sweep_id,
            metric=scored.metric,
            direction=scored.direction,
            policy="SIM_FIRST",
            caveats=response_caveats,
            items=[Scorecard(**card) for card in scored.cards],
        )

    def _assemble(self, row: dict[str, Any], metric: str) -> dict[str, Any]:
        """Normalize one COMPLETE trial into the scoring lib's candidate shape. OOS-first (design
        §6.2 — "computed on OOS/live evidence only"): per-fold OOS metrics aggregate the cohort's
        return/risk signals; a foldless (full-window) run falls back to run-level metrics with a
        caveat. recoveryFactor is derived in Python — its numerator is the OOS-fold mean return
        when folds exist (run-level ``totalReturn`` on a walk-forward run is FULL-WINDOW train+test
        and would inflate exactly the overfit profile the OOS doctrine targets); run-level
        totalReturn feeds it only on the foldless path. turnover/tradeFrequency stay absent (not
        fabricated). A run whose evidence fetch fails (purged/404/timeout) degrades to a
        low-evidence candidate with a ``run fetch failed`` caveat — one dead run must never 500
        the whole retro-score."""
        run_id = row.get("backtestRunId")
        folds: list[dict[str, Any]] = []
        results: dict[str, Any] = {}
        fetch_caveat = None
        if run_id:
            try:
                folds = self._backtest.folds(run_id) or []
                results = self._backtest.results(run_id) or {}
            except httpx.HTTPError as exc:
                folds, results = [], {}
                fetch_caveat = f"run fetch failed: {exc}"
        metrics = results.get("metrics") or {}
        foldless = not folds
        fold_returns = _fold_series(folds, "totalReturn")
        obj_values = row.get("objectiveValues") or {}
        max_dd = _fold_worst(folds, "maxDrawdown") if folds else _num(metrics.get("maxDrawdown"))
        total_return = _num(metrics.get("totalReturn"))
        oos_return = _mean(fold_returns) if folds else total_return
        caveats = list(results.get("caveats") or [])
        if fetch_caveat:
            caveats.append(fetch_caveat)
        guard = leaderboard.guard_metrics(
            {"regimeOos": [f.get("regimeOos") or {} for f in folds]}
        )
        params = row.get("params") or {}
        return {
            "trialNumber": row.get("trialNumber"),
            "runId": run_id,
            "params": params,
            "rawObjective": _num(obj_values.get(metric)),
            "oosReturn": oos_return,
            "oosFoldStd": _pstdev(fold_returns),
            "foldReturns": fold_returns or None,
            "oosTradeCount": (
                _fold_sum_int(folds, "tradeCount") if folds else _int(metrics.get("tradeCount"))
            ),
            "sortino": _fold_mean(folds, "sortino") if folds else _num(metrics.get("sortino")),
            "sharpe": _fold_mean(folds, "sharpe") if folds else _num(metrics.get("sharpe")),
            "maxDrawdown": max_dd,
            "ddDurationBars": (
                _fold_worst(folds, "maxDrawdownDurationBars")
                if folds
                else _num(metrics.get("maxDrawdownDurationBars"))
            ),
            "totalReturn": total_return,
            "recoveryFactor": _recovery(oos_return, max_dd),
            "regimeOosMin": guard.get("regimeOosMin"),
            "regimeOosMean": guard.get("regimeOosMean"),
            "regimesCovered": guard.get("regimesCovered") or [],
            "expectancy": (
                _fold_mean(folds, "expectancy") if folds else _num(metrics.get("expectancy"))
            ),
            "turnover": None,
            "tradeFrequency": None,
            "engineSha": results.get("engineSha"),
            "dataHash": results.get("dataHash"),
            "caveats": caveats,
            "oiGateCoverage": metrics.get("oiGateCoverage"),
            # activeDOF for the DOF penalty + explainability comes from the sweep's tuned-param
            # count (len(parameters), the authoritative search dimensionality) — unified in
            # scoring.score_cohort, so a per-trial realized-param count is intentionally NOT carried
            # here (it would be a second, driftable source of the same quantity). structureGates
            # stay 0 until structure mutations exist (E5).
            "structureGateCount": 0,
            "holdoutRunId": None,
            "foldless": foldless,
        }


def _retro(request: Request) -> RetroScoreService:
    return request.app.state.retro


@router.get("/retro-score/{sweep_job_id}", response_model=RetroScoreResponse)
def retro_score(sweep_job_id: str, request: Request) -> RetroScoreResponse:
    """Score an existing sweep's trials as a cohort (§6 gates + RobustScore); 404 if unknown."""
    return _retro(request).retro_score(sweep_job_id)


# --- Campaign / generation recorder (§12 E1 item 3) ----------------------------------------------
# The WRITE surface: create a campaign, and record a manually-triggered, already-completed sweep as
# a campaign generation (scoring its cohort into evo_candidates rows). No autonomy — no scheduler,
# no proposals, no registry materialization (version_id stays NULL until E2). optimizer-service is
# the evo schema's writer (§2.2). The design keystone (§1.2): the engine REFUSES to score a
# candidate on an evidence plane its policy forbids — a SIM_BLOCKED campaign 422s before it scores.

# The evidence-policy enum, mirrored from the evo_campaigns CHECK constraint (V011). An unknown
# value is a 422 at the API rather than a psycopg CHECK violation (a clean, typed rejection).
_EVIDENCE_POLICIES = {"SIM_FIRST", "LIVE_FIRST", "SIM_BLOCKED"}


class GenerationRecorded(GenerationModel):
    """The recorder's response: the persisted generation (``evo_generations`` shape) plus the count
    of scorecards it wrote — the candidates themselves surface through GET
    /campaigns/{id}/candidates (this PR adds no new read shape)."""

    candidatesRecorded: int


# --- E4 slice-2 selection (SCORED→SURVIVOR, §8.1 / §12 item 12) -----------------------------------
# The per-generation SELECT step: promote a generation's SCORED candidates to SURVIVOR (the
# gate-passing top-K by RobustScore) or RETIRE the rest (hard-gate FAIL or dominated/below-top-K),
# recording the rationale on each candidate's scorecard. Pure ranking over already-scored cards — no
# scoring, no registry writes, no autonomy. SURVIVOR is exactly the state the slice-1 PUBLISH_PAPER
# generator predicates on, so selection makes that generator live end-to-end (record → score →
# select → generate). The §8.2 near-champion / holdout bars stay a PROPOSAL-time filter (slice 1).

# A candidate is re-selectable only from these states — SCORED (fresh) or a prior SURVIVOR/RETIRED
# (a re-run recomputes deterministically). A candidate that has ADVANCED past selection
# (PAPER/TAKE_ELIGIBLE/PROMOTED/ROLLED_BACK — it has live evidence accruing) is NEVER demoted by a
# re-select: it is excluded from both the ranking and any state write.
_SELECTABLE_STATES = frozenset({"SCORED", "SURVIVOR", "RETIRED"})

# The design pins survivors as the "plateau top-K, typically <= 15/gen" (§1.3 control loop step 4).
_DEFAULT_TOP_K = 15
_MAX_TOP_K = 50


class SelectionResult(BaseModel):
    """The outcome of one SELECT pass over a generation: the survivor/retired split, how many rows
    actually changed (idempotent re-run → 0), and the affected candidate rows (updated state +
    the ``selection`` rationale now on each scorecard)."""

    campaignId: str
    generationN: int
    generationId: str
    topK: int
    survivors: int
    retired: int
    updated: int
    # §8.2: each newly-RETIRED candidate gets an auto-APPROVED RETIRE acknowledge-row (actor
    # ``evo:{campaignId}``) — the count minted THIS pass (idempotent: a re-select mints none).
    retireAcks: int = 0
    items: list[CandidateModel] = []


class EvoRecorderService:
    """Records a manually-triggered sweep as a campaign generation. Two writes: ``create_campaign``
    (a new ACTIVE campaign) and ``record_generation`` (score an existing sweep's cohort → one
    generation + one SCORED candidate per trial). REUSES ``RetroScoreService.score_sweep`` for all
    assembly + scoring — this service adds only the campaign gating (policy refusal, idempotency)
    and the atomic persistence. Collaborators injected (the EvoRepo factory + the retro scorer) so
    tests drive it with the in-memory fakes."""

    def __init__(self, repo_factory: Callable[[], Any], scorer: RetroScoreService) -> None:
        self._repo_factory = repo_factory
        self._scorer = scorer

    def create_campaign(self, body: dict[str, Any]) -> CampaignModel:
        strategy_id = body.get("strategyId")
        family = body.get("family")
        policy = body.get("evidencePolicy")
        for field, value in (
            ("strategyId", strategy_id), ("family", family), ("evidencePolicy", policy)
        ):
            if not value:
                raise ApiError(400, "VALIDATION_FAILED", f"missing required field: {field}")
        if policy not in _EVIDENCE_POLICIES:
            raise ApiError(
                422,
                "VALIDATION_FAILED",
                f"unknown evidencePolicy {policy!r}; expected one of {sorted(_EVIDENCE_POLICIES)}",
            )
        repo = self._repo_factory()
        try:
            row = repo.create_campaign(
                strategy_id, family, policy,
                body.get("objectiveSpec"), body.get("searchSpace"), body.get("budget"),
            )
        finally:
            repo.close()
        return CampaignModel(**row)

    def record_generation(self, campaign_id: str, body: dict[str, Any]) -> GenerationRecorded:
        sweep_id = body.get("sweepJobId")
        if not sweep_id:
            raise ApiError(400, "VALIDATION_FAILED", "missing required field: sweepJobId")

        # Gate BEFORE scoring (both to honor the evidence-policy refusal and to never waste a
        # scoring pass on a duplicate): the campaign must exist, its policy must permit sim scoring,
        # and this sweep must not already be recorded.
        repo = self._repo_factory()
        try:
            campaign = repo.get_campaign(campaign_id)
            if campaign is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            policy = campaign["evidencePolicy"]
            if policy == "SIM_BLOCKED":
                raise ApiError(
                    422,
                    "EVIDENCE_POLICY_BLOCKED",
                    f"campaign {campaign_id} is SIM_BLOCKED — a sim sweep is not scorable evidence "
                    "for this family (design §1.2); record live/paper evidence instead",
                )
            prior_sweep_ids: list[str] = []
            for gen in repo.list_generations(campaign_id):
                prior_id = (gen.get("proposal") or {}).get("sweepJobId")
                if prior_id == sweep_id:
                    raise ApiError(
                        409,
                        "CONFLICT_SWEEP_RECORDED",
                        f"sweep {sweep_id} is already recorded as generation {gen['n']} of "
                        f"campaign {campaign_id}",
                    )
                if prior_id:
                    prior_sweep_ids.append(prior_id)
        finally:
            repo.close()

        # §4: "the campaign records total trials-to-date N" — the multiplicity charged to this
        # generation's multiplicity-t-stat gate is campaign-CUMULATIVE: every prior generation's
        # trials (all states, each generation's proposal carries its sweepJobId) + the current
        # sweep's own count (added inside score_sweep). The standalone retro read stays per-sweep.
        prior_trials = sum(self._scorer.count_trials(sid) for sid in prior_sweep_ids)

        # Score the cohort (reuses the retro assembly + score_cohort); 404 for an unknown sweep.
        # The campaign's evidencePolicy drives the fail-closed stage-readiness verdict (§1.2): a
        # LIVE_FIRST sim cohort is functional-smoke only and can NEVER become a SURVIVOR from sim.
        scored = self._scorer.score_sweep(sweep_id, prior_trials=prior_trials, policy=policy)

        # A generation freezes its cohort at registration: recording a still-running sweep would
        # persist a PARTIAL cohort, and the 409 idempotency above would then lock out the full one.
        if scored.job.get("status") != "completed":
            raise ApiError(
                422,
                "SWEEP_NOT_COMPLETED",
                f"sweep {sweep_id} is {scored.job.get('status')!r} — only a completed sweep is "
                "recordable as a generation (a partial cohort must never freeze)",
            )

        # LIVE_FIRST: sim evidence is functional-smoke only, never a ranking plane (§1.2) — stamp
        # every persisted scorecard with the standing descriptive caveat so the record is honest.
        if policy == "LIVE_FIRST":
            for card in scored.cards:
                card["caveats"].append(_RETRO_CAVEAT)

        # The generation's proposal IS the §3.1 pre-registration ledger — sampler/objective/search
        # space frozen from the sweep at registration time (created_at proves "registered when").
        proposal = {
            "source": "manual",
            "sweepJobId": sweep_id,
            "objective": scored.objective,
            "direction": scored.direction,
            "parameters": scored.parameters,
        }
        # engine_sha / data_epoch = the EXACT DOMINANT (engineSha, dataHash) signature scoring used
        # to partition/normalize (round-5 #4) — NOT an independent first-non-null of each field,
        # which could persist a minority/synthetic tuple and make stress falsely flag the unchanged
        # dominant candidates as drifted. NULL on runs predating #703 SHA-stamping (no complete
        # signature) — recorded, never fabricated.
        engine_sha, data_hash = scoring._dominant_signature(scored.candidates)
        data_epoch = {"dataHash": data_hash} if data_hash else None
        candidate_rows = [
            {
                "mutationKind": "PARAMS",
                "params": card.get("params") or {},
                "sweepJobId": sweep_id,
                "scorecard": card,
                "state": "SCORED",
            }
            for card in scored.cards
        ]

        # Persist the generation + all its candidates in ONE transaction (EvoRepo.record_generation)
        # — the scoring above already succeeded, so a generation is never left half-recorded.
        repo = self._repo_factory()
        try:
            gen = repo.record_generation(
                campaign_id=campaign_id,
                proposal=proposal,
                engine_sha=engine_sha,
                data_epoch=data_epoch,
                status="DONE",
                started_at=scored.job.get("startedAt"),
                finished_at=scored.job.get("finishedAt"),
                candidates=candidate_rows,
            )
        finally:
            repo.close()
        return GenerationRecorded(**gen, candidatesRecorded=len(candidate_rows))

    def select_survivors(
        self, campaign_id: str, n: int, top_k: int
    ) -> SelectionResult:
        """Promote generation ``n``'s SCORED candidates → SURVIVOR (gate-passing top-K by
        RobustScore) / RETIRED (§8.1). Idempotent: a re-run recomputes the same split and writes
        only rows whose state OR rationale changed. Candidates already advanced past selection
        (PAPER/…/PROMOTED) are untouched — never demoted. 404 unknown campaign / generation."""
        top_k = min(max(top_k, 1), _MAX_TOP_K)
        repo = self._repo_factory()
        try:
            if repo.get_campaign(campaign_id) is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            generation = repo.get_generation_by_n(campaign_id, n)
            if generation is None:
                raise ApiError(
                    404, "NOT_FOUND_GENERATION",
                    f"campaign {campaign_id} has no generation {n}",
                )
            generation_id = generation["id"]
            candidates = repo.list_candidates_for_generation(generation_id)
            decisions = _decide_selection(candidates, top_k, n)
            updated: list[dict[str, Any]] = []
            retire_acks = 0
            for decision in decisions:
                if decision["changed"]:
                    row = repo.update_candidate_selection(
                        decision["candidateId"], decision["state"], decision["scorecard"]
                    )
                    if row is not None:
                        updated.append(row)
                # §8.2: RETIRE applies autonomously (no owner gate) — write an auto-APPROVED RETIRE
                # acknowledge-row so the inbox shows it as a review/acknowledge item (§8.2 audit
                # trail). Idempotent: one RETIRE ack per candidate (a re-select mints none).
                if decision["state"] == "RETIRED" and _ensure_retire_ack(
                    repo, campaign_id, decision, n
                ):
                    retire_acks += 1
        finally:
            repo.close()
        return SelectionResult(
            campaignId=campaign_id,
            generationN=n,
            generationId=generation_id,
            topK=top_k,
            survivors=sum(1 for d in decisions if d["state"] == "SURVIVOR"),
            retired=sum(1 for d in decisions if d["state"] == "RETIRED"),
            updated=len(updated),
            retireAcks=retire_acks,
            items=[CandidateModel(**r) for r in updated],
        )


# The autonomous-RETIRE acknowledge-row kind (§8.2). Kept as a local literal — evolution.py must not
# import proposals.py (they both hang off the /api/v1/evolution router; a one-way import is harmless
# today but the literal keeps the modules decoupled).
_RETIRE_KIND = "RETIRE"


def _ensure_retire_ack(
    repo: Any, campaign_id: str, decision: dict[str, Any], n: int
) -> bool:
    """§8.2: write an auto-APPROVED RETIRE acknowledge-row for a newly-RETIRED candidate (actor
    ``evo:{campaignId}``), idempotently — one RETIRE proposal per candidate (a re-select finds the
    existing one and mints none). Returns True iff a new ack row was written."""
    candidate_id = decision["candidateId"]
    if repo.find_latest_proposal(candidate_id, _RETIRE_KIND) is not None:
        return False
    selection = (decision.get("scorecard") or {}).get("selection") or {}
    evidence = {
        "kind": _RETIRE_KIND,
        "candidateId": candidate_id,
        "decision": "RETIRED",
        "reason": selection.get("reason"),
        "generationN": n,
        "note": (
            "autonomous RETIRE (§8.2) — auto-APPROVED acknowledge row, no owner gate. Retired != "
            "deleted: the archived draft + scorecard stay, reversible by re-proposing."
        ),
    }
    repo.insert_acknowledged_proposal(
        campaign_id, candidate_id, _RETIRE_KIND, evidence, f"evo:{campaign_id}"
    )
    return True


def _decide_selection(
    candidates: list[dict[str, Any]], top_k: int, n: int
) -> list[dict[str, Any]]:
    """Pure §8.1 selection over a generation's candidates. SELECTABLE = SCORED/SURVIVOR/RETIRED
    (advanced-state candidates are skipped entirely). Among the selectable, ELIGIBLE FOR SELECTION =
    fail-closed STAGE READINESS (audit PF-01: ``scorecard.stageReadiness.ready`` — a required gate
    that FAILed OR was never affirmatively evaluated blocks; a LIVE_FIRST sim-smoke is never ready)
    AND a RobustScore present; rank those by RobustScore desc, keep the top-K as SURVIVOR and RETIRE
    the rest (dominated); the not-ready → RETIRED, EXCEPT a STALE-SIGNATURE candidate (round-5 #3):
    a candidate from a different SHA/data epoch than the cohort's dominant one is NOT comparable
    generation but must be RE-QUEUED not retired (§1.3 "the stale side is re-queued") — it keeps its
    state so it is re-evaluated under the current epoch. Returns one decision per selectable
    candidate: the new state, the scorecard with the ``selection`` rationale added, and whether it
    CHANGED."""
    selectable = [c for c in candidates if c.get("state") in _SELECTABLE_STATES]
    ranked = sorted((c for c in selectable if _is_stage_ready(c)), key=_selection_sort_key)
    rank_by_id = {c["id"]: position for position, c in enumerate(ranked, start=1)}
    rankable_count = len(ranked)

    decisions: list[dict[str, Any]] = []
    for cand in selectable:
        scorecard = cand.get("scorecard") or {}
        robust = _num(scorecard.get("robustScore"))
        rank = rank_by_id.get(cand["id"])
        readiness = scorecard.get("stageReadiness") or {}
        if rank is not None:
            if rank <= top_k:
                state = "SURVIVOR"
                decision = "SURVIVOR"
                reason = f"top-{top_k} by RobustScore (rank {rank}/{rankable_count})"
            else:
                state = "RETIRED"
                decision = "RETIRED"
                reason = (
                    f"dominated: RobustScore rank {rank}/{rankable_count} beyond top-{top_k}"
                )
        elif readiness.get("staleSignature"):
            # §1.3: the stale-signature side is RE-QUEUED, never retired. round-6 #3: park it in the
            # durable REQUEUED state (NOT a cosmetic SCORED label) — REQUEUED is EXCLUDED from
            # re-selection as-is (_SELECTABLE_STATES), so repeating selection never re-processes the
            # same stale evidence. An external scheduler resubmits a REQUEUED candidate's run under
            # the authoritative epoch and flips it back to SCORED with fresh evidence (the actual
            # re-run is that separate orchestration mechanism, not selection).
            state = "REQUEUED"
            decision = "REQUEUED"
            reason = (
                "re-queued: stale signature (a different engine SHA / data epoch than the cohort's "
                "dominant one) — not comparable this generation; parked in REQUEUED (excluded from "
                "re-selection) pending an external re-run under the current epoch (§1.3)"
            )
        else:
            state = "RETIRED"
            decision = "RETIRED"
            reason = _retire_reason(scorecard, robust)
        selection = {
            "decision": decision,
            "reason": reason,
            "rank": rank,
            "rankableCohort": rankable_count,
            "topK": top_k,
            "generationN": n,
            "robustScore": robust,
        }
        new_scorecard = {**scorecard, "selection": selection}
        changed = state != cand.get("state") or scorecard.get("selection") != selection
        decisions.append(
            {
                "candidateId": cand["id"],
                "state": state,
                "scorecard": new_scorecard,
                "changed": changed,
            }
        )
    return decisions


def _is_stage_ready(cand: dict[str, Any]) -> bool:
    """ELIGIBLE FOR SELECTION (audit PF-01) = the fail-closed promotion-admission verdict
    ``scorecard.stageReadiness.ready`` (NOT the permissive descriptive ``rankable``) AND a
    RobustScore present to sort on. A card with no ``stageReadiness`` (pre-PF-01 / a foreign card)
    is treated as NOT ready — fail-closed by construction, never admitted by omission."""
    scorecard = cand.get("scorecard") or {}
    readiness = scorecard.get("stageReadiness") or {}
    ready = bool(readiness.get("ready"))
    return ready and _num(scorecard.get("robustScore")) is not None


def _selection_sort_key(cand: dict[str, Any]) -> tuple[float, float, str]:
    """RobustScore desc, then trialNumber asc, then candidate id — deterministic across re-runs
    (mirrors scoring's rank tiebreak on trialNumber)."""
    scorecard = cand.get("scorecard") or {}
    robust = _num(scorecard.get("robustScore")) or 0.0
    trial = scorecard.get("trialNumber")
    trial_key = float(trial) if isinstance(trial, int | float) else float("inf")
    return (-robust, trial_key, str(cand.get("id")))


def _retire_reason(scorecard: dict[str, Any], robust: float | None) -> str:
    blocked = (scorecard.get("stageReadiness") or {}).get("blockedBy") or []
    if blocked:
        return f"not stage-ready: {', '.join(str(b) for b in blocked)}"
    failed = [g.get("id") for g in scorecard.get("gates") or [] if g.get("status") == "FAIL"]
    if failed:
        return f"hard-gate FAIL: {', '.join(str(g) for g in failed)}"
    if robust is None:
        return "not stage-ready: no RobustScore in the scorecard"
    return "not stage-ready"


def _writer(request: Request) -> EvoRecorderService:
    return request.app.state.evo_writer


@router.post("/campaigns", response_model=CampaignModel, status_code=201)
def create_campaign(body: dict[str, Any], request: Request) -> CampaignModel:
    """Create an evolution campaign (status ACTIVE); 422 on an unknown evidencePolicy."""
    return _writer(request).create_campaign(body)


@router.post(
    "/campaigns/{campaign_id}/generations",
    response_model=GenerationRecorded,
    status_code=201,
)
def record_generation(
    campaign_id: str, body: dict[str, Any], request: Request
) -> GenerationRecorded:
    """Record a completed sweep as this campaign's next generation, scoring its cohort into
    candidate scorecards. 404 unknown campaign / sweep; 409 if the sweep is already recorded; 422 if
    the campaign's evidence policy forbids sim scoring (SIM_BLOCKED)."""
    return _writer(request).record_generation(campaign_id, body)


@router.post(
    "/campaigns/{campaign_id}/generations/{n}/select",
    response_model=SelectionResult,
)
def select_survivors(
    campaign_id: str, n: int, request: Request, body: dict[str, Any] | None = None
) -> SelectionResult:
    """Run the §8.1 SELECT step over generation ``n``: promote the gate-passing top-K candidates by
    RobustScore to SURVIVOR and RETIRE the rest, recording the rationale on each scorecard. Body
    ``{topK}`` optional (default 15, bounded 1..50). Idempotent — a re-run recomputes the same split
    and writes only rows that changed. 404 unknown campaign / generation. NOTHING self-arms:
    SURVIVOR only makes a candidate ELIGIBLE for a PUBLISH_PAPER proposal; the owner still approves
    and executes it."""
    top_k = int((body or {}).get("topK") or _DEFAULT_TOP_K)
    return _writer(request).select_survivors(campaign_id, n, top_k)


# --- fold/run metric-bag helpers (assembly only; the scoring math lives in scoring.py) ----------

def _fold_series(folds: list[dict[str, Any]], key: str) -> list[float]:
    """The present per-fold OOS values for ``key`` (skips folds missing it), in fold order."""
    out: list[float] = []
    for fold in folds:
        val = _num((fold.get("oosMetrics") or {}).get(key))
        if val is not None:
            out.append(val)
    return out


def _fold_mean(folds: list[dict[str, Any]], key: str) -> float | None:
    series = _fold_series(folds, key)
    return statistics.fmean(series) if series else None


def _fold_worst(folds: list[dict[str, Any]], key: str) -> float | None:
    """The worst (max) per-fold OOS value — used for drawdown magnitude / duration (bigger = worse),
    a conservative OOS proxy for the run's p95(maxDD) until Monte Carlo lands in E2."""
    series = _fold_series(folds, key)
    return max(series) if series else None


def _fold_sum_int(folds: list[dict[str, Any]], key: str) -> int | None:
    series = _fold_series(folds, key)
    return int(sum(series)) if series else None


def _mean(values: list[float]) -> float | None:
    return statistics.fmean(values) if values else None


def _pstdev(values: list[float]) -> float | None:
    return statistics.pstdev(values) if len(values) >= 2 else None


def _recovery(total_return: float | None, max_dd: float | None) -> float | None:
    """recoveryFactor = totalReturn ÷ maxDrawdown (design §6.2), guarding a zero/None drawdown."""
    if total_return is None or max_dd is None or max_dd == 0:
        return None
    return total_return / max_dd


def _num(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _int(value: Any) -> int | None:
    num = _num(value)
    return int(num) if num is not None else None
