"""The autonomy scheduler (design §11 / §12 E6 item 16 / §1.3 control loop / §1.4 safety).

The scheduler ORCHESTRATES the E1–E4 pieces — it launches a generation's sweep (SweepService),
records + scores it (EvoRecorderService.record_generation), selects survivors
(EvoRecorderService.select_survivors), and mints owner-inbox proposals
(ProposalService.generate_for_campaign) — and it duplicates NONE of their logic. One ``tick`` sweeps
the proposal-expiry (§8.2) and then advances every ACTIVE (RUNNING) campaign by AT MOST ONE step:

  IDLE      → launch the next generation's sweep (if budget + cadence permit)      → EVALUATING
  EVALUATING→ still running: wait                                                  → EVALUATING
            → completed:   record + select + propose, then                         → IDLE|EXHAUSTED
            → failed:      reset (durable resume — relaunch next tick)             → IDLE
  EXHAUSTED → budget spent: skip (awaiting owner)                                  → EXHAUSTED

Two hard safety invariants (§1.4), encoded not documented:
  * NOTHING self-arms / self-publishes. The scheduler advances RESEARCH only (sweeps / scoring /
    proposals). Publishing a paper clone, promoting a champion, arming a flag — all stay owner-
    clicked (the ProposalService.execute path, gated behind an APPROVED owner decision). The
    scheduler never calls execute.
  * Evidence-policy refusal. Only SIM_FIRST campaigns are sim-sweep-schedulable. A LIVE_FIRST /
    SIM_BLOCKED campaign needs live-evidence windows (shadow / paper / counterfactual) the scheduler
    cannot manufacture, and scoring a sim sweep for those families is a forbidden plane (§1.2) — the
    scheduler REFUSES to advance them (POLICY_REFUSED), it never launches a sweep for them.

Durability (§11 "durable orchestration"): the per-campaign state machine is persisted per step in
evo_campaigns (V018: scheduler_state / pending_sweep_job_id / last_scheduled_at), so a restart
resumes. A restart mid-sweep leaves the OPTIMIZATION job marked ``failed`` by fail_orphaned_sweeps
(main.py boot) — the next tick sees the failed pending sweep and resets the campaign to IDLE to
relaunch (the #728/#729 reap-or-resume precedent; EVALUATING is scheduler-owned, so the tick IS the
reaper — no separate boot pass needed). record_generation's 409 idempotency + select's idempotent
re-run make the record→select→propose step safe to retry after a crash between record and clear.

The whole surface is DEFAULT-OFF: the background driver only starts when ARTHA_EVO_SCHEDULER_ENABLED
is true (owner-gated arming). When off, campaigns advance ONLY via the owner-triggered
POST /scheduler/tick (supervised single-step) — which itself advances research only, nothing arms.
"""

from __future__ import annotations

import logging
import threading
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app.errors import ApiError

log = logging.getLogger("optimizer")

router = APIRouter(prefix="/api/v1/evolution")

# The only evidence policy the scheduler advances with sim sweeps (§1.2). LIVE_FIRST / SIM_BLOCKED
# are refused — their ranking evidence is live-plane and cannot be manufactured by a sim sweep.
_SIM_SWEEP_POLICY = "SIM_FIRST"

# The default sweep objective for a SIM_FIRST walk-forward campaign whose searchSpace omits one —
# OOS fold mean is the anti-overfit default (matches SweepService._fold_objective_guard's steer).
_DEFAULT_OBJECTIVE = {"metric": "oos_fold_mean", "direction": "maximize"}

# The plateau top-K survivors per generation (§1.3 step 4; mirrors evolution._DEFAULT_TOP_K).
_DEFAULT_TOP_K = 15


class CampaignAdvance(BaseModel):
    """One campaign's outcome for a tick. ``action`` is the step taken (or refused); ``reason`` is a
    human-readable trace; ``sweepJobId`` / ``generationN`` populate when the step touched them."""

    campaignId: str
    action: str
    reason: str
    sweepJobId: str | None = None
    generationN: int | None = None


