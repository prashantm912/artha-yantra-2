"""The autonomy scheduler (design §11 / §12 E6 item 16 / §1.3 control loop / §1.4 safety).

The scheduler ORCHESTRATES the E1–E4 pieces — it launches a generation's sweep (SweepService),
submits the plateau neighbor probes (SweepService.probe, §3.2.3), records + scores the cohort
(EvoRecorderService.record_generation), dispatches the cost-stress round (StressService, §3.2.5),
selects survivors (EvoRecorderService.select_survivors), and mints owner-inbox proposals
(ProposalService.generate_for_campaign) — and it duplicates NONE of their logic. One ``tick``
sweeps the proposal expiry (§8.2) and then advances every ENROLLED + ACTIVE campaign by AT MOST ONE
step through the §1.3 loop:

  (not enrolled) → skipped — the owner enrolls each campaign explicitly (/scheduler/enroll);
                   arming the GLOBAL flag alone advances nothing (adversarial review finding 2)
  IDLE       → launch the next generation's sweep (budget + cadence + slot cap permitting)
  EVALUATING → sweep completed: submit neighbor probes                            → PROBING
             → sweep failed/orphaned: reset (durable resume — relaunch next tick) → IDLE
  PROBING    → probes drained: record + score the cohort, dispatch cost-stress    → STRESSING
  STRESSING  → stress round done: select survivors + mint proposals               → IDLE|EXHAUSTED
  EXHAUSTED  → budget spent: skip (awaiting owner — re-enroll after a budget bump)

The §1.3 rungs NOT run autonomously, by design: the one-shot HOLDOUT stays owner-triggered (its
budget must never be auto-spent), and the autonomous proposal mint SKIPS candidates whose holdout
is unconsumed (``require_holdout`` — §6.1's hard gate is never degraded to a caveat by autonomy;
the campaign report surfaces the holdout-pending survivors). The §7.3 live watch IS run: campaigns
holding PAPER/TAKE_ELIGIBLE/PROMOTED candidates get the existing assess_take_eligible /
assess_rollback pass each tick — both only RAISE owner-gated proposals.

Hard safety invariants (§1.4), encoded not documented:
  * NOTHING self-arms / self-publishes. The scheduler advances RESEARCH only. Publishing a paper
    clone, promoting a champion, arming a flag — all stay owner-clicked (ProposalService.execute,
    behind an APPROVED owner decision). The scheduler never calls execute.
  * Evidence-policy refusal. Only SIM_FIRST campaigns are sim-sweep-schedulable; LIVE_FIRST /
    SIM_BLOCKED are refused (their ranking evidence is live-plane, §1.2) — an enrolled LIVE_FIRST
    campaign is live-watch-only.
  * Compute fairness (§1.4.6): at most ARTHA_EVO_MAX_CONCURRENT_SWEEPS scheduler-launched
    generations in flight across ALL campaigns (default 1 — serial), so campaign nights never
    starve the owner's interactive backtests.

Concurrency: ``tick``/enroll/withdraw are serialized by an in-process lock (the armed background
driver and the manual POST /scheduler/tick share this process — interleaved ticks would violate the
repo layer's sole-writer assumption), and the launch claim is a CONDITIONAL UPDATE
(``pending_sweep_job_id IS NULL``) as the DB backstop — a lost claim cancels its just-submitted
sweep and skips (adversarial review finding 1).

Durability (§11 "durable orchestration"): the per-campaign state machine is persisted per step in
evo_campaigns (V017: scheduler_state / pending_sweep_job_id / last_scheduled_at), so a restart
resumes. A restart mid-sweep leaves the OPTIMIZATION job ``failed`` (fail_orphaned_sweeps at boot)
— the next tick resets the campaign to IDLE and relaunches (the #728/#729 reap-or-resume precedent;
the mid-generation states are scheduler-owned, so the tick IS the reaper — no separate boot pass);
a restart mid-probe-drain strands RUNNING trial rows the boot reaper deletes (probes then read as
drained — the E2 recovery semantics); a restart mid-stress leaves the generation's durable
STRESSING marker the boot reaper flips to DONE. record_generation's 409 idempotency + select's
idempotent re-run make every resume path safe to retry.

The whole surface is DEFAULT-OFF: the background driver only starts when ARTHA_EVO_SCHEDULER_ENABLED
is true (owner-gated arming), and even then only ENROLLED campaigns advance.
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

# Neighbor-probe defaults (§3.2.3) — mirror api.ProbeRequest (topK=5, maxProbes=40).
_PROBE_TOP_K = 5
_PROBE_MAX_PROBES = 40

# Cost-stress defaults (§3.2.5 / §12 item 5's "2×/4×") — mirror stress.StressRequest.
_STRESS_TOP_K = 5
_STRESS_MULTIPLIERS = (2.0, 4.0)

# Candidate states that make the §7.3 live watch worth running.
_TAKE_ELIGIBLE_WATCH_STATES = frozenset({"PAPER", "TAKE_ELIGIBLE"})
_ROLLBACK_WATCH_STATES = frozenset({"PROMOTED"})


class CampaignAdvance(BaseModel):
    """One campaign's outcome for a tick. ``action`` is the step taken (or refused); ``reason`` is a
    human-readable trace; ``sweepJobId`` / ``generationN`` populate when the step touched them;
    ``liveWatch`` records the §7.3 take-eligible/rollback assessment pass when it ran."""

    campaignId: str
    action: str
    reason: str
    sweepJobId: str | None = None
    generationN: int | None = None
    liveWatch: str | None = None


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


class EnrollmentResult(BaseModel):
    """The outcome of an enroll/withdraw click: the campaign's autonomy sub-state before and after.
    ``schedulerState`` None = NOT ENROLLED (the default for every campaign)."""

    campaignId: str
    schedulerState: str | None = None
    previousState: str | None = None
    pendingSweepJobId: str | None = None
    changed: bool


class SchedulerStatus(BaseModel):
    """The scheduler heartbeat: when the last tick ran (None = no tick since boot). In-memory by
    design — a restart resets it, and the armed driver's next tick re-stamps it within one interval,
    so a stale/None value IS the signal the loop is not running."""

    lastTickAt: str | None = None


class SchedulerService:
    """Advances ENROLLED + ACTIVE evolution campaigns by orchestrating the E1–E4 services.
    Collaborators are injected (duck-typed, like every other evo service) so tests drive it with
    the in-memory fakes:

      * ``repo_factory``   — an EvoRepo per call (scheduler-state reads/writes + generation reads).
      * ``sweeps``         — the SweepService (submit / job_status / probe / cancel).
      * ``recorder``       — EvoRecorderService (``record_generation`` + ``select_survivors``).
      * ``proposals``      — ProposalService (``generate_for_campaign`` + the §7.3 assess pair).
      * ``trials_factory`` — a TrialsRepo per call (probe-drain detection); optional — absent means
                             probes are never waited on (test substrate).
      * ``stress``         — the StressService (cost-stress dispatch); optional — absent skips the
                             stress rung with an honest note.
      * ``clock``          — a tz-aware ``now`` provider (injected for deterministic cadence tests).
      * ``top_k``          — survivors kept per generation.
      * ``max_concurrent_sweeps`` — §1.4.6 global cap on scheduler-launched generations in flight.
    """

    def __init__(
        self,
        *,
        repo_factory: Callable[[], Any],
        sweeps: Any,
        recorder: Any,
        proposals: Any,
        trials_factory: Callable[[], Any] | None = None,
        stress: Any = None,
        clock: Callable[[], datetime] | None = None,
        top_k: int = _DEFAULT_TOP_K,
        max_concurrent_sweeps: int = 1,
    ) -> None:
        self._repo_factory = repo_factory
        self._sweeps = sweeps
        self._recorder = recorder
        self._proposals = proposals
        self._trials_factory = trials_factory
        self._stress = stress
        self._clock = clock or (lambda: datetime.now(tz=UTC))
        self._top_k = top_k
        self._max_concurrent = max(max_concurrent_sweeps, 0)
        # Serializes tick/enroll/withdraw: the armed background driver and the manual endpoint share
        # this process — interleaved ticks would violate the repo layer's sole-writer assumption
        # (adversarial review finding 1). The conditional claim UPDATE is the DB backstop.
        self._lock = threading.Lock()
        self._last_tick_at: str | None = None

    # --- the public surface ---------------------------------------------------------------------

    def tick(self) -> SchedulerTickResult:
        """One scheduler cycle: expire stale proposals (§8.2), then advance every ENROLLED + ACTIVE
        campaign by at most one step (under the global in-flight cap). A single campaign's fault is
        contained — it becomes an ERROR advance, the others still run."""
        with self._lock:
            now = self._clock()
            self._last_tick_at = now.isoformat()
            expired = self._expire()
            targets = self._list_targets()
            in_flight = sum(1 for t in targets if t.get("pendingSweepJobId"))
            slots = {"remaining": self._max_concurrent - in_flight}
            advances = [self._advance_guarded(target, now, slots) for target in targets]
            return SchedulerTickResult(
                ranAt=now.isoformat(), expiredProposals=len(expired), campaigns=advances
            )

    def expire_proposals(self) -> list[str]:
        """Flip every PENDING proposal past its 7-day ``expires_at`` to EXPIRED (§8.2). Returns the
        expired proposal ids. DB-clock-anchored in the repo — no Python wall-clock is trusted."""
        with self._lock:
            return self._expire()

    def enroll(self, campaign_id: str) -> EnrollmentResult:
        """Enroll one campaign into autonomy (owner-clicked): NULL (not enrolled) or EXHAUSTED (the
        awaiting state — re-enroll after a budget bump) → IDLE. Already-enrolled states are a no-op
        (idempotent; a mid-generation campaign is never reset). 404 unknown campaign. The GLOBAL
        flag arms only the driver — enrollment is the per-campaign consent (finding 2)."""
        with self._lock:
            repo = self._repo_factory()
            try:
                row = repo.get_scheduler_row(campaign_id)
                if row is None:
                    raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
                previous = row.get("schedulerState")
                changed = previous in (None, "EXHAUSTED")
                if changed:
                    row = repo.set_scheduler_state(campaign_id, "IDLE") or row
            finally:
                repo.close()
        return EnrollmentResult(
            campaignId=campaign_id,
            schedulerState=row.get("schedulerState"),
            previousState=previous,
            pendingSweepJobId=row.get("pendingSweepJobId"),
            changed=changed,
        )

    def withdraw(self, campaign_id: str) -> EnrollmentResult:
        """Withdraw one campaign from autonomy (owner-clicked): scheduler_state → NULL and any
        pending sweep is abandoned (it completes as an ordinary sweep, recordable manually via the
        E1 recorder). Idempotent. 404 unknown campaign."""
        with self._lock:
            repo = self._repo_factory()
            try:
                row = repo.get_scheduler_row(campaign_id)
                if row is None:
                    raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
                previous = row.get("schedulerState")
                had_pending = row.get("pendingSweepJobId")
                updated = repo.resolve_pending_sweep(campaign_id, None) or row
            finally:
                repo.close()
        return EnrollmentResult(
            campaignId=campaign_id,
            schedulerState=updated.get("schedulerState"),
            previousState=previous,
            pendingSweepJobId=updated.get("pendingSweepJobId"),
            changed=previous is not None or had_pending is not None,
        )

    def status(self) -> SchedulerStatus:
        return SchedulerStatus(lastTickAt=self._last_tick_at)

    # --- the per-campaign state machine ----------------------------------------------------------

    def advance_campaign(
        self, target: dict[str, Any], now: datetime, slots: dict[str, int]
    ) -> CampaignAdvance:
        """Advance ONE campaign by at most one step. Pure orchestration over the injected services;
        every branch returns a typed :class:`CampaignAdvance`. ``target`` is the
        ``list_active_scheduler_targets`` row (campaign + V017 scheduler columns)."""
        campaign_id = target["id"]
        sched = target.get("schedulerState")

        # Per-campaign consent (finding 2): NULL = NOT ENROLLED — arming the global flag alone
        # advances nothing; the owner enrolls each campaign via /scheduler/enroll.
        if sched is None:
            return _advance(
                campaign_id, "NOT_ENROLLED",
                "campaign is not enrolled in autonomy (scheduler_state NULL) — enroll via "
                "POST /campaigns/{id}/scheduler/enroll",
            )

        # §1.2 evidence-policy refusal — the scheduler launches sim sweeps for SIM_FIRST only.
        policy = target.get("evidencePolicy")
        if policy != _SIM_SWEEP_POLICY:
            return _advance(
                campaign_id, "POLICY_REFUSED",
                f"evidencePolicy {policy!r} is not sim-sweep-schedulable — such families rank on "
                "live evidence (shadow/paper/counterfactual) the scheduler cannot manufacture "
                "(§1.2); no sweep launched (live-watch only)",
            )

        pending = target.get("pendingSweepJobId")
        budget = target.get("budget") or {}
        max_generations = _int(budget.get("maxGenerations"))

        if sched == "EXHAUSTED":
            return _advance(
                campaign_id, "BUDGET_EXHAUSTED",
                "budget exhausted — awaiting owner (bump the budget then re-enroll, or CLOSE)",
            )

        if sched == "EVALUATING" and pending:
            return self._advance_evaluating(campaign_id, pending)
        if sched == "PROBING" and pending:
            return self._advance_probing(campaign_id, pending, max_generations)
        if sched == "STRESSING" and pending:
            return self._advance_stressing(campaign_id, pending, max_generations)
        if pending is None and sched in ("EVALUATING", "PROBING", "STRESSING"):
            # Defensive: a mid-generation state without its sweep link cannot progress — reset.
            self._resolve(campaign_id, "IDLE")
            return _advance(
                campaign_id, "SWEEP_FAILED",
                f"state {sched} with no pending sweep — reset to IDLE",
            )

        # IDLE — maybe launch the next generation.
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
        if slots["remaining"] <= 0:
            return _advance(
                campaign_id, "WAITING_SLOT",
                f"global in-flight cap reached ({self._max_concurrent} scheduler-launched "
                "sweep(s)) — §1.4.6 compute fairness; deferred to a later tick",
            )
        return self._launch(campaign_id, target, budget, slots)

    # --- step implementations -------------------------------------------------------------------

    def _advance_guarded(
        self, target: dict[str, Any], now: datetime, slots: dict[str, int]
    ) -> CampaignAdvance:
        """advance_campaign + the §7.3 live watch, wrapped so one campaign's fault (a bad
        searchSpace, a downstream 5xx) never aborts the tick for the others — it becomes an ERROR
        advance, logged."""
        try:
            advance = self.advance_campaign(target, now, slots)
        except Exception as exc:  # noqa: BLE001 - a per-campaign fault is contained, never fatal
            log.exception("scheduler: campaign %s failed to advance", target.get("id"))
            advance = _advance(
                target.get("id", "?"), "ERROR", f"advance failed: {type(exc).__name__}: {exc}"
            )
        if target.get("schedulerState") is not None:
            advance.liveWatch = self._live_watch(target["id"])
        return advance

    def _advance_evaluating(self, campaign_id: str, pending: str) -> CampaignAdvance:
        status = self._sweep_status(pending)
        if status == "completed":
            return self._start_probes(campaign_id, pending)
        if status in ("failed", "cancelled", "missing"):
            # Durable resume (§11): a restart-orphaned (or genuinely failed) sweep resets the
            # campaign to IDLE — the next tick relaunches. No partial cohort is ever recorded.
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

    def _start_probes(self, campaign_id: str, pending: str) -> CampaignAdvance:
        """§1.3 step 3 rung 1: submit the plateau top-K's neighbor probes as ordinary trials of the
        completed sweep (the EXISTING /probes mechanic — probes must land BEFORE record, which
        freezes the cohort). Fail-soft: a probe rejection skips the rung, never stalls the loop."""
        try:
            receipt = self._sweeps.probe(pending, _PROBE_TOP_K, _PROBE_MAX_PROBES)
            note = f"submitted {receipt.get('submitted', 0)} neighbor probe(s) (§3.2.3)"
        except ApiError as exc:
            note = f"neighbor probes skipped ({exc.code}) — proceeding to record"
        self._set_state(campaign_id, "PROBING")
        return _advance(campaign_id, "PROBES_SUBMITTED", note, sweep_job_id=pending)

    def _advance_probing(
        self, campaign_id: str, pending: str, max_generations: int | None
    ) -> CampaignAdvance:
        if self._probes_running(pending):
            return _advance(
                campaign_id, "WAITING_PROBES",
                f"neighbor probes for sweep {pending} still draining", sweep_job_id=pending,
            )
        return self._record_and_stress(campaign_id, pending, max_generations)

    def _record_and_stress(
        self, campaign_id: str, pending: str, max_generations: int | None
    ) -> CampaignAdvance:
        """Probes drained: record the cohort as a generation (409-idempotent across crash re-ticks),
        then dispatch the cost-stress round (§1.3 step 3 rung 2 — stress needs the candidates still
        SCORED, so it runs BEFORE select). A generation already stressed (a resume) or a stress
        refusal (e.g. an empty cohort) finalizes directly."""
        generation = self._record_generation(campaign_id, pending)
        if generation is None:
            self._resolve(campaign_id, "IDLE")
            return _advance(
                campaign_id, "ERROR",
                f"sweep {pending} reported already-recorded but its generation was not found — "
                "reset to IDLE",
                sweep_job_id=pending,
            )
        n = generation["n"]
        if self._stress is not None and int(generation.get("stressTouches") or 0) == 0:
            try:
                self._stress.stress(generation["id"], _STRESS_TOP_K, list(_STRESS_MULTIPLIERS))
                self._set_state(campaign_id, "STRESSING")
                return _advance(
                    campaign_id, "STRESS_STARTED",
                    f"generation {n} recorded; cost-stress round dispatched "
                    f"(top-{_STRESS_TOP_K} x {list(_STRESS_MULTIPLIERS)})",
                    sweep_job_id=pending, generation_n=n,
                )
            except ApiError as exc:
                if exc.status == 409:
                    # a round is already in flight (restart resume) — wait on its durable marker.
                    self._set_state(campaign_id, "STRESSING")
                    return _advance(
                        campaign_id, "STRESS_STARTED",
                        f"generation {n} recorded; cost-stress round already in flight "
                        f"({exc.code})",
                        sweep_job_id=pending, generation_n=n,
                    )
                note = f"cost-stress skipped ({exc.code})"
        elif self._stress is None:
            note = "cost-stress skipped (no stress service wired)"
        else:
            note = "cost-stress already applied (resume)"
        return self._finalize(campaign_id, n, max_generations, pending, note)

    def _advance_stressing(
        self, campaign_id: str, pending: str, max_generations: int | None
    ) -> CampaignAdvance:
        generation = self._find_generation(campaign_id, pending)
        if generation is None:
            self._resolve(campaign_id, "IDLE")
            return _advance(
                campaign_id, "ERROR",
                f"STRESSING but no generation for sweep {pending} — reset to IDLE",
                sweep_job_id=pending,
            )
        if generation.get("status") == "STRESSING":
            return _advance(
                campaign_id, "WAITING_STRESS",
                f"cost-stress round for generation {generation['n']} still running",
                sweep_job_id=pending, generation_n=generation["n"],
            )
        return self._finalize(
            campaign_id, generation["n"], max_generations, pending, "cost-stress complete"
        )

    def _finalize(
        self,
        campaign_id: str,
        n: int,
        max_generations: int | None,
        pending: str,
        note: str,
    ) -> CampaignAdvance:
        """§1.3 steps 3–6 tail: select survivors (idempotent), mint owner-inbox proposals (holdout
        REQUIRED on the autonomous mint — §6.1), then clear the pending marker and set the next
        state on budget. NOTHING here publishes — proposals await the owner."""
        self._recorder.select_survivors(campaign_id, n, self._top_k)
        self._proposals.generate_for_campaign(campaign_id, require_holdout=True)
        generation_count = self._generation_count(campaign_id)
        exhausted = max_generations is not None and generation_count >= max_generations
        next_state = "EXHAUSTED" if exhausted else "IDLE"
        self._resolve(campaign_id, next_state)
        return _advance(
            campaign_id, "GENERATION_COMPLETE",
            f"{note}; generation {n} selected + proposed; {generation_count} generation(s) used; "
            f"next state {next_state}",
            sweep_job_id=pending, generation_n=n,
        )

    def _record_generation(self, campaign_id: str, sweep_id: str) -> dict[str, Any] | None:
        """Record the completed sweep as a generation, returning ``{id, n, stressTouches}``. On a
        409 (already recorded — a crash re-tick) re-derive it from the existing generations, so the
        idempotent stress/select/propose tail still runs. Any OTHER ApiError propagates."""
        try:
            recorded = self._recorder.record_generation(campaign_id, {"sweepJobId": sweep_id})
            return {"id": recorded.id, "n": recorded.n,
                    "stressTouches": recorded.stressTouches, "status": recorded.status}
        except ApiError as exc:
            if exc.status == 409 and exc.code == "CONFLICT_SWEEP_RECORDED":
                return self._find_generation(campaign_id, sweep_id)
            raise

    def _launch(
        self,
        campaign_id: str,
        target: dict[str, Any],
        budget: dict[str, Any],
        slots: dict[str, int],
    ) -> CampaignAdvance:
        """Build + submit the next generation's sweep, then claim it as the campaign's pending sweep
        (state → EVALUATING). The claim is a CONDITIONAL UPDATE — a lost claim (another writer got
        there first; the DB backstop behind the tick lock) cancels the just-submitted sweep and
        skips."""
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
        claimed = self._claim(campaign_id, sweep_id, self._clock())
        if claimed is None:
            # The conditional claim matched 0 rows — a competing writer pinned a sweep between our
            # read and write. Cancel the orphan we just submitted (fail-soft) and skip.
            try:
                self._sweeps.cancel(sweep_id)
            except Exception:  # noqa: BLE001 - cancel is best-effort cleanup on a lost race
                log.warning(
                    "scheduler: could not cancel orphan sweep %s after lost claim", sweep_id
                )
            return _advance(
                campaign_id, "CLAIM_LOST",
                f"claim lost (another writer pinned a sweep first); submitted sweep {sweep_id} "
                "cancelled",
                sweep_job_id=sweep_id,
            )
        slots["remaining"] -= 1
        return _advance(
            campaign_id, "LAUNCHED",
            f"launched generation sweep {sweep_id} (maxTrials={request.get('maxTrials')})",
            sweep_job_id=sweep_id,
        )

    def _live_watch(self, campaign_id: str) -> str | None:
        """§7.3 / §8.2 continuous watch: run the EXISTING assess_take_eligible (campaigns holding
        PAPER/TAKE_ELIGIBLE candidates) and assess_rollback (PROMOTED) passes — both only advance
        research state and RAISE owner-gated proposals; the design explicitly calls the raising
        autonomous. Fail-soft per pass; None when no candidate state warrants a watch."""
        try:
            repo = self._repo_factory()
            try:
                states = {
                    c.get("state") for c in repo.list_candidates_for_campaign(campaign_id)
                }
            finally:
                repo.close()
        except Exception as exc:  # noqa: BLE001 - the watch must never break the advance
            return f"live watch skipped: {type(exc).__name__}"
        notes: list[str] = []
        if states & _TAKE_ELIGIBLE_WATCH_STATES:
            notes.append(self._watch_pass("take-eligible", campaign_id,
                                          self._proposals.assess_take_eligible))
        if states & _ROLLBACK_WATCH_STATES:
            notes.append(self._watch_pass("rollback", campaign_id,
                                          self._proposals.assess_rollback))
        return "; ".join(notes) if notes else None

    @staticmethod
    def _watch_pass(name: str, campaign_id: str, assess: Callable[[str], Any]) -> str:
        try:
            result = assess(campaign_id)
            return (
                f"{name}: assessed {result.assessed}, eligible {result.eligible}, "
                f"proposals +{result.generated}/~{result.refreshed}"
            )
        except Exception as exc:  # noqa: BLE001 - a watch fault is reported, never fatal
            log.warning("scheduler: %s watch failed for campaign %s: %s", name, campaign_id, exc)
            return f"{name}: watch failed ({type(exc).__name__})"

    # --- repo/collaborator helpers (one connection per call, the service-wide pattern) -----------

    def _expire(self) -> list[str]:
        repo = self._repo_factory()
        try:
            rows = repo.expire_pending_proposals()
        finally:
            repo.close()
        return [r["id"] for r in rows]

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

    def _find_generation(self, campaign_id: str, sweep_id: str) -> dict[str, Any] | None:
        repo = self._repo_factory()
        try:
            for gen in repo.list_generations(campaign_id):
                if (gen.get("proposal") or {}).get("sweepJobId") == sweep_id:
                    return gen
        finally:
            repo.close()
        return None

    def _probes_running(self, sweep_id: str) -> bool:
        """Any RUNNING trial rows left for the sweep = the probe drain is still working. A restart
        mid-drain strands RUNNING rows the boot reaper deletes, so this reads drained after a
        restart (partial probes accepted — the E2 recovery semantics). No trials repo → False."""
        if self._trials_factory is None:
            return False
        trials = self._trials_factory()
        try:
            return bool(trials.list_for_sweep(sweep_id, "RUNNING", 1, 0))
        finally:
            trials.close()

    def _sweep_status(self, sweep_id: str) -> str:
        """The pending sweep's job status, or ``"missing"`` if the job row is gone (a 404 — treated
        like a failure and relaunched, never a crash)."""
        try:
            return self._sweeps.job_status(sweep_id)["status"]
        except ApiError as exc:
            if exc.status == 404:
                return "missing"
            raise

    def _claim(
        self, campaign_id: str, sweep_id: str, scheduled_at: datetime
    ) -> dict[str, Any] | None:
        repo = self._repo_factory()
        try:
            return repo.claim_pending_sweep(campaign_id, sweep_id, scheduled_at)
        finally:
            repo.close()

    def _resolve(self, campaign_id: str, next_state: str | None) -> None:
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
    then advance every ENROLLED + ACTIVE campaign by at most one step. This advances RESEARCH only
    (launch a sweep, probe/record/stress/select a completed one, mint proposals) — NOTHING self-arms
    or self-publishes (publish/promote stay owner-clicked via the proposal execute path). Serialized
    against the background driver by the in-process tick lock."""
    return _service(request).tick()


