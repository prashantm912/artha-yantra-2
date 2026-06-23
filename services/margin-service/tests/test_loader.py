"""SpanLoader — latest-``.spn``-by-mtime selection + memoized parse + no-file path.

Uses an injected ``engine_factory`` so no binary ``.spn`` is needed; the factory
records the path it was handed and returns the synthetic engine."""

import os
import time

import pytest

from app.span_loader import NoSpanFileError, SpanLoader
from tests.fakes import synthetic_engine


def test_no_spn_raises(tmp_path):
    loader = SpanLoader(str(tmp_path))
    with pytest.raises(NoSpanFileError):
        loader.engine()
    assert loader.spn_date() is None


def test_missing_dir_is_no_spn():
    loader = SpanLoader("/this/dir/does/not/exist")
    with pytest.raises(NoSpanFileError):
        loader.engine()


def test_picks_latest_by_mtime(tmp_path):
    older = tmp_path / "PR_230626.spn"
    newer = tmp_path / "PR_240626.spn"
    older.write_text("x")
    newer.write_text("y")
    # Make `newer` strictly newer by mtime.
    os.utime(older, (time.time() - 100, time.time() - 100))

    seen: list[str] = []

    def factory(path: str):
        seen.append(path)
        return synthetic_engine()

    loader = SpanLoader(str(tmp_path), engine_factory=factory)
    loader.engine()
    assert seen[-1].endswith("PR_240626.spn")
    assert loader.spn_date() == "2026-06-24"


def test_memoizes_until_mtime_changes(tmp_path):
    spn = tmp_path / "PR_240626.spn"
    spn.write_text("x")
    calls: list[str] = []

    def factory(path: str):
        calls.append(path)
        return synthetic_engine()

    loader = SpanLoader(str(tmp_path), engine_factory=factory)
    loader.engine()
    loader.engine()
    assert len(calls) == 1  # parsed once, then served from cache

    # Touch the file with a new mtime -> cache invalidates, factory runs again.
    os.utime(spn, (time.time() + 10, time.time() + 10))
    loader.engine()
    assert len(calls) == 2
