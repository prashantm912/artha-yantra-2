"""EVO E2 item 5 — cost-stress orchestration + degradation slope (design §3.2.5 / §6.2 / §12).

Layers, each deterministic (the drain thread runs against fakes whose ``job_status`` resolves every
run on the first poll, so it always terminates — never a spinning daemon):

* slope math   — ``_lstsq_slope`` / ``_cost_resilience`` hand-computed, sign- + direction-checked;
* scoring plumb— ``scoring.score_cohort`` picks the ``costResilience`` sub-signal into the component
                 (resilient ranks higher), unstressed cohort byte-identical, per-candidate caveat;
* collect      — ``_collect`` polls runs to terminal, degrades a failed/dead run to a missing point;
* rescore      — ``_rescore`` attaches slopes, re-scores, persists every card + bumps stress_touches
                 atomically; a failed multiplier → slope over the survivors (or "incomplete");
                 evidence drift keeps the stored card; an unstressed candidate's gates stay pinned;
* lifecycle    — the durable STRESSING→DONE round marker: set at dispatch, restored atomically,
                 orphans healed by the boot reaper, 409 across the marker;
* end-to-end   — ``StressService.stress`` dispatches BACKTEST re-runs (NEVER trials — the point),
                 plus the 404 / 422 / 409 guards, validation, and the REST surface.
"""

from __future__ import annotations

import threading
import time
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import scoring, stress
from app.errors import ApiError, api_error_handler
from app.evolution import RetroScoreService
from app.stress import (
    StressService,
    _cost_resilience,
    _lstsq_slope,
    _mult_key,
    _select_top_k,
    _validate_multipliers,
)
from tests.fakes import FakeEvoRepo, FakeJobs, FakeTrials

# ================================================================================================
# slope math (pure)
# ================================================================================================


def test_lstsq_slope_hand_computed():
    # y = 2x + 1 exactly → slope 2.
    assert _lstsq_slope([(1.0, 3.0), (2.0, 5.0), (4.0, 9.0)]) == 2.0


def test_lstsq_slope_none_when_no_x_spread():
    assert _lstsq_slope([(2.0, 1.0), (2.0, 5.0)]) is None


def test_cost_resilience_maximize_resilient_scores_above_degrader():
    # base 1.0. Resilient barely drops; degrader collapses. Both slopes ≤ 0 (maximize); the
    # resilient one is CLOSEST to 0 → the higher (less negative) sub-signal (design z(−degr)).
    resilient = _cost_resilience(1.0, {2.0: 0.95, 4.0: 0.90}, "maximize")
    degrader = _cost_resilience(1.0, {2.0: 0.5, 4.0: 0.1}, "maximize")
    assert resilient is not None and degrader is not None
    assert resilient > degrader
    assert resilient < 0  # a degrading maximize objective yields a negative normalized slope


def test_cost_resilience_normalized_by_abs_base():
    # Same absolute drop but a 2× larger base ⇒ HALF the relative degradation ⇒ higher (less neg).
    small_base = _cost_resilience(1.0, {2.0: 0.9, 4.0: 0.7}, "maximize")
    large_base = _cost_resilience(2.0, {2.0: 1.9, 4.0: 1.7}, "maximize")
    assert large_base > small_base


def test_cost_resilience_minimize_direction_inverts():
    # A minimize objective (e.g. maxDrawdown) WORSENS by RISING under slippage; the candidate whose
    # drawdown rises LESS must still score higher — the direction flip delivers that.
    resilient = _cost_resilience(10.0, {2.0: 10.5, 4.0: 11.0}, "minimize")
    degrader = _cost_resilience(10.0, {2.0: 15.0, 4.0: 25.0}, "minimize")
    assert resilient > degrader


def test_cost_resilience_needs_two_points():
    # base only (both stressed runs failed) → 1 point → no slope ("stress incomplete").
    assert _cost_resilience(1.0, {2.0: None, 4.0: None}, "maximize") is None
    # base missing but two stressed survivors → slope over the survivors (≥ 2 points).
    assert _cost_resilience(None, {2.0: 0.8, 4.0: 0.6}, "maximize") is not None
    # base missing + one survivor → 1 point → None.
    assert _cost_resilience(None, {2.0: 0.8, 4.0: None}, "maximize") is None


def test_cost_resilience_survives_one_failed_multiplier():
    # 4× failed; the slope is taken over (1×, base) + (2×, stressed) — never wedged by the dead run.
    assert _cost_resilience(1.0, {2.0: 0.5, 4.0: None}, "maximize") is not None


def test_cost_resilience_positive_improvement_clamps_to_zero():
    # A stress run that IMPROVES the objective is noise (wider fills cannot genuinely help) —
    # clamped to 0 in maximize space, so it ranks EQUAL to flat-resilient, never above it.
    assert _cost_resilience(1.0, {2.0: 1.1, 4.0: 1.3}, "maximize") == 0.0
    # minimize form: a drawdown that SHRINKS under stress is the same noise — also clamps.
    assert _cost_resilience(10.0, {2.0: 9.0, 4.0: 8.0}, "minimize") == 0.0
    # flat-resilient is exactly 0 too — the improver gains nothing over it.
    assert _cost_resilience(1.0, {2.0: 1.0, 4.0: 1.0}, "maximize") == 0.0