@router.post("/proposals/expire", response_model=ExpireResult)
def expire_proposals(request: Request) -> ExpireResult:
    """Sweep the proposal inbox for PENDING proposals past their §8.2 7-day expiry and mark them
    EXPIRED. Idempotent; DB-clock-anchored. (The scheduler tick also runs this; the endpoint is the
    standalone ops surface.)"""
    expired = _service(request).expire_proposals()
    return ExpireResult(expired=len(expired), proposalIds=expired)


@router.post(
    "/campaigns/{campaign_id}/scheduler/enroll", response_model=EnrollmentResult
)
def enroll_campaign(campaign_id: str, request: Request) -> EnrollmentResult:
    """Enroll this campaign into scheduler autonomy (owner-clicked, per-campaign consent): NULL /
    EXHAUSTED → IDLE. Idempotent for already-enrolled states. 404 unknown campaign. The global
    ARTHA_EVO_SCHEDULER_ENABLED flag arms only the background driver — nothing advances without
    BOTH the flag (or a manual tick) AND this per-campaign enrollment."""
    return _service(request).enroll(campaign_id)


@router.post(
    "/campaigns/{campaign_id}/scheduler/withdraw", response_model=EnrollmentResult
)
def withdraw_campaign(campaign_id: str, request: Request) -> EnrollmentResult:
    """Withdraw this campaign from scheduler autonomy: scheduler_state → NULL; a pending sweep is
    abandoned (completes as an ordinary sweep, recordable manually). Idempotent. 404 unknown."""
    return _service(request).withdraw(campaign_id)


@router.get("/scheduler/status", response_model=SchedulerStatus)
def scheduler_status(request: Request) -> SchedulerStatus:
    """The scheduler heartbeat: when the last tick ran (manual or driver). None = no tick since
    boot — with the driver armed, a persistently-None/stale value means the loop is not running."""
    return _service(request).status()
