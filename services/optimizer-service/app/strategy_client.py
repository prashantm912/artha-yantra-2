"""Read-only client for strategy-signal-service's versioning REST (D11 — immutable
versions, so the config can be cached). Phase 33 fetches the pinned version's
config to expand its ``optimize`` block; Phase 34's promote writes a new draft
back through this same service."""

from __future__ import annotations

from typing import Any

import httpx


class StrategyClient:
    """Thin httpx wrapper over ``/api/v1/strategies``."""

    def __init__(self, base_url: str, client: httpx.Client | None = None) -> None:
        self._base = base_url.rstrip("/")
        self._client = client or httpx.Client(timeout=10.0)

    def version_config(self, strategy_id: str, version: str) -> dict[str, Any]:
        """The immutable config JSON for one strategy version."""
        resp = self._client.get(
            f"{self._base}/api/v1/strategies/{strategy_id}/versions/{version}"
        )
        resp.raise_for_status()
        body = resp.json()
        return body.get("config", body)

    def resolve(
        self, strategy_id: str, version: str | None
    ) -> tuple[str, dict[str, Any]]:
        """Resolve a strategy version to ``(version, config)`` (§D.5). An explicit version is
        fetched directly; an omitted version pins the registry's current resolution (latest
        published, else latest draft) at submission, mirroring backtest-service's
        StrategyVersionClient so a sweep is never "whatever is current"."""
        if version:
            return version, self.version_config(strategy_id, version)
        resp = self._client.get(f"{self._base}/api/v1/strategies/{strategy_id}")
        resp.raise_for_status()
        body = resp.json()
        return body["version"], body.get("config", {})

    def create_draft(
        self, strategy_id: str, config: dict[str, Any], notes: str, created_by: str | None = None
    ) -> dict[str, Any]:
        """Promotes a winner: PUT a new draft version (Phase 34). Returns the new version row.
        ``created_by`` (audit T3) stamps the machine-readable actor on the version's ``created_by``
        column — ``optimizer:{sweepId}`` for a promote — so provenance is a first-class column, not
        a note; omitted → the registry defaults to ``'owner'``."""
        import json

        body: dict[str, Any] = {
            "config": json.dumps(config),
            "versionBump": "minor",
            "notes": notes,
        }
        if created_by is not None:
            body["createdBy"] = created_by
        resp = self._client.put(f"{self._base}/api/v1/strategies/{strategy_id}", json=body)
        resp.raise_for_status()
        return resp.json()
