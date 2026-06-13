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

    def create_draft(
        self, strategy_id: str, config: dict[str, Any], notes: str
    ) -> dict[str, Any]:
        """Promotes a winner: PUT a new draft version (Phase 34). Returns the new version row."""
        import json

        resp = self._client.put(
            f"{self._base}/api/v1/strategies/{strategy_id}",
            json={"config": json.dumps(config), "versionBump": "minor", "notes": notes},
        )
        resp.raise_for_status()
        return resp.json()
