"""EVO E2 cost-stress orchestration (design §3.2.5 / §6.2 / §12 E2 item 5).

Re-runs a generation's plateau top-K at DOUBLED and QUADRUPLED slippage (the backtest-service
``stressOverrides {slippageMultiplier}`` request field, #727), measures each candidate's objective
DEGRADATION SLOPE across 1×/2×/4×, and re-scores the generation's cohort so the §6.2
``cost_resilience`` component — a z=0 stub with a "no stress runs (E2)" caveat until now — carries a
real signal.

Mechanics, deliberately mirroring the neighbor-probe orchestrator (#728) where they fit:
  * A stress run is an ORDINARY BACKTEST job (kind=BACKTEST, ``JobsRepo.insert_backtest``), NEVER a
    trial — it never enters the ``optimization_trials`` cohort/plateau/z-cohorts and never emits
    onto ``optimizations.results`` (only a TRIAL does). Its request = the sweep's own trial template
    (``child_trial_request`` — same resolved version/window/fold context) with ``paramsOverride``
    swapped to the candidate's params and ``stressOverrides`` added; BacktestRunner applies both off
    the request JSONB regardless of kind.
  * Dispatch is synchronous (returns a 202 receipt); a background daemon thread POLLS each run's
    ``/jobs/{id}`` status to completion (bounded), reads the stressed objective off ``/results``,
    then re-scores. One dead run never wedges the round — a bounded poll + terminal-status reconcile
    degrade it to a missing point.
  * The re-score REASSEMBLES the generation's cohort (``RetroScoreService.assemble_cohort`` — the
    same assembly the recorder used, at the same campaign-cumulative N), attaches each stressed
    candidate's slope, calls ``scoring.score_cohort``, and UPDATEs every candidate's scorecard +
    bumps ``stress_touches`` ONCE, atomically. A candidate whose re-assembled evidence DRIFTED
    since recording (purged run / engineSha / dataHash mismatch) keeps its stored card + a skip
    caveat — a stress round must never silently rewrite gates from different evidence.
  * Round lifecycle is DURABLE: ``evo_generations.status`` flips to ``STRESSING`` (committed) at
    dispatch and back to ``DONE`` inside the same atomic txn that applies the round; a restart
    mid-round leaves a STRESSING orphan the boot reaper flips to DONE (re-POST to rerun) — the
    409 in-flight guard consults BOTH the in-memory registry and the status marker.

Degradation-slope formula (self-flagged in the receipt): the least-squares slope of the primary
objective over the slippage multipliers ``[(1, base), (m, stressed_m) …]`` — a fair per-step rate —
normalized by ``|base|`` (relative degradation) when ``base != 0``, direction-oriented so a
SMALLER worse-direction drop scores HIGHER (this IS the design's ``z(−degradation slope)``: for a
maximize objective the raw slope is ≤ 0 and a resilient candidate's slope is closest to 0, hence
highest; a minimize objective negates), and clamped to ≤ 0 (an "improvement" under stress is
noise, ranked equal to flat-resilient). Needs ≥ 2 points (the 1× base + ≥ 1 stressed), else the
candidate is left un-stressed (z drops out + a per-candidate caveat).
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import httpx
from fastapi import APIRouter, Request
from pydantic import BaseModel

from app import scoring
from app.errors import ApiError
from app.evolution import _RETRO_CAVEAT
from app.service import _primary_objective

router = APIRouter(prefix="/api/v1/evolution")

_LOG = logging.getLogger(__name__)

# Request bounds. A stress round fans out topK × multipliers BACKTEST jobs onto the shared worker
# pool, so both are capped (register the runaway-ask class of §9-8, mirroring the probe ceiling).
_MAX_TOPK = 20
_MAX_MULTIPLIERS = 4
_MIN_MULT = 1.01  # a multiplier of 1 is unstressed (== base); the stress must actually widen fills
_MAX_MULT = 100.0  # matches backtest-service JobsService.validatedSlippageMultiplier (fat-finger)

# A run whose status stays non-terminal is polled at this cadence up to the timeout; a fake-driven
# test resolves every job on the first pass, so neither is hit there.
_POLL_INTERVAL_SECONDS = 3.0
_POLL_TIMEOUT_SECONDS = 1800.0  # 30 min — a stress round is small; a slow run degrades, never hangs

_TERMINAL_NO_RESULT = {"failed", "cancelled"}

# Appended to a candidate's STORED card when the round's re-assembly saw different evidence than the
# recording did (purged run / engineSha / dataHash drift) — the card is kept, never rewritten.
_DRIFT_CAVEAT = "stress rescore skipped: evidence drift/unavailable"


# --- request / response models ------------------------------------------------------------------


class StressRequest(BaseModel):
    """Cost-stress options: how many plateau leaders to re-run, at which slippage multipliers."""

    topK: int = 5
    multipliers: list[float] = [2, 4]


class StressRunRef(BaseModel):
    multiplier: float
    jobId: str


class StressCandidateDispatch(BaseModel):
    candidateId: str
    trialNumber: int | None = None
    runs: list[StressRunRef]


class StressReceipt(BaseModel):
    """202 receipt for a dispatched cost-stress round (§3.2.5): the BACKTEST re-runs submitted per
    top-K candidate, and how many SCORED candidates were not selected (beyond top-K / not rankable /
    no params). The degradation slopes + re-score land asynchronously on the candidate cards."""

    generationId: str
    dispatched: int
    skipped: int
    multipliers: list[float]
    candidates: list[StressCandidateDispatch]


# --- slope math (pure) --------------------------------------------------------------------------


def _lstsq_slope(points: list[tuple[float, float]]) -> float | None:
    """Least-squares slope of ``y`` on ``x`` over ``points`` (≥ 2). ``None`` iff every ``x`` is
    equal (no spread) — impossible here (the 1× base + a distinct stressed multiplier spread)."""
    n = len(points)
    x_bar = sum(x for x, _ in points) / n
    y_bar = sum(y for _, y in points) / n
    denom = sum((x - x_bar) ** 2 for x, _ in points)
    if denom == 0:
        return None
    return sum((x - x_bar) * (y - y_bar) for x, y in points) / denom


def _cost_resilience(
    base: float | None, stressed: dict[float, float | None], direction: str
) -> float | None:
    """The §6.2 ``cost_resilience`` sub-signal for one candidate: the least-squares slope of the
    objective over the slippage multipliers, normalized by ``|base|`` when base ≠ 0, oriented so a
    smaller worse-direction drop scores higher (see the module docstring), and CLAMPED to ≤ 0 in
    maximize space — a stress run that IMPROVES the objective is noise (wider fills cannot
    genuinely help) and must rank equal to flat-resilient, never above it. ``None`` when < 2 points
    survive (the 1× base + ≥ 1 completed stressed run) — "stress incomplete".

    On the ``|base|`` normalization (spec-faithful, kept deliberately): a NEAR-ZERO base amplifies
    the relative slope — a candidate whose base objective is ~0 reads extremely fragile from a
    small absolute drop. Arguably honest (no edge ⇒ no cost buffer), but worth knowing when
    reading z-outliers."""
    points: list[tuple[float, float]] = []
    if base is not None:
        points.append((1.0, base))
    for m in sorted(stressed):
        obj = stressed[m]
        if obj is not None:
            points.append((float(m), obj))
    if len(points) < 2:
        return None
    slope = _lstsq_slope(points)
    if slope is None:
        return None
    if base is not None and base != 0:
        slope = slope / abs(base)
    oriented = -slope if direction == "minimize" else slope
    return min(oriented, 0.0)


def _mult_key(multiplier: float) -> str:
    """The scorecard ``evidence.stressRuns`` key for a multiplier — ``"2"`` / ``"4"`` for integral
    values (the design's shape), ``"2.5"`` otherwise."""
    return str(int(multiplier)) if float(multiplier).is_integer() else str(multiplier)


def _validate_multipliers(multipliers: Any) -> list[float]:
    """Coerce + range-check the slippage multipliers: 1–``_MAX_MULTIPLIERS`` numbers each in
    ``[1.01, 100]``. A non-numeric or out-of-range value is a clean 422, never a downstream 500."""
    if not multipliers:
        raise ApiError(422, "VALIDATION_FAILED", "multipliers must be a non-empty list")
    if len(multipliers) > _MAX_MULTIPLIERS:
        raise ApiError(
            422, "VALIDATION_FAILED", f"at most {_MAX_MULTIPLIERS} multipliers allowed"
        )
    out: list[float] = []
    for m in multipliers:
        try:
            value = float(m)
        except (TypeError, ValueError):
            raise ApiError(
                422, "VALIDATION_FAILED", f"multiplier must be numeric; got {m!r}"
            ) from None
        if not _MIN_MULT <= value <= _MAX_MULT:
            raise ApiError(
                422,
                "VALIDATION_FAILED",
                f"multiplier must be in [{_MIN_MULT}, {_MAX_MULT}]; got {value}",
            )
        out.append(value)
    return out


def _num(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


# --- orchestration ------------------------------------------------------------------------------


@dataclass(frozen=True)
class _RoundContext:
    """The frozen context a dispatched round's async drain needs to re-score: the source sweep, the
    generation being re-scored (+ its campaign / n / evidence policy for N reproduction and the
    LIVE_FIRST caveat, + its recorded ``data_epoch`` for the evidence-drift guard), the ranked
    metric, and EVERY SCORED candidate row (the whole cohort is re-scored, not just the stressed
    top-K). Direction is NOT carried — ``assemble_cohort``'s ``direction`` is authoritative."""

    sweep_id: str
    generation_id: str
    campaign_id: str
    generation_n: int
    policy: str | None
    metric: str
    data_epoch: dict[str, Any] | None
    candidate_rows: list[dict[str, Any]]


class StressService:
    """Orchestrates a cost-stress round for one generation. Collaborators injected (EvoRepo factory,
    JobsRepo factory, the retro scorer for cohort assembly, the Redis dispatcher, the backtest REST
    client) so tests drive it with in-memory fakes. The in-flight guard is an in-memory registry
    (single-process optimizer): a generation with a round already dispatched 409s until its drain
    completes."""

    def __init__(
        self,
        *,
        repo_factory: Callable[[], Any],
        jobs_factory: Callable[[], Any],
        scorer: Any,
        dispatcher: Any,
        backtest_client: Any,
        poll_interval: float = _POLL_INTERVAL_SECONDS,
        poll_timeout: float = _POLL_TIMEOUT_SECONDS,
    ) -> None:
        self._repo_factory = repo_factory
        self._jobs_factory = jobs_factory
        self._scorer = scorer
        self._dispatcher = dispatcher
        self._backtest = backtest_client
        self._poll_interval = poll_interval
        self._poll_timeout = poll_timeout
        self._in_flight: set[str] = set()
        self._lock = threading.Lock()

    # --- public entry ---------------------------------------------------------------------------

    def stress(
        self, generation_id: str, top_k: int, multipliers: list[float]
    ) -> dict[str, Any]:
        """Dispatch a cost-stress round for a generation's plateau top-K, then re-score in the
        background (design §3.2.5). 404 unknown generation; 422 when the campaign is SIM_BLOCKED, or
        the generation has no SCORED candidates, or has no source sweep; 409 when a round is already
        in flight for this generation (in-memory registry AND the durable ``status='STRESSING'``
        marker, so the guard survives a restart as a re-POST-able state — the boot reaper flips an
        orphaned STRESSING back to DONE). Returns a 202 ``StressReceipt``."""
        top_k = min(max(top_k, 1), _MAX_TOPK)
        multipliers = _validate_multipliers(multipliers)

        repo = self._repo_factory()
        try:
            generation = repo.get_generation(generation_id)
            if generation is None:
                raise ApiError(404, "NOT_FOUND_GENERATION", f"no generation {generation_id}")
            campaign = repo.get_campaign(generation["campaignId"])
            policy = (campaign or {}).get("evidencePolicy")
            if policy == "SIM_BLOCKED":
                raise ApiError(
                    422,
                    "EVIDENCE_POLICY_BLOCKED",
                    f"campaign {generation['campaignId']} is SIM_BLOCKED — a sim cost-stress "
                    "re-run is not scorable evidence for this family (design §1.2)",
                )
            candidates = repo.list_candidates_for_generation(generation_id)
        finally:
            repo.close()

        # Durable in-flight guard (crosses restarts): a generation stuck at STRESSING either has a
        # live round (this process) or was orphaned by a crash — the boot reaper flips orphans to
        # DONE at startup, so a persistent STRESSING while the process lives means genuinely in
        # flight.
        if generation.get("status") == "STRESSING":
            raise ApiError(
                409,
                "CONFLICT_STRESS_IN_FLIGHT",
                f"a cost-stress round is already in flight for generation {generation_id} "
                "(status STRESSING)",
            )

        scored = [c for c in candidates if c.get("state") == "SCORED"]
        if not scored:
            raise ApiError(
                422,
                "NO_SCORED_CANDIDATES",
                f"generation {generation_id} has no SCORED candidates to stress",
            )
        sweep_id = (generation.get("proposal") or {}).get("sweepJobId")
        if not sweep_id:
            raise ApiError(
                422,
                "GENERATION_NOT_STRESSABLE",
                f"generation {generation_id} has no source sweep to re-run",
            )

        top = _select_top_k(scored, top_k)
        if not top:
            # SCORED candidates exist but none are rankable (all failed a hard gate) / carry params
            # — nothing to re-run. Honest empty receipt, no round started, no touch bumped.
            return _receipt(generation_id, [], len(scored), multipliers)

        if not self._acquire(generation_id):
            raise ApiError(
                409,
                "CONFLICT_STRESS_IN_FLIGHT",
                f"a cost-stress round is already in flight for generation {generation_id}",
            )
        prior_status = generation.get("status")
        marked = False
        try:
            # Committed BEFORE dispatch — the durable marker a mid-round restart leaves behind for
            # the boot reaper (evo_generations.status is free TEXT, no CHECK enum — V011).
            self._set_status(generation_id, "STRESSING")
            marked = True
            return self._dispatch_round(
                generation, policy, sweep_id, top, scored, multipliers
            )
        except Exception:
            if marked:  # a dispatch failure must not leave a permanent in-process 409
                self._try_set_status(generation_id, prior_status)
            self._release(generation_id)
            raise

    # --- dispatch -------------------------------------------------------------------------------

    def _dispatch_round(
        self,
        generation: dict[str, Any],
        policy: str | None,
        sweep_id: str,
        top: list[dict[str, Any]],
        scored: list[dict[str, Any]],
        multipliers: list[float],
    ) -> dict[str, Any]:
        jobs = self._jobs_factory()
        try:
            template = jobs.child_trial_request(sweep_id)
            if template is None:  # a recorded sweep always has a trial job to template from
                raise ApiError(
                    409,
                    "CONFLICT_JOB_TERMINAL",
                    f"sweep {sweep_id} has no trial job to template a stress run from",
                )
            sweep_job = jobs.get(sweep_id) or {}
            objective = (sweep_job.get("request") or {}).get("objective", {})
            metric = _primary_objective(objective)[0]
            pending: dict[str, tuple[str, float]] = {}
            descriptors: list[dict[str, Any]] = []
            for cand in top:
                runs: list[dict[str, Any]] = []
                for multiplier in multipliers:
                    request = _stress_request(template, cand["params"], multiplier)
                    job_id = jobs.insert_backtest(
                        sweep_id, request, created_by=f"optimizer:{sweep_id}:stress"
                    )
                    self._dispatcher.dispatch_backtest(job_id)
                    pending[str(job_id)] = (cand["id"], multiplier)
                    runs.append({"multiplier": multiplier, "jobId": str(job_id)})
                descriptors.append(
                    {
                        "candidateId": cand["id"],
                        "trialNumber": (cand.get("scorecard") or {}).get("trialNumber"),
                        "runs": runs,
                    }
                )
        finally:
            jobs.close()

        context = _RoundContext(
            sweep_id=sweep_id,
            generation_id=generation["id"],
            campaign_id=generation["campaignId"],
            generation_n=generation.get("n") or 0,
            policy=policy,
            metric=metric,
            data_epoch=generation.get("dataEpoch"),
            candidate_rows=scored,
        )
        self._start_drain(context, pending)
        return _receipt(generation["id"], descriptors, len(scored) - len(top), multipliers)

    # --- async drain ----------------------------------------------------------------------------

    def _start_drain(
        self, context: _RoundContext, pending: dict[str, tuple[str, float]]
    ) -> None:
        threading.Thread(
            target=self._drain,
            kwargs={"context": context, "pending": pending},
            daemon=True,
            name=f"stress-{context.generation_id[:8]}",
        ).start()

    def _drain(
        self, *, context: _RoundContext, pending: dict[str, tuple[str, float]]
    ) -> None:
        """The background drain (fresh per-thread repos, like the sweep/probe drains): poll every
        dispatched stress run to a terminal state, then re-score the cohort. The in-flight guard is
        released here in a finally so a crash never wedges the generation permanently; a drain
        crash also restores ``status='DONE'`` (best-effort) so a re-POST can rerun the round —
        if that write fails too (DB down), the boot reaper heals the STRESSING orphan at restart."""
        try:
            outcomes = self._collect(context, pending)
            self._rescore(context, outcomes)
        except Exception:  # noqa: BLE001 - never crash the daemon thread; the guard still releases
            _LOG.exception("stress drain for generation %s failed", context.generation_id)
            self._try_set_status(context.generation_id, "DONE")
        finally:
            self._release(context.generation_id)

    def _collect(
        self, context: _RoundContext, pending: dict[str, tuple[str, float]]
    ) -> dict[str, dict[float, tuple[float | None, str | None]]]:
        """Poll each dispatched BACKTEST stress run to a terminal state, reading the stressed
        objective off a completed run and degrading a failed/cancelled/timed-out one to a MISSING
        point (``(None, None)``). Bounded by ``_poll_timeout`` so one dead run never hangs it.
        Returns ``{candidate_id: {multiplier: (objective, run_id)}}``."""
        outcomes: dict[str, dict[float, tuple[float | None, str | None]]] = {
            cand_id: {} for cand_id, _ in pending.values()
        }
        remaining = dict(pending)
        deadline = time.monotonic() + self._poll_timeout
        while remaining and time.monotonic() < deadline:
            progressed = False
            for job_id in list(remaining):
                status = self._safe_job_status(job_id)
                if status is None:
                    continue  # transient read error — retried next pass
                state = status.get("status")
                if state == "completed":
                    cand_id, multiplier = remaining.pop(job_id)
                    run_id = status.get("resultRef")
                    outcomes[cand_id][multiplier] = (
                        self._run_objective(run_id, context.metric),
                        run_id,
                    )
                    progressed = True
                elif state in _TERMINAL_NO_RESULT:
                    cand_id, multiplier = remaining.pop(job_id)
                    outcomes[cand_id][multiplier] = (None, None)  # missing point
                    progressed = True
            if remaining and not progressed:
                time.sleep(self._poll_interval)
        for _job_id, (cand_id, multiplier) in remaining.items():  # timed out → missing point
            outcomes[cand_id][multiplier] = (None, None)
        return outcomes

    def _rescore(
        self,
        context: _RoundContext,
        outcomes: dict[str, dict[float, tuple[float | None, str | None]]],
    ) -> None:
        """Re-assemble the generation's cohort (at the recorder's campaign-cumulative N), attach
        each stressed candidate's ``costResilience`` slope, re-score, and persist every candidate's
        scorecard + bump ``stress_touches`` atomically.

        Evidence-drift guard: the re-assembly reads LIVE backtest evidence — if a candidate's run
        was purged or its engineSha/dataHash drifted since recording, blindly overwriting would
        silently change its gates/components beyond cost_resilience. Such a candidate KEEPS its
        stored card (plus a skip caveat), and its bag contributes no stress slope."""
        prior_trials = self._prior_trials(context.campaign_id, context.generation_n)
        cohort = self._scorer.assemble_cohort(context.sweep_id, prior_trials=prior_trials)
        by_trial = {c.get("trialNumber"): c for c in cohort.candidates}

        # Drift detection FIRST, so a drifted candidate's untrustworthy base never feeds a slope.
        drifted: set[str] = set()
        for cand in context.candidate_rows:
            stored = cand.get("scorecard") or {}
            bag = by_trial.get(stored.get("trialNumber"))
            if bag is None or _evidence_drifted(stored, bag, context.data_epoch):
                drifted.add(cand["id"])

        stress_runs: dict[str, dict[str, str]] = {}
        for cand in context.candidate_rows:
            card = cand.get("scorecard") or {}
            bag = by_trial.get(card.get("trialNumber"))
            outcome = outcomes.get(cand["id"])
            if bag is None or not outcome or cand["id"] in drifted:
                continue
            # Base (1×) objective = the candidate's OWN recorded run, read via the SAME extractor as
            # the stressed runs — so the slope is apples-to-apples (never the stored rawObjective,
            # which a fold sweep computes differently than a run-level headline read).
            base = self._run_objective(card.get("runId"), context.metric)
            stressed = {m: obj for m, (obj, _r) in outcome.items()}
            resilience = _cost_resilience(base, stressed, cohort.direction)
            if resilience is not None:
                bag["costResilience"] = resilience
            else:
                # Selected for this round but < 2 usable points (dead/timed-out runs) — mark it so
                # the scorecard says "stress incomplete", NOT "not stress-tested" (it WAS tried).
                bag["stressIncomplete"] = True
            runs = {_mult_key(m): run_id for m, (_o, run_id) in outcome.items() if run_id}
            if runs:
                stress_runs[cand["id"]] = runs

        cards = scoring.score_cohort(
            cohort.candidates,
            cohort.parameters,
            direction=cohort.direction,
            n_trials=cohort.n_trials,
        )
        if context.policy == "LIVE_FIRST":  # sim evidence never ranks a LIVE_FIRST family (§1.2)
            for card in cards:
                card["caveats"].append(_RETRO_CAVEAT)
        cards_by_trial = {c.get("trialNumber"): c for c in cards}

        updates: list[dict[str, Any]] = []
        for cand in context.candidate_rows:
            stored = cand.get("scorecard") or {}
            if cand["id"] in drifted:
                # Keep the recorded card verbatim (evidence changed under us — a rescore here would
                # silently rewrite gates/z's from different evidence); append the skip note once.
                kept = dict(stored)
                caveats = list(kept.get("caveats") or [])
                if _DRIFT_CAVEAT not in caveats:
                    caveats.append(_DRIFT_CAVEAT)
                kept["caveats"] = caveats
                updates.append({"candidateId": cand["id"], "scorecard": kept})
                continue
            new_card = cards_by_trial.get(stored.get("trialNumber"))
            if new_card is None:  # unreachable (drift covers a missing bag) — belt and braces
                continue
            runs = stress_runs.get(cand["id"])
            if runs:
                new_card.setdefault("evidence", {})["stressRuns"] = runs
            updates.append({"candidateId": cand["id"], "scorecard": new_card})

        repo = self._repo_factory()
        try:
            repo.apply_stress_round(context.generation_id, updates)
        finally:
            repo.close()

    def _prior_trials(self, campaign_id: str, generation_n: int) -> int:
        """The campaign's trials-to-date from generations recorded BEFORE this one (n < gen_n) —
        reproduces the recorder's §4 campaign-cumulative multiplicity N so the re-scored card's
        deflated-Sharpe gate is byte-identical to the recorded one and ONLY cost_resilience moves.
        Append-only, monotonic ``n`` makes this set stable (later generations never count)."""
        repo = self._repo_factory()
        try:
            generations = repo.list_generations(campaign_id)
        finally:
            repo.close()
        total = 0
        for gen in generations:
            if gen.get("n") is not None and gen["n"] < generation_n:
                sweep_id = (gen.get("proposal") or {}).get("sweepJobId")
                if sweep_id:
                    total += self._scorer.count_trials(sweep_id)
        return total

    def _run_objective(self, run_id: str | None, metric: str) -> float | None:
        """The primary objective of one completed run, read off ``/results`` metrics (run-level
        headline). A fold-only objective (``oos_fold_mean``) has no run-level headline → the net
        ``totalReturn`` is the cost-degradation proxy. A purged/404 run degrades to ``None``."""
        if not run_id:
            return None
        try:
            results = self._backtest.results(run_id) or {}
        except httpx.HTTPError:
            return None
        metrics = results.get("metrics") or {}
        value = metrics.get(metric)
        if value is None:
            value = metrics.get("totalReturn")
        return _num(value)

    def _safe_job_status(self, job_id: str) -> dict[str, Any] | None:
        try:
            return self._backtest.job_status(job_id)
        except httpx.HTTPError:
            return None

    # --- in-flight guard ------------------------------------------------------------------------

    def _acquire(self, generation_id: str) -> bool:
        with self._lock:
            if generation_id in self._in_flight:
                return False
            self._in_flight.add(generation_id)
            return True

    def _release(self, generation_id: str) -> None:
        with self._lock:
            self._in_flight.discard(generation_id)

    def _set_status(self, generation_id: str, status: str | None) -> None:
        """Write the generation's lifecycle status (the durable STRESSING/DONE round marker)."""
        repo = self._repo_factory()
        try:
            repo.set_generation_status(generation_id, status)
        finally:
            repo.close()

    def _try_set_status(self, generation_id: str, status: str | None) -> None:
        """Best-effort status restore on a failure path — a second failure (DB down) is logged and
        left for the boot reaper, never raised over the original error."""
        try:
            self._set_status(generation_id, status)
        except Exception:  # noqa: BLE001 - the original failure is the one that matters
            _LOG.exception(
                "could not restore status %r on generation %s (boot reaper will heal it)",
                status, generation_id,
            )


# --- helpers ------------------------------------------------------------------------------------


def _select_top_k(scored: list[dict[str, Any]], top_k: int) -> list[dict[str, Any]]:
    """The rankable top-K SCORED candidates by RobustScore desc (a candidate that failed a hard gate
    is not rankable — no point stressing an already-eliminated one; one with no params can't be
    re-run). Ties break by trialNumber for a stable selection."""
    rankable = [
        c
        for c in scored
        if (c.get("scorecard") or {}).get("rankable") and c.get("params")
    ]
    rankable.sort(
        key=lambda c: (
            -((c.get("scorecard") or {}).get("robustScore") or 0.0),
            _sort_key((c.get("scorecard") or {}).get("trialNumber")),
        )
    )
    return rankable[:top_k]


def _sort_key(trial_number: Any) -> tuple[int, Any]:
    return (0, trial_number) if isinstance(trial_number, int) else (1, str(trial_number))


def _evidence_drifted(
    stored: dict[str, Any], bag: dict[str, Any], data_epoch: dict[str, Any] | None
) -> bool:
    """True when the re-assembled bag's evidence no longer matches what the candidate was RECORDED
    on: the bag carries a ``run fetch failed`` caveat (purged/unreachable run), its ``engineSha``
    differs from the stored card's comparability-gate value, or its ``dataHash`` differs from the
    generation's recorded ``data_epoch``. A side missing (pre-#703 NULL SHA, UNKNOWN gate, no
    epoch) can't ESTABLISH drift — only a present-and-different pair does (degrade, don't block)."""
    if any(str(c).startswith("run fetch failed") for c in bag.get("caveats") or []):
        return True
    stored_sha = next(
        (g.get("value") for g in stored.get("gates") or [] if g.get("id") == "comparability"),
        None,
    )
    bag_sha = bag.get("engineSha")
    if stored_sha is not None and bag_sha is not None and stored_sha != bag_sha:
        return True
    epoch_hash = (data_epoch or {}).get("dataHash")
    bag_hash = bag.get("dataHash")
    return epoch_hash is not None and bag_hash is not None and epoch_hash != bag_hash


def _stress_request(
    template: dict[str, Any], params: dict[str, Any], multiplier: float
) -> dict[str, Any]:
    """A stress run's BACKTEST request = the sweep's own trial template (resolved version + window +
    fold context) with ``paramsOverride`` swapped to the candidate's params and ``stressOverrides``
    added, so the run is a FAITHFUL re-run of the candidate at the widened slippage. BacktestRunner
    reads both fields off the request JSONB regardless of job kind."""
    request = dict(template)
    request["paramsOverride"] = params
    request["stressOverrides"] = {"slippageMultiplier": multiplier}
    return request


def _receipt(
    generation_id: str,
    descriptors: list[dict[str, Any]],
    skipped: int,
    multipliers: list[float],
) -> dict[str, Any]:
    dispatched = sum(len(d["runs"]) for d in descriptors)
    return {
        "generationId": generation_id,
        "dispatched": dispatched,
        "skipped": skipped,
        "multipliers": multipliers,
        "candidates": descriptors,
    }


def _service(request: Request) -> StressService:
    return request.app.state.stress


@router.post(
    "/generations/{generation_id}/stress",
    response_model=StressReceipt,
    status_code=202,
)
def stress(
    generation_id: str, request: Request, body: StressRequest | None = None
) -> dict[str, Any]:
    """Re-run a generation's plateau top-K at 2×/4× slippage (design §3.2.5), measuring the
    objective degradation slope that feeds §6.2 ``cost_resilience``. Body optional (``topK`` 5,
    ``multipliers`` default ``[2, 4]``). 404 unknown generation; 422 SIM_BLOCKED / no SCORED
    candidates / no source sweep; 409 if a round is already in flight."""
    opts = body or StressRequest()
    return _service(request).stress(generation_id, opts.topK, opts.multipliers)
