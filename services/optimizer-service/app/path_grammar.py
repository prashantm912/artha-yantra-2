"""The closed parameter-path whitelist grammar (§D.12 / COMMON §12.5).

Both optimizer-service (at sweep submission) and backtest-service (again before
applying a trial's overrides) enforce the SAME grammar; anything outside it is a
400 ``INVALID_PARAMETER_PATH``. Selectors are matched LITERALLY against the
validated config tree — no reflection, no expression evaluation, no wildcards —
so a path can never reach an arbitrary object graph.

    path           := indicator-path | exit-path | scoring-path | risk-path
    indicator-path := "indicators[" selector "].params." ident
    exit-path      := "exit_rules[" selector "].params." ident
    scoring-path   := "entry_rules.scoring." ident
    risk-path      := "risk.position_sizing." ident
    selector       := "alias=" ident | "type=" ident | int
    ident          := [a-z][a-z0-9_]*        int := [0-9]+
"""

from __future__ import annotations

import re

_IDENT = r"[a-z][a-z0-9_]*"
_SELECTOR = rf"(?:alias={_IDENT}|type={_IDENT}|[0-9]+)"

_PATTERNS = (
    re.compile(rf"^indicators\[{_SELECTOR}\]\.params\.{_IDENT}$"),
    re.compile(rf"^exit_rules\[{_SELECTOR}\]\.params\.{_IDENT}$"),
    re.compile(rf"^entry_rules\.scoring\.{_IDENT}$"),
    re.compile(rf"^risk\.position_sizing\.{_IDENT}$"),
)


class InvalidParameterPath(ValueError):
    """A path that is not in the closed grammar — surfaced as 400 INVALID_PARAMETER_PATH."""


def is_valid(path: str) -> bool:
    """True when ``path`` matches one of the four closed productions."""
    return any(pattern.match(path) for pattern in _PATTERNS)


def validate(path: str) -> str:
    """Returns ``path`` if grammatical, else raises :class:`InvalidParameterPath`."""
    if not is_valid(path):
        raise InvalidParameterPath(f"parameter path not in the closed grammar: {path!r}")
    return path
