"""optimizer-service entrypoint: FastAPI app, health, Prometheus metrics, and the
wiring of the sweep service to real Postgres + Redis + strategy-signal (§D.1)."""

from __future__ import annotations

import logging

import psycopg
import redis
from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator

from app import api, evolution, insights
from app.backtest_client import BacktestClient
from app.errors import ApiError, api_error_handler, invalid_path_handler
from app.path_grammar import InvalidParameterPath
from app.repos import EvoRepo, JobsRepo, TrialsRepo
from app.service import SweepService
from app.settings import Settings
from app.strategy_client import StrategyClient
from app.streams import TrialDispatcher

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
    # Reuses the same jobs/trials factories + backtest client as the sweep service.
    app.state.retro = evolution.RetroScoreService(
        jobs_factory=lambda: JobsRepo(open_conn()),
        trials_factory=lambda: TrialsRepo(open_conn()),
        backtest_client=backtest_client,
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

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP"}

    app.include_router(api.router)
    app.include_router(evolution.router)
    app.include_router(insights.router)
    Instrumentator().instrument(app).expose(app, endpoint="/metrics")
    return app


app = build_app()