def test_mult_key_integral_vs_fractional():
    assert _mult_key(2.0) == "2"
    assert _mult_key(4) == "4"
    assert _mult_key(2.5) == "2.5"


# ================================================================================================
# validation
# ================================================================================================


def test_validate_multipliers_defaults_and_bounds():
    assert _validate_multipliers([2, 4]) == [2.0, 4.0]
    for bad in ([], [2, 3, 4, 5, 6], [1.0], [0.5], [101], ["x"]):
        with pytest.raises(ApiError) as exc:
            _validate_multipliers(bad)
        assert exc.value.status == 422


# ================================================================================================
# scoring plumb: cost_resilience picks up the costResilience sub-signal
# ================================================================================================


def test_scoring_cost_resilience_ranks_resilient_higher():
    # Two stressed candidates: A resilient (−0.03), B degrader (−0.29). cost_res z ranks A > B.
    cands = [
        {"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 10.0, "costResilience": -0.03},
        {"trialNumber": 2, "rawObjective": 1.0, "oosReturn": 10.0, "costResilience": -0.29},
    ]
    cards = {c["trialNumber"]: c for c in scoring.score_cohort(cands, [])}
    cr = {t: next(x for x in c["components"] if x["id"] == "cost_resilience")
          for t, c in cards.items()}
    assert cr[1]["z"] == 1.0
    assert cr[2]["z"] == -1.0
    assert cr[1]["raw"] == {"costResilience": -0.03}
    # neither stressed candidate carries the "no stress runs" caveat any more
    assert not any("cost_resilience: no stress runs" in c for c in cards[1]["caveats"])


def test_scoring_unstressed_candidate_in_stressed_cohort_is_caveated_and_drops_out():
    # A round stressed candidate 1 only; candidate 2 has no costResilience → z=0 + a per-candidate
    # "not stress-tested this round" caveat (NOT the uniform "no stress runs" note).
    cands = [
        {"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 10.0, "costResilience": -0.03},
        {"trialNumber": 2, "rawObjective": 1.0, "oosReturn": 10.0},
    ]
    cards = {c["trialNumber"]: c for c in scoring.score_cohort(cands, [])}
    cr2 = next(x for x in cards[2]["components"] if x["id"] == "cost_resilience")
    assert cr2["z"] == 0.0
    assert any("not stress-tested this round" in c for c in cards[2]["caveats"])
    assert not any("no stress runs" in c for c in cards[2]["caveats"])


def test_scoring_stress_incomplete_candidate_gets_distinct_caveat():
    # A top-K candidate whose stress runs died (marked stressIncomplete) reads a distinct caveat
    # from a candidate simply not selected — both drop out of the z, but say WHY differently.
    cands = [
        {"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 10.0, "costResilience": -0.03},
        {"trialNumber": 2, "rawObjective": 1.0, "oosReturn": 10.0, "stressIncomplete": True},
    ]
    cards = {c["trialNumber"]: c for c in scoring.score_cohort(cands, [])}
    assert any("stress incomplete" in c for c in cards[2]["caveats"])
    assert not any("not stress-tested" in c for c in cards[2]["caveats"])


def test_scoring_no_stress_cohort_is_byte_identical():
    # The pre-E2 behaviour is preserved when NO candidate carries costResilience.
    cands = [{"trialNumber": 1, "rawObjective": 1.0, "oosReturn": 5.0}]
    card = scoring.score_cohort(cands, [])[0]
    cr = next(x for x in card["components"] if x["id"] == "cost_resilience")
    assert cr["z"] == 0.0
    assert any("cost_resilience: no stress runs (E2" in c for c in card["caveats"])


# ================================================================================================
# fixtures for the orchestration layers
# ================================================================================================

_CAMPAIGN_ID = "camp-1"
_GEN_ID = "gen-1"
_METRIC = "sharpe"


class FakeStressBacktest:
    """Serves BOTH roles the stress drain needs off one fake: ``folds``/``results`` for the ORIGINAL
    candidate runs (cohort re-assembly + base objective) and ``job_status``/``results`` for the
    dispatched stress BACKTEST runs. A stress job resolves to ``completed`` with a run id off its
    job id, and its stressed objective is synthesized from the job's own request (paramsOverride +
    stressOverrides), mirroring how the real worker degrades a fill at a widened slippage."""

    def __init__(
        self,
        run_metrics: dict[str, dict[str, Any]],
        jobs: FakeJobs | None = None,
        job_statuses: dict[str, str] | None = None,
    ) -> None:
        self._run_metrics = run_metrics
        self._jobs = jobs
        self._job_statuses = job_statuses or {}
        self.job_status_calls: list[str] = []

    def folds(self, run_id: str) -> Any:
        return []

    def guard_summary(self, run_id: str) -> Any:
        return None

    def results(self, run_id: str) -> dict[str, Any]:
        return {"metrics": self._run_metrics.get(run_id, {})}

    def job_status(self, job_id: str) -> dict[str, Any]:
        self.job_status_calls.append(job_id)
        status = self._job_statuses.get(job_id, "completed")
        if status != "completed":
            return {"status": status, "resultRef": None}
        run_id = f"sr-{job_id}"
        if self._jobs is not None and run_id not in self._run_metrics:
            request = self._jobs.rows[job_id]["request"]
            multiplier = float(request["stressOverrides"]["slippageMultiplier"])
            # base sharpe 1.0 degrades by 0.2 per unit multiplier over 1 → deterministic slope.
            self._run_metrics[run_id] = {_METRIC: 1.0 - 0.2 * (multiplier - 1.0)}
        return {"status": "completed", "resultRef": run_id}


def _seed_sweep(
    jobs: FakeJobs, trials: FakeTrials, n: int,
    metric: str = _METRIC, direction: str = "maximize", value: float = 1.0,
) -> str:
    """A completed sweep + one child TRIAL template + ``n`` COMPLETE trials (run-i)."""
    request = {
        "strategyId": "s1", "strategyVersion": "1.0.0", "from": "2026-01-01", "to": "2026-01-10",
        "parameters": [], "objective": {"metric": metric, "direction": direction},
    }
    sweep_id = jobs.insert_sweep(None, request)
    jobs.insert_trial(sweep_id, None, {
        "strategyId": "s1", "strategyVersion": "1.0.0", "from": "2026-01-01", "to": "2026-01-10",
        "paramsOverride": {"period": 10}, "foldContext": False,
    })
    for i in range(n):
        row_id = trials.insert(sweep_id, i, {"period": 10 + i})
        trials.complete(row_id, {metric: value}, f"run-{i}")
    jobs.set_status(sweep_id, "completed", 100)
    return sweep_id


def _candidate(i: int, *, rankable: bool = True, robust: float = 1.0) -> dict[str, Any]:
    return {
        "id": f"cand-{i}", "generationId": _GEN_ID, "params": {"period": 10 + i},
        "sweepJobId": "sweep-1", "state": "SCORED",
        "scorecard": {"trialNumber": i, "runId": f"run-{i}", "rankable": rankable,
                      "robustScore": robust},
    }


def _seed_generation(
    repo: FakeEvoRepo, sweep_id: str, candidates: list[dict[str, Any]],
    policy: str = "SIM_FIRST", n: int = 1,
) -> None:
    repo.campaigns.append({"id": _CAMPAIGN_ID, "evidencePolicy": policy})
    repo.generations[_CAMPAIGN_ID] = [{
        "id": _GEN_ID, "campaignId": _CAMPAIGN_ID, "n": n,
        "proposal": {"sweepJobId": sweep_id}, "stressTouches": 0,
    }]
    repo.candidates[_CAMPAIGN_ID] = candidates


def _service(
    repo: FakeEvoRepo, jobs: FakeJobs, trials: FakeTrials, backtest: FakeStressBacktest,
) -> StressService:
    scorer = RetroScoreService(lambda: jobs, lambda: trials, backtest)
    return StressService(
        repo_factory=lambda: repo, jobs_factory=lambda: jobs, scorer=scorer,
        dispatcher=_CountingDispatcher(), backtest_client=backtest,
        poll_interval=0.0, poll_timeout=2.0,
    )


class _CountingDispatcher:
    def __init__(self) -> None:
        self.backtests: list[str] = []
        self.trials: list[str] = []

    def dispatch_backtest(self, job_id: str) -> None:
        self.backtests.append(str(job_id))

    def dispatch(self, job_id: str) -> None:  # a stress round must NEVER call this
        self.trials.append(str(job_id))


def _wait(cond, timeout: float = 3.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if cond():
            return
        time.sleep(0.01)
    raise AssertionError("stress drain did not resolve in time")


def _ctx(
    sweep_id: str, candidates: list[dict[str, Any]],
    policy: str = "SIM_FIRST", metric: str = _METRIC,
):
    return stress._RoundContext(
        sweep_id=sweep_id, generation_id=_GEN_ID, campaign_id=_CAMPAIGN_ID, generation_n=1,
        policy=policy, metric=metric, data_epoch=None, candidate_rows=candidates,
    )


# ================================================================================================
# collect: poll runs to terminal, degrade dead runs to missing points
# ================================================================================================


def test_collect_reads_objectives_and_degrades_failed_run():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 1)
    # j-ok completes with a run; j-dead fails → a missing point, never a hang.
    backtest = FakeStressBacktest(
        run_metrics={"sr-j-ok": {_METRIC: 0.6}},
        job_statuses={"j-ok": "completed", "j-dead": "failed"},
    )
    svc = _service(FakeEvoRepo(), jobs, trials, backtest)
    outcomes = svc._collect(
        _ctx(sweep_id, []), {"j-ok": ("cand-0", 2.0), "j-dead": ("cand-0", 4.0)}
    )
    assert outcomes["cand-0"][2.0] == (0.6, "sr-j-ok")
    assert outcomes["cand-0"][4.0] == (None, None)


class _ConcurrencyProbe:
    """Plays BOTH the dispatcher and the backtest-client, sharing an in-flight set so the test can
    assert the stress-dispatch cap. A run completes only on its SECOND poll, so dispatched runs
    genuinely overlap in flight; every dispatch records the peak simultaneous in-flight count."""

    def __init__(self, metric: str = _METRIC) -> None:
        self._metric = metric
        self.backtests: list[str] = []  # dispatch order (dispatcher role)
        self.trials: list[str] = []
        self._outstanding: set[str] = set()
        self._polls: dict[str, int] = {}
        self.peak_in_flight = 0

    # --- dispatcher role ---
    def dispatch_backtest(self, job_id: str) -> None:
        self.backtests.append(str(job_id))
        self._outstanding.add(str(job_id))
        self.peak_in_flight = max(self.peak_in_flight, len(self._outstanding))

    def dispatch(self, job_id: str) -> None:  # a stress round must NEVER touch the trial stream
        self.trials.append(str(job_id))

    # --- backtest-client role ---
    def job_status(self, job_id: str) -> dict[str, Any]:
        self._polls[job_id] = self._polls.get(job_id, 0) + 1
        if self._polls[job_id] < 2:
            return {"status": "running", "resultRef": None}  # still in flight this pass
        self._outstanding.discard(str(job_id))
        return {"status": "completed", "resultRef": f"sr-{job_id}"}

    def results(self, run_id: str) -> dict[str, Any]:
        return {"metrics": {self._metric: 0.5}}


def test_collect_caps_concurrent_in_flight_and_advances_on_completion():
    # The chip's invariant: N runs, cap 2 → never > 2 dispatched-and-not-yet-terminal at once, and a
    # completion advances the queue (all N eventually dispatch + resolve). Runs ride the interactive
    # stream, which B16 does not protect, so this cap is the only starvation guard.
    probe = _ConcurrencyProbe()
    svc = StressService(
        repo_factory=lambda: FakeEvoRepo(),
        jobs_factory=lambda: FakeJobs(),
        scorer=None,
        dispatcher=probe,
        backtest_client=probe,
        poll_interval=0.0,
        poll_timeout=2.0,
        max_concurrent=2,
    )
    pending = {f"j{i}": (f"cand-{i}", 2.0) for i in range(6)}
    outcomes = svc._collect(_ctx("sweep-1", []), pending)

    assert probe.peak_in_flight == 2  # never > the cap in flight (and the cap was reached)
    assert sorted(probe.backtests) == sorted(pending)  # all 6 eventually dispatched (advanced)
    assert probe.trials == []  # the trial stream is never touched
    resolved = [obj for cand in outcomes.values() for obj, _run in cand.values()]
    assert resolved == [0.5] * 6  # every run drained to a completed objective


def test_collect_never_exceeds_cap_of_one():
    # cap 1 fully serializes the round — one run in flight at a time, still draining every cand.
    probe = _ConcurrencyProbe()
    svc = StressService(
        repo_factory=lambda: FakeEvoRepo(),
        jobs_factory=lambda: FakeJobs(),
        scorer=None,
        dispatcher=probe,
        backtest_client=probe,
        poll_interval=0.0,
        poll_timeout=2.0,
        max_concurrent=1,
    )
    pending = {f"j{i}": (f"cand-{i}", 2.0) for i in range(4)}
    svc._collect(_ctx("sweep-1", []), pending)
    assert probe.peak_in_flight == 1
    assert len(probe.backtests) == 4


# ================================================================================================
# rescore: attach slopes, re-score, persist atomically
# ================================================================================================


def test_rescore_fills_cost_resilience_and_bumps_stress_touches():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 2)
    cands = [_candidate(0), _candidate(1)]
    _seed_generation(repo, sweep_id, cands)
    backtest = FakeStressBacktest(run_metrics={"run-0": {_METRIC: 1.0}, "run-1": {_METRIC: 1.0}})
    svc = _service(repo, jobs, trials, backtest)

    # cand-0 resilient (barely drops), cand-1 degrader.
    outcomes = {
        "cand-0": {2.0: (0.95, "sr-a2"), 4.0: (0.90, "sr-a4")},
        "cand-1": {2.0: (0.50, "sr-b2"), 4.0: (0.10, "sr-b4")},
    }
    svc._rescore(_ctx(sweep_id, cands), outcomes)

    persisted = {c["scorecard"]["trialNumber"]: c["scorecard"]
                 for c in repo.list_candidates_for_generation(_GEN_ID)}
    cr0 = next(x for x in persisted[0]["components"] if x["id"] == "cost_resilience")
    cr1 = next(x for x in persisted[1]["components"] if x["id"] == "cost_resilience")
    assert cr0["z"] > cr1["z"]                       # resilient outranks degrader
    assert cr0["z"] > 0 > cr1["z"]
    assert persisted[0]["evidence"]["stressRuns"] == {"2": "sr-a2", "4": "sr-a4"}
    # stress_touches bumped exactly once for the round
    assert repo.get_generation(_GEN_ID)["stressTouches"] == 1


def test_rescore_failed_multiplier_slopes_over_survivors():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 2)
    cands = [_candidate(0), _candidate(1)]
    _seed_generation(repo, sweep_id, cands)
    backtest = FakeStressBacktest(run_metrics={"run-0": {_METRIC: 1.0}, "run-1": {_METRIC: 1.0}})
    svc = _service(repo, jobs, trials, backtest)
    # cand-0: 4× failed → slope over (1×, 2×). cand-1: both present. Both still get a cost_res.
    outcomes = {
        "cand-0": {2.0: (0.9, "sr-a2"), 4.0: (None, None)},
        "cand-1": {2.0: (0.5, "sr-b2"), 4.0: (0.2, "sr-b4")},
    }
    svc._rescore(_ctx(sweep_id, cands), outcomes)
    persisted = {c["scorecard"]["trialNumber"]: c["scorecard"]
                 for c in repo.list_candidates_for_generation(_GEN_ID)}
    cr0 = next(x for x in persisted[0]["components"] if x["id"] == "cost_resilience")
    assert cr0["raw"].get("costResilience") is not None    # slope over the two survivors
    # only the successful multiplier's run id is recorded on the incomplete candidate
    assert persisted[0]["evidence"]["stressRuns"] == {"2": "sr-a2"}


def test_rescore_all_runs_failed_marks_stress_incomplete():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 2)
    cands = [_candidate(0), _candidate(1)]
    _seed_generation(repo, sweep_id, cands)
    backtest = FakeStressBacktest(run_metrics={"run-0": {_METRIC: 1.0}, "run-1": {_METRIC: 1.0}})
    svc = _service(repo, jobs, trials, backtest)
    # cand-0: both stressed runs died → only the 1× base point → no slope → "stress incomplete".
    outcomes = {
        "cand-0": {2.0: (None, None), 4.0: (None, None)},
        "cand-1": {2.0: (0.5, "sr-b2"), 4.0: (0.2, "sr-b4")},
    }
    svc._rescore(_ctx(sweep_id, cands), outcomes)
    persisted = {c["scorecard"]["trialNumber"]: c["scorecard"]
                 for c in repo.list_candidates_for_generation(_GEN_ID)}
    cr0 = next(x for x in persisted[0]["components"] if x["id"] == "cost_resilience")
    assert cr0["z"] == 0.0
    assert any("stress incomplete" in c for c in persisted[0]["caveats"])
    assert repo.get_generation(_GEN_ID)["stressTouches"] == 1     # a round still ran


