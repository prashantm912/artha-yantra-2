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
_GATE = re.compile(rf"^entry_rules\.gate\.({_IDENT})$")
_RISK_CAP = re.compile(r"^risk\.(max_positions|max_positions_per_underlying)$")
_GATE_LITERAL = re.compile(rf"^\s*({_IDENT})\s*(>=|<=|==|!=|>|<)\s*(-?[0-9]+(?:\.[0-9]+)?)\s*$")


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
    gate_match = _GATE.match(path)
    if gate_match:
        _set_gate_constant(config, gate_match.group(1), value, path)
        return
    risk_cap_match = _RISK_CAP.match(path)
    if risk_cap_match:
        field = risk_cap_match.group(1)
        risk = config.get("risk", {})
        _require(field in risk, path)
        risk[field] = value
        return
    raise InvalidParameterPath(f"parameter path not in the closed grammar: {path!r}")


def _set_gate_constant(config: dict[str, Any], lhs: str, value: Any, path: str) -> None:
    """Rewrites the sole gate comparison whose LHS is ``lhs`` to ``lhs op <value>`` (EVO row 21),
    mirroring backtest-service's ``TrialOverrides``. Resolves iff exactly one literal-RHS comparison
    carries this LHS — zero or many is a loud non-resolve."""
    if not isinstance(value, int | float) or isinstance(value, bool):
        raise InvalidParameterPath(f"gate-constant override must be numeric: {path!r}")
    gate = config.get("entry_rules", {}).get("gate")
    _require(_count_gate_literals(gate, lhs) == 1, path)
    config["entry_rules"]["gate"] = _rewrite_gate(gate, lhs, _format_number(value))


def _count_gate_literals(node: Any, lhs: str) -> int:
    if isinstance(node, str):
        match = _GATE_LITERAL.match(node)
        return 1 if match and match.group(1) == lhs else 0
    if not isinstance(node, dict):
        return 0
    for key in ("all", "any"):
        if key in node:
            return sum(_count_gate_literals(child, lhs) for child in node[key])
    if "not" in node:
        return _count_gate_literals(node["not"], lhs)
    return 0


def _rewrite_gate(node: Any, lhs: str, literal: str) -> Any:
    if isinstance(node, str):
        match = _GATE_LITERAL.match(node)
        if match and match.group(1) == lhs:
            return f"{lhs} {match.group(2)} {literal}"
        return node
    if isinstance(node, dict):
        for key in ("all", "any"):
            if key in node:
                node[key] = [_rewrite_gate(child, lhs, literal) for child in node[key]]
                return node
        if "not" in node:
            node["not"] = _rewrite_gate(node["not"], lhs, literal)
    return node


def _format_number(value: int | float) -> str:
    """Renders the override as the gate grammar's numeric literal (no scientific notation), matching
    BigDecimal.toPlainString for the doctrine-plausible ranges the optimizer samples."""
    return str(value) if isinstance(value, int) else repr(float(value))


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