class SchedulerTickResult(BaseModel):
    """The outcome of one scheduler tick: when it ran, how many stale proposals it expired, and the
    per-campaign advances. The owner-triggered POST /scheduler/tick returns this; the background
    driver logs a summary of it."""

    ranAt: str
    expiredProposals: int
    campaigns: list[CampaignAdvance]


class ExpireResult(BaseModel):
    """The outcome of a standalone proposal-expiry sweep (§8.2 7-day expiry)."""

    expired: int
    proposalIds: list[str]


class SchedulerService:
    """Advances ACTIVE evolution campaigns by orchestrating the E1–E4 services. Collaborators are
    injected (duck-typed, like every other evo service) so tests drive it with the in-memory fakes:

      * ``repo_factory``  — an EvoRepo per call (scheduler-state reads/writes + generation counts).
      * ``sweeps``        — the SweepService (``submit`` a sweep, poll ``job_status``).
      * ``recorder``      — EvoRecorderService (``record_generation`` + ``select_survivors``).
      * ``proposals``     — ProposalService (``generate_for_campaign``).
      * ``clock``         — a tz-aware ``now`` provider (injected for deterministic cadence tests).
      * ``top_k``         — survivors kept per generation.
    """

    def __init__(
        self,
        *,
        repo_factory: Callable[[], Any],
        sweeps: Any,
        recorder: Any,
        proposals: Any,
        clock: Callable[[], datetime] | None = None,
        top_k: int = _DEFAULT_TOP_K,
    ) -> None:
        self._repo_factory = repo_factory
        self._sweeps = sweeps
        self._recorder = recorder
        self._proposals = proposals
        self._clock = clock or (lambda: datetime.now(tz=UTC))
        self._top_k = top_k

    # --- the public surface ---------------------------------------------------------------------

    def tick(self) -> SchedulerTickResult:
        """One scheduler cycle: expire stale proposals (§8.2), then advance every ACTIVE campaign by
        at most one step. A single campaign's fault is contained — it becomes an ERROR advance, the
        others still run (the driver must never be killed by one bad campaign)."""
        now = self._clock()
        expired = self.expire_proposals()
        targets = self._list_targets()
        advances = [self._advance_guarded(target, now) for target in targets]
        return SchedulerTickResult(
            ranAt=now.isoformat(), expiredProposals=len(expired), campaigns=advances
        )

    def expire_proposals(self) -> list[str]:
        """Flip every PENDING proposal past its 7-day ``expires_at`` to EXPIRED (§8.2). Returns the
        expired proposal ids. DB-clock-anchored in the repo — no Python wall-clock is trusted."""
        repo = self._repo_factory()
        try:
            rows = repo.expire_pending_proposals()
        finally:
            repo.close()
        return [r["id"] for r in rows]

    def advance_campaign(self, target: dict[str, Any], now: datetime) -> CampaignAdvance:
        """Advance ONE campaign by at most one step. Pure orchestration over the injected services;
        every branch returns a typed :class:`CampaignAdvance`. ``target`` is the
        ``list_active_scheduler_targets`` row (campaign + V018 scheduler columns)."""
        campaign_id = target["id"]
        policy = target.get("evidencePolicy")

        # §1.2 evidence-policy refusal — the scheduler advances SIM_FIRST campaigns only.
        if policy != _SIM_SWEEP_POLICY:
            return _advance(
                campaign_id, "POLICY_REFUSED",
                f"evidencePolicy {policy!r} is not sim-sweep-schedulable — such families rank on "
                "live evidence (shadow/paper/counterfactual) the scheduler cannot manufacture "
                "(§1.2); no sweep launched",
            )

        sched = target.get("schedulerState")
        pending = target.get("pendingSweepJobId")
        budget = target.get("budget") or {}
        max_generations = _int(budget.get("maxGenerations"))

        if sched == "EXHAUSTED":
            return _advance(
                campaign_id, "BUDGET_EXHAUSTED",
                "budget exhausted — the scheduler advances no further; awaiting owner (extend the "
                "budget or CLOSE the campaign)",
            )

        # A) resolve an in-flight sweep -------------------------------------------------------
        if pending:
            status = self._sweep_status(pending)
            if status == "completed":
                return self._record_completed(campaign_id, pending, max_generations)
            if status in ("failed", "cancelled", "missing"):
                # Durable resume (§11): a restart-orphaned (or genuinely failed) sweep resets the
                # campaign to IDLE — the next tick relaunches a fresh generation. No partial cohort
                # is ever recorded (record only runs on a completed sweep).
                self._resolve(campaign_id, "IDLE")
                return _advance(
                    campaign_id, "SWEEP_FAILED",
                    f"pending sweep {pending} is {status} — reset to IDLE for relaunch",
                    sweep_job_id=pending,
                )
            return _advance(
                campaign_id, "WAITING_SWEEP",
                f"generation sweep {pending} is in flight ({status})", sweep_job_id=pending,
            )

        # B) idle — maybe launch the next generation ------------------------------------------
        generation_count = self._generation_count(campaign_id)
        if max_generations is not None and generation_count >= max_generations:
            self._set_state(campaign_id, "EXHAUSTED")
            return _advance(
                campaign_id, "BUDGET_EXHAUSTED",
                f"{generation_count}/{max_generations} generations used — marked EXHAUSTED",
            )

        cadence_wait = _cadence_block(target, now)
        if cadence_wait is not None:
            return _advance(campaign_id, "WAITING_CADENCE", cadence_wait)

        return self._launch(campaign_id, target, budget)

    # --- step implementations -------------------------------------------------------------------

    def _advance_guarded(self, target: dict[str, Any], now: datetime) -> CampaignAdvance:
        """advance_campaign wrapped so one campaign's fault (a bad searchSpace, a downstream 5xx)
        never aborts the tick for the others — it becomes an ERROR advance, logged."""
        try:
            return self.advance_campaign(target, now)
        except Exception as exc:  # noqa: BLE001 - a per-campaign fault is contained, never fatal
            log.exception("scheduler: campaign %s failed to advance", target.get("id"))
            return _advance(
                target.get("id", "?"), "ERROR", f"advance failed: {type(exc).__name__}: {exc}"
            )

    def _record_completed(
        self, campaign_id: str, sweep_id: str, max_generations: int | None
    ) -> CampaignAdvance:
        """A pending sweep has completed: record it as a generation, select survivors, mint
        proposals — all idempotent — then clear the pending marker and set the next state on budget.
        The 409 (sweep already recorded, from a crash between record and clear) is absorbed: we
        re-derive the generation n and continue the idempotent tail."""
        generation_n = self._record_generation(campaign_id, sweep_id)
        if generation_n is not None:
            self._recorder.select_survivors(campaign_id, generation_n, self._top_k)
        # PROPOSE ACTIONS (§1.3 step 6): mint/refresh PUBLISH_PAPER proposals to the owner inbox.
        # Idempotent (one OPEN proposal per candidate); NOTHING self-arms — the owner approves.
        self._proposals.generate_for_campaign(campaign_id)

        generation_count = self._generation_count(campaign_id)
        exhausted = max_generations is not None and generation_count >= max_generations
        next_state = "EXHAUSTED" if exhausted else "IDLE"
        self._resolve(campaign_id, next_state)
        detail = (
            f"recorded generation {generation_n}; {generation_count} generation(s) used; "
            f"next state {next_state}"
        )
        return _advance(
            campaign_id, "RECORDED", detail, sweep_job_id=sweep_id, generation_n=generation_n
        )

    def _record_generation(self, campaign_id: str, sweep_id: str) -> int | None:
        """Record the completed sweep as a generation, returning its ``n``. On a 409 (already
        recorded — a crash re-tick) re-derive ``n`` from the existing generations, so the idempotent
        select/propose tail still runs. Any OTHER ApiError propagates (a genuine fault)."""
        try:
            return self._recorder.record_generation(campaign_id, {"sweepJobId": sweep_id}).n
        except ApiError as exc:
            if exc.status == 409 and exc.code == "CONFLICT_SWEEP_RECORDED":
                return self._find_generation_n(campaign_id, sweep_id)
            raise

    def _launch(
        self, campaign_id: str, target: dict[str, Any], budget: dict[str, Any]
    ) -> CampaignAdvance:
        """Build + submit the next generation's sweep, then claim it as the campaign's pending
        sweep (state → EVALUATING). A campaign whose searchSpace cannot form a sweep request, or
        whose submission is rejected, is SKIPPED (never crashes the tick)."""
        request = _build_sweep_request(target, budget)
        if request is None:
            return _advance(
                campaign_id, "NO_SEARCH_SPACE",
                "campaign searchSpace lacks strategyId/from/to — cannot form a sweep request; "
                "no generation launched",
            )
        try:
            sweep_id = self._sweeps.submit(request)
        except ApiError as exc:
            return _advance(
                campaign_id, "SUBMIT_FAILED",
                f"sweep submission rejected ({exc.code}): {exc.message}",
            )
        self._claim(campaign_id, sweep_id, self._clock())
        return _advance(
            campaign_id, "LAUNCHED",
            f"launched generation sweep {sweep_id} (maxTrials={request.get('maxTrials')})",
            sweep_job_id=sweep_id,
        )

    # --- repo helpers (one connection per call, matching the service-wide pattern) --------------

    def _list_targets(self) -> list[dict[str, Any]]:
        repo = self._repo_factory()
        try:
            return repo.list_active_scheduler_targets()
        finally:
            repo.close()

    def _generation_count(self, campaign_id: str) -> int:
        repo = self._repo_factory()
        try:
            return len(repo.list_generations(campaign_id))
        finally:
            repo.close()

    def _find_generation_n(self, campaign_id: str, sweep_id: str) -> int | None:
        repo = self._repo_factory()
        try:
            for gen in repo.list_generations(campaign_id):
                if (gen.get("proposal") or {}).get("sweepJobId") == sweep_id:
                    return gen["n"]
        finally:
            repo.close()
        return None

    def _sweep_status(self, sweep_id: str) -> str:
        """The pending sweep's job status, or ``"missing"`` if the job row is gone (a 404 — the
        scheduler treats it like a failure and relaunches, never a crash)."""
        try:
            return self._sweeps.job_status(sweep_id)["status"]
        except ApiError as exc:
            if exc.status == 404:
                return "missing"
            raise

    def _claim(self, campaign_id: str, sweep_id: str, scheduled_at: datetime) -> None:
        repo = self._repo_factory()
        try:
            repo.claim_pending_sweep(campaign_id, sweep_id, scheduled_at)
        finally:
            repo.close()

    def _resolve(self, campaign_id: str, next_state: str) -> None:
        repo = self._repo_factory()
        try:
            repo.resolve_pending_sweep(campaign_id, next_state)
        finally:
            repo.close()

    def _set_state(self, campaign_id: str, state: str) -> None:
        repo = self._repo_factory()
        try:
            repo.set_scheduler_state(campaign_id, state)
        finally:
            repo.close()