def test_rescore_minimize_objective_ranks_smaller_rise_higher():
    # End-to-end minimize (maxDrawdown): the objective WORSENS by RISING under stress; the
    # candidate whose drawdown rises LESS must land the higher cost_resilience z after the cohort
    # re-score (the helper-level inversion carried all the way through score_cohort).
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 2, metric="maxDrawdown", direction="minimize", value=10.0)
    cands = [_candidate(0), _candidate(1)]
    _seed_generation(repo, sweep_id, cands)
    backtest = FakeStressBacktest(
        run_metrics={"run-0": {"maxDrawdown": 10.0}, "run-1": {"maxDrawdown": 10.0}}
    )
    svc = _service(repo, jobs, trials, backtest)
    outcomes = {
        "cand-0": {2.0: (10.5, "sr-a2"), 4.0: (11.0, "sr-a4")},   # barely rises — resilient
        "cand-1": {2.0: (15.0, "sr-b2"), 4.0: (25.0, "sr-b4")},   # blows up — degrader
    }
    svc._rescore(_ctx(sweep_id, cands, metric="maxDrawdown"), outcomes)
    persisted = {c["scorecard"]["trialNumber"]: c["scorecard"]
                 for c in repo.list_candidates_for_generation(_GEN_ID)}
    cr0 = next(x for x in persisted[0]["components"] if x["id"] == "cost_resilience")
    cr1 = next(x for x in persisted[1]["components"] if x["id"] == "cost_resilience")
    assert cr0["z"] > cr1["z"]
    assert cr0["raw"]["costResilience"] > cr1["raw"]["costResilience"]


