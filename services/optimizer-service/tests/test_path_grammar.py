"""§D.12 closed parameter-path grammar."""

import pytest

from app.path_grammar import InvalidParameterPath, is_valid, validate


@pytest.mark.parametrize(
    "path",
    [
        "indicators[0].params.period",
        "indicators[alias=ema_fast].params.period",
        "indicators[type=ema].params.length",
        "exit_rules[1].params.atr_mult",
        "entry_rules.scoring.threshold",
        "risk.position_sizing.capital_pct",
    ],
)
def test_accepts_grammatical_paths(path):
    assert is_valid(path)
    assert validate(path) == path


@pytest.mark.parametrize(
    "path",
    [
        "universe.symbols[0]",
        "indicators[0].period",  # missing .params.
        "indicators[*].params.period",  # wildcard
        "indicators[0].params.Period",  # uppercase ident
        "risk.position_sizing",  # no leaf
        "entry_rules.scoring",  # no leaf
        "",
    ],
)
def test_rejects_paths_outside_the_grammar(path):
    assert not is_valid(path)
    with pytest.raises(InvalidParameterPath):
        validate(path)