class SchedulerDriver:
    """The DEFAULT-OFF background driver — a daemon thread that ticks the scheduler on an interval.
    Started (from main.py) ONLY when ARTHA_EVO_SCHEDULER_ENABLED is true. A tick fault is logged and
    swallowed so one bad cycle never kills the loop; ``stop`` ends it cleanly (used by tests)."""

    def __init__(self, service: SchedulerService, interval_seconds: int) -> None:
        self._service = service
        self._interval = max(interval_seconds, 1)
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._loop, name="evo-scheduler", daemon=True)
        self._thread.start()

    def _loop(self) -> None:
        # wait() returns True on stop, False on timeout — sleep the interval BEFORE the first tick
        # (no boot-time hammer) and between ticks, exiting promptly on stop.
        while not self._stop.wait(self._interval):
            try:
                result = self._service.tick()
                acts = ", ".join(f"{c.campaignId}:{c.action}" for c in result.campaigns) or "none"
                log.info(
                    "evo scheduler tick: %d campaign(s) [%s]; %d proposal(s) expired",
                    len(result.campaigns), acts, result.expiredProposals,
                )
            except Exception:  # noqa: BLE001 - a tick fault must never kill the driver
                log.exception("evo scheduler tick failed")

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)


# --- request-building + cadence helpers (pure) --------------------------------------------------