class ShaStampedBacktest(FakeStressBacktest):
    """``results()`` stamps engineSha ``sha-NEW`` on every run — the evidence-drift fixture."""

    def results(self, run_id: str) -> dict[str, Any]:
        payload = super().results(run_id)
        payload["engineSha"] = "sha-NEW"
        return payload


def test_rescore_drifted_candidate_keeps_stored_card_with_skip_caveat():
    # Drift guard: a candidate whose live evidence no longer matches its RECORDED card (engineSha
    # changed under it) keeps the stored card verbatim + a skip caveat — a stress round must never
    # silently rewrite gates/components from different evidence. The matching candidate rescores.
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 2)
    match = _candidate(0)
    match["scorecard"]["gates"] = [{"id": "comparability", "status": "PASS", "value": "sha-NEW"}]
    drift = _candidate(1)
    drift["scorecard"]["gates"] = [{"id": "comparability", "status": "PASS", "value": "sha-OLD"}]
    drift["scorecard"]["robustScore"] = 0.777                     # sentinel — must survive verbatim
    original_gates = [dict(g) for g in drift["scorecard"]["gates"]]  # pre-round copy to pin against
    _seed_generation(repo, sweep_id, [match, drift])
    backtest = ShaStampedBacktest(
        run_metrics={"run-0": {_METRIC: 1.0}, "run-1": {_METRIC: 1.0}}
    )
    svc = _service(repo, jobs, trials, backtest)
    outcomes = {
        "cand-0": {2.0: (0.9, "sr-a2"), 4.0: (0.8, "sr-a4")},
        "cand-1": {2.0: (0.9, "sr-b2"), 4.0: (0.8, "sr-b4")},
    }
    svc._rescore(_ctx(sweep_id, [match, drift]), outcomes)
    persisted = {c["id"]: c["scorecard"] for c in repo.list_candidates_for_generation(_GEN_ID)}
    kept = persisted["cand-1"]
    assert kept["robustScore"] == 0.777                           # stored card kept verbatim
    assert kept["gates"] == original_gates
    assert any("stress rescore skipped" in c for c in kept["caveats"])
    assert "components" not in kept                               # never rewritten from new bags
    rescored = persisted["cand-0"]
    assert any(x["id"] == "cost_resilience" for x in rescored["components"])
    assert repo.get_generation(_GEN_ID)["stressTouches"] == 1     # the round still applied


