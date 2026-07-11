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
    weights: dict[str, float]
    gates: list[GateResult]
    components: list[ComponentScore]
    penalties: Penalties
    flags: list[str] = []
    caveats: list[str] = []
    evidence: Evidence
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

# The standalone retro read has no campaign context, so its deflated-Sharpe multiplicity N is the
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
    here so a re-score reproduces the recorded card's deflated-Sharpe gate exactly."""

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
    ) -> None:
        self._jobs_factory = jobs_factory
        self._trials_factory = trials_factory
        self._backtest = backtest_client

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
        the deflated-Sharpe multiplicity N (§4). Raises 404 for an unknown/non-OPTIMIZATION job."""
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
            # Multiplicity N for the §4 deflated-Sharpe gate = the sweep's FULL trial count (every
            # state — failed/pruned trials are still "looks" the search took at the data), NOT just
            # the COMPLETE cohort scored below, PLUS the campaign's prior-generation trials when the
            # recorder supplies them. A dedicated count keeps the complete-cohort read (and its
            # truncation caveat) byte-identical; capped at _COHORT_CAP like the cohort.
            n_trials = len(trials.list_for_sweep(sweep_id, None, _COHORT_CAP, 0)) + prior_trials
        finally:
            jobs.close()
            trials.close()
        candidates = [self._assemble(row, metric) for row in rows]
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

    def score_sweep(self, sweep_id: str, prior_trials: int = 0) -> ScoredSweep:
        """Assemble + score an existing sweep's COMPLETE trials as a cohort — the shared core reused
        by the retro-score read AND the campaign recorder (do NOT duplicate this assembly). Reads
        the sweep job + its trials + each trial's backtest evidence, then scores the whole cohort
        via ``scoring.score_cohort``. ``prior_trials`` is the campaign's trials-to-date from PRIOR
        generations, added to this sweep's own full trial count to form the deflated-Sharpe
        multiplicity N (§4) — the recorder passes it; the standalone retro read has no campaign
        context so it stays 0 (per-sweep N, flagged by ``_RETRO_N_CAVEAT``). Returns the raw cards
        (no retro caveat appended — the caller stamps it) plus the frozen job/objective/parameters
        context. Raises 404 for an unknown / non-OPTIMIZATION job (the shared idiom)."""
        cohort = self.assemble_cohort(sweep_id, prior_trials)
        cards = scoring.score_cohort(
            cohort.candidates, cohort.parameters,
            direction=cohort.direction, n_trials=cohort.n_trials,
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
        # generation's deflated-Sharpe gate is campaign-CUMULATIVE: every prior generation's sweep
        # trials (all states, each generation's proposal carries its sweepJobId) + the current
        # sweep's own count (added inside score_sweep). The standalone retro read stays per-sweep.
        prior_trials = sum(self._scorer.count_trials(sid) for sid in prior_sweep_ids)

        # Score the cohort (reuses the retro assembly + score_cohort); 404 for an unknown sweep.
        scored = self._scorer.score_sweep(sweep_id, prior_trials=prior_trials)

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
        # engine_sha / data_epoch lifted from the sweep's run evidence when present (all a sweep's
        # trials share one engine SHA / data epoch — the first assembled candidate that carries them
        # is representative; NULL on runs predating #703 SHA-stamping — recorded, never fabricated).
        engine_sha = next((c["engineSha"] for c in scored.candidates if c.get("engineSha")), None)
        data_hash = next((c["dataHash"] for c in scored.candidates if c.get("dataHash")), None)
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
