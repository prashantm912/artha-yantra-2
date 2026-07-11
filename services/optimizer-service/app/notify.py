"""Fail-soft ntfy push for the optimizer (EVO E4 §12 item 11).

The optimizer had no notification path, so this mirrors the platform's Java ``NtfyClient``
CONVENTION (market-data-service ``alerts/NtfyClient.java``): a POST to ``{url}/{topic}`` with
``Title`` / ``Priority`` headers and a plain-text body. No topic configured → silent no-op (mock
mode needs no ntfy config). Every failure logs and NEVER propagates — a push must not break the
proposal write path (design §8.2 "optionally mirrored ... away-from-desk"; the alert is advisory).
"""

from __future__ import annotations

import logging

import httpx

_LOG = logging.getLogger(__name__)


class NtfyClient:
    """One fail-soft POST to an env-configured ntfy topic. ``topic`` blank → no-op."""

    def __init__(self, url: str, topic: str, timeout: float = 5.0) -> None:
        self._url = (url or "https://ntfy.sh").rstrip("/")
        self._topic = topic or ""
        self._timeout = timeout

    def send(self, title: str, message: str, priority: str = "default") -> None:
        """Send one alert; a blank topic or any transport failure is swallowed (logged)."""
        if not self._topic:
            return
        try:
            httpx.post(
                f"{self._url}/{self._topic}",
                headers={"Title": title, "Priority": priority},
                content=message.encode("utf-8"),
                timeout=self._timeout,
            )
        except Exception as exc:  # noqa: BLE001 - a push must never break the write path
            _LOG.warning("ntfy send failed: %s", exc)
