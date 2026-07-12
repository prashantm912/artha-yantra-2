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

    # --- EVO E4 slice 2: sibling-clone materialization (§8.2) ------------------------------------
    # The optimizer calls strategy-signal DIRECTLY (service-to-service inside the compose network —
    # no gateway/XSRF: these are internal calls on the same base as the version reads above).

    def create(
        self,
        name: str,
        description: str | None,
        tags: list[str],
        config: dict[str, Any],
        created_by: str | None = None,
    ) -> dict[str, Any]:
        """POST /api/v1/strategies — create a NEW strategy as draft 1.0.0 (RegistryController
        ``CreateRequest``). The registry derives the SLUG from ``config.id`` and 409s on a duplicate
        slug OR name (``CONFLICT_SLUG_EXISTS``) — the caller absorbs that. ``config`` is serialized
        to the JSON string the registry parser accepts (YAML is a JSON superset, as the promote path
        already relies on). Returns ``{id, version, status, checksum}``."""
        import json

        body: dict[str, Any] = {
            "name": name,
            "description": description,
            "tags": tags,
            "config": json.dumps(config),
        }
        if created_by is not None:
            body["createdBy"] = created_by
        resp = self._client.post(f"{self._base}/api/v1/strategies", json=body)
        resp.raise_for_status()
        return resp.json()

    def publish(
        self, strategy_id: str, target_version: str | None = None, notes: str | None = None
    ) -> dict[str, Any]:
        """POST /api/v1/strategies/{id}/publish — publish a draft (RegistryController
        ``PublishRequest``, body optional). Returns ``{id, version, status}``."""
        body: dict[str, Any] = {}
        if target_version is not None:
            body["targetVersion"] = target_version
        if notes is not None:
            body["notes"] = notes
        resp = self._client.post(
            f"{self._base}/api/v1/strategies/{strategy_id}/publish", json=body
        )
        resp.raise_for_status()
        return resp.json()

    def detail(self, strategy_id: str) -> dict[str, Any]:
        """GET /api/v1/strategies/{id} — the full detail (latest version), incl. the version-row
        ``versionId`` UUID the PUBLISH_PAPER execute stamps onto ``evo_candidates.version_id``."""
        resp = self._client.get(f"{self._base}/api/v1/strategies/{strategy_id}")
        resp.raise_for_status()
        return resp.json()

    def list_strategies(
        self, tag: str | None = None, status: str | None = None, limit: int = 500
    ) -> list[dict[str, Any]]:
        """GET /api/v1/strategies?tag=&status= — the registry list (``{items:[...]}`` envelope).
        The PUBLISH_PAPER cap check (§1.4.3) filters ``tag=evo`` + ``status=published`` and counts
        the family members client-side."""
        params: dict[str, Any] = {"limit": limit}
        if tag is not None:
            params["tag"] = tag
        if status is not None:
            params["status"] = status
        resp = self._client.get(f"{self._base}/api/v1/strategies", params=params)
        resp.raise_for_status()
        return resp.json().get("items", [])

    # --- EVO E4 slice 3: PROMOTE / ROLLBACK registry writes (§8.2) -------------------------------

    def rollback(
        self, strategy_id: str, version: str, and_publish: bool = True
    ) -> dict[str, Any]:
        """POST /api/v1/strategies/{id}/rollback — the copy-forward rollback (RegistryController
        ``RollbackRequest{version, andPublish}``, returns 201). EVO E4 ROLLBACK (§8.2) restores the
        demoted-champion SEMVER as a new version and republishes it (``andPublish=true``), so the
        champion pointer moves back. Returns the registry body ``{id, version, status, ...}``."""
        resp = self._client.post(
            f"{self._base}/api/v1/strategies/{strategy_id}/rollback",
            json={"version": version, "andPublish": and_publish},
        )
        resp.raise_for_status()
        return resp.json()

    def archive(self, strategy_id: str) -> dict[str, Any]:
        """POST /api/v1/strategies/{id}/archive — archive a strategy (the signal engine unloads it;
        existing archive semantics). EVO E4 PROMOTE (§8.2 "closing/retiring archives the clone")
        archives the candidate's now-redundant evo PAPER clone after its config is published onto
        the base strategy."""
        resp = self._client.post(f"{self._base}/api/v1/strategies/{strategy_id}/archive")
        resp.raise_for_status()
        return resp.json()

    def register_shadow_variant(
        self,
        name: str,
        campaign_id: str | None,
        spec: dict[str, Any],
        created_by: str | None = None,
    ) -> dict[str, Any]:
        """POST /api/v1/shadow-variants — register a runtime shadow challenger variant (#733,
        ``ShadowVariantsController.RegisterRequest{name, campaignId, spec, createdBy}``, returns
        201). EVO E4 PROMOTE (§8.2) uses this to keep the DEMOTED CHAMPION accruing counterfactual
        P&L for a scalper family (the shadow book hosts scalper rejection-path entries only).
        ``spec`` is the variant vocabulary body ``{rails, compositeThreshold}`` (unknown knobs 422).
        Returns the created ``ShadowVariantView``."""
        body: dict[str, Any] = {"name": name, "spec": spec}
        if campaign_id is not None:
            body["campaignId"] = campaign_id
        if created_by is not None:
            body["createdBy"] = created_by
        resp = self._client.post(f"{self._base}/api/v1/shadow-variants", json=body)
        resp.raise_for_status()
        return resp.json()
