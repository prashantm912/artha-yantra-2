"""margin-service entrypoint: FastAPI app, health (with loaded .spn date),
Prometheus metrics, and the wiring of the SPAN service to the read-only /spn mount (§8)."""

from __future__ import annotations

import logging

from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator

from app import api
from app.errors import ApiError, api_error_handler
from app.service import SpanService
from app.settings import Settings
from app.span_loader import SpanLoader

logging.basicConfig(level=logging.INFO, format='{"level":"%(levelname)s","msg":"%(message)s"}')
log = logging.getLogger("margin")


def build_app(settings: Settings | None = None, loader: SpanLoader | None = None) -> FastAPI:
    settings = settings or Settings.load()
    app = FastAPI(title="margin-service", version="1.0.0")

    app.add_exception_handler(ApiError, api_error_handler)

    loader = loader or SpanLoader(settings.spn_dir)
    app.state.margin = SpanService(loader)

    @app.get("/health")
    def health() -> dict[str, str | None]:
        # Reports the loaded SPAN business date so a stale (or missing) .spn file
        # is visible (§8.9 — never silently wrong).
        spn_date = loader.spn_date()
        return {"status": "UP", "spnDate": spn_date, "spnLoaded": str(spn_date is not None)}

    app.include_router(api.router)
    Instrumentator().instrument(app).expose(app, endpoint="/metrics")
    return app


app = build_app()