def test_rescore_unstressed_candidate_gates_pinned_byte_equal():
    # The reviewer's regression: after a stress round, an UNSTRESSED candidate's persisted card may
    # move only in cohort-relative values/caveats — its GATES (incl. the multiplicity-t-stat
    # value) are byte-equal to the recorded card (same evidence, same campaign-cumulative N).
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 2)
    # maxDrawdown carried so the REQUIRED drawdown_cap gate is evaluated (audit PF-01) and the
    # foldless candidate is rankable — hence stress-targetable.
    metrics = {"sharpe": 1.0, "tradeCount": 60, "totalReturn": 12.0, "maxDrawdown": 12.0}
    backtest = FakeStressBacktest(
        run_metrics={"run-0": dict(metrics), "run-1": dict(metrics)}, jobs=jobs
    )
    scorer = RetroScoreService(lambda: jobs, lambda: trials, backtest)
    stored_cards = scorer.score_sweep(sweep_id).cards             # the RECORDED cards
    cands = [
        {"id": f"cand-{i}", "generationId": _GEN_ID, "params": card["params"],
         "sweepJobId": sweep_id, "state": "SCORED", "scorecard": card}
        for i, card in enumerate(stored_cards)
    ]
    _seed_generation(repo, sweep_id, cands)
    svc = _service(repo, jobs, trials, backtest)

    receipt = svc.stress(_GEN_ID, top_k=1, multipliers=[2, 4])    # stresses trial 0 only
    assert receipt["dispatched"] == 2
    _wait(lambda: repo.get_generation(_GEN_ID)["stressTouches"] == 1)

    persisted = {c["scorecard"]["trialNumber"]: c["scorecard"]
                 for c in repo.list_candidates_for_generation(_GEN_ID)}
    stored_1 = next(c for c in stored_cards if c["trialNumber"] == 1)
    unstressed = persisted[1]
    assert unstressed["gates"] == stored_1["gates"]        # byte-equal, incl. multiplicity value
    assert unstressed["params"] == stored_1["params"]
    assert unstressed["evidence"] == stored_1["evidence"]         # no stressRuns on the unstressed
    assert any("not stress-tested this round" in c for c in unstressed["caveats"])
    # the stressed candidate carries the new signal + run evidence
    assert set(persisted[0]["evidence"]["stressRuns"]) == {"2", "4"}
    cr0 = next(x for x in persisted[0]["components"] if x["id"] == "cost_resilience")
    assert cr0["raw"].get("costResilience") is not None


