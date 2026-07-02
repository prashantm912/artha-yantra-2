"""optimizer-service entrypoint: FastAPI app, health, Prometheus metrics, and the
wiring of the sweep service to real Postgres + Redis + strategy-signal (§D.1)."""

from __future__ import annotations

import logging

import psycopg
import redis
from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator

from app import api
from app.backtest_client import BacktestClient
from app.errors import ApiError, api_error_handler, invalid_path_handler
from app.path_grammar import InvalidParameterPath
from app.repos import JobsRepo, TrialsRepo
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
        backtest_client=BacktestClient(settings.backtest_base),
        jobs_factory=lambda: JobsRepo(open_conn()),
        trials_factory=lambda: TrialsRepo(open_conn()),
        dispatcher=dispatcher,
    )

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP"}

    app.include_router(api.router)
    Instrumentator().instrument(app).expose(app, endpoint="/metrics")
    return app


app = build_app()
