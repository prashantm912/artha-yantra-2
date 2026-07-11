"""§D.9 promote-side override application (the mirror of backtest-service's TrialOverrides): the
closed grammar patches only named leaves, the source is left untouched, and a path outside the
grammar or to a missing/ambiguous leaf raises InvalidParameterPath (→ 400)."""

import copy

import pytest

from app.config_patch import apply_overrides
from app.path_grammar import InvalidParameterPath


def _base():
    return {
        "indicators": [{"alias": "ema_fast", "type": "ema", "params": {"period": 9}}],
        "entry_rules": {
            "gate": {
                "all": [
                    {"crossover": {"fast": "ema_fast", "slow": "ema_slow"}},
                    "vol > 1.2",
                    "px > sma20",
                ]
            },
            "scoring": {"threshold": 60},
        },
        "risk": {"position_sizing": {"params": {"capital_pct": 10}}, "max_positions": 5},
    }


def test_applies_existing_families_and_leaves_source_untouched():
    base = _base()
    snapshot = copy.deepcopy(base)
    patched = apply_overrides(
        base,
        {
            "indicators[alias=ema_fast].params.period": 21,
            "entry_rules.scoring.threshold": 75,
            "risk.position_sizing.capital_pct": 4,
        },
    )
    assert patched["indicators"][0]["params"]["period"] == 21
    assert patched["entry_rules"]["scoring"]["threshold"] == 75
    assert patched["risk"]["position_sizing"]["params"]["capital_pct"] == 4
    assert base == snapshot  # source untouched


def test_applies_gate_constant_and_position_cap():
    base = _base()
    patched = apply_overrides(base, {"entry_rules.gate.vol": 2.5, "risk.max_positions": 8})
    gate = patched["entry_rules"]["gate"]["all"]
    assert gate[1] == "vol > 2.5"  # only the matching literal-RHS comparison rewritten
    assert gate[2] == "px > sma20"  # sibling untouched
    assert gate[0]["crossover"]["fast"] == "ema_fast"  # crossover node untouched
    assert patched["risk"]["max_positions"] == 8
    assert base["entry_rules"]["gate"]["all"][1] == "vol > 1.2"  # source untouched


def test_gate_constant_rewrites_inside_nested_any():
    base = {"entry_rules": {"gate": {"all": [{"not": {"any": ["adx > 20", "close > vwap"]}}]}}}
    patched = apply_overrides(base, {"entry_rules.gate.adx": 25})
    assert patched["entry_rules"]["gate"]["all"][0]["not"]["any"][0] == "adx > 25"


def test_integer_gate_constant_renders_without_decimal():
    base = {"entry_rules": {"gate": {"all": ["volume > 125000"]}}}
    patched = apply_overrides(base, {"entry_rules.gate.volume": 90000})
    assert patched["entry_rules"]["gate"]["all"][0] == "volume > 90000"


def test_non_numeric_gate_constant_raises():
    with pytest.raises(InvalidParameterPath, match="must be numeric"):
        apply_overrides(_base(), {"entry_rules.gate.vol": "high"})


def test_gate_constant_on_non_literal_comparison_raises():
    with pytest.raises(InvalidParameterPath, match="does not resolve"):
        apply_overrides(_base(), {"entry_rules.gate.px": 100})


def test_absent_position_cap_raises():
    with pytest.raises(InvalidParameterPath, match="does not resolve"):
        apply_overrides(_base(), {"risk.max_positions_per_underlying": 2})


def test_path_outside_grammar_raises():
    with pytest.raises(InvalidParameterPath, match="closed grammar"):
        apply_overrides(_base(), {"risk.max_daily_loss_pct": 3})
