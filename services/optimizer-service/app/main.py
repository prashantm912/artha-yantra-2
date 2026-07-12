"""optimizer-service entrypoint: FastAPI app, health, Prometheus metrics, and the
wiring of the sweep service to real Postgres + Redis + strategy-signal (§D.1)."""

from __future__ import annotations

import logging

import psycopg
import redis
from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator

from app import (
    ablation,
    api,
    evolution,
    insights,
    proposals,
    reconciliation,
    reports,
    scheduler,
    stress,
    suggesters,
)
from app.backtest_client import BacktestClient
from app.errors import ApiError, api_error_handler, invalid_path_handler
from app.notify import NtfyClient
from app.path_grammar import InvalidParameterPath
from app.repos import EvoRepo, JobsRepo, LiveEvidenceRepo, ReconciliationRepo, TrialsRepo
from app.service import SweepService
from app.settings import Settings
from app.strategy_client import StrategyClient
from app.streams import TrialDispatcher
from app.structure_repos import StructureRepo

logging.basicConfig(level=logging.INFO, format='{"level":"%(levelname)s","msg":"%(message)s"}')
log = logging.getLogger("optimizer")


def build_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.load()
    app = FastAPI(title="optimizer-service", version="1.0.0")

    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(InvalidParameterPath, invalid_path_handler)

    redis_client = redis.Redis.from_url(settings.redis_url)
    dispatcher = TrialDispatcher(redis_client)
    try:
        dispatcher.ensure_group()
    except Exception as exc:  # noqa: BLE001 - redis may be down at boot in some envs
        log.warning("ensure_group skipped: %s", exc)

    def open_conn() -> psycopg.Connection:
        return psycopg.connect(settings.conninfo)

    backtest_client = BacktestClient(settings.backtest_base)

    # Boot recovery (P1-10b): sweeps are in-memory daemon threads — a restart strands their
    # rows at running forever unless marked failed here (Postgres may be down at boot: warn only).
    try:
        repo = JobsRepo(open_conn())
        try:
            orphaned = repo.fail_orphaned_sweeps()
            if orphaned:
                log.warning("marked %d orphaned OPTIMIZATION row(s) failed at boot", orphaned)
        finally:
            repo.close()
    except Exception as exc:  # noqa: BLE001 - postgres may be down at boot in some envs
        log.warning("orphaned-sweep recovery skipped: %s", exc)

    # Boot reaper (EVO E2 neighbor probes): a restart mid-probe-drain strands optimization_trials
    # rows at RUNNING under an already-terminal sweep — nothing can ever resolve them, and a
    # stranded row dedupes its cell away from every future probe POST. DELETE frees the cell +
    # trial_number so a re-POST re-probes it. Runs AFTER fail_orphaned_sweeps so a restart-killed
    # sweep's own RUNNING rows (its parent just flipped to failed) are swept in the same boot.
    try:
        trials_repo = TrialsRepo(open_conn())
        try:
            stranded = trials_repo.delete_stranded_running()
            if stranded:
                log.warning("deleted %d stranded RUNNING trial row(s) at boot", stranded)
        finally:
            trials_repo.close()
    except Exception as exc:  # noqa: BLE001 - postgres may be down at boot in some envs
        log.warning("stranded-trial cleanup skipped: %s", exc)

    # Boot reaper (EVO E2 cost-stress): a restart mid-stress-round kills the in-memory drain,
    # stranding its generation at STRESSING — the durable 409 guard would then refuse every future
    # stress POST. Flip orphans back to DONE so a re-POST reruns the round.
    try:
        evo_repo = EvoRepo(open_conn())
        try:
            orphaned_rounds = evo_repo.reap_stressing_generations()
            if orphaned_rounds:
                log.warning(
                    "flipped %d orphaned STRESSING generation(s) to DONE at boot "
                    "(restart mid-stress-round; re-POST /stress to rerun)", orphaned_rounds)
        finally:
            evo_repo.close()
    except Exception as exc:  # noqa: BLE001 - postgres may be down at boot in some envs
        log.warning("stress-round reaper skipped: %s", exc)

    app.state.sweeps = SweepService(
        strategy_client=StrategyClient(settings.strategy_signal_base),
        backtest_client=backtest_client,
        jobs_factory=lambda: JobsRepo(open_conn()),
        trials_factory=lambda: TrialsRepo(open_conn()),
        dispatcher=dispatcher,
    )

    # Evolution-engine read surface (§12 E1): its own repo factory, read-only.
    app.state.evo = evolution.EvoReadService(repo_factory=lambda: EvoRepo(open_conn()))

    # Retro-scoring (§12 E1 item 2): the §6 scoring lib over an existing sweep's trials, read-only.
    # Reuses the same jobs/trials factories + backtest client as the sweep service. The
    # recon_factory (E4 item 10) feeds the (version, window §7.1.4) reconciliation attach in
    # assemble_cohort — a no-op until a scored cohort carries lived versionIds (retro has none).
    app.state.retro = evolution.RetroScoreService(
        jobs_factory=lambda: JobsRepo(open_conn()),
        trials_factory=lambda: TrialsRepo(open_conn()),
        backtest_client=backtest_client,
        recon_factory=lambda: ReconciliationRepo(open_conn()),
    )

    # Campaign/generation recorder (§12 E1 item 3): the evo WRITE surface — create a campaign, and
    # record a manually-triggered sweep as a generation. Reuses the retro scorer for all assembly +
    # scoring; its own EvoRepo factory for the writes. No autonomy (no scheduler/proposals).
    app.state.evo_writer = evolution.EvoRecorderService(
        repo_factory=lambda: EvoRepo(open_conn()),
        scorer=app.state.retro,
    )

    # Parameter-effect insights (§12 E2 item 6): importance/brittleness/slices over an existing
    # sweep's trials, read-only. Reuses the jobs/trials factories; needs no backtest client.
    app.state.insights = insights.InsightsService(
        jobs_factory=lambda: JobsRepo(open_conn()),
        trials_factory=lambda: TrialsRepo(open_conn()),
    )

    # Cost-stress orchestration (§12 E2 item 5): re-run a generation's top-K at 2×/4× slippage and
    # re-score the cohort's cost_resilience. Reuses the retro scorer for cohort assembly, its own
    # EvoRepo + JobsRepo factories for the evo writes / BACKTEST-job submission, the dispatcher for
    # the backtest stream, and the backtest client to poll the runs + read stressed objectives.
    app.state.stress = stress.StressService(
        repo_factory=lambda: EvoRepo(open_conn()),
        jobs_factory=lambda: JobsRepo(open_conn()),
        scorer=app.state.retro,
        dispatcher=dispatcher,
        backtest_client=backtest_client,
        max_concurrent=settings.stress_max_concurrent_jobs,
    )

    # Backtest-vs-live reconciliation (§12 E3 item 7 / §7): re-simulate the EXACT version over the
    # EXACT lived window (purpose reconcile), pair against live paper trades, persist the gap
    # vector + gapZ + verdict + diagnosis. Its own reconciliation-store repo (backtest schema) + a
    # read-only live-evidence repo (strategy schema — cross-schema reads as `artha`); reuses the
    # backtest client to submit the re-sim + read its run. No autonomy, no scoring wiring (item 10).
    app.state.reconciliation = reconciliation.ReconciliationService(
        repo_factory=lambda: ReconciliationRepo(open_conn()),
        live_factory=lambda: LiveEvidenceRepo(open_conn()),
        backtest_client=backtest_client,
    )

    # Proposals inbox + the PUBLISH_PAPER action (§12 E4 items 11-12 / §8): generate PUBLISH_PAPER
    # proposals for candidates meeting the §8.2 bar, serve the owner inbox (list/approve/reject),
    # and — on an APPROVED proposal via the explicit owner-clicked /execute — create + publish the
    # §8.2 sibling evo paper-lane clone (NOTHING self-arms; no scheduler). Its own EvoRepo factory,
    # a fail-soft ntfy client (blank ARTHA_NTFY_TOPIC → no-op), the strategy-signal registry client
    # (create + publish the clone, service-to-service), the jobs factory (read the candidate's sweep
    # for the base config), and the §1.4.3 per-family evo-paper cap.
    # Slice 3 (§12 item 13) adds the TAKE_ELIGIBLE / PROMOTE / ROLLBACK actions + demoted-champion
    # counterfactual registration — so it also gets the live-evidence repo (the evo clone's paper
    # book + config, strategy schema, read as `artha`) + the reconciliation store (the §7.2 live-gap
    # verdict) for the assessment reads. Still NOTHING self-arms: assessment only advances state to
    # TAKE_ELIGIBLE and mints owner-inbox proposals; PROMOTE/ROLLBACK stay owner-clicked (execute).
    app.state.proposals = proposals.ProposalService(
        repo_factory=lambda: EvoRepo(open_conn()),
        ntfy=NtfyClient(settings.ntfy_url, settings.ntfy_topic),
        strategy_client=StrategyClient(settings.strategy_signal_base),
        jobs_factory=lambda: JobsRepo(open_conn()),
        evo_paper_cap=settings.evo_paper_cap,
        live_factory=lambda: LiveEvidenceRepo(open_conn()),
        recon_factory=lambda: ReconciliationRepo(open_conn()),
    )

    # Structure experimentation (§12 E5): the ablation protocol + gate-candidate suggesters. Both
    # read/write the E5 tables (evo_ablations / evo_graveyard, V016) + the REVIEW_GATE evo_proposals
    # via their own StructureRepo (its own module — the E5 slice touches no existing repo). The
    # ablation stream evaluates paired structure mutations, buries rejections in the graveyard, and
    # emits a REVIEW_GATE proposal for an IS-only rejection; the suggesters emit gate-candidate
    # REVIEW_GATE proposals (regime-gate emit-only — regimes frozen, §5.1.2), suppressing any
    # already-graveyarded structure. NOTHING self-arms.
    app.state.ablation = ablation.AblationService(
        repo_factory=lambda: StructureRepo(open_conn())
    )
    app.state.suggesters = suggesters.SuggesterService(
        repo_factory=lambda: StructureRepo(open_conn())
    )

    # Autonomy scheduler (§12 E6 item 16): ORCHESTRATES the E1–E4 services — launch a generation's
    # sweep, record/score/select a completed one, mint proposals — advancing each ACTIVE campaign by
    # one step per tick. It duplicates none of their logic; it holds the durable per-campaign state
    # machine (V018) via its own EvoRepo factory, and reuses the already-built sweeps / recorder
    # (evo_writer) / proposals services. NOTHING self-arms or self-publishes (§1.4.1) — it advances
    # research only; publish/promote stay owner-clicked (the proposal execute path). The background
    # DRIVER is DEFAULT-OFF: it starts below only when ARTHA_EVO_SCHEDULER_ENABLED is armed.
    app.state.scheduler = scheduler.SchedulerService(
        repo_factory=lambda: EvoRepo(open_conn()),
        sweeps=app.state.sweeps,
        recorder=app.state.evo_writer,
        proposals=app.state.proposals,
    )

    # Per-campaign report (§12 E6 item 16 / §8.3): read-only aggregation — generations, scores,
    # survivors, proposals, budget spend, graveyard. Its own read-only EvoRepo factory.
    app.state.reports = reports.CampaignReportService(repo_factory=lambda: EvoRepo(open_conn()))

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP"}

    app.include_router(api.router)
    app.include_router(evolution.router)
    app.include_router(insights.router)
    app.include_router(stress.router)
    app.include_router(reconciliation.router)
    app.include_router(proposals.router)
    app.include_router(ablation.router)
    app.include_router(suggesters.router)
    app.include_router(scheduler.router)
    app.include_router(reports.router)
    Instrumentator().instrument(app).expose(app, endpoint="/metrics")

    # Arm the background scheduler driver ONLY when explicitly enabled (owner-gated .env flip →
    # the compose passthrough of the SAME name). Default OFF → dormant: campaigns then advance only
    # via the owner-triggered POST /scheduler/tick. Even armed, the driver advances research only.
    if settings.scheduler_enabled:
        driver = scheduler.SchedulerDriver(app.state.scheduler, settings.scheduler_interval_seconds)
        driver.start()
        app.state.scheduler_driver = driver
        log.warning(
            "evo scheduler ARMED (ARTHA_EVO_SCHEDULER_ENABLED=true) — advancing ACTIVE campaigns "
            "every %ds; research only, nothing self-arms/self-publishes",
            settings.scheduler_interval_seconds,
        )
    else:
        log.info(
            "evo scheduler DISABLED (ARTHA_EVO_SCHEDULER_ENABLED unset/false) — no autonomous "
            "advancement; use POST /api/v1/evolution/scheduler/tick for a supervised step"
        )
    return app


app = build_app()
