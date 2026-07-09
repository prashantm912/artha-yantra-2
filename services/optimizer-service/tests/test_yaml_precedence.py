"""Sweep-control fields (method/maxTrials/objective/walkForward) come from the /optimizations/run
request, never the strategy YAML's optimize block — only optimize.parameters is read from YAML. The
precedence warning surfaces each control field the YAML sets but the request omits, so a YAML-only
walk_forward/objective can't be silently ignored (register §9-7)."""

from app.service import _yaml_precedence_warnings

_WF = {"train_days": 30, "test_days": 30, "step_days": 20}
_OBJ = {"metric": "oos_fold_mean", "direction": "maximize"}


def test_yaml_control_fields_omitted_from_request_each_warn():
    optimize = {"method": "grid", "max_trials": 50, "objective": _OBJ, "walk_forward": _WF}
    warnings = _yaml_precedence_warnings(optimize, request={})
    assert len(warnings) == 4
    assert any("optimize.walk_forward" in w and "walkForward" in w for w in warnings)
    assert any("optimize.objective" in w and "objective" in w for w in warnings)


def test_request_field_present_suppresses_its_warning():
    optimize = {"objective": _OBJ, "walk_forward": _WF}
    # walkForward supplied in the request -> only the objective warning remains.
    warnings = _yaml_precedence_warnings(optimize, request={"walkForward": _WF})
    assert len(warnings) == 1
    assert "optimize.objective" in warnings[0]


def test_no_optimize_control_fields_no_warnings():
    # A YAML that only carries parameters (the supported path) warns about nothing.
    assert _yaml_precedence_warnings({"parameters": [{"path": "a.b"}]}, request={}) == []


def test_empty_optimize_block_no_warnings():
    assert _yaml_precedence_warnings({}, request={}) == []