def _build_sweep_request(
    target: dict[str, Any], budget: dict[str, Any]
) -> dict[str, Any] | None:
    """Form the SweepService.submit request from the campaign's frozen ``searchSpace`` (the YAML
    optimize block + window) + ``budget.maxTrialsPerGen``. The searchSpace carries the sweep window
    and any per-sweep controls; the objective defaults to OOS fold mean (the SIM_FIRST default).
    Returns ``None`` when the minimum inputs (strategyId + from + to) are absent — the campaign is
    then SKIPPED rather than submitting an invalid sweep."""
    search_space = target.get("searchSpace") or {}
    strategy_id = target.get("strategyId")
    window_from = search_space.get("from")
    window_to = search_space.get("to")
    if not (strategy_id and window_from and window_to):
        return None

    request: dict[str, Any] = {
        "strategyId": strategy_id,
        "from": window_from,
        "to": window_to,
        "objective": search_space.get("objective") or _DEFAULT_OBJECTIVE,
    }
    if search_space.get("parameters") is not None:
        request["parameters"] = search_space["parameters"]
    max_trials = _int(budget.get("maxTrialsPerGen"))
    if max_trials is not None:
        request["maxTrials"] = max_trials
    # Pass through any per-sweep controls the searchSpace pins (else SweepService uses its default).
    passthrough = (
        "walkForward", "interval", "initialCapital", "method", "seed", "strategyVersion",
    )
    for optional in passthrough:
        if search_space.get(optional) is not None:
            request[optional] = search_space[optional]
    return request


