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

    def run(self, request: dict[str, Any]) -> dict[str, Any]:
        """Submit a backtest through the FULL job pipeline (``POST /api/v1/backtests/run``) — EVO
        E3's reconciliation re-sim of the EXACT version over the EXACT lived window (``purpose
        reconcile``, §7.1). The HTTP endpoint (not a jobs INSERT like the cost-stress re-runs) is
        deliberate: JobsService.submit resolves + pins the version, runs the coverage/regime
        pre-flight, and — critically — PINS the swing FUNNEL universe (manas_arora_funnel /
        minervini_funnel resolve ONLY there, JobsService.java:155-183), without which a SIM_FIRST
        swing re-sim throws "needs a pinned universe". Returns ``{jobId, status}``
        (JobsController.java:38-39)."""
        resp = self._client.post(f"{self._base}/api/v1/backtests/run", json=request)
        resp.raise_for_status()
        return resp.json()

    def trades(self, run_id: str, limit: int = 1000, offset: int = 0) -> list[dict[str, Any]]:
        """The paged trades of one completed run (``GET /{id}/trades`` → ``{items,…}``), returned as
        the bare ``items`` list. Each trade carries ``tradingsymbol`` / ``entryTs`` / ``entryPrice``
        (ResultsController + TradeRepository.findByRun) — the sim side the reconciliation computer
        pairs against the live paper trades. Read-only §D.5 surface."""
        resp = self._client.get(
            f"{self._base}/api/v1/backtests/{run_id}/trades",
            params={"limit": limit, "offset": offset},
        )
        resp.raise_for_status()
        return resp.json().get("items", [])

    def job_status(self, job_id: str) -> dict[str, Any]:
        """One backtest job's status payload (``GET /jobs/{id}``): ``{status, resultRef, ...}``.
        The E2 cost-stress drain polls this for a dispatched BACKTEST stress run — a completed run's
        ``resultRef`` is the run id whose ``/results`` carries the stressed objective. Read-only."""
        resp = self._client.get(f"{self._base}/api/v1/backtests/jobs/{job_id}")
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
