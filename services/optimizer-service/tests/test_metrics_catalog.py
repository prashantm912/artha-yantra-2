"""The shared trial-metrics catalog (remaining-items §9 candidate 03): the optimizer derives its
objective allow-list from contracts/metrics/trial-metrics-catalog.json instead of a hardcoded
frozenset, so a metric added there is automatically accepted (and one removed is rejected) with no
second copy to keep in sync. These tests pin that the derived set is byte-identical to the old
hardcoded list (parity-neutral refactor) and that the catalog schema is well-formed."""

import pytest

from app import metrics_catalog
from app.errors import ApiError
from app.service import _ALLOWED_OBJECTIVE_METRICS, _validate_objective_metrics

# The set the frozenset held before it was derived from the catalog, PLUS the E1 evolution-engine
# metric adds (recoveryFactor / tradeFrequency / turnover — design §6.2, emitted by
# MetricsCalculator). Deriving from the catalog must accept EXACTLY the metrics the catalog carries.
_LEGACY_ALLOWED = frozenset(
    {
        "totalReturn",
        "cagr",
        "sharpe",
        "sortino",
        "maxDrawdown",
        "maxDrawdownDurationBars",
        "winRate",
        "profitFactor",
        "expectancy",
        "averageTrade",
        "exposure",
        "tradeCount",
        "recoveryFactor",
        "tradeFrequency",
        "turnover",
        "foldsExcluded",
        "alpha",
        "beta",
        "informationRatio",
        "excessCagr",
        "upCapture",
        "downCapture",
        "oos_fold_mean",
    }
)

_UNITS = {"percent", "ratio", "currency", "count", "bars"}
_DIRECTIONS = {"higher_is_better", "lower_is_better", "neutral"}
_SOURCES = {"core", "benchmark", "fold", "fold_aggregate"}


def test_catalog_loads_and_has_metrics():
    rows = metrics_catalog.metrics()
    assert rows, "catalog carries at least one metric"
    assert all(row["key"] for row in rows)


def test_derived_allow_list_matches_legacy_frozenset():
    # Parity guard: deriving from the catalog must accept EXACTLY the metrics the old literal did.
    assert metrics_catalog.objective_metric_names() == _LEGACY_ALLOWED
    assert _ALLOWED_OBJECTIVE_METRICS == _LEGACY_ALLOWED


def test_every_catalog_row_is_well_formed():
    for row in metrics_catalog.metrics():
        assert row["unit"] in _UNITS, row
        assert row["direction"] in _DIRECTIONS, row
        assert row["source"] in _SOURCES, row
        assert row.get("label"), row


def test_metric_keys_are_unique():
    keys = [row["key"] for row in metrics_catalog.metrics()]
    assert len(keys) == len(set(keys))


def test_direction_lookup():
    assert metrics_catalog.direction("maxDrawdown") == "lower_is_better"
    assert metrics_catalog.direction("sharpe") == "higher_is_better"
    assert metrics_catalog.direction("no-such-metric") is None


def test_validation_accepts_a_catalog_metric():
    _validate_objective_metrics("tpe", {"metric": "sortino"})  # in the catalog -> no raise


def test_validation_rejects_a_non_catalog_metric():
    with pytest.raises(ApiError) as exc:
        _validate_objective_metrics("tpe", {"metric": "totalReturns"})  # typo, not in the catalog
    assert exc.value.status == 400 and exc.value.code == "VALIDATION_FAILED"