def test_live_first_candidate_not_stage_ready_after_stress_rescore():
    # audit PF-01 #5 (regression guard): the stress rescore PERSISTS the card, so it MUST carry the
    # campaign's evidencePolicy. A LIVE_FIRST candidate must stay NOT stage-ready (never selectable)
    # after a stress rescore — else a sim-smoke would be re-stamped SIM_FIRST and leak into SURVIVOR
    # selection through the stress path.
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 2)
    metrics = {"sharpe": 1.0, "tradeCount": 60, "totalReturn": 12.0, "maxDrawdown": 12.0}
    backtest = FakeStressBacktest(
        run_metrics={"run-0": dict(metrics), "run-1": dict(metrics)}, jobs=jobs
    )
    scorer = RetroScoreService(lambda: jobs, lambda: trials, backtest)
    stored_cards = scorer.score_sweep(sweep_id, policy="LIVE_FIRST").cards
    cands = [
        {"id": f"cand-{i}", "generationId": _GEN_ID, "params": card["params"],
         "sweepJobId": sweep_id, "state": "SCORED", "scorecard": card}
        for i, card in enumerate(stored_cards)
    ]
    _seed_generation(repo, sweep_id, cands, policy="LIVE_FIRST")
    svc = _service(repo, jobs, trials, backtest)

    svc.stress(_GEN_ID, top_k=1, multipliers=[2, 4])   # descriptive rankable selects; policy=LF
    _wait(lambda: repo.get_generation(_GEN_ID)["stressTouches"] == 1)

    persisted = [c["scorecard"] for c in repo.list_candidates_for_generation(_GEN_ID)]
    assert persisted  # sanity
    for card in persisted:
        assert card["stageReadiness"]["evidencePolicy"] == "LIVE_FIRST"
        assert card["stageReadiness"]["ready"] is False   # never selectable, even after stress