def _cadence_block(target: dict[str, Any], now: datetime) -> str | None:
    """The per-campaign generation-cadence gate (§1.3 step 7 — swing weekly, scalper per evidence
    window). ``budget.cadenceSeconds`` is the minimum interval between generation launches; a launch
    within that window of ``last_scheduled_at`` is DEFERRED. Returns the wait reason, or ``None``
    when no cadence is configured / the anchor is unset / enough time has elapsed."""
    budget = target.get("budget") or {}
    cadence_seconds = _int(budget.get("cadenceSeconds"))
    last = target.get("lastScheduledAt")
    if cadence_seconds is None or not last:
        return None
    last_dt = _parse_ts(last)
    if last_dt is None:
        return None
    elapsed = (now - last_dt).total_seconds()
    if elapsed < cadence_seconds:
        return (
            f"cadence gate: {int(cadence_seconds - elapsed)}s until the next generation "
            f"(interval {cadence_seconds}s)"
        )
    return None


def _advance(
    campaign_id: str,
    action: str,
    reason: str,
    *,
    sweep_job_id: str | None = None,
    generation_n: int | None = None,
) -> CampaignAdvance:
    return CampaignAdvance(
        campaignId=campaign_id, action=action, reason=reason,
        sweepJobId=sweep_job_id, generationN=generation_n,
    )


def _int(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _parse_ts(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return value
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return None


# --- router -------------------------------------------------------------------------------------

def _service(request: Request) -> SchedulerService:
    return request.app.state.scheduler


@router.post("/scheduler/tick", response_model=SchedulerTickResult)
def scheduler_tick(request: Request) -> SchedulerTickResult:
    """Run ONE scheduler cycle NOW (owner/ops-triggered supervised advance): expire stale proposals,
    then advance every ACTIVE campaign by at most one step. This advances RESEARCH only (launch a
    sweep, record/score/select a completed one, mint proposals) — NOTHING self-arms/self-publishes
    (publish/promote stay owner-clicked via the proposal execute path). Safe to call whether or not
    the background driver is armed."""
    return _service(request).tick()


@router.post("/proposals/expire", response_model=ExpireResult)
def expire_proposals(request: Request) -> ExpireResult:
    """Sweep the proposal inbox for PENDING proposals past their §8.2 7-day expiry and mark them
    EXPIRED. Idempotent; DB-clock-anchored. (The scheduler tick also runs this; the endpoint is the
    standalone ops surface.)"""
    expired = _service(request).expire_proposals()
    return ExpireResult(expired=len(expired), proposalIds=expired)
