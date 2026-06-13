"""Applies a winning trial's parameter overrides onto a source version config to
materialize the promoted draft (§D.9 promote, Phase 34).

This is the optimizer-side mirror of backtest-service's ``TrialOverrides`` — the
same closed grammar (§D.12), applied literally to the validated config tree, so a
path can never reach an arbitrary object graph. Only the named leaves change, so
the resulting draft's diff is exactly the trial's parameter deltas. A path outside
the grammar, or one that does not resolve to an existing leaf, raises
:class:`~app.path_grammar.InvalidParameterPath`.
"""

from __future__ import annotations

import copy
import re
from typing import Any

from app.path_grammar import InvalidParameterPath

_IDENT = r"[a-z][a-z0-9_]*"
_SELECTOR = rf"(?:alias={_IDENT}|type={_IDENT}|[0-9]+)"
_ARRAY = re.compile(rf"^(indicators|exit_rules)\[({_SELECTOR})\]\.params\.({_IDENT})$")
_SCORING = re.compile(rf"^entry_rules\.scoring\.({_IDENT})$")
_SIZING = re.compile(rf"^risk\.position_sizing\.({_IDENT})$")


def apply_overrides(config: dict[str, Any], params: dict[str, Any]) -> dict[str, Any]:
    """Returns a deep copy of ``config`` with each ``path: value`` override applied; the input is
    left untouched."""
    patched = copy.deepcopy(config)
    for path, value in params.items():
        _set_leaf(patched, path, value)
    return patched


def _set_leaf(config: dict[str, Any], path: str, value: Any) -> None:
    array_match = _ARRAY.match(path)
    if array_match:
        section, selector, field = array_match.groups()
        element = _select(config.get(section), selector, path)
        _require(isinstance(element.get("params"), dict) and field in element["params"], path)
        element["params"][field] = value
        return
    scoring_match = _SCORING.match(path)
    if scoring_match:
        field = scoring_match.group(1)
        scoring = config.get("entry_rules", {}).get("scoring", {})
        _require(field in scoring, path)
        scoring[field] = value
        return
    sizing_match = _SIZING.match(path)
    if sizing_match:
        field = sizing_match.group(1)
        sizing = config.get("risk", {}).get("position_sizing", {})
        if field in sizing:
            sizing[field] = value
        elif isinstance(sizing.get("params"), dict) and field in sizing["params"]:
            sizing["params"][field] = value
        else:
            _require(False, path)
        return
    raise InvalidParameterPath(f"parameter path not in the closed grammar: {path!r}")


def _select(array: Any, selector: str, path: str) -> dict[str, Any]:
    _require(isinstance(array, list), path)
    if selector.isdigit():
        index = int(selector)
        _require(0 <= index < len(array), path)
        return array[index]
    kind, _, wanted = selector.partition("=")
    for element in array:
        if isinstance(element, dict) and element.get(kind) == wanted:
            return element
    raise InvalidParameterPath(f"selector did not match any element: {path!r}")


def _require(condition: bool, path: str) -> None:
    if not condition:
        raise InvalidParameterPath(f"parameter path does not resolve to an existing leaf: {path!r}")