# ================================================================================================
# lifecycle: the durable STRESSING→DONE round marker + boot reaper
# ================================================================================================


class GatedStressBacktest(FakeStressBacktest):
    """``job_status`` blocks until the gate opens — freezes the drain mid-round deterministically
    so the test can observe the committed STRESSING marker and the mid-round 409."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self.gate = threading.Event()

    def job_status(self, job_id: str) -> dict[str, Any]:
        self.gate.wait(5)
        return super().job_status(job_id)


def test_stress_lifecycle_stressing_then_done_then_repostable():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 2)
    _seed_generation(repo, sweep_id, [_candidate(0), _candidate(1)])
    # Full base-run evidence so the round's RE-SCORE (which overwrites the candidate scorecards)
    # leaves them RANKABLE — else the re-POSTed round finds no rankable candidate to stress (audit
    # PF-01: a re-scored card missing required-gate evidence is no longer rankable). Production
    # reads this from real backtest results; the fixture must mirror it for the repostable round.
    base = {_METRIC: 1.0, "tradeCount": 60, "maxDrawdown": 12.0, "totalReturn": 12.0}
    backtest = GatedStressBacktest(
        run_metrics={"run-0": dict(base), "run-1": dict(base)}, jobs=jobs
    )
    svc = _service(repo, jobs, trials, backtest)

    svc.stress(_GEN_ID, top_k=1, multipliers=[2, 4])
    # the durable marker is committed at dispatch, while the drain is still blocked mid-round
    assert repo.get_generation(_GEN_ID)["status"] == "STRESSING"
    with pytest.raises(ApiError) as exc:                          # a second POST 409s mid-round
        svc.stress(_GEN_ID, top_k=1, multipliers=[2, 4])
    assert exc.value.status == 409

    backtest.gate.set()                                           # let the round finish
    _wait(lambda: repo.get_generation(_GEN_ID)["status"] == "DONE")
    assert repo.get_generation(_GEN_ID)["stressTouches"] == 1

    # a COMPLETED round is re-POST-able (the guard is per-flight, never permanent)
    svc.stress(_GEN_ID, top_k=1, multipliers=[2, 4])
    _wait(lambda: repo.get_generation(_GEN_ID)["stressTouches"] == 2)
    assert repo.get_generation(_GEN_ID)["status"] == "DONE"


def test_stress_409_on_orphaned_stressing_until_boot_reaper_heals():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 2)
    _seed_generation(repo, sweep_id, [_candidate(0), _candidate(1)])
    repo.get_generation(_GEN_ID)["status"] = "STRESSING"          # orphan of a crashed process
    backtest = FakeStressBacktest(
        run_metrics={"run-0": {_METRIC: 1.0}, "run-1": {_METRIC: 1.0}}, jobs=jobs
    )
    svc = _service(repo, jobs, trials, backtest)

    with pytest.raises(ApiError) as exc:                          # durable guard crosses restarts
        svc.stress(_GEN_ID, 5, [2, 4])
    assert exc.value.status == 409
    assert exc.value.code == "CONFLICT_STRESS_IN_FLIGHT"

    assert repo.reap_stressing_generations() == 1                 # the boot reaper heals the orphan
    assert repo.get_generation(_GEN_ID)["status"] == "DONE"

    receipt = svc.stress(_GEN_ID, top_k=1, multipliers=[2, 4])    # a re-POST reruns the round
    assert receipt["dispatched"] == 2
    _wait(lambda: repo.get_generation(_GEN_ID)["stressTouches"] == 1)


# ================================================================================================
# end-to-end: dispatch + guards + NOT-a-trial proof + REST surface
# ================================================================================================


def test_stress_dispatches_backtest_jobs_never_trials():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 2)
    cands = [_candidate(0, robust=1.0), _candidate(1, robust=0.5)]
    _seed_generation(repo, sweep_id, cands)
    backtest = FakeStressBacktest(
        run_metrics={"run-0": {_METRIC: 1.0}, "run-1": {_METRIC: 1.0}}, jobs=jobs
    )
    svc = _service(repo, jobs, trials, backtest)

    receipt = svc.stress(_GEN_ID, top_k=2, multipliers=[2, 4])
    assert receipt["dispatched"] == 4                      # 2 candidates × 2 multipliers
    assert receipt["multipliers"] == [2.0, 4.0]

    # NOT-a-trial proof: every dispatched job is kind=BACKTEST, dispatched on the backtest stream,
    # and NO optimization_trials row was created (only the 2 seeded COMPLETE trials remain).
    actor = f"optimizer:{sweep_id}:stress"
    stress_jobs = [r for r in jobs.rows.values() if r.get("created_by") == actor]
    assert len(stress_jobs) == 4
    assert all(r["kind"] == "BACKTEST" for r in stress_jobs)
    assert len(svc._dispatcher.backtests) == 4
    assert svc._dispatcher.trials == []                    # the trial stream is never touched
    assert len(trials.rows) == 2                           # no new ledger rows

    _wait(lambda: repo.get_generation(_GEN_ID)["stressTouches"] == 1)
    persisted = {c["scorecard"]["trialNumber"]: c["scorecard"]
                 for c in repo.list_candidates_for_generation(_GEN_ID)}
    for card in persisted.values():
        cr = next(x for x in card["components"] if x["id"] == "cost_resilience")
        assert cr["raw"].get("costResilience") is not None
        assert "stressRuns" in card["evidence"]


def test_stress_selects_only_rankable_top_k():
    cands = [
        _candidate(0, rankable=True, robust=0.9),
        _candidate(1, rankable=False, robust=2.0),   # highest score but a FAILed gate → excluded
        _candidate(2, rankable=True, robust=0.5),
    ]
    top = _select_top_k(cands, top_k=5)
    assert [c["id"] for c in top] == ["cand-0", "cand-2"]  # rankable only, by robustScore desc


def test_stress_unknown_generation_is_404():
    svc = _service(FakeEvoRepo(), FakeJobs(), FakeTrials(), FakeStressBacktest({}))
    with pytest.raises(ApiError) as exc:
        svc.stress("nope", 5, [2, 4])
    assert exc.value.status == 404
    assert exc.value.code == "NOT_FOUND_GENERATION"


def test_stress_sim_blocked_is_422():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 1)
    _seed_generation(repo, sweep_id, [_candidate(0)], policy="SIM_BLOCKED")
    svc = _service(repo, jobs, trials, FakeStressBacktest({}))
    with pytest.raises(ApiError) as exc:
        svc.stress(_GEN_ID, 5, [2, 4])
    assert exc.value.status == 422
    assert exc.value.code == "EVIDENCE_POLICY_BLOCKED"
    assert svc._dispatcher.backtests == []                 # nothing dispatched


def test_stress_no_scored_candidates_is_422():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 1)
    proposed = _candidate(0) | {"state": "PROPOSED"}
    _seed_generation(repo, sweep_id, [proposed])
    svc = _service(repo, jobs, trials, FakeStressBacktest({}))
    with pytest.raises(ApiError) as exc:
        svc.stress(_GEN_ID, 5, [2, 4])
    assert exc.value.status == 422
    assert exc.value.code == "NO_SCORED_CANDIDATES"


def test_stress_in_flight_round_is_409():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 1)
    _seed_generation(repo, sweep_id, [_candidate(0)])
    svc = _service(repo, jobs, trials, FakeStressBacktest({}))
    assert svc._acquire(_GEN_ID)                           # simulate a round already in flight
    with pytest.raises(ApiError) as exc:
        svc.stress(_GEN_ID, 5, [2, 4])
    assert exc.value.status == 409
    assert exc.value.code == "CONFLICT_STRESS_IN_FLIGHT"


def test_stress_no_rankable_candidates_returns_empty_receipt():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 1)
    _seed_generation(repo, sweep_id, [_candidate(0, rankable=False)])
    svc = _service(repo, jobs, trials, FakeStressBacktest({}))
    receipt = svc.stress(_GEN_ID, 5, [2, 4])
    assert receipt["dispatched"] == 0
    assert receipt["skipped"] == 1
    assert svc._dispatcher.backtests == []


# --- REST surface -------------------------------------------------------------------------------


def _client(repo, jobs, trials, backtest) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.stress = _service(repo, jobs, trials, backtest)
    app.include_router(stress.router)
    return TestClient(app)


def test_stress_endpoint_returns_202_receipt():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 2)
    _seed_generation(repo, sweep_id, [_candidate(0), _candidate(1)])
    backtest = FakeStressBacktest(
        run_metrics={"run-0": {_METRIC: 1.0}, "run-1": {_METRIC: 1.0}}, jobs=jobs
    )
    resp = _client(repo, jobs, trials, backtest).post(
        f"/api/v1/evolution/generations/{_GEN_ID}/stress", json={"topK": 1, "multipliers": [2, 4]}
    )
    assert resp.status_code == 202, resp.text
    body = resp.json()
    assert body["dispatched"] == 2                         # top-1 candidate × 2 multipliers
    assert len(body["candidates"]) == 1
    assert {r["multiplier"] for r in body["candidates"][0]["runs"]} == {2.0, 4.0}


def test_stress_endpoint_body_optional_defaults():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 2)
    _seed_generation(repo, sweep_id, [_candidate(0), _candidate(1)])
    backtest = FakeStressBacktest(
        run_metrics={"run-0": {_METRIC: 1.0}, "run-1": {_METRIC: 1.0}}, jobs=jobs
    )
    resp = _client(repo, jobs, trials, backtest).post(
        f"/api/v1/evolution/generations/{_GEN_ID}/stress"
    )
    assert resp.status_code == 202
    assert resp.json()["multipliers"] == [2.0, 4.0]        # defaults topK=5, multipliers=[2,4]


def test_stress_endpoint_bad_multiplier_is_422():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, 1)
    _seed_generation(repo, sweep_id, [_candidate(0)])
    resp = _client(repo, jobs, trials, FakeStressBacktest({})).post(
        f"/api/v1/evolution/generations/{_GEN_ID}/stress", json={"multipliers": [0.5]}
    )
    assert resp.status_code == 422
