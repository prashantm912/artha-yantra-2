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

    def results(self, run_id: str) -> dict[str, Any]:
        """The run-level results payload (§D.5 ``/results``): the ``metrics`` JSONB (totalReturn,
        maxDrawdown, sortino, expectancy, …), ``dataHash``, ``engineSha`` (P0-2 / #703), and the
        run's ``caveats``. Read by retro-scoring to compute the RobustScore components that live at
        the run level (drawdown, recovery, caveats) — a read-only §D.5 surface, no recompute."""
        resp = self._client.get(f"{self._base}/api/v1/backtests/{run_id}/results")
        resp.raise_for_status()
        return resp.json()

    def guard_summary(self, run_id: str) -> dict[str, Any] | None:
        """The persisted §D.4 guard outputs for one run — ``dataHash`` + ``foldsExcluded`` off the
        run's results payload and each fold's ``regimeOos`` off ``/folds``. Returns ``None`` for a
        full-window run (no fold structure), so the ``/best`` leaderboard simply omits the nested
        ``guardMetrics`` for a legacy/full-window trial rather than a hollow one. No recompute —
        both reads hit persisted §D.5 surfaces."""
        folds = self.folds(run_id)
        if not folds:
            return None
        resp = self._client.get(f"{self._base}/api/v1/backtests/{run_id}/results")
        resp.raise_for_status()
        results = resp.json()
        metrics = results.get("metrics") or {}
        return {
            "dataHash": results.get("dataHash"),
            "foldsExcluded": metrics.get("foldsExcluded"),
            "regimeOos": [fold.get("regimeOos") or {} for fold in folds],
        }
