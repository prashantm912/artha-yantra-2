"""Read-only client for backtest-service's results REST (§D.5). Phase 34's
``/trials/{id}/folds`` route resolves a trial's ``backtest_run_id`` and fetches the
persisted per-fold metric array from backtest-service — the optimizer never reads
the ``backtest`` analytics tables directly."""

from __future__ import annotations

from typing import Any

import httpx


class BacktestClient:
    """Thin httpx wrapper over ``/api/v1/backtests``."""

    def __init__(self, base_url: str, client: httpx.Client | None = None) -> None:
        self._base = base_url.rstrip("/")
        self._client = client or httpx.Client(timeout=10.0)

    def folds(self, run_id: str) -> Any:
        """The per-fold train/OOS metric array for one backtest run ([] when full-window)."""
        resp = self._client.get(f"{self._base}/api/v1/backtests/{run_id}/folds")
        resp.raise_for_status()
        return resp.json()
