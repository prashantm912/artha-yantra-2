"""Selects the latest ``.spn`` under ``SPN_DIR`` and memoizes the parsed engine.

The host fetcher (``tools/span-fetch/fetch_spn.ps1``, §8.2) drops a fresh
``.spn`` daily; this loader picks the newest by mtime and parses it ONCE, caching
the :class:`SpanEngine` keyed by ``(path, mtime)`` so every ``/margin`` call after
the first is sub-millisecond CPU. A new file (or a re-fetch) invalidates the cache
on the mtime change. ``spn_date`` (the file's SPAN business date) is exposed so a
stale file is visible at ``/health`` — never silently wrong (§8.9)."""

from __future__ import annotations

import os
from collections.abc import Callable
from pathlib import Path

from app.marginism_adapter import MarginismEngine, SpanEngine


class NoSpanFileError(Exception):
    """No ``.spn`` file is present under ``SPN_DIR`` yet (maps to 503 DATA_GAP)."""


class SpanLoader:
    """Latest-``.spn``-by-mtime selection with a single-entry parse cache."""

    def __init__(
        self,
        spn_dir: str,
        engine_factory: Callable[[str], SpanEngine] = MarginismEngine.from_file,
    ) -> None:
        self._dir = spn_dir
        # ``engine_factory`` is injectable so tests load a synthetic file without a
        # real binary ``.spn`` (it still drives the real marginism algorithm).
        self._factory = engine_factory
        self._cache_key: tuple[str, float] | None = None
        self._engine: SpanEngine | None = None

    def _latest_path(self) -> str | None:
        """The newest-by-mtime ``.spn`` under ``SPN_DIR`` (None if the dir is empty)."""
        directory = Path(self._dir)
        if not directory.is_dir():
            return None
        candidates = sorted(
            (p for p in directory.glob("*.spn") if p.is_file()),
            key=lambda p: p.stat().st_mtime,
            reverse=True,
        )
        return str(candidates[0]) if candidates else None

    def engine(self) -> SpanEngine:
        """The parsed engine for the latest ``.spn`` (memoized per file+mtime)."""
        path = self._latest_path()
        if path is None:
            raise NoSpanFileError(self._dir)
        key = (path, os.path.getmtime(path))
        if key != self._cache_key:
            self._engine = self._factory(path)
            self._cache_key = key
        assert self._engine is not None  # noqa: S101 - set on the cache-miss branch above
        return self._engine

    def spn_date(self) -> str | None:
        """ISO business date of the currently loaded ``.spn`` (None if none loaded)."""
        try:
            return self.engine().business_date
        except NoSpanFileError:
            return None
